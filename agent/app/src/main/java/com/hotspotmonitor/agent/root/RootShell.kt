package com.hotspotmonitor.agent.root

import com.hotspotmonitor.agent.core.AgentLog
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.ceil

/** Result of one shell command. Non-zero exit codes are data, not exceptions. */
data class ShellResult(
    val code: Int,
    val out: String,
    val err: String,
    val durationMs: Long,
) {
    val ok: Boolean get() = code == 0
    val timedOut: Boolean get() = code == CODE_TIMEOUT

    /** Non-empty stdout lines. */
    val outLines: List<String> get() = out.lines().filter { it.isNotBlank() }

    /** One line for logs and command output: stdout, else stderr, truncated. */
    fun summary(max: Int = 200): String {
        val text = out.ifBlank { err }.trim().replace('\n', ' ')
        return if (text.length <= max) text else text.take(max) + "…"
    }

    companion object {
        /** Like coreutils `timeout`. */
        const val CODE_TIMEOUT = 124

        /** The command could not be started (no shell, exception). */
        const val CODE_ERROR = -1
    }
}

/** A command template that succeeded, and its result (see [RootShell.runFirstSuccess]). */
data class ShellAttempt(val command: String, val result: ShellResult)

/**
 * Every privileged command goes through here (plan 6.1 "Root shell"). Each call is logged with its
 * duration and truncated output, except successful `quiet` calls (periodic telemetry reads that
 * would otherwise fill the 1000-line log ring); a quiet call that fails is still logged.
 *
 * Rules for callers:
 * - Call from a coroutine; the implementation switches to Dispatchers.IO.
 * - Commands share one root shell process and run one at a time, so never `sleep` inside a command:
 *   wait in the coroutine (delay) between two calls instead.
 * - Commands containing secrets (hotspot password, SMS text) pass `logAs` with a redacted label.
 */
interface RootShell {
    /** null until the first check, then whether the root shell was granted. */
    val rooted: StateFlow<Boolean?>

    /**
     * Runs [cmd] with `sh -c`, killed after [timeoutMs]. Never throws for a failing command.
     * [quiet] skips the log line when the command succeeds (use it for periodic reads).
     */
    suspend fun run(
        cmd: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        logAs: String? = null,
        quiet: Boolean = false,
    ): ShellResult

    /** Whether root is available (requests it on the first call; Magisk may show a prompt). */
    suspend fun isRooted(): Boolean

    /** "magisk 27.0", "kernelsu 0.9.5", "su 2.82:SUPERSU" or null. */
    suspend fun rootImpl(): String?

    /** Runs each command in order and returns every result. */
    suspend fun runAll(cmds: List<String>, timeoutMs: Long = DEFAULT_TIMEOUT_MS): List<ShellResult> =
        cmds.map { run(it, timeoutMs) }

    /** Runs commands in order until one exits 0 (profile command fallbacks). Null if none did. */
    suspend fun runFirstSuccess(cmds: List<String>, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShellAttempt? {
        for (cmd in cmds) {
            val result = run(cmd, timeoutMs)
            if (result.ok) return ShellAttempt(cmd, result)
        }
        return null
    }

    /** Reads a (sysfs/proc) file as root; null when it cannot be read. */
    suspend fun readFile(path: String, timeoutMs: Long = 5_000, quiet: Boolean = false): String? {
        val r = run("cat ${shellQuote(path)}", timeoutMs, quiet = quiet)
        return if (r.ok) r.out.trim() else null
    }

    /** Writes [value] to a (sysfs) file as root. */
    suspend fun writeFile(path: String, value: String, timeoutMs: Long = 5_000): ShellResult =
        run("echo ${shellQuote(value)} > ${shellQuote(path)}", timeoutMs)

    suspend fun fileExists(path: String): Boolean = run("test -e ${shellQuote(path)}", 5_000).ok

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
    }
}

/** Single-quotes [value] for sh: `it's` -> `'it'\''s'`. */
fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

/**
 * libsu-backed [RootShell]. Commands are wrapped in toybox `timeout -s KILL` so a hung command
 * cannot block the shared root shell; a Java-side wait with a grace period is the second guard.
 */
class LibsuRootShell(private val log: AgentLog) : RootShell {
    private val _rooted = MutableStateFlow<Boolean?>(null)
    override val rooted: StateFlow<Boolean?> = _rooted.asStateFlow()

    @Volatile
    private var hasTimeoutBinary: Boolean? = null

