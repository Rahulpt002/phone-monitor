package com.hotspotmonitor.dashboard.data

import android.content.Context
import com.hotspotmonitor.dashboard.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Phase { SIGNED_OUT, ENROLL, READY }

data class SessionState(
    val serverUrl: String,
    val token: String? = null,
    val user: UserInfo? = null,
    val totpEnrollmentRequired: Boolean = false,
    /** Shown on the sign-in screen after an automatic sign-out. */
    val notice: String? = null,
) {
    val phase: Phase
        get() = when {
            token == null -> Phase.SIGNED_OUT
            totpEnrollmentRequired -> Phase.ENROLL
            else -> Phase.READY
        }
    val isAdmin: Boolean get() = user?.isAdmin == true
    /** Admin with TOTP: may send commands, start the screen, change settings. */
    val canControl: Boolean get() = isAdmin && !totpEnrollmentRequired
}

/** Signed-in state, persisted (token encrypted). The known-device token is kept per username. */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("hsm_session", Context.MODE_PRIVATE)
    private val box = SecureBox()

    private val _state = MutableStateFlow(load())
    val state: StateFlow<SessionState> = _state.asStateFlow()
    val current: SessionState get() = _state.value

    private fun load(): SessionState {
        val url = prefs.getString(KEY_SERVER, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_SERVER_URL
        val token = prefs.getString(KEY_TOKEN, null)?.let { box.decrypt(it) }
        val user = prefs.getString(KEY_USER, null)?.let { decodeOrNull(UserInfo.serializer(), AppJson.parseToJsonElement(it)) }
        val enroll = prefs.getBoolean(KEY_ENROLL, false)
        return SessionState(serverUrl = url, token = token, user = user, totpEnrollmentRequired = enroll && token != null)
    }

    fun setServer(url: String) {
        val clean = url.trim().trimEnd('/')
        prefs.edit().putString(KEY_SERVER, clean).apply()
        _state.update { it.copy(serverUrl = clean) }
    }

    fun signedIn(token: String, user: UserInfo, enrollmentRequired: Boolean, deviceToken: String?) {
        prefs.edit()
            .putString(KEY_TOKEN, box.encrypt(token))
            .putString(KEY_USER, AppJson.encodeToString(UserInfo.serializer(), user))
            .putBoolean(KEY_ENROLL, enrollmentRequired)
            .apply()
        if (deviceToken != null) prefs.edit().putString(deviceKey(user.username), box.encrypt(deviceToken)).apply()
        _state.update { it.copy(token = token, user = user, totpEnrollmentRequired = enrollmentRequired, notice = null) }
    }

    fun updateMe(user: UserInfo, enrollmentRequired: Boolean) {
        prefs.edit()
            .putString(KEY_USER, AppJson.encodeToString(UserInfo.serializer(), user))
            .putBoolean(KEY_ENROLL, enrollmentRequired)
            .apply()
        _state.update { it.copy(user = user, totpEnrollmentRequired = enrollmentRequired) }
    }

    fun deviceToken(username: String): String? =
        prefs.getString(deviceKey(username), null)?.let { box.decrypt(it) }

    fun signOut(notice: String? = null) {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_USER).remove(KEY_ENROLL).apply()
        _state.update { SessionState(serverUrl = it.serverUrl, notice = notice) }
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    private fun deviceKey(username: String) = "device_token." + username.lowercase()

    companion object {
        private const val KEY_SERVER = "server_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_USER = "user"
        private const val KEY_ENROLL = "enroll"
    }
}
