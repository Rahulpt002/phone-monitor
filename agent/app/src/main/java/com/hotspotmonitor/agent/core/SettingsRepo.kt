package com.hotspotmonitor.agent.core

import com.hotspotmonitor.agent.protocol.AgentJson
import com.hotspotmonitor.agent.protocol.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The settings the Agent runs on (docs/protocol.md 3.4). The Server owns them; the Agent persists
 * the last applied copy so the watchdog keeps working offline, and starts from Settings.DEFAULT
 * (version 0) before the first `hello`.
 *
 * Read with `settings.value` for a one-off value, or collect [settings] to react to changes.
 *
 * @param onApplied called after a Server version is applied (outside any lock); AgentGraph emits
 *   the `settings_applied` event from it.
 */
class SettingsRepo(
    private val store: KeyValueStore,
    private val log: AgentLog,
    private val onApplied: (version: Int) -> Unit = {},
) {
    private val _settings: MutableStateFlow<Settings>
    private val _version: MutableStateFlow<Int>

    val settings: StateFlow<Settings>

    /** Last applied Server version; 0 = defaults. Sent in every heartbeat. */
    val version: StateFlow<Int>

    /** Shortcut for `settings.value`. */
    val current: Settings get() = _settings.value

    /** True after [updateLocal]; the next Server copy is applied even if its version matches. */
    @Volatile
    var hasLocalEdits: Boolean = store.getBoolean(KEY_LOCAL_EDITS, false)
        private set

    init {
        val (loaded, loadedVersion) = load()
        _settings = MutableStateFlow(loaded)
        _version = MutableStateFlow(loadedVersion)
        settings = _settings.asStateFlow()
        version = _version.asStateFlow()
    }

    /** Whether a Server copy with [version] would change what the Agent runs on. */
    fun needsApply(version: Int): Boolean = version != _version.value || hasLocalEdits

    /**
     * Applies and persists a Server copy (from `hello` or a `settings` message). Does nothing and
     * returns false when [version] is already applied and there are no local edits, unless [force].
     */
    fun apply(settings: Settings, version: Int, force: Boolean = false): Boolean {
        synchronized(this) {
            if (!force && !needsApply(version)) return false
            persist(settings, version, localEdits = false)
            _settings.value = settings
            _version.value = version
            hasLocalEdits = false
        }
        log.i(TAG, "Applied settings version $version")
        runCatching { onApplied(version) }.onFailure { log.w(TAG, "settings_applied callback failed", it) }
        return true
    }

    /**
     * Changes settings on the phone only (local UI, offline Finder tuning). The version is kept;
     * the next Server copy replaces the edit, because the Server owns the settings.
     */
    fun updateLocal(transform: (Settings) -> Settings): Settings = synchronized(this) {
        val next = transform(_settings.value)
        persist(next, _version.value, localEdits = true)
        _settings.value = next
        hasLocalEdits = true
        next
    }

    private fun persist(settings: Settings, version: Int, localEdits: Boolean) {
        store.putString(KEY_JSON, AgentJson.encodeToString(Settings.serializer(), settings))
        store.putLong(KEY_VERSION, version.toLong())
        store.putBoolean(KEY_LOCAL_EDITS, localEdits)
    }

    private fun load(): Pair<Settings, Int> {
        val json = store.getString(KEY_JSON) ?: return Settings.DEFAULT to 0
        return try {
            AgentJson.decodeFromString(Settings.serializer(), json) to store.getLong(KEY_VERSION, 0).toInt()
        } catch (e: Exception) {
            log.w(TAG, "Stored settings are unreadable; using defaults until the Server sends a copy", e)
            Settings.DEFAULT to 0
        }
    }

    companion object {
        private const val TAG = "settings"
        private const val KEY_JSON = "settings.json"
        private const val KEY_VERSION = "settings.version"
        private const val KEY_LOCAL_EDITS = "settings.local_edits"
    }
}
