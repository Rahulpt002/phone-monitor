package com.hotspotmonitor.agent.core

import android.os.SystemClock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Time source for the whole Agent.
 *
 * - [serverNow] / [isoNow]: best-known UTC time = phone clock + offset learned from the Server's
 *   `hello.server_time` / `probe_ack.server_time` (docs/protocol.md 3.1). Use these for every `ts`
 *   field and for command expiry, so a phone with a wrong clock still produces timestamps the Server
 *   treats as live. The offset is 0 until the first sync. The phone's clock itself is never changed.
 * - [elapsedMs]: monotonic, includes deep sleep. Use it for every duration and timer; it does not
 *   jump when the offset changes.
 * - [systemNowMs]: the raw phone clock, for local-time decisions (weekly reboot, peak hour).
 */
open class Clock {
    @Volatile
    var serverOffsetMs: Long = 0
        private set

    @Volatile
    var synced: Boolean = false
        private set

    open fun systemNowMs(): Long = System.currentTimeMillis()

    open fun elapsedMs(): Long = SystemClock.elapsedRealtime()

    fun serverNow(): Long = systemNowMs() + serverOffsetMs

    /** Alias of [serverNow]: the best-known UTC time in epoch milliseconds. */
    fun nowMs(): Long = serverNow()

    fun isoNow(): String = formatIso(serverNow())

    /**
     * Learns the offset from a Server timestamp received at [localReceiveMs] (phone clock).
     * Returns false and keeps the old offset when [serverTimeIso] cannot be parsed.
     */
    fun syncWithServer(serverTimeIso: String, localReceiveMs: Long = systemNowMs()): Boolean {
        val server = parseIso(serverTimeIso) ?: return false
        setServerOffset(server - localReceiveMs)
        return true
    }

    fun setServerOffset(offsetMs: Long) {
        serverOffsetMs = offsetMs
        synced = true
    }

    companion object {
        private val ISO_MILLIS: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        /** ISO-8601 UTC with milliseconds, e.g. 2026-11-19T10:15:00.000Z. */
        fun formatIso(epochMs: Long): String = ISO_MILLIS.format(Instant.ofEpochMilli(epochMs))

        /** Parses ISO-8601 with `Z` or an offset, with or without fractions. Null when invalid. */
        fun parseIso(text: String?): Long? {
            if (text.isNullOrBlank()) return null
            return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
                ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
        }
    }
}
