package com.hotspotmonitor.agent.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.math.roundToInt

// Remote screen (shared/src/screen.ts, docs/protocol.md section 8).

/** Server -> Agent over /ws/agent: start streaming for this session. */
@Serializable
@SerialName("screen_start")
data class ScreenStartMsg(
    @SerialName("session_id") val sessionId: String,
    val quality: String = "standard",
    @SerialName("max_size") val maxSize: Int = 1280,
    @SerialName("bit_rate") val bitRate: Int = 2_000_000,
    @SerialName("max_fps") val maxFps: Int = 24,
    @SerialName("max_duration_s") val maxDurationS: Int = 1800,
    val user: String? = null,
) : ServerToAgent

/** Server -> Agent over /ws/agent: stop the session. */
@Serializable
@SerialName("screen_stop")
data class ScreenStopMsg(
    @SerialName("session_id") val sessionId: String,
    val reason: String = "stopped",
) : ServerToAgent

/** Agent -> Server over /ws/agent. Not buffered (a stale report is meaningless). */
@Serializable
@SerialName("screen_state")
data class ScreenStateMsg(
    @SerialName("session_id") val sessionId: String,
    /** starting | streaming | ended | failed */
    val state: String,
    val error: String? = null,
) : AgentToServer

object ScreenStates {
    const val STARTING = "starting"
    const val STREAMING = "streaming"
    const val ENDED = "ended"
    const val FAILED = "failed"
}

/** Agent -> viewer over the relay socket: video geometry. */
@Serializable
@SerialName("screen_meta")
data class ScreenMetaMsg(
    val codec: String = "h264",
    val width: Int,
    val height: Int,
    @SerialName("device_name") val deviceName: String? = null,
) : ScreenRelayOut

@Serializable
sealed interface ScreenRelayOut

/** Viewer -> Agent control messages, relayed and validated by the Server. */
@Serializable
sealed interface ScreenControl

@Serializable
@SerialName("touch")
data class ScreenTouch(val action: String, val pointer: Int = 0, val x: Double, val y: Double) : ScreenControl

@Serializable
@SerialName("scroll")
data class ScreenScroll(val x: Double, val y: Double, val dx: Double = 0.0, val dy: Double = 0.0) : ScreenControl

@Serializable
@SerialName("key")
data class ScreenKey(val key: String) : ScreenControl

@Serializable
@SerialName("text")
data class ScreenText(val text: String) : ScreenControl

@Serializable
@SerialName("panel")
data class ScreenPanel(val panel: String) : ScreenControl

@Serializable
@SerialName("keyframe")
data object ScreenKeyframe : ScreenControl

object ScreenWire {
    /** A control message, or null for an unknown or malformed one (ignored, like every receiver). */
    fun decodeControl(text: String): ScreenControl? =
        runCatching { AgentJson.decodeFromString(ScreenControl.serializer(), text) }.getOrNull()

    fun encode(msg: ScreenRelayOut): String = AgentJson.encodeToString(ScreenRelayOut.serializer(), msg)

    const val FRAME_START: Byte = 1
    const val FRAME_CONT: Byte = 2
    const val HEADER_BYTES = 12

    /** Largest binary frame (shared SCREEN.max_chunk_bytes): the Server's frame limit is 512 KiB. */
    const val MAX_CHUNK_BYTES = 256 * 1024

    const val FLAG_SESSION = 1L shl 63
    const val FLAG_CONFIG = 1L shl 62
    const val FLAG_KEY = 1L shl 61

    /**
     * Splits one scrcpy packet into relay frames (shared/src/screen.ts "Binary video frames"):
     * a START frame with the 12-byte header and the first chunk, then CONT frames.
     */
    fun frames(ptsAndFlags: Long, payload: ByteArray, maxChunk: Int = MAX_CHUNK_BYTES): List<ByteArray> {
        val out = ArrayList<ByteArray>(1 + payload.size / maxChunk)
        val firstLen = minOf(payload.size, maxChunk - 1 - HEADER_BYTES)
        val first = ByteArray(1 + HEADER_BYTES + firstLen)
        first[0] = FRAME_START
        putLong(first, 1, ptsAndFlags)
        putInt(first, 9, payload.size)
        System.arraycopy(payload, 0, first, 1 + HEADER_BYTES, firstLen)
        out += first
        var pos = firstLen
        while (pos < payload.size) {
            val len = minOf(payload.size - pos, maxChunk - 1)
            val f = ByteArray(1 + len)
            f[0] = FRAME_CONT
            System.arraycopy(payload, pos, f, 1, len)
            out += f
            pos += len
        }
        return out
    }

    private fun putLong(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 8) b[at + i] = (v ushr (56 - 8 * i)).toByte()
    }

    private fun putInt(b: ByteArray, at: Int, v: Int) {
        for (i in 0 until 4) b[at + i] = (v ushr (24 - 8 * i)).toByte()
    }
}

/**
 * scrcpy 5.0.1 control messages (server ControlMessageReader): big-endian, the first byte is the
 * type. Coordinates are pixels in the current video frame, which must match [width] x [height]
 * or the server ignores the event.
 */
