package com.hotspotmonitor.agent.service

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.hotspotmonitor.agent.AgentApp
import com.hotspotmonitor.agent.R
import com.hotspotmonitor.agent.core.AgentStartReason
import com.hotspotmonitor.agent.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The foreground service that keeps the Agent alive 24/7 (plan 6.1 "Service core"). Type
 * `location`, so cell info keeps flowing with the screen off. Started at boot (BootReceiver), by
 * ADB / the Magisk keep-alive (ControlReceiver) and by the local UI; START_STICKY.
 *
 * Android 14+ only lets a `location` service start from the background when the location
 * permission (and, from BOOT_COMPLETED, background location) is granted. Until RootSetup has granted
 * them the service runs as `specialUse` and switches to `location` as soon as it may.
 *
 * Wake lock: the hotspot phone runs on USB power around the clock, so holding a partial wake lock
 * costs nothing that matters, while letting the CPU sleep would stall heartbeats (the Server would
 * report the phone offline), the WebSocket pings and the watchdog timers. The doze whitelist set by
 * RootSetup keeps the lock effective when charging is paused by the charge limit.
 */
class AgentService : LifecycleService() {
    private val graph by lazy { AgentApp.graph(this) }

    private var inForeground = false
    private var runningAsLocation = false

    /** Permission state of the last attempt to switch to the location type (attempt once per state). */
    private var lastUpgradeKey: String? = null
    private var statusText: String = ""

    private var upgradeJob: Job? = null
    private var statusJob: Job? = null
    private var wakeLockJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        statusText = getString(R.string.notification_text)
        ensureChannel()
        enterForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // A null intent means Android restarted the sticky service after the process was killed.
        val reason = AgentService.parseReason(intent?.getStringExtra(EXTRA_REASON)) ?: AgentStartReason.RESTART
        if (!inForeground) enterForeground()
        if (!inForeground) {
            graph.log.e(TAG, "Could not enter the foreground; stopping and retrying through root")
            StartRetry.schedule(this, reason)
            stopSelf()
            return START_NOT_STICKY
        }
        // Opened from the UI the app is in the foreground, so the location type is allowed now.
        if (!runningAsLocation && hasForegroundLocation()) switchToLocationType()

