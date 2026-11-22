package com.hotspotmonitor.dashboard.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.hotspotmonitor.dashboard.HsmApp

/** Receives FCM messages (data payload: title, body, url, alert_id) and shows them. */
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        HsmApp.graph(this).push.onNewToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val title = data["title"] ?: message.notification?.title ?: "Hotspot Monitor"
        val body = data["body"] ?: message.notification?.body ?: ""
        val id = data["alert_id"]?.toIntOrNull() ?: (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        HsmApp.graph(this).push.show(id, title, body, data["url"])
    }
}
