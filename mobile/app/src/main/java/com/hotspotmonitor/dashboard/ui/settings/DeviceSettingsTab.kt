package com.hotspotmonitor.dashboard.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import com.hotspotmonitor.dashboard.ui.components.keepInViewWhenFocused
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hotspotmonitor.dashboard.AppGraph
import com.hotspotmonitor.dashboard.data.ApiException
import com.hotspotmonitor.dashboard.data.DataState
import com.hotspotmonitor.dashboard.data.SessionState
import com.hotspotmonitor.dashboard.data.SettingsResponse
import com.hotspotmonitor.dashboard.data.userMessage
import com.hotspotmonitor.dashboard.ui.components.LinkButton
import com.hotspotmonitor.dashboard.ui.components.Notice
import com.hotspotmonitor.dashboard.ui.components.Pill
import com.hotspotmonitor.dashboard.ui.components.ReauthDialog
import com.hotspotmonitor.dashboard.ui.components.SectionCard
import com.hotspotmonitor.dashboard.ui.components.Tone
import com.hotspotmonitor.dashboard.ui.components.prettyJson
import com.hotspotmonitor.dashboard.ui.graph
import com.hotspotmonitor.dashboard.ui.theme.hsm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

// --- state ------------------------------------------------------------------------------------

/**
 * Draft of the selected phone's settings. One text per field (so a half-typed number is kept);
 * values, changes and validation derive from the texts. Saves a minimal deep patch.
 */
@Stable
class EditorState {
    var deviceId: String? by mutableStateOf(null)
        private set
    private var bound = false

    /** The Server's settings merged over the defaults (what "no change" means). */
    var base: JsonObject by mutableStateOf(DEFAULT_SETTINGS_JSON)
        private set
    var baseVersion: Int? by mutableStateOf(null)
        private set
    val texts = mutableStateMapOf<String, String>()
    var overrideText by mutableStateOf("")
    var saving by mutableStateOf(false)
    var saveError by mutableStateOf<String?>(null)
    /** The password prompt is open (profile override change, or the Server asked for it). */
    var reauth by mutableStateOf(false)
    val expanded = mutableStateMapOf<String, Boolean>()

    init {
        resetTo(null)
    }

    val values: Map<String, JsonElement?> by derivedStateOf {
        ALL_FIELDS.associate { it.path to parseValue(it, texts[it.path].orEmpty()) }
    }
    val override: OverrideParse by derivedStateOf { parseOverride(overrideText) }
    private val baseOverride: JsonObject? get() = base[OVERRIDE_PATH] as? JsonObject

    val changed: Set<String> by derivedStateOf {
        ALL_FIELDS.filter { d ->
            val v = values[d.path]
            v == null || !sameValue(v, getPath(base, d.path))
        }.map { it.path }.toSet()
    }
    val overrideChanged: Boolean by derivedStateOf { override.error == null && !sameValue(override.value, baseOverride) }
    val validation: Validation by derivedStateOf { validateSettings(texts, values, override.value) }

    val overrideError: String? get() = override.error ?: validation.errors[OVERRIDE_PATH]
    val changes: Int get() = changed.size + if (overrideChanged) 1 else 0
    val errorCount: Int get() = validation.errors.size + if (override.error != null) 1 else 0

    fun resetTo(r: SettingsResponse?) {
        base = r?.settings?.let { mergeJson(DEFAULT_SETTINGS_JSON, it) } ?: DEFAULT_SETTINGS_JSON
        baseVersion = r?.version
        texts.clear()
        for (d in ALL_FIELDS) texts[d.path] = toText(d, getPath(base, d.path))
        overrideText = baseOverride?.let(::prettyJson) ?: ""
        saveError = null
    }

    /** Device switch: start over. Newer Server version: follow it unless there are local edits. */
    fun bind(id: String?, r: SettingsResponse?) {
        if (!bound || id != deviceId) {
            bound = true
            deviceId = id
            resetTo(r)
            return
        }
        if (r != null && r.version != baseVersion && (changes == 0 || baseVersion == null)) resetTo(r)
    }

    fun revert(d: FieldDef) {
        texts[d.path] = toText(d, getPath(base, d.path))
    }