        acquireWakeLock()
        if (graph.startAll(reason)) graph.log.i(TAG, "Agent service started (${reason.wire})")
        StartRetry.onServiceStarted()
        startStatusUpdates()
        if (!runningAsLocation) watchForLocationPermission()
        return START_STICKY
    }

    override fun onDestroy() {
        graph.log.i(TAG, "Agent service stopping")
        statusJob?.cancel()
        upgradeJob?.cancel()
        wakeLockJob?.cancel()
        graph.stopAll()
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        inForeground = false
        super.onDestroy()
    }

    // --- foreground and service type ----------------------------------------------------------------

    private fun enterForeground() {
        val notification = buildNotification(statusText)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || hasForegroundLocation()) {
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                inForeground = true
                runningAsLocation = true
                return
            } catch (e: Exception) {
                graph.log.w(TAG, "Cannot run as a location service yet: ${describeStartError(e)}")
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                graph.log.w(TAG, "Running as a specialUse service until location access allows the location type")
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            inForeground = true
        } catch (e: Exception) {
            graph.log.e(TAG, "Could not enter the foreground: ${describeStartError(e)}", e)
        }
    }

    /** Re-declares the running service as `location` (allowed while already in the foreground). */
    private fun switchToLocationType() {
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(statusText), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
            runningAsLocation = true
            graph.log.i(TAG, "Now running as a location service")
        } catch (e: Exception) {
            graph.log.w(TAG, "Still cannot use the location service type: ${describeStartError(e)}")
        }
    }

    private fun watchForLocationPermission() {
        if (upgradeJob?.isActive == true) return
        upgradeJob = lifecycleScope.launch {
            while (isActive && !runningAsLocation) {
                delay(PERMISSION_POLL_MS)
                if (!hasForegroundLocation()) continue
                val key = "bg=${hasBackgroundLocation()}"
                if (key == lastUpgradeKey) continue
                lastUpgradeKey = key
                switchToLocationType()
            }
        }
    }

    private fun hasForegroundLocation(): Boolean =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun hasBackgroundLocation(): Boolean = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    // --- wake lock ----------------------------------------------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLockJob?.isActive == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        val lock = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).also {
            it.setReferenceCounted(false)
            wakeLock = it
        }
        // Timed and renewed, so a missed onDestroy (process killed) can never leave it held.
        wakeLockJob = lifecycleScope.launch {
            while (isActive) {
                try {
                    lock.acquire(WAKE_LOCK_TIMEOUT_MS)
                } catch (e: Exception) {
                    graph.log.w(TAG, "Could not acquire the wake lock", e)
                }
                delay(WAKE_LOCK_RENEW_MS)
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        wakeLock = null
    }

    // --- notification -------------------------------------------------------------------------------

    private fun startStatusUpdates() {
        if (statusJob?.isActive == true) return
        statusJob = lifecycleScope.launch {
            combine(graph.sync.state, graph.telemetry.radio, graph.telemetry.power) { conn, radio, power ->
                ServiceStatusText.line(conn, radio?.score, power?.percent, power?.status)
            }
                .distinctUntilChanged()
                .conflate()
                .collect { text ->
                    statusText = text
                    postNotification(text)
                    // Notification updates are rate-limited by Android anyway; keep them rare.
                    delay(STATUS_MIN_INTERVAL_MS)
                }
        }
    }

    private fun postNotification(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification(text)) }
            .onFailure { graph.log.w(TAG, "Could not update the notification", it) }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_agent)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    companion object {
        private const val TAG = "service"
        const val CHANNEL_ID = "agent_status"
        const val NOTIFICATION_ID = 1
        const val EXTRA_REASON = "reason"

        /** Set by the local UI's Stop button; opening the app then does not restart the service. */
        const val KEY_USER_STOPPED = "service.user_stopped"

        private const val PERMISSION_POLL_MS = 10_000L
        private const val STATUS_MIN_INTERVAL_MS = 5_000L
        private const val WAKE_LOCK_TAG = "HotspotAgent:service"
        private const val WAKE_LOCK_TIMEOUT_MS = 60 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_MS = 30 * 60 * 1000L

        fun parseReason(wire: String?): AgentStartReason? = AgentStartReason.entries.firstOrNull { it.wire == wire }

        /**
         * Starts (or pokes) the service. Returns false when Android refused the start; a root retry
         * is then scheduled (see [StartRetry]).
         */
        fun start(context: Context, reason: AgentStartReason): Boolean {
            val app = context.applicationContext
            val intent = Intent(app, AgentService::class.java).putExtra(EXTRA_REASON, reason.wire)
            return try {
                ContextCompat.startForegroundService(app, intent)
                true
            } catch (e: Exception) {
                AgentApp.graph(app).log.e(TAG, "Android refused to start the Agent service (${reason.wire}): ${describeStartError(e)}")
                StartRetry.schedule(app, reason)
                false
            }
        }

        /** Stops the service (local UI). The Magisk keep-alive, if installed, starts it again. */
        fun stop(context: Context) {
            val app = context.applicationContext
            app.stopService(Intent(app, AgentService::class.java))
        }

        internal fun describeStartError(e: Exception): String {
            val kind = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isStartNotAllowed(e) -> "background start not allowed"
                e is SecurityException -> "missing permission"
                else -> e.javaClass.simpleName
            }
            return "$kind (${e.message?.take(200) ?: "no details"})"
        }

        @RequiresApi(Build.VERSION_CODES.S)
        private fun isStartNotAllowed(e: Exception): Boolean = e is ForegroundServiceStartNotAllowedException
    }
}
