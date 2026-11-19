package com.hotspotmonitor.agent.finder

import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * Pure parts of headless control (plan 7.3 "Headless"): reading volume-key presses from the Linux
 * input layer and turning them into long-press gestures. Unit-tested in HeadlessInputTest; the
 * process handling lives in HeadlessKeyListener.
 */

enum class VolumeKey { UP, DOWN }

/** SHORT presses are ignored (pocket / accidental presses); LONG >= 1 s; VERY_LONG >= 3 s. */
enum class PressKind { SHORT, LONG, VERY_LONG }

data class KeyGesture(val key: VolumeKey, val kind: PressKind, val durationMs: Long)

/** One volume-key transition. [atMs] is the kernel event time in ms, or null when unknown. */
data class KeyEdge(val key: VolumeKey, val down: Boolean, val atMs: Long?)

object Getevent {
    const val EV_KEY = 1
    const val KEY_VOLUMEDOWN = 114
    const val KEY_VOLUMEUP = 115

    /** `struct input_event` size for a 64-bit / 32-bit reader process. */
    const val EVENT_SIZE_64 = 24
    const val EVENT_SIZE_32 = 16

    private val TIMESTAMP = Regex("""^\[\s*(\d+)\.(\d+)\s*]""")
    private val ADD_DEVICE = Regex("""^add device \d+:\s*(\S+)""")
    private val SECTION = Regex("""^\s*[A-Z]{2,}\s*\([0-9a-fA-F]{4}\):""")

    fun volumeKeyOf(code: Int): VolumeKey? = when (code) {
        KEY_VOLUMEUP -> VolumeKey.UP
        KEY_VOLUMEDOWN -> VolumeKey.DOWN
        else -> null
    }

    /**
     * Parses one line of `getevent -l -q [-t]` (or the numeric form without -l), e.g.
     * `[   8401.120552] /dev/input/event2: EV_KEY       KEY_VOLUMEDOWN       DOWN` or
     * `/dev/input/event0: 0001 0073 00000000`. Returns null for everything except a volume-key
     * press or release (autorepeat included).
     */
    fun parseKeyLine(line: String): KeyEdge? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val atMs = TIMESTAMP.find(trimmed)?.let { m ->
            val sec = m.groupValues[1].toLongOrNull() ?: return@let null
            val frac = m.groupValues[2].padEnd(6, '0').take(6).toLongOrNull() ?: return@let null
            sec * 1000 + frac / 1000
        }
        val tokens = trimmed.replace(TIMESTAMP, "").trim().split(Regex("\\s+"))
        if (tokens.size < 3) return null
        val (type, code, value) = tokens.takeLast(3)
        if (type != "EV_KEY" && hexOrNull(type) != EV_KEY) return null
        val key = when (code) {
            "KEY_VOLUMEUP" -> VolumeKey.UP
            "KEY_VOLUMEDOWN" -> VolumeKey.DOWN
            else -> hexOrNull(code)?.let(::volumeKeyOf)
        } ?: return null
        val down = when (value) {
            "DOWN" -> true
            "UP" -> false
            "REPEAT" -> return null
            else -> when (hexOrNull(value)) {
                1 -> true
                0 -> false
                else -> return null
            }
        }
        return KeyEdge(key, down, atMs)
    }

    /**
     * Input devices that can report a volume key, from the output of `getevent -lp` (labels) or
     * `getevent -p` (hex codes 0072 / 0073 in the KEY section).
     */
    fun volumeKeyDevices(output: String): List<String> {
        val found = LinkedHashSet<String>()
        var device: String? = null
        var inKeySection = false
        for (raw in output.lineSequence()) {
            val line = raw.trimEnd()
            val added = ADD_DEVICE.find(line.trim())
            if (added != null) {
                device = added.groupValues[1]
                inKeySection = false
                continue
            }
            val current = device ?: continue
            val section = SECTION.find(line)
            if (section != null) {
                inKeySection = line.trimStart().startsWith("KEY")
            } else if (line.isNotEmpty() && !line.startsWith("        ") && line.trim().endsWith(":")) {
                // "input props:", "events:" and similar headers end the KEY list.
                inKeySection = false
            } else if (line.trim().startsWith("name:") || line.trim().startsWith("input props")) {
                inKeySection = false
            }
            if (!inKeySection) continue
            val body = if (section != null) line.substring(section.range.last + 1) else line
            val tokens = body.trim().split(Regex("\\s+"))
            val hasVolume = tokens.any { t ->
                t == "KEY_VOLUMEUP" || t == "KEY_VOLUMEDOWN" ||
                    (t.length == 4 && hexOrNull(t)?.let(::volumeKeyOf) != null)
            }
            if (hasVolume) found.add(current)
        }
        return found.toList()
    }

    private fun hexOrNull(token: String): Int? =
        if (token.isNotEmpty() && token.length <= 8 && token.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            token.toLongOrNull(16)?.toInt()
        } else {
            null
        }
}

