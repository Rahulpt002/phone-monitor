package com.hotspotmonitor.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hotspotmonitor.agent.AgentApp
import com.hotspotmonitor.agent.core.AgentStartReason

/**
 * Exported entry point for ADB and the Magisk keep-alive script:
 *
 *   am broadcast -a com.hotspotmonitor.agent.action.START -n com.hotspotmonitor.agent/.service.ControlReceiver
 *
 * It can only start the service. The optional string extra `reason` (boot | restart | update |
 * manual, default manual) labels the `agent_start` event; any other value is ignored.
 */
class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_START) return
        val reason = AgentService.parseReason(intent.getStringExtra(AgentService.EXTRA_REASON)) ?: AgentStartReason.MANUAL
        AgentApp.graph(context).log.i(TAG, "Start requested by broadcast (${reason.wire})")
        AgentService.start(context, reason)
    }

    companion object {
        const val ACTION_START = "com.hotspotmonitor.agent.action.START"
        private const val TAG = "service"
    }
}