    fun resetDefault(d: FieldDef) {
        texts[d.path] = toText(d, defaultFor(d.path))
    }

    fun discard() {
        for (d in ALL_FIELDS) revert(d)
        overrideText = baseOverride?.let(::prettyJson) ?: ""
        saveError = null
    }

    /** Only the changed values; profile_override goes complete (the Server replaces it whole). */
    fun patch(): JsonObject? {
        val entries = ALL_FIELDS.filter { it.path in changed }
            .mapNotNull { d -> values[d.path]?.let { d.path to it } }
            .toMutableList()
        if (overrideChanged) entries += OVERRIDE_PATH to (override.value ?: JsonNull)
        return buildPatch(entries)
    }

    private suspend fun put(g: AppGraph, ctx: Context, password: String?) {
        val id = deviceId ?: return
        val p = patch() ?: return
        val res = g.api.putSettings(id, p, password)
        g.store.setSettings(res)
        resetTo(res)
        Toast.makeText(ctx, "Settings saved (version ${res.version}). The phone applies them when connected.", Toast.LENGTH_LONG).show()
    }

    fun save(g: AppGraph, ctx: Context, scope: kotlinx.coroutines.CoroutineScope) {
        if (deviceId == null || changes == 0 || errorCount > 0 || saving) return
        saveError = null
        if (overrideChanged) {
            reauth = true
            return
        }
        saving = true
        scope.launch {
            try {
                put(g, ctx, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The Server decides what needs the password; ask for it rather than failing.
                if (e is ApiException && e.isCode("reauth_required")) reauth = true else saveError = e.userMessage()
            } finally {
                saving = false
            }
        }
    }

    suspend fun saveWithPassword(g: AppGraph, ctx: Context, password: String) {
        saving = true
        try {
            put(g, ctx, password)
            reauth = false
        } finally {
            saving = false
        }
    }
}

// --- list items -------------------------------------------------------------------------------

internal fun LazyListScope.deviceItems(editor: EditorState, session: SessionState, data: DataState, deviceId: String?) {
    val server = deviceId?.let { data.settings[it] }
    val readOnly = !session.canControl || deviceId == null
    val phoneName = deviceId?.let { id -> data.devices.firstOrNull { it.deviceId == id }?.name?.ifBlank { null } ?: id }

    if (!session.isAdmin) {
        item(key = "dev-viewer") { Notice(Tone.INFO, null, "Viewer accounts can read the settings but not change them.") }
    } else if (!session.canControl) {
        item(key = "dev-totp") { Notice(Tone.WARN, null, "Set up two-factor authentication before changing settings.") }
    }
    if (deviceId == null) {
        item(key = "dev-nophone") {
            Notice(
                Tone.INFO, "No phone is paired yet",
                "Settings are stored per phone. These are the defaults a newly paired phone starts with; you can change them once it is paired.",
            )
        }
    } else if (server == null) {
        item(key = "dev-defaults") { Notice(Tone.INFO, null, "Showing the default values until the phone's settings load.") }
    }
    val remoteChanged = server != null && editor.baseVersion != null && server.version != editor.baseVersion
    if (remoteChanged && editor.changes > 0) {
        item(key = "dev-remote") {
            Notice(
                Tone.WARN, "Settings changed elsewhere (now version ${server!!.version})",
                "Saving now applies your edits on top of the newer version.",
            ) {
                OutlinedButton(onClick = { editor.resetTo(server) }) { Text("Discard mine and reload") }
            }
        }
    }

    item(key = "dev-summary") {
        val allOpen = SETTINGS_SECTIONS.all { editor.expanded[it.id] == true } && editor.expanded[OVERRIDE_PATH] == true
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(phoneName ?: "Defaults", style = MaterialTheme.typography.titleMedium, color = hsm.fg)
                    Text(
                        when {
                            deviceId == null -> "Not saved: no phone paired"
                            editor.baseVersion != null -> "Settings version ${editor.baseVersion} · the phone applies changes when connected"
                            else -> "Loading the saved settings…"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = hsm.muted,
                    )
                }
                LinkButton(if (allOpen) "Collapse all" else "Expand all") {
                    val open = !allOpen
                    for (s in SETTINGS_SECTIONS) editor.expanded[s.id] = open
                    editor.expanded[OVERRIDE_PATH] = open
                }
            }
        }
    }

    for (section in SETTINGS_SECTIONS) {
        item(key = "sec-${section.id}") {
            val paths = section.fields.map { it.path }
            val changed = paths.count { it in editor.changed }
            val errors = paths.count { it in editor.validation.errors }
            CollapsibleCard(
                title = section.title,
                description = section.description,
                icon = section.icon,
                expanded = editor.expanded[section.id] == true,
                onToggle = { editor.expanded[section.id] = editor.expanded[section.id] != true },
                changed = changed,
                errors = errors,
            ) {
                var lastGroup: String? = null
                section.fields.forEachIndexed { i, def ->
                    if (def.group != null && def.group != lastGroup) {
                        SubHeading(def.group)
                    } else if (i > 0) {
                        HorizontalDivider(color = hsm.border.copy(alpha = 0.6f))
                    }
                    lastGroup = def.group
                    FieldEditor(def, editor, readOnly)
                }
            }
        }
    }

    item(key = "sec-profile") {
        val err = editor.overrideError
        CollapsibleCard(
            title = "Device profile override",
            description = "JSON merged over the phone's built-in device profile.",
            icon = Icons.Outlined.DataObject,
            expanded = editor.expanded[OVERRIDE_PATH] == true,
            onToggle = { editor.expanded[OVERRIDE_PATH] = editor.expanded[OVERRIDE_PATH] != true },
            changed = if (editor.overrideChanged) 1 else 0,
            errors = if (err != null) 1 else 0,
        ) {
            Text(
                "Charge-control path, USB function, thermal zones and command templates. Leave empty to use the built-in profile. " +
                    "Saving a change asks for your password because the phone runs these templates as root.",
                style = MaterialTheme.typography.bodySmall,
                color = hsm.muted,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )
            if (readOnly) {
                Text(
                    editor.overrideText.ifBlank { "No override: the built-in profile is used." },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = if (editor.overrideText.isBlank()) hsm.muted else hsm.fg,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(hsm.surface2).padding(10.dp),
                )
            } else {
                OutlinedTextField(
                    value = editor.overrideText,
                    onValueChange = { editor.overrideText = it },
                    label = { Text("Override (JSON object)") },
                    placeholder = {
                        Text(
                            "{\n  \"charge_control\": [{ \"path\": \"/sys/class/power_supply/battery/batt_slate_mode\", \"on\": \"0\", \"off\": \"1\" }]\n}",
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        )
                    },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    isError = err != null,
                    supportingText = err?.let { { Text(it) } },
                    minLines = 6,
                    maxLines = 18,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().keepInViewWhenFocused(editor.overrideText),
                )
                if (editor.overrideChanged) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        LinkButton("Undo") { editor.overrideText = (editor.base[OVERRIDE_PATH] as? JsonObject)?.let(::prettyJson) ?: "" }
                    }
                }
            }
        }
    }
}

