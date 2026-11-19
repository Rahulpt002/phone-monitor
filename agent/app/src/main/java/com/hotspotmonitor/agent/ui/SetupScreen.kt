package com.hotspotmonitor.agent.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.hotspotmonitor.agent.core.AgentGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Permission onboarding (plan 5 "Agent permissions"): runtime permissions, then background
 * location as a second step (Android requires that order), notifications, battery optimisation,
 * usage access, or everything at once through root.
 */
@Composable
fun SetupScreen(graph: AgentGraph, onDone: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val report = remember(refresh) { PermissionReport.read(context, graph) }
    var rootRunning by remember { mutableStateOf(false) }
    var rootResult by remember { mutableStateOf<String?>(null) }

    val foregroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh++
    }
    val backgroundLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refresh++
        // Android 11+ often shows no dialog for "all the time"; the app settings page is the way there.
        if (!granted) openAppDetails(context)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Set up this phone", style = MaterialTheme.typography.titleLarge)
        HintText(
            "The Agent runs headless on the hotspot phone. It needs these permissions so it keeps " +
                "working with the screen off and after reboots. On a rooted phone one button grants everything.",
        )

        SectionCard("Fastest: grant everything with root") {
            HintText(
                "Runs pm grant, appops, the doze whitelist and the location switch through Magisk/KernelSU. " +
                    "Accept the root prompt if one appears.",
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = !rootRunning,
                    onClick = {
                        rootRunning = true
                        rootResult = null
                        // Process scope: the grants finish even if the screen is left meanwhile.
                        graph.appScope.launch {
                            val text = withContext(Dispatchers.IO) {
                                try {
                                    val r = graph.rootSetup.ensure(force = true)
                                    when {
                                        !r.rooted -> "No root access. Grant the permissions one by one below."
                                        r.failed.isEmpty() -> "Done: permissions granted and background limits lifted."
                                        else -> "Partly done. Failed: " + r.failed.joinToString("; ").take(400)
                                    }
                                } catch (e: Exception) {
                                    "Root setup failed: ${e.message ?: e.javaClass.simpleName}"
                                }
                            }
                            withContext(Dispatchers.Main) {
                                rootResult = text
                                rootRunning = false
                                refresh++
                            }
                        }
                    },
                ) { Text("Grant all via root") }
                if (rootRunning) CircularProgressIndicator(Modifier.size(28.dp))
            }
            rootResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }

        SetupStep(
            title = "1. App permissions",
            done = report.missingForeground.isEmpty(),
            detail = if (report.missingForeground.isEmpty()) "All granted" else
                "Missing: " + report.missingForeground.joinToString { PermissionReport.label(it) },
            action = "Grant",
        ) { foregroundLauncher.launch(report.missingForeground.toTypedArray()) }

        SetupStep(
            title = "2. Location all the time",
            done = report.backgroundLocation,
            detail = when {
                report.backgroundLocation -> "Granted"
                !report.foregroundLocation -> "Grant location in step 1 first"
                else -> "Choose \"Allow all the time\" so cell data keeps flowing with the screen off"
            },
            action = "Allow",
            enabled = report.foregroundLocation,
        ) { backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }

        SetupStep(
            title = "3. Battery: unrestricted",
            done = report.batteryExempt,
            detail = if (report.batteryExempt) "The Agent is exempt from battery optimisation" else
                "Lets the Agent start at boot and run in the background without being stopped",
            action = "Open",
        ) { requestBatteryExemption(context) }

        SetupStep(
            title = "4. Usage access",
            done = report.usageAccess,
            detail = if (report.usageAccess) "Granted" else "Needed for data usage today and this billing cycle",
            action = "Open",
        ) { openSettings(context, Intent(AndroidSettings.ACTION_USAGE_ACCESS_SETTINGS)) }

        SetupStep(
            title = "5. Location services on",
            done = report.locationServices,
            detail = if (report.locationServices) "On" else
                "Android reports no cell or signal data while location is switched off",
            action = "Open",
        ) { openSettings(context, Intent(AndroidSettings.ACTION_LOCATION_SOURCE_SETTINGS)) }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onDone, modifier = Modifier.weight(1f)) {
                Text(if (report.allGood) "Done" else "Continue anyway")
            }
            OutlinedButton(onClick = { openAppDetails(context) }, modifier = Modifier.weight(1f)) { Text("App settings") }
        }
    }
}

@Composable
private fun SetupStep(
    title: String,
    done: Boolean,
    detail: String,
    action: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = LocalStatusColors.current
    SectionCard(title) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusPill(if (done) "OK" else "Missing", if (done) colors.good else colors.warn)
            Text(detail, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        if (!done) {
            OutlinedButton(onClick = onClick, enabled = enabled) { Text(action) }
        }
    }
}

private fun requestBatteryExemption(context: Context) {
    val direct = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    if (!openSettings(context, direct, quiet = true)) {
        openSettings(context, Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

internal fun openAppDetails(context: Context) {
    openSettings(
        context,
        Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )
}

/** Starts a settings screen; false (and a toast unless [quiet]) when this phone has none. */
internal fun openSettings(context: Context, intent: Intent, quiet: Boolean = false): Boolean = try {
    context.startActivity(intent)
    true
} catch (e: ActivityNotFoundException) {
    if (!quiet) toast(context, "This phone has no such settings screen")
    false
} catch (e: SecurityException) {
    if (!quiet) toast(context, "Android refused to open that settings screen")
    false
}
