package com.hotspotmonitor.dashboard.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.hotspotmonitor.dashboard.BuildConfig
import com.hotspotmonitor.dashboard.MainActivity
import com.hotspotmonitor.dashboard.R
import com.hotspotmonitor.dashboard.data.ApiClient
import com.hotspotmonitor.dashboard.data.Phase
import com.hotspotmonitor.dashboard.data.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

enum class PushStatus { NOT_CONFIGURED, OFF, REGISTERING, ON, FAILED }

/**
 * Firebase Cloud Messaging for alerts while the app is closed. Firebase is set up from BuildConfig
 * (mobile/firebase.properties); without it push is simply unavailable. The token is registered
 * with the Server for the signed-in session and dropped on sign-out.
 */
class Push(
    private val context: Context,
    private val api: ApiClient,
    private val session: SessionStore,
    private val scope: CoroutineScope,
) {
    private val _status = MutableStateFlow(if (configured) PushStatus.OFF else PushStatus.NOT_CONFIGURED)
    val status: StateFlow<PushStatus> = _status.asStateFlow()
    private var token: String? = null

    val configured: Boolean
        get() = BuildConfig.FIREBASE_APP_ID.isNotBlank() && BuildConfig.FIREBASE_PROJECT_ID.isNotBlank()

    fun init() {
        createChannel()
        if (!configured) return
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                    .build(),
            )
        }
    }

    /** After sign-in (and on every start while signed in): get the token and register it. */
    fun register() {
        if (!configured || session.current.phase != Phase.READY) return
        scope.launch {
            _status.value = PushStatus.REGISTERING
            try {
                val t = FirebaseMessaging.getInstance().token.await()
                token = t
                api.registerFcm(t)
                _status.value = PushStatus.ON
            } catch (e: Exception) {
                _status.value = PushStatus.FAILED
            }
        }
    }

    fun onNewToken(t: String) {
        token = t
        if (session.current.phase == Phase.READY) scope.launch { runCatching { api.registerFcm(t) } }
    }

    suspend fun unregister(sessionToken: String) {
        val t = token ?: return
        api.unregisterFcm(t, sessionToken)
        _status.value = if (configured) PushStatus.OFF else PushStatus.NOT_CONFIGURED
    }

    private fun createChannel() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_alerts_desc)
            },
        )
    }

    /** Shows one alert notification; tapping opens [url] (a Dashboard path) in the app. */
    fun show(id: Int, title: String, body: String, url: String?) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (!url.isNullOrBlank()) data = Uri.parse(BuildConfig.DASHBOARD_URL + url)
        }
        val pi = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }

    companion object {
        const val CHANNEL_ALERTS = "alerts"
    }
}
