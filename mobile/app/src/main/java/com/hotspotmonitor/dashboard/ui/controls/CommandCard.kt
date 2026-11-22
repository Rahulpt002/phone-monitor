package com.hotspotmonitor.dashboard.ui.controls

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hotspotmonitor.agent.protocol.COMMANDS
import com.hotspotmonitor.agent.protocol.CapabilityEntry
import com.hotspotmonitor.agent.protocol.CommandSpec
import com.hotspotmonitor.agent.protocol.Settings
import com.hotspotmonitor.agent.protocol.UsbFunction
import com.hotspotmonitor.dashboard.data.CommandRecord
import com.hotspotmonitor.dashboard.data.DeviceSnapshot
import com.hotspotmonitor.dashboard.data.trimNumber
import com.hotspotmonitor.dashboard.ui.Dest
import com.hotspotmonitor.dashboard.ui.LocalNavigate
import com.hotspotmonitor.dashboard.ui.components.LinkButton
import com.hotspotmonitor.dashboard.ui.components.NETWORK_MODE_LABELS
import com.hotspotmonitor.dashboard.ui.components.Pill
import com.hotspotmonitor.dashboard.ui.components.RevertCountdown
import com.hotspotmonitor.dashboard.ui.components.Tone
import com.hotspotmonitor.dashboard.ui.graph
import com.hotspotmonitor.dashboard.ui.theme.hsm
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

// One control from the command catalog (port of web CommandCard.tsx + forms.tsx): label, safety
// badges, capability, its own argument form, armed auto-reverts and the last result.

/** Everything a control needs from its screen, read once per recomposition. */
class ControlEnv(
    val runner: CommandRunner,
    val snapshot: DeviceSnapshot?,
    val settings: Settings,
    val lastByName: Map<String, CommandRecord>,
    val pending: List<CommandRecord>,
    val capabilities: Map<String, CapabilityEntry>,
    val isAdmin: Boolean,
    val canControl: Boolean,
    val totpRequired: Boolean,
)

@Composable
fun rememberControlEnv(runner: CommandRunner): ControlEnv {
    val g = graph()
    val data by g.store.state.collectAsStateWithLifecycle()
    val deviceId by g.repo.deviceId.collectAsStateWithLifecycle()
    val session by g.session.state.collectAsStateWithLifecycle()
    val snap = deviceId?.let { data.snapshots[it] }
    return ControlEnv(
        runner = runner,
        snapshot = snap,
        settings = deviceId?.let { data.settings[it]?.typed } ?: Settings.DEFAULT,
        lastByName = data.lastCommandByName(deviceId),
        pending = data.pendingReverts(deviceId),
        capabilities = snap?.groups?.capabilities ?: emptyMap(),
        isAdmin = session.isAdmin,
        canControl = session.canControl,
        totpRequired = session.totpEnrollmentRequired,
    )
}

data class ControlState(val supported: Boolean, val disabled: Boolean, val reason: String? = null)

/** Unsupported per the capability map, blocked by policy, or locked for this account. */
fun controlState(name: String, env: ControlEnv): ControlState {
    val cap = env.capabilities[name]
    if (cap?.supported == false) {
        return ControlState(false, true, "Not supported on this phone" + (cap.note?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "."))
    }
    if (name == "power_off" && !env.settings.commands.allowPowerOff) {
        return ControlState(
            true, true,
            "Blocked by default: a phone that is switched off needs a hand to switch it on. Enable \"Allow remote power off\" in Settings > Command safety.",
        )
    }
    if (env.totpRequired) return ControlState(true, true, "Set up two-factor authentication to use controls.")
    if (!env.canControl) return ControlState(true, true, "Only admins with two-factor authentication can send commands.")
    return ControlState(true, false)
}

fun hasRevert(spec: CommandSpec): Boolean {
    val on = buildJsonObject { put("enabled", true) }
    val off = buildJsonObject { put("enabled", false) }
    return spec.revertFor(on) != null || spec.revertFor(off) != null || spec.revertFor(JsonObject(emptyMap())) != null
}

/** Current on/off state reported by the phone, when it reports one. */
fun currentToggle(name: String, snap: DeviceSnapshot?): Boolean? {
    val gr = snap?.groups ?: return null
    return when (name) {
        "mobile_data" -> gr.radio?.dataEnabled
        "airplane_mode" -> gr.radio?.airplane
        "charging" -> gr.power?.chargingEnabled
        "usb_tethering" -> gr.tether?.usbTetherOn
        "hotspot" -> gr.tether?.hotspotOn
        "screen" -> gr.system?.screenOn
        else -> null
    }
}

