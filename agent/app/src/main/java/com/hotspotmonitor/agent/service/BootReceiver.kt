package com.hotspotmonitor.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hotspotmonitor.agent.AgentApp
import com.hotspotmonitor.agent.core.AgentStartReason
import kotlinx.coroutines.launch

/**
 * Starts the Agent after boot and after an app update. BOOT_COMPLETED is one of the broadcasts
 * allowed to start a foreground service from the background.
 *
 * The `boot` event is not emitted here: TelemetryHubImpl emits it once per BOOT_COUNT when the
 * modules start, which also covers a service started by the Magisk script before BOOT_COMPLETED.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED -> AgentStartReason.BOOT
            Intent.ACTION_MY_PACKAGE_REPLACED -> AgentStartReason.UPDATE
            else -> return
        }
        val app = context.applicationContext
        val graph = AgentApp.graph(app)
        graph.log.i(TAG, "Received ${intent.action}")
        val pending = goAsync()
        graph.appScope.launch {
            try {
                if (reason == AgentStartReason.BOOT) {
                    // A Stop pressed before the reboot does not survive it: the phone must come back up monitored.
                    graph.prefs.putBoolean(AgentService.KEY_USER_STOPPED, false)
                }
                AgentService.start(app, reason)
            } catch (e: Exception) {
                graph.log.e(TAG, "Boot handling failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "service"
    }
}
