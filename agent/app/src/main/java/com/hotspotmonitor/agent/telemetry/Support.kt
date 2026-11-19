package com.hotspotmonitor.agent.telemetry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import com.hotspotmonitor.agent.core.AgentLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Logs a failing collector once per failure kind instead of on every tick (and once more when it
 * works again), so a permanently missing permission or sysfs file does not flood the log ring.
 */
internal class FailureLog(private val log: AgentLog, private val tag: String) {
    private val failing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun fail(kind: String, message: String, error: Throwable? = null) {
        if (failing.add(kind)) log.w(tag, "$message (logged once until '$kind' works again)", error)
    }

    fun ok(kind: String) {
        if (failing.remove(kind)) log.i(tag, "'$kind' works again")
    }
}

/**
 * Runs [block]; on failure logs once per [kind] and returns null. Cancellation is rethrown.
 * LinkageError covers vendor ROMs that lack a documented method or class.
 */
internal inline fun <T> FailureLog.soft(kind: String, block: () -> T): T? = try {
    block().also { ok(kind) }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    fail(kind, "$kind failed", e)
    null
} catch (e: LinkageError) {
    fail(kind, "$kind is not available on this ROM", e)
    null
}

/** Runs framework callbacks on the calling binder thread; they only store a value. */
internal val DirectExecutor: Executor = Executor { it.run() }

internal fun Context.hasPermission(permission: String): Boolean =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

/**
 * Registers [receiver] for protected system broadcasts. Exported, because some of them come from
 * system modules that do not run as the system uid (tethering); only the system may send them.
 */
internal fun Context.registerSystemReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    } else {
        registerReceiver(receiver, filter)
    }
}

/** The current sticky broadcast for [action] (battery, USB state, tethering), or null. */
internal fun Context.stickyIntent(action: String): Intent? {
    val filter = IntentFilter(action)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        registerReceiver(null, filter, Context.RECEIVER_EXPORTED)
    } else {
        registerReceiver(null, filter)
    }
}

internal fun Context.unregisterQuietly(receiver: BroadcastReceiver) {
    try {
        unregisterReceiver(receiver)
    } catch (e: IllegalArgumentException) {
        // Not registered (start failed half-way); nothing to undo.
    }
}

/** Exit code and merged stdout/stderr of a local (non-root) process. */
internal data class ProcessOutput(val code: Int, val out: String, val timedOut: Boolean)

/**
 * Runs a program without a shell (arguments are passed as-is, so nothing needs quoting) and
 * kills it after [timeoutMs]. Null when it could not be started. Meant for small outputs (ping,
 * getprop): the output is read after the process ends.
 */
internal suspend fun runProcess(command: List<String>, timeoutMs: Long): ProcessOutput? = runInterruptible(Dispatchers.IO) {
    val process = try {
        ProcessBuilder(command).redirectErrorStream(true).start()
    } catch (e: IOException) {
        return@runInterruptible null
    } catch (e: SecurityException) {
        return@runInterruptible null
    }
    try {
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) process.destroy()
        val out = process.inputStream.bufferedReader().use { it.readText() }
        ProcessOutput(code = if (finished) process.exitValue() else -1, out = out, timedOut = !finished)
    } finally {
        process.destroy()
    }
}

/** Suspends until the OkHttp call completes; cancelling the coroutine cancels the call. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation {
        try {
            cancel()
        } catch (e: Exception) {
            // Cancelling an OkHttp call never needs handling.
        }
    }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }

        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    })
}