/** What a form body gets. [run] sends with [key] marking which button is busy. */
class ControlProps(
    val name: String,
    val spec: CommandSpec,
    val env: ControlEnv,
    val disabled: Boolean,
    val busyKey: String?,
    val last: CommandRecord?,
    val run: (key: String, args: JsonObject) -> Unit,
) {
    val busy: Boolean get() = busyKey != null
    fun busy(key: String) = busyKey == key
}

private fun obj(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
    for ((k, v) in pairs) when (v) {
        is Boolean -> put(k, v)
        is Number -> put(k, v)
        else -> put(k, v.toString())
    }
}

/**
 * A control card. [compact] hides the help line and output; [danger] draws the danger-zone frame.
 */
@Composable
fun CommandCard(name: String, env: ControlEnv, modifier: Modifier = Modifier, compact: Boolean = false, danger: Boolean = false) {
    val spec = COMMANDS[name] ?: return
    val state = controlState(name, env)
    val cap = env.capabilities[name]
    val last = env.lastByName[name]
    val armed = env.pending.filter { it.name == name }
    val scope = rememberCoroutineScope()
    var busyKey by remember { mutableStateOf<String?>(null) }
    val props = ControlProps(name, spec, env, state.disabled, busyKey, last) { key, args ->
        if (busyKey == null) {
            busyKey = key
            scope.launch {
                try {
                    env.runner.run(name, args)
                } finally {
                    busyKey = null
                }
            }
        }
    }

    Card(
        modifier = modifier.fillMaxWidth().then(if (!state.supported) Modifier.alpha(0.75f) else Modifier),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = hsm.surface, contentColor = hsm.fg),
        border = BorderStroke(1.dp, if (danger) hsm.danger.copy(alpha = 0.45f) else hsm.border),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Header: label, live state, safety badges.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(spec.label, style = MaterialTheme.typography.titleMedium, color = if (danger) hsm.danger else hsm.fg, modifier = Modifier.weight(1f))
                if (!state.supported) {
                    Pill("Not supported", Tone.NEUTRAL)
                } else {
                    val now = currentToggle(name, env.snapshot)
                    if (now != null) Pill(if (now) "Now on" else "Now off", if (now) Tone.OK else Tone.NEUTRAL, dot = true)
                }
            }
            if (state.supported && (spec.confirm || spec.reauth || hasRevert(spec))) {
                ChipRow {
                    if (spec.confirm) Pill("Confirm", Tone.NEUTRAL)
                    if (spec.reauth) Pill("Password", Tone.WARN)
                    if (hasRevert(spec)) Pill("Auto-revert", Tone.WARN)
                }
            }
            if (!compact) COMMAND_HELP[name]?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = hsm.muted) }
            if (cap != null && (cap.method != null || (cap.note != null && cap.supported))) {
                Text(
                    listOfNotNull(cap.method?.let { "Method: $it" }, cap.note.takeIf { cap.supported }).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = hsm.faint,
                )
            }
            if (!state.supported) {
                ReasonBox(state.reason ?: "Not supported on this phone.")
                return@Column
            }
            if (state.reason != null) {
                ReasonBox(state.reason) {
                    if (name == "power_off") {
                        val navigate = LocalNavigate.current
                        LinkButton("Open Settings") { navigate(Dest.SETTINGS, "device") }
                    }
                }
            }
            ControlBody(props)
            for (c in armed) RevertCountdown(c)
            HorizontalDivider(color = hsm.border)
            LastResult(last, compact = compact)
        }
    }
}

