package com.hotspotmonitor.dashboard.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Orchestrates what the screens see: which phone is selected, REST loading for it, the live socket
 * subscription, sign-in and sign-out. Screens read [store] and [session] and call these methods.
 */
class Repo(
    context: Context,
    val api: ApiClient,
    val session: SessionStore,
    val store: Store,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("hsm_ui", Context.MODE_PRIVATE)

    lateinit var socket: DashboardSocket

    private val _deviceId = MutableStateFlow(prefs.getString(KEY_DEVICE, null))
    val deviceId: StateFlow<String?> = _deviceId.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    /** True while a screen needs the Finder mirror (rates.mirror on the phone). */
    private var finder = false
    private var loadJob: Job? = null

    /** Called when the signed-in session is usable: load everything and connect. */
    fun startSession() {
        socket.start()
        reload()
    }

    fun selectDevice(id: String) {
        if (_deviceId.value == id) return
        _deviceId.value = id
        prefs.edit().putString(KEY_DEVICE, id).apply()
        socket.subscribe(id, finder)
        reloadDevice()
    }

    fun setFinder(on: Boolean) {
        finder = on
        socket.subscribe(_deviceId.value, on)
    }

    /** Full reload: device list, then the selected phone's data. */
    fun reload() {
        loadJob?.cancel()
        loadJob = scope.launch {
            _loading.value = true
            try {
                val devices = api.devices()
                store.setDevices(devices)
                val cur = _deviceId.value
                val pick = if (cur != null && devices.any { it.deviceId == cur }) cur else devices.firstOrNull()?.deviceId
                if (pick != _deviceId.value) {
                    _deviceId.value = pick
                    prefs.edit().putString(KEY_DEVICE, pick).apply()
                }
                socket.subscribe(pick, finder)
                if (pick != null) loadDevice(pick)
                _loadError.value = null
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) _loadError.value = e.userMessage()
            } finally {
                _loading.value = false
            }
        }
    }

    fun reloadDevice() {
        val id = _deviceId.value ?: return
        loadJob?.cancel()
        loadJob = scope.launch {
            _loading.value = true
            try {
                loadDevice(id)
                _loadError.value = null
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) _loadError.value = e.userMessage()
            } finally {
                _loading.value = false
            }
        }
    }

    private suspend fun loadDevice(id: String) = coroutineScope {
        val admin = session.current.isAdmin
        val snap = async { api.device(id) }
        val commands = async { runCatching { api.commands(id) }.getOrNull() }
        val events = async { runCatching { api.events(id) }.getOrNull() }
        val alerts = async { runCatching { api.alerts(null) }.getOrNull() }
        val settings = async { runCatching { api.settings(id) }.getOrNull() }
        val spots = async { runCatching { api.spots(id) }.getOrNull() }
        val sms = async { if (admin) runCatching { api.sms(id) }.getOrNull() else null }
        store.setSnapshot(snap.await())
        commands.await()?.let(store::mergeCommands)
        events.await()?.let(store::mergeEvents)
        alerts.await()?.let(store::mergeAlerts)
        settings.await()?.let(store::setSettings)
        spots.await()?.let { store.setSpots(id, it.spots, it.baselineId) }
        sms.await()?.let { store.mergeSms(id, it) }
    }

    /** After the socket reconnects: refetch what may have changed while it was down. */
    fun onReconnect() {
        val id = _deviceId.value ?: return
        scope.launch {
            runCatching { store.setSnapshot(api.device(id)) }
            runCatching { store.mergeCommands(api.commands(id)) }
            runCatching { store.mergeEvents(api.events(id)) }
        }
    }

    // --- commands --------------------------------------------------------------------------------

    /** Sends a command for the selected phone; the record is merged so the UI follows it live. */
    suspend fun runCommand(name: String, args: JsonObject = JsonObject(emptyMap()), confirmed: Boolean = false, password: String? = null): CommandRecord {
        val rec = api.sendCommand(_deviceId.value, name, args, confirmed, password)
        store.mergeCommands(listOf(rec))
        return rec
    }

    suspend fun keep(id: String): CommandRecord = api.keepCommand(id).also { store.mergeCommands(listOf(it)) }

    // --- auth ------------------------------------------------------------------------------------

    /** Signs out locally at once; the Server session ends in the background. */
    fun signOut(notice: String? = null) {
        val token = session.current.token
        socket.stop()
        store.reset()
        session.signOut(notice)
        if (token != null && notice == null) scope.launch { onSignOut?.invoke(token); api.logout(token) }
    }

    /** Hook for push unregistration (set by AppGraph). */
    var onSignOut: (suspend (token: String) -> Unit)? = null

    suspend fun refreshMe() {
        val me = api.me()
        session.updateMe(me.user, me.totpEnrollmentRequired)
    }

    companion object {
        private const val KEY_DEVICE = "device_id"
    }
}