// --- sections and fields ----------------------------------------------------------------------

@Composable
private fun CollapsibleCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    changed: Int,
    errors: Int,
    content: @Composable () -> Unit,
) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    SectionCard(contentPadding = PaddingValues(0.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(hsm.infoSoft), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = hsm.accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = hsm.fg)
                Text(description, style = MaterialTheme.typography.bodySmall, color = hsm.muted, maxLines = if (expanded) 4 else 1)
                if (changed > 0 || errors > 0) {
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (errors > 0) Pill("$errors to fix", Tone.DANGER)
                        if (changed > 0) Pill("$changed changed", Tone.INFO)
                    }
                }
            }
            Icon(Icons.Outlined.ExpandMore, if (expanded) "Collapse" else "Expand", tint = hsm.muted, modifier = Modifier.rotate(rotation))
        }
        AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                HorizontalDivider(color = hsm.border)
                content()
            }
        }
    }
}

@Composable
private fun FieldEditor(def: FieldDef, editor: EditorState, readOnly: Boolean) {
    val text = editor.texts[def.path].orEmpty()
    val value = editor.values[def.path]
    val error = editor.validation.errors[def.path]
    val warning = editor.validation.warnings[def.path]
    val changed = def.path in editor.changed
    val isDefault = value != null && sameValue(value, defaultFor(def.path))
    val set: (String) -> Unit = { editor.texts[def.path] = it }

    if (readOnly) {
        ReadOnlyField(def, value, warning)
        return
    }

    Column(Modifier.fillMaxWidth().padding(vertical = if (def.kind == FieldKind.BOOL) 2.dp else 10.dp)) {
        when (def.kind) {
            FieldKind.BOOL -> {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .toggleable(value = text == "true", role = Role.Switch, onValueChange = { set(it.toString()) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        FieldLabel(def.label, changed)
                        def.help?.let { Help(it) }
                    }
                    Switch(checked = text == "true", onCheckedChange = null)
                }
            }
            FieldKind.SELECT -> {
                FieldLabel(def.label, changed)
                def.help?.let { Help(it) }
                Spacer(Modifier.height(4.dp))
                for (o in def.options) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .selectable(selected = text == o.value, role = Role.RadioButton, onClick = { set(o.value) })
                            .heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = text == o.value, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
                        Text(o.label, style = MaterialTheme.typography.bodyMedium, color = hsm.fg)
                    }
                }
            }
            FieldKind.WEEKDAY -> {
                FieldLabel(def.label, changed)
                Spacer(Modifier.height(6.dp))
                WeekdayChips(text.toIntOrNull()) { set(it.toString()) }
            }
            FieldKind.TIME -> {
                FieldLabel(def.label, changed)
                def.help?.let { Help(it) }
                Spacer(Modifier.height(6.dp))
                TimeField(def.label, text, onPick = set)
            }
            else -> {
                FieldLabel(def.label, changed)
                def.help?.let { Help(it) }
                Spacer(Modifier.height(6.dp))
                TextValueField(def, text, error != null, set)
            }
        }
        val hint = if (def.kind == FieldKind.INT || def.kind == FieldKind.NUMBER) friendlyHint(def, text) else null
        val info = if (error == null && def.kind != FieldKind.BOOL) {
            listOfNotNull(rangeText(def).takeIf { warning == null }, hint).joinToString(" · ")
        } else ""
        if (error != null || warning != null || info.isNotEmpty()) {
            Column(Modifier.padding(top = 4.dp)) {
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = hsm.danger) }
                warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = hsm.warn) }
                if (info.isNotEmpty()) Text(info, style = MaterialTheme.typography.bodySmall, color = hsm.faint)
            }
        }
        // A switch is its own undo; other fields get "Undo" (back to the saved value) and a reset.
        if (def.kind != FieldKind.BOOL && (changed || !isDefault)) {
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (changed) LinkButton("Undo") { editor.revert(def) }
                if (!isDefault) {
                    val label = if (def.kind == FieldKind.URL || def.kind == FieldKind.HOST) "Reset to default" else "Default: ${describeDefault(def)}"
                    LinkButton(label) { editor.resetDefault(def) }
                }
            }
        }
    }
}