@Composable
private fun ControlBody(p: ControlProps) {
    val w = p.env.settings.watchdog
    when (p.name) {
        "mobile_data", "airplane_mode", "wifi", "bluetooth", "charging" -> ToggleBody(p)
        "data_cycle" -> NumberArgBody(p, "off_seconds", "Data off for", "s", 1, 120, w.dataOffS, "Cycle data")
        "airplane_cycle" -> NumberArgBody(p, "off_seconds", "Airplane on for", "s", 3, 120, w.airplaneOffS, "Cycle airplane")
        "usb_tethering" -> UsbTetherBody(p)
        "hotspot" -> HotspotBody(p)
        "network_mode" -> NetworkModeBody(p)
        "screen" -> ScreenBody(p)
        "brightness" -> BrightnessBody(p)
        "charge_limit" -> ChargeLimitBody(p)
        "app_list" -> NavRunBody(p, "Open app list", Dest.SYSTEM, "apps")
        "process_list" -> NavRunBody(p, "Open in System", Dest.SYSTEM, "overview", button = "Run top")
        "app_force_stop", "app_disable", "app_enable" -> PackageBody(p)
        "shell" -> ShellBody()
        "reboot" -> RebootBody(p)
        "watchdog_simulate" -> NumberArgBody(
            p, "duration_s", "Simulated outage", "s", 30, 3600, maxOf(120, w.airplaneAfterS + 30), "Simulate",
            extra = mapOf("scenario" to "internet_down"), style = BtnStyle.DANGER,
        )
        "send_sms", "ussd" -> GoBody("Send texts and USSD codes on the Messages screen.", "Open Messages", Dest.MESSAGES)
        "speed_test", "finder_spot_test", "finder_set_baseline", "finder_delete_spot" ->
            GoBody("Run this from the Signal Finder screen.", "Open Signal Finder", Dest.FINDER)
        else -> RunBody(p)
    }
}

@Composable
private fun RunBody(p: ControlProps) {
    val danger = p.name == "reboot" || p.name == "power_off"
    ActionButton(
        p.spec.label,
        Modifier.fillMaxWidth(),
        style = if (danger) BtnStyle.DANGER else BtnStyle.PRIMARY,
        enabled = !p.disabled,
        busy = p.busy("run"),
    ) { p.run("run", JsonObject(emptyMap())) }
}

@Composable
private fun RebootBody(p: ControlProps) {
    val used = p.env.snapshot?.groups?.system?.rebootsInWindow
    val w = p.env.settings.watchdog
    Text(
        "${used ?: "?"} of ${w.maxReboots} reboots used in the last ${trimNumber(w.rebootWindowH, 1)} h.",
        style = MaterialTheme.typography.bodySmall,
        color = if (used != null && used >= w.maxReboots) hsm.warn else hsm.muted,
    )
    RunBody(p)
}

/** Two buttons for an { enabled } command, the one that changes the state highlighted. */
@Composable
private fun ToggleBody(p: ControlProps) {
    val current = currentToggle(p.name, p.env.snapshot)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionButton(
            "Turn on",
            Modifier.weight(1f),
            style = if (current == false) BtnStyle.PRIMARY else BtnStyle.SECONDARY,
            enabled = !p.disabled && !p.busy,
            busy = p.busy("on"),
        ) { p.run("on", obj("enabled" to true)) }
        ActionButton(
            "Turn off",
            Modifier.weight(1f),
            style = if (current == true) BtnStyle.PRIMARY else BtnStyle.SECONDARY,
            enabled = !p.disabled && !p.busy,
            busy = p.busy("off"),
        ) { p.run("off", obj("enabled" to false)) }
    }
    if (current == null && (p.name == "wifi" || p.name == "bluetooth")) {
        Text("The phone does not report this radio's state.", style = MaterialTheme.typography.bodySmall, color = hsm.faint)
    }
}