/**
 * Decodes raw `struct input_event` records as read from /dev/input/eventN (native byte order).
 * Keeps a partial record between [feed] calls. Only volume-key press/release edges are returned.
 */
class InputEventDecoder(private val eventSize: Int, private val order: ByteOrder = ByteOrder.nativeOrder()) {
    init {
        require(eventSize == Getevent.EVENT_SIZE_64 || eventSize == Getevent.EVENT_SIZE_32) { "unsupported event size $eventSize" }
    }

    private val pending = ByteArray(eventSize)
    private var pendingLen = 0

    fun feed(bytes: ByteArray, length: Int = bytes.size): List<KeyEdge> {
        val edges = ArrayList<KeyEdge>()
        var offset = 0
        while (offset < length) {
            val take = minOf(eventSize - pendingLen, length - offset)
            System.arraycopy(bytes, offset, pending, pendingLen, take)
            pendingLen += take
            offset += take
            if (pendingLen == eventSize) {
                decode(pending)?.let(edges::add)
                pendingLen = 0
            }
        }
        return edges
    }

    private fun decode(record: ByteArray): KeyEdge? {
        val buf = ByteBuffer.wrap(record).order(order)
        val sec: Long
        val usec: Long
        val base: Int
        if (eventSize == Getevent.EVENT_SIZE_64) {
            sec = buf.getLong(0)
            usec = buf.getLong(8)
            base = 16
        } else {
            sec = buf.getInt(0).toLong() and 0xffffffffL
            usec = buf.getInt(4).toLong() and 0xffffffffL
            base = 8
        }
        val type = buf.getShort(base).toInt() and 0xffff
        val code = buf.getShort(base + 2).toInt() and 0xffff
        val value = buf.getInt(base + 4)
        if (type != Getevent.EV_KEY) return null
        val key = Getevent.volumeKeyOf(code) ?: return null
        val down = when (value) {
            1 -> true
            0 -> false
            else -> return null // 2 = autorepeat
        }
        val atMs = if (sec > 0 || usec > 0) sec * 1000 + usec / 1000 else null
        return KeyEdge(key, down, atMs)
    }
}

/**
 * Turns press/release edges into gestures, measured with the kernel timestamps when both edges
 * have one (immune to delivery delays), else with [nowMs] at arrival. A release without a press
 * and repeated presses are ignored. Thread-safe.
 */
class LongPressDetector(
    private val longMs: Long = LONG_PRESS_MS,
    private val veryLongMs: Long = VERY_LONG_PRESS_MS,
) {
    private data class Press(val eventMs: Long?, val arrivalMs: Long)

    private val pressed = HashMap<VolumeKey, Press>()

    /** Returns the gesture when [edge] releases a key, else null. */
    @Synchronized
    fun onEdge(edge: KeyEdge, nowMs: Long): KeyGesture? {
        if (edge.down) {
            if (!pressed.containsKey(edge.key)) pressed[edge.key] = Press(edge.atMs, nowMs)
            return null
        }
        val press = pressed.remove(edge.key) ?: return null
        val duration = if (press.eventMs != null && edge.atMs != null && edge.atMs >= press.eventMs) {
            edge.atMs - press.eventMs
        } else {
            nowMs - press.arrivalMs
        }
        return KeyGesture(edge.key, classify(duration), duration)
    }

    @Synchronized
    fun isHeld(key: VolumeKey): Boolean = pressed.containsKey(key)

    @Synchronized
    fun reset() = pressed.clear()

    fun classify(durationMs: Long): PressKind = when {
        durationMs >= veryLongMs -> PressKind.VERY_LONG
        durationMs >= longMs -> PressKind.LONG
        else -> PressKind.SHORT
    }

    companion object {
        const val LONG_PRESS_MS = 1_000L
        const val VERY_LONG_PRESS_MS = 3_000L
    }
}
