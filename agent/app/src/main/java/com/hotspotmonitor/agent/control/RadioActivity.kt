package com.hotspotmonitor.agent.control

import com.hotspotmonitor.agent.core.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide gate for long radio / power operations (a user's data cycle, a watchdog recovery
 * step, a heat radio rest). Whoever holds it owns the radios; the executor answers conflicting
 * commands `busy` and the watchdog postpones its step. Single short shell actions are serialized
 * separately inside ControlsImpl, so an auto-revert undo never waits for this gate.
 */
internal object RadioActivity {
    private val owner = MutableStateFlow<String?>(null)

    /** Who holds the gate, e.g. "command:c_123" or "watchdog:data_cycle"; null when free. */
    val current: StateFlow<String?> = owner.asStateFlow()

    fun tryAcquire(who: String): Boolean = owner.compareAndSet(null, who)

    fun release(who: String) {
        owner.compareAndSet(who, null)
    }
}

/**
 * The user's wish to keep USB tethering off (a kept `usb_tethering off`, or one switched off on the
 * phone itself). The watchdog's tether rule does not turn tethering back on while it is set; it is
 * cleared when USB is disconnected or when a user turns tethering on.
 */
internal object TetherIntent {
    private const val KEY = "control.tether_user_off"

    fun userWantsOff(prefs: KeyValueStore): Boolean = prefs.getBoolean(KEY, false)

    fun set(prefs: KeyValueStore, off: Boolean) {
        if (prefs.getBoolean(KEY, false) != off) prefs.putBoolean(KEY, off)
    }
}