    override suspend fun run(cmd: String, timeoutMs: Long, logAs: String?, quiet: Boolean): ShellResult = withContext(Dispatchers.IO) {
        val label = logAs ?: cmd
        val startedNs = System.nanoTime()
        val result = try {
            val shell = Shell.getShell()
            if (_rooted.value == null) _rooted.value = shell.isRoot
            val wrapped = wrapWithTimeout(cmd, timeoutMs)
            val out = ArrayList<String>()
            val err = ArrayList<String>()
            val future = Shell.cmd(wrapped).to(out, err).enqueue()
            try {
                val r = future.get(timeoutMs + GRACE_MS, TimeUnit.MILLISECONDS)
                val elapsed = (System.nanoTime() - startedNs) / 1_000_000
                val code = if (looksTimedOut(r.code, elapsed, timeoutMs)) ShellResult.CODE_TIMEOUT else r.code
                ShellResult(code, out.joinToString("\n"), err.joinToString("\n"), elapsed)
            } catch (e: TimeoutException) {
                ShellResult(ShellResult.CODE_TIMEOUT, out.joinToString("\n"), "timed out after $timeoutMs ms", elapsedMs(startedNs))
            }
        } catch (e: Exception) {
            ShellResult(ShellResult.CODE_ERROR, "", e.message ?: e.javaClass.simpleName, elapsedMs(startedNs))
        }
        if (!(quiet && result.ok)) logResult(label, result)
        result
    }

    override suspend fun isRooted(): Boolean = withContext(Dispatchers.IO) {
        val rooted = try {
            Shell.getShell().isRoot
        } catch (e: Exception) {
            log.w(TAG, "Root shell unavailable", e)
            false
        }
        _rooted.value = rooted
        rooted
    }

    override suspend fun rootImpl(): String? {
        if (!isRooted()) return null
        run("magisk -v", 5_000).takeIf { it.ok && it.out.isNotBlank() }?.let { r ->
            return "magisk " + r.out.trim().substringBefore(':')
        }
        run("ksud -V", 5_000).takeIf { it.ok && it.out.isNotBlank() }?.let { r ->
            return "kernelsu " + r.out.trim().substringAfter(' ', r.out.trim())
        }
        if (fileExists("/data/adb/ksu")) return "kernelsu"
        run("su -v", 5_000).takeIf { it.ok && it.out.isNotBlank() }?.let { r -> return "su " + r.out.trim() }
        return "unknown"
    }

    private suspend fun wrapWithTimeout(cmd: String, timeoutMs: Long): String {
        val available = hasTimeoutBinary ?: probeTimeoutBinary()
        if (!available) return cmd
        val seconds = ceil(timeoutMs / 1000.0).toLong().coerceAtLeast(1)
        return "timeout -s KILL $seconds sh -c ${shellQuote(cmd)}"
    }

    private fun probeTimeoutBinary(): Boolean {
        val found = try {
            Shell.cmd("command -v timeout").exec().isSuccess
        } catch (e: Exception) {
            false
        }
        hasTimeoutBinary = found
        if (!found) log.w(TAG, "No 'timeout' binary; shell commands run without a kill timer")
        return found
    }

    private fun looksTimedOut(code: Int, elapsedMs: Long, timeoutMs: Long): Boolean =
        elapsedMs >= timeoutMs && (code == 124 || code == 137 || code == 143)

    private fun logResult(label: String, r: ShellResult) {
        val line = "$ ${label.take(300)} -> ${r.code} (${r.durationMs} ms)" +
            (if (r.out.isNotBlank()) " out=${r.out.trim().replace('\n', ' ').take(LOG_OUTPUT_CHARS)}" else "") +
            (if (r.err.isNotBlank()) " err=${r.err.trim().replace('\n', ' ').take(LOG_OUTPUT_CHARS)}" else "")
        if (r.ok) log.d(TAG, line) else log.w(TAG, line)
    }

    private fun elapsedMs(startedNs: Long) = (System.nanoTime() - startedNs) / 1_000_000

    companion object {
        private const val TAG = "root"
        private const val GRACE_MS = 5_000L
        private const val LOG_OUTPUT_CHARS = 200

        /** Seconds libsu waits for the root grant (Magisk prompt / auto-grant) when creating the shell. */
        private const val ROOT_REQUEST_TIMEOUT_S = 20L

        /** Must run once, before any shell is created (AgentApp.onCreate). */
        fun configure() {
            runCatching {
                Shell.enableVerboseLogging = false
                Shell.setDefaultBuilder(Shell.Builder.create().setTimeout(ROOT_REQUEST_TIMEOUT_S))
            }
        }
    }
}