@Composable
private fun FieldLabel(label: String, changed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (changed) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(hsm.accent))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = hsm.fg)
    }
}

@Composable
private fun Help(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = hsm.muted)
}

@Composable
private fun TextValueField(def: FieldDef, text: String, isError: Boolean, onText: (String) -> Unit) {
    val numeric = def.kind == FieldKind.INT || def.kind == FieldKind.NUMBER
    val mono = def.kind == FieldKind.URL || def.kind == FieldKind.HOST
    OutlinedTextField(
        value = text,
        onValueChange = onText,
        singleLine = def.kind != FieldKind.URL,
        maxLines = if (def.kind == FieldKind.URL) 3 else 1,
        isError = isError,
        suffix = def.unit?.takeIf { numeric || def.kind == FieldKind.INT_LIST }?.let { u -> { Text(u, color = hsm.muted) } },
        textStyle = if (mono) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp) else MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(
            keyboardType = when (def.kind) {
                FieldKind.INT -> KeyboardType.Number
                FieldKind.NUMBER -> KeyboardType.Decimal
                FieldKind.URL, FieldKind.HOST -> KeyboardType.Uri
                else -> KeyboardType.Text
            },
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        modifier = (if (numeric) Modifier.width(200.dp) else Modifier.fillMaxWidth()).keepInViewWhenFocused(text),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekdayChips(selected: Int?, enabled: Boolean = true, onPick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        WEEKDAYS.forEachIndexed { i, d ->
            FilterChip(
                selected = selected == i,
                onClick = { onPick(i) },
                enabled = enabled,
                label = { Text(d.take(3)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = hsm.infoSoft,
                    selectedLabelColor = hsm.accent,
                ),
            )
        }
    }
}

@Composable
private fun TimeField(label: String, text: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 52.dp)) {
        Icon(Icons.Outlined.Schedule, null, Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text.ifBlank { "--:--" }, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
    }
    if (open) {
        TimePickDialog(label, text, onDismiss = { open = false }, onPick = {
            onPick(it)
            open = false
        })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickDialog(title: String, initial: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val h = initial.substringBefore(':').toIntOrNull()?.coerceIn(0, 23) ?: 4
    val m = initial.substringAfter(':', "").toIntOrNull()?.coerceIn(0, 59) ?: 0
    val state = rememberTimePickerState(initialHour = h, initialMinute = m, is24Hour = true)
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = hsm.surface, tonalElevation = 6.dp) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = hsm.fg, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp))
                TimePicker(state = state)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onPick(String.format(java.util.Locale.US, "%02d:%02d", state.hour, state.minute)) }) { Text("OK") }
                }
            }
        }
    }
}