@Composable
private fun NumberArgBody(
    p: ControlProps,
    arg: String,
    label: String,
    unit: String,
    min: Int,
    max: Int,
    initial: Int,
    action: String,
    extra: Map<String, String> = emptyMap(),
    style: BtnStyle = BtnStyle.PRIMARY,
) {
    var value by remember(initial) { mutableStateOf(initial.toString()) }
    val n = value.trim().toIntOrNull()
    val error = when {
        n == null -> "Enter a whole number."
        n < min || n > max -> "Between $min and $max."
        else -> null
    }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { v -> value = v.filter { it.isDigit() }.take(5) },
            label = { Text("$label ($unit)") },
            singleLine = true,
            enabled = !p.disabled,
            isError = error != null,
            supportingText = { Text(error ?: "$min to $max $unit") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            modifier = Modifier.weight(1f),
        )
        ActionButton(
            action,
            Modifier.padding(top = 8.dp),
            style = style,
            enabled = !p.disabled && error == null,
            busy = p.busy("run"),
        ) {
            if (n != null && error == null) {
                p.run("run", buildJsonObject {
                    for ((k, v) in extra) put(k, v)
                    put(arg, n)
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UsbTetherBody(p: ControlProps) {
    val tether = p.env.snapshot?.groups?.tether
    val reported = when (tether?.usbFunction) {
        UsbFunction.NCM -> "ncm"
        UsbFunction.RNDIS -> "rndis"
        else -> p.env.settings.watchdog.usbFunction.takeIf { it == "ncm" || it == "rndis" } ?: "rndis"
    }
    var fn by remember(reported) { mutableStateOf(reported) }
    if (tether?.usbConnected == false) Pill("USB cable not connected", Tone.WARN)
    Text("USB function", style = MaterialTheme.typography.labelMedium, color = hsm.muted)
    val options = listOf("rndis" to "RNDIS", "ncm" to "NCM")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (v, l) ->
            SegmentedButton(
                selected = fn == v,
                onClick = { fn = v },
                enabled = !p.disabled,
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(l) }
        }
    }
    Text(
        if (fn == "rndis") "RNDIS: Windows and most routers." else "NCM: macOS and Linux.",
        style = MaterialTheme.typography.bodySmall, color = hsm.faint,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionButton(
            "Turn on",
            Modifier.weight(1f),
            style = if (tether?.usbTetherOn == false) BtnStyle.PRIMARY else BtnStyle.SECONDARY,
            enabled = !p.disabled && !p.busy,
            busy = p.busy("on"),
        ) { p.run("on", obj("enabled" to true, "function" to fn)) }
        ActionButton(
            "Turn off",
            Modifier.weight(1f),
            style = BtnStyle.SECONDARY,
            enabled = !p.disabled && !p.busy,
            busy = p.busy("off"),
        ) { p.run("off", obj("enabled" to false)) }
    }
}

@Composable
private fun HotspotBody(p: ControlProps) {
    val tether = p.env.snapshot?.groups?.tether
    var ssid by remember(tether?.hotspotSsid) { mutableStateOf(tether?.hotspotSsid ?: "") }
    var password by remember { mutableStateOf("") }
    var showPw by remember { mutableStateOf(false) }
    var touched by remember { mutableStateOf(false) }
    val ssidErr = when {
        ssid.isBlank() -> "Enter a network name."
        ssid.toByteArray(Charsets.UTF_8).size > 32 -> "At most 32 bytes."
        else -> null
    }
    val pwErr = hotspotPasswordError(password)
    fun turnOn() {
        touched = true
        if (ssidErr == null && pwErr == null) p.run("on", obj("enabled" to true, "ssid" to ssid.trim(), "password" to password))
    }
    OutlinedTextField(
        value = ssid,
        onValueChange = { ssid = it },
        label = { Text("Network name (SSID)") },
        singleLine = true,
        enabled = !p.disabled,
        isError = touched && ssidErr != null,
        supportingText = if (touched && ssidErr != null) ({ Text(ssidErr) }) else null,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Password") },
        singleLine = true,
        enabled = !p.disabled,
        isError = touched && pwErr != null,
        supportingText = { Text(if (touched && pwErr != null) pwErr else "8 to 63 printable ASCII characters (WPA2).") },
        visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { showPw = !showPw }) {
                Icon(if (showPw) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (showPw) "Hide password" else "Show password")
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { turnOn() }),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionButton("Turn on", Modifier.weight(1f), enabled = !p.disabled && !p.busy, busy = p.busy("on")) { turnOn() }
        ActionButton("Turn off", Modifier.weight(1f), style = BtnStyle.SECONDARY, enabled = !p.disabled && !p.busy, busy = p.busy("off")) {
            p.run("off", obj("enabled" to false))
        }
    }
}

@Composable
private fun NetworkModeBody(p: ControlProps) {
    val fromLast = (p.last?.args?.get("mode") as? JsonPrimitive)?.contentOrNull?.takeIf { it in NETWORK_MODE_LABELS }
    var mode by remember { mutableStateOf(fromLast ?: "auto") }
    Dropdown(
        label = "Allowed networks",
        value = mode,
        options = NETWORK_MODE_LABELS.entries.map { it.key to it.value },
        onChange = { mode = it },
        enabled = !p.disabled,
        modifier = Modifier.fillMaxWidth(),
    )
    ActionButton("Apply", Modifier.fillMaxWidth(), enabled = !p.disabled, busy = p.busy("run")) { p.run("run", obj("mode" to mode)) }
}

@Composable
private fun ScreenBody(p: ControlProps) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionButton("Wake", Modifier.weight(1f), style = BtnStyle.SECONDARY, enabled = !p.disabled && !p.busy, busy = p.busy("on")) {
            p.run("on", obj("on" to true))
        }
        ActionButton("Sleep", Modifier.weight(1f), style = BtnStyle.SECONDARY, enabled = !p.disabled && !p.busy, busy = p.busy("off")) {
            p.run("off", obj("on" to false))
        }
    }
}

