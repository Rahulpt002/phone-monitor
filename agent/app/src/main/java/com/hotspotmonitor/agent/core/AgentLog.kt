package com.hotspotmonitor.agent.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class LogLevel(val letter: Char, val priority: Int) {
    DEBUG('D', Log.DEBUG),
    INFO('I', Log.INFO),
    WARN('W', Log.WARN),
    ERROR('E', Log.ERROR),
}

data class LogLine(val atMs: Long, val level: LogLevel, val tag: String, val message: String) {
    fun format(): String = "${Clock.formatIso(atMs)} ${level.letter}/$tag: $message"
}

/**
 * The Agent's log: every module logs through here, never through android.util.Log directly.
 *
 * - Keeps the last [capacity] lines in memory as [lines] (LogScreen shows them).
 * - Echoes to logcat with the tag prefix "HSM/".
 * - Appends to `<logDir>/agent.log`, rotated to `agent.log.1` at [maxFileBytes] (so at most
 *   2 x 1 MB on disk). File writes run on one background thread; logging never blocks on disk.
 *
 * Never log secrets (device token, hotspot password, SMS bodies in full). RootShell accepts a
 * `logAs` label for commands that contain secrets.
 */
class AgentLog(
    private val logDir: File?,
    private val echoToLogcat: Boolean = true,
    private val capacity: Int = 1000,
    private val maxFileBytes: Long = 1_000_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val buffer = ArrayDeque<LogLine>(capacity)
    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines.asStateFlow()

    private val writerThread: ExecutorService? = logDir?.let {
        Executors.newSingleThreadExecutor { r -> Thread(r, "agent-log-writer").apply { isDaemon = true } }
    }
    private var writer: BufferedWriter? = null
    private var fileBytes: Long = 0

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = log(LogLevel.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = log(LogLevel.ERROR, tag, message, error)

    fun log(level: LogLevel, tag: String, message: String, error: Throwable?) {
        val text = buildString {
            append(message.take(MAX_MESSAGE_CHARS))
            if (error != null) {
                append(" | ").append(error.javaClass.simpleName).append(": ").append(error.message ?: "")
                if (level == LogLevel.ERROR) append('\n').append(error.stackTraceToString().take(MAX_STACK_CHARS))
            }
        }
        val line = LogLine(now(), level, tag, text)
        synchronized(buffer) {
            if (buffer.size >= capacity) buffer.removeFirst()
            buffer.addLast(line)
            _lines.value = buffer.toList()
        }
        if (echoToLogcat) {
            runCatching { Log.println(level.priority, "HSM/$tag", text) }
        }
        writerThread?.let { thread ->
            runCatching { thread.execute { appendToFile(line.format()) } }
        }
    }

    /** Current and rotated log files, newest first (for export / the LogScreen share button). */
    fun files(): List<File> {
        val dir = logDir ?: return emptyList()
        return listOf(File(dir, FILE_NAME), File(dir, "$FILE_NAME.1")).filter { it.exists() }
    }

    /** Waits until queued lines are on disk (tests, before a reboot). */
    fun flush(timeoutMs: Long = 2_000) {
        val thread = writerThread ?: return
        runCatching { thread.submit { writer?.flush() }.get(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    fun clearMemory() {
        synchronized(buffer) {
            buffer.clear()
            _lines.value = emptyList()
        }
    }

    // Runs on writerThread only.
    private fun appendToFile(text: String) {
        val dir = logDir ?: return
        try {
            var w = writer ?: openWriter(dir)
            val bytes = text.toByteArray(Charsets.UTF_8).size + 1L
            if (fileBytes > 0 && fileBytes + bytes > maxFileBytes) {
                rotate(dir)
                w = writer ?: openWriter(dir)
            }
            w.write(text)
            w.newLine()
            w.flush()
            fileBytes += bytes
        } catch (e: Exception) {
            // Disk problems must never break the Agent; logcat still has the line.
            runCatching { writer?.close() }
            writer = null
        }
    }

    private fun openWriter(dir: File): BufferedWriter {
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, FILE_NAME)
        fileBytes = if (file.exists()) file.length() else 0
        return BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8)).also { writer = it }
    }

    private fun rotate(dir: File) {
        runCatching { writer?.close() }
        writer = null
        val current = File(dir, FILE_NAME)
        val old = File(dir, "$FILE_NAME.1")
        if (old.exists()) old.delete()
        if (current.exists()) current.renameTo(old)
        fileBytes = 0
        openWriter(dir)
    }

    companion object {
        const val FILE_NAME = "agent.log"
        private const val MAX_MESSAGE_CHARS = 4_000
        private const val MAX_STACK_CHARS = 4_000
    }
}