@Composable
private fun ReadOnlyField(def: FieldDef, value: JsonElement?, warning: String?) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(def.label, style = MaterialTheme.typography.bodyMedium, color = hsm.fg)
            def.help?.let { Help(it) }
            warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = hsm.warn) }
        }
        if (def.kind == FieldKind.BOOL) {
            val on = describeValue(def, value) == "On"
            Pill(if (on) "On" else "Off", if (on) Tone.OK else Tone.NEUTRAL)
        } else {
            Text(
                describeValue(def, value),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                fontFamily = if (def.kind == FieldKind.URL || def.kind == FieldKind.HOST) FontFamily.Monospace else null,
                color = hsm.fg,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

// --- save bar and dialogs ---------------------------------------------------------------------

/** Sticky bar under the list while there is something to save. */
@Composable
internal fun SaveBar(editor: EditorState, editable: Boolean, scope: kotlinx.coroutines.CoroutineScope) {
    val g = graph()
    val ctx = LocalContext.current.applicationContext
    val changes = editor.changes
    val errors = editor.errorCount
    val visible = editable && (changes > 0 || editor.saveError != null || editor.saving)
    AnimatedVisibility(visible, enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Surface(color = hsm.surface, border = BorderStroke(1.dp, hsm.border), shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                val (status, color) = when {
                    editor.saveError != null -> editor.saveError!! to hsm.danger
                    errors > 0 -> "$errors ${if (errors == 1) "value needs" else "values need"} fixing" to hsm.danger
                    else -> "$changes unsaved ${if (changes == 1) "change" else "changes"}" +
                        (editor.baseVersion?.let { " · on top of version $it" } ?: "") to hsm.muted
                }
                Text(status, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 2)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { editor.discard() },
                        enabled = changes > 0 && !editor.saving,
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) { Text("Discard") }
                    Button(
                        onClick = { editor.save(g, ctx, scope) },
                        enabled = changes > 0 && errors == 0 && !editor.saving,
                        modifier = Modifier.weight(1.7f).height(48.dp),
                    ) {
                        if (editor.saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = hsm.onAccent)
                        else Text("Save changes ($changes)", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
internal fun EditorDialogs(editor: EditorState) {
    if (!editor.reauth) return
    val g = graph()
    val ctx = LocalContext.current
    val override = editor.overrideChanged
    ReauthDialog(
        title = if (override) "Confirm the device profile change" else "Confirm the settings change",
        text = if (override) {
            "The profile override holds the charge-control paths and command templates the phone runs as root. " +
                "Enter your password to save it. The change is recorded in the event log."
        } else {
            "The Server asks for your password to save this change."
        },
        confirmLabel = "Confirm and save",
        onDismiss = { editor.reauth = false },
        onConfirm = { password, _ -> editor.saveWithPassword(g, ctx, password) },
    )
}