@Composable
private fun BrightnessBody(p: ControlProps) {
    var level by remember { mutableIntStateOf(10) }
    Text("Brightness: $level of 255", style = MaterialTheme.typography.labelLarge, color = hsm.fg)
    Slider(
        value = level.toFloat(),
        onValueChange = { level = it.toInt().coerceIn(0, 255) },
        valueRange = 0f..255f,
        enabled = !p.disabled,
    )
    ActionButton("Apply", Modifier.fillMaxWidth(), enabled = !p.disabled, busy = p.busy("run")) { p.run("run", obj("level" to level)) }
}

@Composable
private fun ChargeLimitBody(p: ControlProps) {
    val current = p.env.snapshot?.groups?.power?.chargeLimitPct ?: p.env.settings.watchdog.chargeLimitPct
    var pct by remember(current) { mutableIntStateOf(current) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Stop charging at $pct%", style = MaterialTheme.typography.labelLarge, color = hsm.fg, modifier = Modifier.weight(1f))
        Text("now $current%", style = MaterialTheme.typography.bodySmall, color = hsm.muted)
    }
    Slider(
        value = pct.toFloat(),
        onValueChange = { pct = ((it / 5f).roundToInt() * 5).coerceIn(20, 100) },
        valueRange = 20f..100f,
        steps = 15,
        enabled = !p.disabled,
    )
    Text(
        if (pct > 80) "Above 80% shortens battery life and raises the swelling risk in heat."
        else "50 to 60% is safest for a phone that stays plugged in outdoors.",
        style = MaterialTheme.typography.bodySmall,
        color = if (pct > 80) hsm.warn else hsm.faint,
    )
    ActionButton("Set limit", Modifier.fillMaxWidth(), enabled = !p.disabled && pct != current, busy = p.busy("run")) {
        p.run("run", obj("percent" to pct))
    }
}

private val PACKAGE_RE = Regex("^[a-zA-Z]\\w*(\\.[a-zA-Z_]\\w*)+$")

@Composable
private fun PackageBody(p: ControlProps) {
    val navigate = LocalNavigate.current
    var pkg by remember { mutableStateOf("") }
    val valid = PACKAGE_RE.matches(pkg.trim())
    OutlinedTextField(
        value = pkg,
        onValueChange = { pkg = it },
        label = { Text("Package name") },
        placeholder = { Text("com.example.app") },
        singleLine = true,
        enabled = !p.disabled,
        isError = pkg.isNotEmpty() && !valid,
        supportingText = if (pkg.isNotEmpty() && !valid) ({ Text("Like com.example.app") }) else null,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        ActionButton(
            p.spec.label,
            style = if (p.name == "app_disable") BtnStyle.DANGER else BtnStyle.PRIMARY,
            enabled = !p.disabled && valid,
            busy = p.busy("run"),
        ) { p.run("run", obj("package" to pkg.trim())) }
        Spacer(Modifier.width(8.dp))
        LinkButton("Pick from app list") { navigate(Dest.SYSTEM, "apps") }
    }
}

@Composable
private fun NavRunBody(p: ControlProps, link: String, dest: Dest, tab: String?, button: String = p.spec.label) {
    val navigate = LocalNavigate.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        ActionButton(button, enabled = !p.disabled, busy = p.busy("run")) { p.run("run", JsonObject(emptyMap())) }
        Spacer(Modifier.width(8.dp))
        LinkButton(link) { navigate(dest, tab) }
    }
}

@Composable
private fun ShellBody() {
    val navigate = LocalNavigate.current
    ActionButton("Open root console", Modifier.fillMaxWidth(), style = BtnStyle.DANGER) { navigate(Dest.SYSTEM, "console") }
}

@Composable
private fun GoBody(text: String, label: String, dest: Dest) {
    val navigate = LocalNavigate.current
    Column {
        Text(text, style = MaterialTheme.typography.bodySmall, color = hsm.muted)
        LinkButton(label) { navigate(dest, null) }
    }
}
