package com.hotspotmonitor.dashboard

import android.app.Application
import android.content.Context
import com.hotspotmonitor.dashboard.data.ApiClient
import com.hotspotmonitor.dashboard.data.DashboardSocket
import com.hotspotmonitor.dashboard.data.Repo
import com.hotspotmonitor.dashboard.data.SessionStore
import com.hotspotmonitor.dashboard.data.Store
import com.hotspotmonitor.dashboard.push.Push
import com.hotspotmonitor.dashboard.ui.UiPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Process-wide objects (a small manual service locator, like the Agent's AgentGraph). */
class AppGraph(val app: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // A free-tier Server can take up to a minute to wake up.
        .readTimeout(70, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val session = SessionStore(app)
    val store = Store()
    val api = ApiClient(http, session)
    val repo = Repo(app, api, session, store, scope)
    val push = Push(app, api, session, scope)
    val ui = UiPrefs(app)

    init {
        repo.socket = DashboardSocket(
            http = http,
            session = session,
            scope = scope,
            onMessage = store::apply,
            onAuthFailed = { enrollment ->
                if (enrollment) {
                    session.current.user?.let { session.updateMe(it, true) }
                } else {
                    repo.signOut("Your session has expired. Please sign in again.")
                }
            },
            onRoleChanged = { scope.launch { runCatching { repo.refreshMe() } } },
            onOpen = { reconnect -> if (reconnect) repo.onReconnect() },
        )
        api.onUnauthorized = {
            if (session.current.token != null) repo.signOut("Your session has expired. Please sign in again.")
        }
        api.onEnrollmentRequired = { session.current.user?.let { session.updateMe(it, true) } }
        repo.onSignOut = { token -> push.unregister(token) }
    }
}

class HsmApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.push.init()
    }

    companion object {
        fun graph(context: Context): AppGraph = (context.applicationContext as HsmApp).graph
    }
}
