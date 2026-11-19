package com.hotspotmonitor.agent.control

import com.hotspotmonitor.agent.core.AgentGraph
import com.hotspotmonitor.agent.core.RebootLimiter
import com.hotspotmonitor.agent.protocol.CommandSource

/**
 * Reboot limit backed by RebootLogEntity (plan 6.4 rule 3); the rules live in [RebootLimitPolicy].
 * `nowMs` is graph.clock.systemNowMs().
 */
class RebootLimiterImpl(private val graph: AgentGraph) : RebootLimiter {
    private val policy: RebootLimitPolicy by lazy {
        RebootLimitPolicy(graph.db.rebootLogDao()) { graph.settings.current.watchdog }
    }

    override suspend fun canReboot(nowMs: Long): Boolean = policy.canReboot(nowMs)

    override suspend fun record(source: CommandSource, nowMs: Long) = policy.record(source, nowMs)

    override suspend fun countInWindow(nowMs: Long): Int = policy.countInWindow(nowMs)
}
