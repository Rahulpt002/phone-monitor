package com.hotspotmonitor.dashboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotspotmonitor.dashboard.ui.AppRoot
import com.hotspotmonitor.dashboard.ui.LocalGraph
import com.hotspotmonitor.dashboard.ui.theme.HsmTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** Dashboard path from a link or notification ("/screen", "/alerts"), consumed by AppRoot. */
    private val deepLink = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = HsmApp.graph(this)
        handleIntent(intent)
        askNotificationPermission()
        setContent {
            val theme by graph.ui.theme.collectAsStateWithLifecycle()
            val link by deepLink.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalGraph provides graph) {
                HsmTheme(theme) {
                    AppRoot(deepLink = link, onDeepLinkHandled = { deepLink.value = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        HsmApp.graph(this).repo.socket.retryNow()
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        deepLink.value = (uri.path ?: "/") + (uri.query?.let { "?$it" } ?: "")
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
