package com.hotspotmonitor.agent

import android.app.Application
import android.content.Context
import com.hotspotmonitor.agent.core.AgentGraph
import com.hotspotmonitor.agent.root.LibsuRootShell

/** Creates the [AgentGraph] once per process. Get it anywhere with `AgentApp.graph(context)`. */
class AgentApp : Application() {
    lateinit var graph: AgentGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // libsu reads its builder when the first shell is created; configure before anything runs.
        LibsuRootShell.configure()
        graph = AgentGraph(this)
        graph.log.i("app", "Process started, Agent ${graph.agentVersion}")
    }

    companion object {
        fun graph(context: Context): AgentGraph = (context.applicationContext as AgentApp).graph
    }
}
