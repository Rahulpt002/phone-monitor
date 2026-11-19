package com.hotspotmonitor.agent.service

import android.content.Context
import com.hotspotmonitor.agent.AgentApp
import com.hotspotmonitor.agent.core.AgentStartReason
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * When Android refuses to start the foreground service (Android 12+ background-start limits,
 * Android 14+ type rules), retries through root the way the Magisk keep-alive does: lift the
 * background limits (doze whitelist), then the ControlReceiver broadcast as a foreground broadcast,
 * and finally `am start-foreground-service` from the root shell, which the limits do not apply to.
 * Bounded, so a phone without root does not loop.
 */
internal object StartRetry {
    private const val TAG = "service"
    private const val MAX_ATTEMPTS = 3
    private const val BASE_DELAY_MS = 5_000L
    private const val CHECK_AFTER_BROADCAST_MS = 10_000L

    /** 0x10000020 = FLAG_RECEIVER_FOREGROUND | FLAG_INCLUDE_STOPPED_PACKAGES (as the Magisk module). */
    private const val BROADCAST_FLAGS = "0x10000020"

    private val attempts = AtomicInteger(0)

    fun onServiceStarted() {
        attempts.set(0)
    }

    fun schedule(context: Context, reason: AgentStartReason) {
        val app = context.applicationContext
        val graph = AgentApp.graph(app)
        val attempt = attempts.incrementAndGet()
        if (attempt > MAX_ATTEMPTS) {
            graph.log.e(TAG, "Android refused to start the Agent service $MAX_ATTEMPTS times; open the app once or check root")
            return
        }
        graph.appScope.launch {
            delay(BASE_DELAY_MS * attempt)
            if (graph.running.value) return@launch
            if (!graph.shell.isRooted()) {
                graph.log.e(TAG, "Cannot start the service from the background and there is no root to retry with; open the app once")
                return@launch
            }
            val pkg = app.packageName
            graph.log.w(TAG, "Retrying the service start through root (attempt $attempt, ${reason.wire})")
            graph.shell.run("dumpsys deviceidle whitelist +$pkg")
            graph.shell.run(
                "am broadcast --user 0 -a ${ControlReceiver.ACTION_START} " +
                    "-n $pkg/${ControlReceiver::class.java.name} -f $BROADCAST_FLAGS " +
                    "--es ${AgentService.EXTRA_REASON} ${reason.wire}",
            )
            delay(CHECK_AFTER_BROADCAST_MS)
            if (graph.running.value) return@launch
            graph.shell.run(
                "am start-foreground-service --user 0 -n $pkg/${AgentService::class.java.name} " +
                    "--es ${AgentService.EXTRA_REASON} ${reason.wire}",
            )
        }
    }
}
