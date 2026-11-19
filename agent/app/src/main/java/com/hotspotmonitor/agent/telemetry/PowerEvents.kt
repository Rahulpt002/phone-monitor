package com.hotspotmonitor.agent.telemetry

import com.hotspotmonitor.agent.control.ChargeSwitchWrite

/**
 * Tells a real USB power loss from the plug change the Agent's own charge switch causes.
 *
 * Several charge_control paths (input_suspend, batt_slate_mode, ...) cut USB input, so Android
 * reports the charger as unplugged (ACTION_POWER_DISCONNECTED) right after the Agent stops charging
 * for the limit or the heat rule, and as plugged in again when it resumes. Those must not become
 * `power_lost` / `power_connected` events (power_lost raises the usb_power_lost alert).
 *
 * - A disconnect within [windowMs] of the Agent writing "off" is its own doing: no event.
 * - The matching connect within [windowMs] of the Agent writing "on" is not reported either.
 * - While such a disconnect is pending, a real loss shows as: the USB data link (USB_STATE, the
 *   tethering host) going away, or the plug not coming back within [windowMs] after charging was
 *   switched on again. [check] then reports the loss once.
 *
 * Thread-safe; times are Clock.elapsedMs.
 */
internal class PowerEventFilter(private val windowMs: Long = WINDOW_MS) {
    private var pending = false
    private var usbAtPending: Boolean? = null

    enum class Lost { USB_GONE, NOT_BACK_AFTER_RESUME }

    /** ACTION_POWER_DISCONNECTED; true when it is a real loss to report as `power_lost`. */
    @Synchronized
    fun onDisconnected(nowMs: Long, lastWrite: ChargeSwitchWrite?, usbConnected: Boolean?): Boolean {
        val own = lastWrite != null && !lastWrite.enabled && within(nowMs, lastWrite)
        pending = own
        usbAtPending = if (own) usbConnected else null
        return !own
    }

    /** ACTION_POWER_CONNECTED; true when it is to be reported as `power_connected`. */
    @Synchronized
    fun onConnected(nowMs: Long, lastWrite: ChargeSwitchWrite?): Boolean {
        val wasPending = pending
        pending = false
        usbAtPending = null
        val own = wasPending && lastWrite != null && lastWrite.enabled && within(nowMs, lastWrite)
        return !own
    }

    /**
     * Periodic check while a self-caused disconnect is pending. Returns how a real loss showed, once,
     * or null.
     */
    @Synchronized
    fun check(nowMs: Long, lastWrite: ChargeSwitchWrite?, plugged: Boolean?, usbConnected: Boolean?): Lost? {
        if (!pending) return null
        if (plugged == true) {
            // Back without a broadcast we saw: nothing was lost.
            pending = false
            usbAtPending = null
            return null
        }
        val lost = when {
            usbAtPending == true && usbConnected == false -> Lost.USB_GONE
            lastWrite != null && lastWrite.enabled && nowMs - lastWrite.atMs > windowMs -> Lost.NOT_BACK_AFTER_RESUME
            else -> null
        }
        if (lost != null) {
            pending = false
            usbAtPending = null
        }
        return lost
    }

    private fun within(nowMs: Long, write: ChargeSwitchWrite): Boolean = nowMs - write.atMs in -SKEW_MS..windowMs

    companion object {
        /** A plug change this soon after the Agent's own switch write is attributed to it. */
        const val WINDOW_MS = 30_000L

        /** The write's time is taken after the shell write returns; the broadcast may come first. */
        private const val SKEW_MS = 10_000L
    }
}
