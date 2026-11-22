package com.hotspotmonitor.dashboard.ui.controls

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotspotmonitor.dashboard.ui.Dest
import com.hotspotmonitor.dashboard.ui.LocalNavigate
import com.hotspotmonitor.dashboard.ui.components.Notice
import com.hotspotmonitor.dashboard.ui.components.Page
import com.hotspotmonitor.dashboard.ui.components.PendingReverts
import com.hotspotmonitor.dashboard.ui.components.SectionCard
import com.hotspotmonitor.dashboard.ui.components.SectionTitle
import com.hotspotmonitor.dashboard.ui.components.Tone
import com.hotspotmonitor.dashboard.ui.graph
import com.hotspotmonitor.dashboard.ui.more.NoPhone
import com.hotspotmonitor.dashboard.ui.theme.hsm

private class ControlGroup(val id: String, val title: String, val subtitle: String?, val names: List<String>)

/** Topic groups; the dangerous ones live in their own zone at the bottom. */
private val GROUPS = listOf(
    ControlGroup(
        "radio", "Radio and data", "Mobile data, airplane mode, sharing and radios.",
        listOf("mobile_data", "data_cycle", "airplane_mode", "airplane_cycle", "network_mode", "usb_tethering", "hotspot", "wifi", "bluetooth"),
    ),
    ControlGroup("power", "Power and screen", null, listOf("charging", "charge_limit", "screen", "brightness")),
    ControlGroup("apps", "Apps and processes", null, listOf("app_list", "app_force_stop", "app_enable", "process_list")),
    ControlGroup("admin", "Admin", null, listOf("refresh", "capability_probe", "watchdog_simulate")),
)

private val DANGER_ZONE = listOf("reboot", "power_off", "app_disable", "shell")

@Composable
fun ControlsScreen() {
    val g = graph()
    val data by g.store.state.collectAsStateWithLifecycle()
    val deviceId by g.repo.deviceId.collectAsStateWithLifecycle()
    val loading by g.repo.loading.collectAsStateWithLifecycle()
    val loadError by g.repo.loadError.collectAsStateWithLifecycle()
    val runner = rememberCommandRunner()
    val env = rememberControlEnv(runner)
    val navigate = LocalNavigate.current

    if (!loading && data.devices.isEmpty() && loadError == null) {
        NoPhone()
        return
    }

    CommandDialogHost(runner)
    val snap = env.snapshot
    Page(
        title = "Controls",
        subtitle = "Every action is audited. Changes that can cut the phone's own link undo themselves unless confirmed.",
        refreshing = loading,
        onRefresh = { g.repo.reloadDevice() },
    ) {
        if (loadError != null) item(key = "err") { Notice(Tone.DANGER, "Couldn't load the phone", loadError) }
        if (!env.isAdmin) {
            item(key = "viewer") { Notice(Tone.INFO, "Read-only account", "Only admins can send commands to the phone.") }
            return@Page
        }
        if (env.totpRequired) {
            item(key = "totp") { Notice(Tone.WARN, "Controls are locked", "Admins need two-factor authentication before sending commands.") }
        }
        if (snap != null && !snap.online) {
            item(key = "offline") {
                Notice(
                    Tone.WARN, "The phone is offline",
                    "Commands are queued and delivered when it reconnects, unless they expire first (${env.settings.commands.expiryS} s).",
                )
            }
        }
        if (snap != null && env.capabilities.isEmpty()) {
            item(key = "nocaps") {
                Notice(Tone.INFO, null, "The phone has not reported its capability map yet, so every control is shown as available.")
            }
        }
        item(key = "reverts") { PendingReverts() }

        for (group in GROUPS) {
            item(key = "h_${group.id}") {
                Column {
                    SectionTitle(group.title)
                    if (group.subtitle != null) Text(group.subtitle, style = MaterialTheme.typography.bodySmall, color = hsm.muted)
                }
            }
            items(group.names, key = { "c_$it" }) { name -> CommandCard(name, env) }
        }

        item(key = "h_elsewhere") { SectionTitle("Elsewhere") }
        item(key = "elsewhere") {
            SectionCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                GoRow(Icons.Outlined.Sms, "Messages", "Send texts and run USSD codes") { navigate(Dest.MESSAGES, null) }
                HorizontalDivider(color = hsm.border)
                GoRow(Icons.Outlined.Explore, "Signal Finder", "Speed tests, spot tests and the baseline") { navigate(Dest.FINDER, null) }
                HorizontalDivider(color = hsm.border)
                GoRow(Icons.Outlined.Memory, "Command log", "Every command with its output") { navigate(Dest.SYSTEM, "commands") }
            }
        }

        item(key = "h_danger") {
            Column(Modifier.padding(top = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.WarningAmber, null, tint = hsm.danger, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Danger zone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = hsm.danger)
                }
                Text(
                    "Each of these asks for confirmation and your password. A reboot counts toward the reboot limit; " +
                        "a phone that is powered off needs a hand to switch it back on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = hsm.muted,
                )
            }
        }
        items(DANGER_ZONE, key = { "d_$it" }) { name -> CommandCard(name, env, danger = true) }
    }
}

@Composable
private fun GoRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, tint = hsm.accent, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = hsm.fg)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = hsm.muted)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = hsm.faint)
    }
}