object ScrcpyControl {
    const val TYPE_INJECT_KEYCODE = 0
    const val TYPE_INJECT_TEXT = 1
    const val TYPE_INJECT_TOUCH_EVENT = 2
    const val TYPE_INJECT_SCROLL_EVENT = 3
    const val TYPE_EXPAND_NOTIFICATION_PANEL = 5
    const val TYPE_EXPAND_SETTINGS_PANEL = 6
    const val TYPE_COLLAPSE_PANELS = 7
    const val TYPE_RESET_VIDEO = 17

    // android.view.KeyEvent / MotionEvent
    const val KEY_ACTION_DOWN = 0
    const val KEY_ACTION_UP = 1
    const val MOTION_ACTION_DOWN = 0
    const val MOTION_ACTION_UP = 1
    const val MOTION_ACTION_MOVE = 2
    const val MOTION_ACTION_CANCEL = 3

    const val MAX_TEXT_BYTES = 300

    val KEYCODES: Map<String, Int> = mapOf(
        "back" to 4, // KEYCODE_BACK
        "home" to 3, // KEYCODE_HOME
        "recents" to 187, // KEYCODE_APP_SWITCH
        "power" to 26, // KEYCODE_POWER
        "volume_up" to 24,
        "volume_down" to 25,
        "enter" to 66,
        "delete" to 67, // KEYCODE_DEL (backspace)
        "menu" to 82,
    )

    /**
     * Encodes [msg] as one or more scrcpy messages (a key press is down + up), or null when it
     * cannot be sent (unknown key, no video size yet, empty text).
     */
    fun encode(msg: ScreenControl, width: Int, height: Int): List<ByteArray>? = when (msg) {
        is ScreenTouch -> touch(msg, width, height)?.let { listOf(it) }
        is ScreenScroll -> scroll(msg, width, height)?.let { listOf(it) }
        is ScreenKey -> KEYCODES[msg.key]?.let { code -> listOf(key(KEY_ACTION_DOWN, code), key(KEY_ACTION_UP, code)) }
        is ScreenText -> text(msg.text)?.let { listOf(it) }
        is ScreenPanel -> when (msg.panel) {
            "notifications" -> listOf(byteArrayOf(TYPE_EXPAND_NOTIFICATION_PANEL.toByte()))
            "settings" -> listOf(byteArrayOf(TYPE_EXPAND_SETTINGS_PANEL.toByte()))
            "collapse" -> listOf(byteArrayOf(TYPE_COLLAPSE_PANELS.toByte()))
            else -> null
        }
        ScreenKeyframe -> listOf(resetVideo())
    }

    fun resetVideo(): ByteArray = byteArrayOf(TYPE_RESET_VIDEO.toByte())

    fun key(action: Int, keycode: Int): ByteArray = build {
        writeByte(TYPE_INJECT_KEYCODE)
        writeByte(action)
        writeInt(keycode)
        writeInt(0) // repeat
        writeInt(0) // metaState
    }

    fun text(value: String): ByteArray? {
        var bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) return null
        if (bytes.size > MAX_TEXT_BYTES) bytes = bytes.copyOf(MAX_TEXT_BYTES)
        return build {
            writeByte(TYPE_INJECT_TEXT)
            writeInt(bytes.size)
            write(bytes)
        }
    }

    fun touch(t: ScreenTouch, width: Int, height: Int): ByteArray? {
        if (width <= 0 || height <= 0) return null
        val action = when (t.action) {
            "down" -> MOTION_ACTION_DOWN
            "up" -> MOTION_ACTION_UP
            "move" -> MOTION_ACTION_MOVE
            "cancel" -> MOTION_ACTION_CANCEL
            else -> return null
        }
        val pressure = if (action == MOTION_ACTION_UP || action == MOTION_ACTION_CANCEL) 0 else 0xFFFF
        return build {
            writeByte(TYPE_INJECT_TOUCH_EVENT)
            writeByte(action)
            writeLong(t.pointer.coerceIn(0, 9).toLong())
            position(t.x, t.y, width, height)
            writeShort(pressure)
            writeInt(0) // actionButton
            writeInt(0) // buttons
        }
    }

    fun scroll(s: ScreenScroll, width: Int, height: Int): ByteArray? {
        if (width <= 0 || height <= 0) return null
        return build {
            writeByte(TYPE_INJECT_SCROLL_EVENT)
            position(s.x, s.y, width, height)
            writeShort(fixedI16(s.dx / 16.0))
            // Android's vertical scroll axis is positive upward; the viewer's dy is positive downward.
            writeShort(fixedI16(-s.dy / 16.0))
            writeInt(0) // buttons
        }
    }

    private fun DataOutputStream.position(x: Double, y: Double, width: Int, height: Int) {
        writeInt((x.coerceIn(0.0, 1.0) * width).toInt().coerceIn(0, width - 1))
        writeInt((y.coerceIn(0.0, 1.0) * height).toInt().coerceIn(0, height - 1))
        writeShort(width)
        writeShort(height)
    }

    /** Value in [-1, 1] as scrcpy's i16 fixed point (0x7fff = 1). */
    private fun fixedI16(v: Double): Int {
        val c = v.coerceIn(-1.0, 1.0)
        return if (c >= 1.0) 0x7fff else (c * 32768).roundToInt().coerceIn(-32768, 32767)
    }

    private inline fun build(block: DataOutputStream.() -> Unit): ByteArray {
        val bos = ByteArrayOutputStream(32)
        DataOutputStream(bos).use { it.block() }
        return bos.toByteArray()
    }
}
