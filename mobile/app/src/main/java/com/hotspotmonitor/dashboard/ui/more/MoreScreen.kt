package com.hotspotmonitor.dashboard.ui.more

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.PhonelinkSetup
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotspotmonitor.dashboard.BuildConfig
import com.hotspotmonitor.dashboard.push.PushStatus
import com.hotspotmonitor.dashboard.ui.Dest
import com.hotspotmonitor.dashboard.ui.LocalNavigate
import com.hotspotmonitor.dashboard.ui.components.Page
import com.hotspotmonitor.dashboard.ui.components.Pill
import com.hotspotmonitor.dashboard.ui.components.SectionCard
import com.hotspotmonitor.dashboard.ui.components.Tone
import com.hotspotmonitor.dashboard.ui.graph
import com.hotspotmonitor.dashboard.ui.theme.ThemeMode
import com.hotspotmonitor.dashboard.ui.theme.hsm

@Composable
fun MoreScreen() {
    val g = graph()
    val session by g.session.state.collectAsStateWithLifecycle()
    val theme by g.ui.theme.collectAsStateWithLifecycle()
    val push by g.push.status.collectAsStateWithLifecycle()
    val navigate = LocalNavigate.current
    val ctx = LocalContext.current
    Page(title = "More") {
        item {
            SectionCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp)) {
                val items = Dest.more(session.canControl)
                items.forEachIndexed { i, d ->
                    if (i > 0) HorizontalDivider(color = hsm.border, modifier = Modifier.padding(start = 56.dp))
                    MenuRow(d.icon, d.label) { navigate(d, null) }
                }
            }
        }
        item {
            SectionCard(title = "Account") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(session.user?.username ?: "", style = MaterialTheme.typography.titleMedium, color = hsm.fg)
                        Text(session.serverUrl.removePrefix("https://"), style = MaterialTheme.typography.bodySmall, color = hsm.muted)
                    }
                    Pill(if (session.isAdmin) "Admin" else "Viewer", if (session.isAdmin) Tone.INFO else Tone.NEUTRAL)
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Push notifications", color = hsm.fg, modifier = Modifier.weight(1f))
                    when (push) {
                        PushStatus.ON -> Pill("On", Tone.OK, dot = true)
                        PushStatus.REGISTERING -> Pill("Connecting", Tone.INFO)
                        PushStatus.FAILED -> Pill("Failed", Tone.DANGER)
                        PushStatus.OFF -> Pill("Off", Tone.NEUTRAL)
                        PushStatus.NOT_CONFIGURED -> Pill("Not set up", Tone.NEUTRAL)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Theme", style = MaterialTheme.typography.labelMedium, color = hsm.muted)
                Spacer(Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val modes = listOf(ThemeMode.SYSTEM to "System", ThemeMode.DARK to "Dark", ThemeMode.LIGHT to "Light")
                    modes.forEachIndexed { i, (m, label) ->
                        SegmentedButton(selected = theme == m, onClick = { g.ui.setTheme(m) }, shape = SegmentedButtonDefaults.itemShape(i, modes.size)) {
                            Text(label)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.DASHBOARD_URL))) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Open the web Dashboard")
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { g.repo.signOut() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Outlined.Logout, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Sign out")
                }
            }
        }
        item {
            Text(
                "Hotspot Monitor ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall, color = hsm.faint,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(hsm.infoSoft), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = hsm.accent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = hsm.fg, modifier = Modifier.weight(1f))
        Icon(Icons.Outlined.ChevronRight, null, tint = hsm.faint)
    }
}

/** No phone paired yet: explains pairing and links to it (admins). */
@Composable
fun NoPhone() {
    val g = graph()
    val session by g.session.state.collectAsStateWithLifecycle()
    val navigate = LocalNavigate.current
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        SectionCard {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.PhonelinkSetup, null, tint = hsm.accent, modifier = Modifier.size(40.dp))
                Text("No phone paired", style = MaterialTheme.typography.titleLarge, color = hsm.fg)
                Text(
                    if (session.canControl) "Pair the hotspot phone: create a pairing code, then scan it with the phone or run the adb command shown."
                    else "An admin needs to pair the hotspot phone first.",
                    color = hsm.muted, style = MaterialTheme.typography.bodyMedium,
                )
                if (session.canControl) Button(onClick = { navigate(Dest.SETTINGS, "pairing") }) { Text("Pair a phone") }
                OutlinedButton(onClick = { g.repo.reload() }) { Text("Refresh") }
            }
        }
    }
}
