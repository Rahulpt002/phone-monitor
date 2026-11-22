package com.hotspotmonitor.dashboard.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.ui.graphics.vector.ImageVector
import com.hotspotmonitor.agent.protocol.ALERT_KINDS
import com.hotspotmonitor.agent.protocol.Settings
import com.hotspotmonitor.dashboard.data.AppJson
import com.hotspotmonitor.dashboard.data.formatBytes
import com.hotspotmonitor.dashboard.data.formatDuration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.floor

// Port of web/src/lib/settingsSchema.ts: labels, units, limits and help for every editable device
// setting, plus validation, per-field defaults and the minimal patch for PUT /settings. The editor
// keeps one text per field; values are parsed from it, so a half-typed number is never lost.

enum class FieldKind { INT, NUMBER, BOOL, HOST, URL, SELECT, TIME, WEEKDAY, INT_LIST }

data class FieldOption(val value: String, val label: String)

data class FieldDef(
    val path: String,
    val label: String,
    val kind: FieldKind,
    val unit: String? = null,
    val help: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    val options: List<FieldOption> = emptyList(),
    /** Sub-heading inside the section (fields with the same group follow each other). */
    val group: String? = null,
)

data class SectionDef(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val fields: List<FieldDef>,
)

val ALERT_LABEL: Map<String, String> = mapOf(
    "offline" to "Phone offline",
    "back_online" to "Back online",
    "weak_power" to "Weak power (plugged in but draining)",
    "low_battery" to "Low battery",
    "hot" to "Hot (temperature warning / critical)",
    "signal_drop" to "Signal drop vs baseline",
    "cell_changed" to "Serving cell changed",
    "tether_down" to "USB tethering down",
    "usb_power_lost" to "USB power lost",
    "data_quota" to "Data quota steps",
    "moved" to "Phone moved or tilted",
    "watchdog" to "Watchdog acted",
    "carrier_sms" to "Carrier SMS received",
    "server_test" to "Test alerts",
)

val WEEKDAYS = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

private fun int(path: String, label: String, unit: String?, min: Number, max: Number, help: String? = null, group: String? = null) =
    FieldDef(path, label, FieldKind.INT, unit, help, min.toDouble(), max.toDouble(), group = group)

private fun num(path: String, label: String, unit: String?, min: Number, max: Number, help: String? = null, group: String? = null) =
    FieldDef(path, label, FieldKind.NUMBER, unit, help, min.toDouble(), max.toDouble(), group = group)

private fun bool(path: String, label: String, help: String? = null, group: String? = null) =
    FieldDef(path, label, FieldKind.BOOL, help = help, group = group)

val SETTINGS_SECTIONS: List<SectionDef> = listOf(
    SectionDef(
        "watchdog", "Watchdog", "Local recovery rules that run on the phone, even without internet.", Icons.Outlined.HealthAndSafety,
        listOf(
            bool("watchdog.enabled", "Watchdog enabled", "Turn off only while testing on the bench."),
            int("watchdog.internet_fail_s", "Cycle mobile data after internet down for", "s", 10, 86_400, group = "Internet recovery"),
            int("watchdog.data_off_s", "Mobile data off time during the cycle", "s", 1, 600, group = "Internet recovery"),
            int("watchdog.airplane_after_s", "Cycle airplane mode after internet down for", "s", 10, 86_400, group = "Internet recovery"),
            int("watchdog.airplane_off_s", "Airplane mode on time during the cycle", "s", 1, 600, group = "Internet recovery"),
            int("watchdog.reboot_after_s", "Reboot after internet down for", "s", 30, 604_800, group = "Internet recovery"),
            int("watchdog.reboot_retry_s", "Retry reboot every (once the limit is hit)", "s", 60, 604_800, group = "Internet recovery"),
            bool("watchdog.tether_fix", "Turn USB tethering back on when it drops", group = "USB tethering"),
            int("watchdog.tether_fix_after_s", "Re-enable tethering after it is off for", "s", 1, 3600, group = "USB tethering"),
            FieldDef(
                "watchdog.usb_function", "USB tethering function", FieldKind.SELECT,
                help = "Fallback only: the device profile (and its override) wins, and the watchdog re-enables the function last seen with tethering up.",
                options = listOf(
                    FieldOption("rndis", "RNDIS (Windows, most routers)"),
                    FieldOption("ncm", "NCM (macOS, Linux, newer routers)"),
                ),
                group = "USB tethering",
            ),
            int("watchdog.charge_limit_pct", "Stop charging at", "%", 20, 100, "Keep the battery around 50 to 60 % for safety.", group = "Charging and heat"),
            int("watchdog.charge_resume_drop_pct", "Resume charging this far below the limit", "%", 1, 50, group = "Charging and heat"),
            num("watchdog.temp_warn_c", "Warning alert at battery temperature", "°C", 0, 90, group = "Charging and heat"),
            num("watchdog.temp_stop_charge_c", "Stop charging at battery temperature", "°C", 0, 90, group = "Charging and heat"),
            num("watchdog.temp_resume_c", "Resume charging below", "°C", 0, 90, group = "Charging and heat"),
            num("watchdog.temp_radio_rest_c", "Rest the radio at battery temperature", "°C", 0, 100, group = "Charging and heat"),
            int("watchdog.radio_rest_s", "Radio rest duration", "s", 10, 86_400, group = "Charging and heat"),
            int("watchdog.weak_power_min", "\"Power supply too weak\" after battery falls while plugged in for", "min", 1, 1440, group = "Charging and heat"),
            num("watchdog.move_tilt_deg", "Moved alert when tilt changes by", "°", 1, 180, group = "Movement"),
            int("watchdog.move_distance_m", "Moved alert when location changes by", "m", 1, 100_000, group = "Movement"),
            int("watchdog.max_reboots", "Reboot limit", "reboots", 0, 50, "From any source, within the window below.", group = "Reboots"),
            int("watchdog.reboot_window_h", "Reboot limit window", "h", 1, 168, group = "Reboots"),
            bool("watchdog.weekly_reboot.enabled", "Weekly reboot", "Optional preventive reboot.", group = "Reboots"),
            FieldDef("watchdog.weekly_reboot.day", "Weekly reboot day", FieldKind.WEEKDAY, group = "Reboots"),
            FieldDef("watchdog.weekly_reboot.time", "Weekly reboot time (phone local)", FieldKind.TIME, group = "Reboots"),
        ),
    ),
    SectionDef(
        "commands", "Command safety", "Rules applied to every remote command.", Icons.Outlined.VerifiedUser,
        listOf(
            int("commands.revert_after_s", "Auto-revert timer", "s", 30, 3600, "A change that can cut the link is undone if it is not confirmed in time."),
            int("commands.expiry_s", "Discard commands older than", "s", 10, 3600),
            bool("commands.allow_power_off", "Allow remote power off", "A phone that is switched off needs a hand to switch it on again."),
        ),
    ),
    SectionDef(
        "alerts", "Alerts", "When the Server sends Telegram and push alerts.", Icons.Outlined.NotificationsActive,
        listOf(
            int("alerts.offline_after_s", "Phone offline after no heartbeat for", "s", 30, 86_400, group = "When to alert"),
            int("alerts.low_battery_pct", "Low battery at or below", "%", 0, 100, group = "When to alert"),
            int("alerts.signal_drop_pct", "Signal drop when score is below this share of the baseline", "%", 1, 100, group = "When to alert"),
            int("alerts.signal_drop_min", "Signal drop must last", "min", 1, 1440, group = "When to alert"),
            int("alerts.cell_change_min", "Cell changed must last", "min", 1, 1440, group = "When to alert"),
            int("alerts.tether_down_min", "Tethering down must last", "min", 1, 1440, group = "When to alert"),
            FieldDef(
                "alerts.quota_steps_pct", "Data quota alert steps", FieldKind.INT_LIST, "%",
                "Comma-separated, ascending, e.g. 80, 90, 100. Values above 100 warn after the plan is used up.", 1.0, 1000.0,
                group = "When to alert",
            ),
            int("alerts.cooldown_min", "Do not repeat the same alert within", "min", 0, 10_080, group = "When to alert"),
            bool("alerts.channels.telegram", "Send to Telegram", group = "Channels"),
            bool("alerts.channels.push", "Send push notifications (app and browser)", group = "Channels"),
        ) + ALERT_KINDS.map { k -> bool("alerts.enabled.$k", ALERT_LABEL[k] ?: k, group = "Alert types") },
    ),
    SectionDef(
        "plan", "Data plan", "Used for the usage bar and quota alerts.", Icons.Outlined.PieChart,
        listOf(
            num("plan.quota_gb", "Quota per billing cycle", "GB", 0, 1_000_000, "0 = unlimited (quota alerts off). 1 GB = 1,000,000,000 bytes."),
            int("plan.cycle_start_day", "Billing cycle starts on day", null, 1, 28),
        ),
    ),
    SectionDef(
        "telemetry", "Telemetry", "How often the phone reports. Shorter intervals use more mobile data.", Icons.Outlined.Timeline,
        listOf(
            int("telemetry.heartbeat_s", "Heartbeat", "s", 5, 600, "Also keeps a free hosting instance awake.", group = "Report intervals"),
            int("telemetry.fast_active_s", "Signal and speed while a Dashboard is open", "s", 1, 600, group = "Report intervals"),
            int("telemetry.fast_idle_s", "Signal and speed otherwise", "s", 1, 3600, group = "Report intervals"),
            int("telemetry.mirror_s", "Finder mirror", "s", 1, 60, group = "Report intervals"),
            int("telemetry.slow_s", "Battery, heat, system and tethering", "s", 5, 3600, group = "Report intervals"),
            int("telemetry.internet_check_s", "Internet check interval", "s", 5, 3600, group = "Internet checks"),
            FieldDef("telemetry.probe_url", "Online check URL (HTTP 204)", FieldKind.URL, group = "Internet checks"),
            FieldDef("telemetry.ping_host", "Ping host", FieldKind.HOST, group = "Internet checks"),
            FieldDef("telemetry.dns_host", "DNS check host", FieldKind.HOST, group = "Internet checks"),
            FieldDef("telemetry.public_ip_url", "Public IP URL", FieldKind.URL, group = "Internet checks"),
            int("telemetry.public_ip_every_s", "Check public IP every", "s", 60, 86_400, group = "Internet checks"),
        ),
    ),
    SectionDef(
        "finder", "Signal Finder", "Score weights and survey tests. The weights should add up to 1.", Icons.Outlined.Explore,
        listOf(
            num("finder.w_sinr", "SINR weight", null, 0, 1, group = "Score"),
            num("finder.w_rsrp", "RSRP weight", null, 0, 1, group = "Score"),
            num("finder.w_rsrq", "RSRQ weight", null, 0, 1, group = "Score"),
            int("finder.smooth_s", "Smooth the score over", "s", 1, 60, group = "Score"),
            int("finder.spot_test_s", "Spot test duration", "s", 5, 600, group = "Spot test"),
            int("finder.speed_runs", "Speed test runs", null, 1, 10, group = "Spot test"),
            int("finder.speed_bytes", "Download size per run", "bytes", 100_000, 1_000_000_000, group = "Spot test"),
            FieldDef("finder.speed_down_url", "Download URL", FieldKind.URL, help = "{bytes} is replaced with the size above.", group = "Spot test"),
            FieldDef("finder.speed_up_url", "Upload URL", FieldKind.URL, group = "Spot test"),
            int("finder.speak_every_s", "Speak the score every", "s", 1, 60, group = "Voice"),
        ),
    ),
)

val ALL_FIELDS: List<FieldDef> = SETTINGS_SECTIONS.flatMap { it.fields }

// --- JSON helpers -----------------------------------------------------------------------------

private val DefaultsEncoder = Json { encodeDefaults = true; explicitNulls = true }

/** The built-in defaults as JSON (same values as shared DEFAULT_SETTINGS). */
val DEFAULT_SETTINGS_JSON: JsonObject by lazy {
    DefaultsEncoder.encodeToJsonElement(Settings.serializer(), Settings.DEFAULT).jsonObject
}

fun getPath(obj: JsonElement?, path: String): JsonElement? {
    var cur: JsonElement? = obj
    for (k in path.split('.')) cur = (cur as? JsonObject)?.get(k) ?: return null
    return cur
}

/** Deep merge of [patch] over [base] (objects merge, everything else replaces). */
fun mergeJson(base: JsonObject, patch: JsonObject): JsonObject {
    val out = LinkedHashMap<String, JsonElement>(base)
    for ((k, v) in patch) {
        val b = out[k]
        out[k] = if (b is JsonObject && v is JsonObject && k != "profile_override") mergeJson(b, v) else v
    }
    return JsonObject(out)
}

fun defaultFor(path: String): JsonElement? = getPath(DEFAULT_SETTINGS_JSON, path)

private val JsonElement?.num: Double?
    get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

private val JsonElement?.bool: Boolean?
    get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

private val JsonElement?.str: String?
    get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Structural equality where 40 and 40.0 are the same number. */
fun sameValue(a: JsonElement?, b: JsonElement?): Boolean {
    val an = a == null || a is JsonNull
    val bn = b == null || b is JsonNull
    if (an || bn) return an && bn
    return when (a) {
        is JsonObject -> b is JsonObject && a.keys == b.keys && a.all { (k, v) -> sameValue(v, b[k]) }
        is JsonArray -> b is JsonArray && a.size == b.size && a.indices.all { sameValue(a[it], b[it]) }
        is JsonPrimitive -> {
            if (b !is JsonPrimitive) false
            else if (a.isString || b.isString) a.isString == b.isString && a.content == b.content
            else {
                val x = a.doubleOrNull
                val y = b.doubleOrNull
                if (x != null && y != null) x == y else a.content == b.content
            }
        }
        else -> false
    }
}

/** A number as people type it: 40.0 -> "40", 0.45 -> "0.45". */
fun numText(d: Double): String =
    if (d == floor(d) && abs(d) < 1e15) d.toLong().toString()
    else runCatching { BigDecimal(d.toString()).stripTrailingZeros().toPlainString() }.getOrDefault(d.toString())

private fun numJson(d: Double): JsonPrimitive = if (d == floor(d) && abs(d) < 1e15) JsonPrimitive(d.toLong()) else JsonPrimitive(d)

/** Text shown in the editor for a stored value. */
fun toText(def: FieldDef, v: JsonElement?): String = when (def.kind) {
    FieldKind.BOOL -> (v.bool ?: false).toString()
    FieldKind.INT_LIST -> (v as? JsonArray)?.mapNotNull { it.num }?.joinToString(", ") { numText(it) } ?: ""
    FieldKind.INT, FieldKind.NUMBER, FieldKind.WEEKDAY -> v.num?.let(::numText) ?: ""
    else -> v.str ?: (v as? JsonPrimitive)?.content ?: ""
}

private val INT_PART = Regex("^-?\\d+$")

/** The list parts of "80, 90, 100" (empty parts dropped). */
fun listParts(text: String): List<String> = text.split(Regex("[,\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }

/** Parses the editor text into the value to store; null when it is not a valid number or list. */
fun parseValue(def: FieldDef, text: String): JsonElement? = when (def.kind) {
    FieldKind.BOOL -> JsonPrimitive(text == "true")
    FieldKind.INT, FieldKind.NUMBER, FieldKind.WEEKDAY ->
        text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }?.let(::numJson)
    FieldKind.INT_LIST -> {
        val parts = listParts(text)
        if (parts.all { INT_PART.matches(it) }) JsonArray(parts.map { JsonPrimitive(it.toLong()) }) else null
    }
    else -> JsonPrimitive(text)
}

// --- display ----------------------------------------------------------------------------------

fun withUnit(value: String, unit: String?): String = if (unit.isNullOrBlank()) value else "$value $unit"

/** Read-only display of a stored value. */
fun describeValue(def: FieldDef, v: JsonElement?): String = when (def.kind) {
    FieldKind.BOOL -> if (v.bool == true) "On" else "Off"
    FieldKind.WEEKDAY -> v.num?.toInt()?.let { WEEKDAYS.getOrNull(it) } ?: toText(def, v)
    FieldKind.SELECT -> def.options.firstOrNull { it.value == v.str }?.label ?: toText(def, v)
    FieldKind.INT_LIST -> withUnit(toText(def, v), def.unit)
    else -> {
        val t = toText(def, v)
        if (def.path == "finder.speed_bytes") formatBytes(v.num) else if (t.isEmpty()) "—" else withUnit(t, def.unit)
    }
}

fun describeDefault(def: FieldDef): String = describeValue(def, defaultFor(def.path))

/** A friendlier reading of a number field ("= 1 h 30 min"), or null when it adds nothing. */
fun friendlyHint(def: FieldDef, text: String): String? {
    val v = parseValue(def, text).num ?: return null
    return when {
        def.path == "finder.speed_bytes" -> "= ${formatBytes(v)}"
        def.unit == "s" && v >= 60 -> "= ${formatDuration(v)}"
        def.unit == "min" && v >= 60 -> "= ${formatDuration(v * 60)}"
        def.unit == "h" && v >= 24 -> "= ${formatDuration(v * 3600)}"
        else -> null
    }
}

fun rangeText(def: FieldDef): String? {
    if (def.kind != FieldKind.INT && def.kind != FieldKind.NUMBER) return null
    val min = def.min ?: return null
    val max = def.max ?: return null
    return "Allowed ${numText(min)} to ${numText(max)}"
}

// --- validation -------------------------------------------------------------------------------

private val HOST_RE = Regex("^[A-Za-z0-9.:_-]+$")
private val TIME_RE = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
private val PLACEHOLDER_RE = Regex("\\{[a-z_]+\\}")
private val SCHEME_RE = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

private fun limitText(v: Double, unit: String?) = withUnit(numText(v), unit)

fun validateField(def: FieldDef, text: String, value: JsonElement?): String? = when (def.kind) {
    FieldKind.BOOL -> null
    FieldKind.INT, FieldKind.NUMBER -> {
        val v = value.num
        when {
            v == null -> "Enter a number."
            def.kind == FieldKind.INT && v != floor(v) -> "Enter a whole number."
            def.min != null && v < def.min -> "Must be at least ${limitText(def.min, def.unit)}."
            def.max != null && v > def.max -> "Must be at most ${limitText(def.max, def.unit)}."
            else -> null
        }
    }
    FieldKind.HOST -> when {
        text.isBlank() -> "Required."
        text.length > 253 || !HOST_RE.matches(text) -> "Use a host name or IP address."
        else -> null
    }
    FieldKind.URL -> when {
        text.isBlank() -> "Required."
        text.replace(PLACEHOLDER_RE, "1").toHttpUrlOrNull() != null -> null
        SCHEME_RE.containsMatchIn(text) && !text.startsWith("http", ignoreCase = true) -> "Use an http(s) URL."
        else -> "Enter a valid URL."
    }
    FieldKind.SELECT -> if (def.options.any { it.value == text }) null else "Choose an option."
    FieldKind.TIME -> if (TIME_RE.matches(text)) null else "Use HH:MM (24 h)."
    FieldKind.WEEKDAY -> {
        val v = value.num
        if (v != null && v == floor(v) && v in 0.0..6.0) null else "Choose a day."
    }
    FieldKind.INT_LIST -> {
        val parts = listParts(text)
        when {
            parts.isEmpty() -> "Enter at least one value."
            parts.size > 10 -> "At most 10 values."
            parts.any { !INT_PART.matches(it) } -> "Use whole numbers separated by commas."
            else -> {
                val nums = parts.map { it.toLong() }
                when {
                    def.min != null && nums.any { it < def.min } -> "Each value must be at least ${numText(def.min)}."
                    def.max != null && nums.any { it > def.max } -> "Each value must be at most ${numText(def.max)}."
                    nums.zipWithNext().any { (a, b) -> b <= a } -> "Values must be ascending and unique."
                    else -> null
                }
            }
        }
    }
}

data class Validation(val errors: Map<String, String>, val warnings: Map<String, String>)

/** Field rules plus the cross-field rules that keep the watchdog ladder and heat rules coherent. */
fun validateSettings(texts: Map<String, String>, values: Map<String, JsonElement?>, override: JsonObject?): Validation {
    val errors = LinkedHashMap<String, String>()
    val warnings = LinkedHashMap<String, String>()
    for (def in ALL_FIELDS) {
        validateField(def, texts[def.path].orEmpty(), values[def.path])?.let { errors[def.path] = it }
    }
    fun n(path: String): Double? = values[path].num
    /** Applies when both sides are valid numbers (a bad number already has its own error). */
    fun rule(a: String, b: String, ok: (Double, Double) -> Boolean, target: String, msg: String) {
        val x = n(a) ?: return
        val y = n(b) ?: return
        if (!ok(x, y) && target !in errors) errors[target] = msg
    }
    rule("watchdog.airplane_after_s", "watchdog.internet_fail_s", { a, b -> a > b }, "watchdog.airplane_after_s", "Must be later than the mobile data cycle.")
    rule("watchdog.reboot_after_s", "watchdog.airplane_after_s", { a, b -> a > b }, "watchdog.reboot_after_s", "Must be later than the airplane mode cycle.")
    rule("watchdog.temp_warn_c", "watchdog.temp_stop_charge_c", { a, b -> a <= b }, "watchdog.temp_warn_c", "Must not be above the stop-charging temperature.")
    rule("watchdog.temp_resume_c", "watchdog.temp_stop_charge_c", { a, b -> a <= b }, "watchdog.temp_resume_c", "Must not be above the stop-charging temperature.")
    rule("watchdog.temp_radio_rest_c", "watchdog.temp_stop_charge_c", { a, b -> a > b }, "watchdog.temp_radio_rest_c", "Must be above the stop-charging temperature.")
    rule("watchdog.charge_resume_drop_pct", "watchdog.charge_limit_pct", { a, b -> a < b }, "watchdog.charge_resume_drop_pct", "Must be smaller than the charge limit.")
    rule("telemetry.fast_active_s", "telemetry.fast_idle_s", { a, b -> a <= b }, "telemetry.fast_active_s", "Must not be longer than the idle interval.")
    rule("alerts.offline_after_s", "telemetry.heartbeat_s", { a, b -> a > b }, "alerts.offline_after_s", "Must be longer than the heartbeat interval.")
    if (override != null && AppJson.encodeToString(JsonObject.serializer(), override).length > 64 * 1024) {
        errors[OVERRIDE_PATH] = "Larger than 64 KiB."
    }
    val w = listOf(n("finder.w_sinr"), n("finder.w_rsrp"), n("finder.w_rsrq"))
    if (w.all { it != null }) {
        val sum = w.sumOf { it!! }
        if (!(sum > 0)) {
            if ("finder.w_sinr" !in errors) errors["finder.w_sinr"] = "At least one weight must be above 0."
        } else if (abs(sum - 1) > 0.001) {
            warnings["finder.w_sinr"] = "Weights add up to ${String.format(java.util.Locale.US, "%.2f", sum)}; scores will not be on the 0 to 100 scale."
        }
    }
    n("commands.revert_after_s")?.let { if (it < 60) warnings["commands.revert_after_s"] = "Short timers can undo a change before a slow link confirms it." }
    n("watchdog.charge_limit_pct")?.let {
        if (it > 80) warnings["watchdog.charge_limit_pct"] = "Holding a battery above 80 % in a hot box shortens its life and raises the swelling risk."
    }
    return Validation(errors, warnings)
}

// --- profile override -------------------------------------------------------------------------

const val OVERRIDE_PATH = "profile_override"

data class OverrideParse(val value: JsonObject?, val error: String?)

fun parseOverride(text: String): OverrideParse {
    if (text.isBlank()) return OverrideParse(null, null)
    return try {
        when (val v = AppJson.parseToJsonElement(text)) {
            is JsonObject -> OverrideParse(v, null)
            else -> OverrideParse(null, "Must be a JSON object like { \"usb_function\": \"ncm\" }.")
        }
    } catch (e: Exception) {
        OverrideParse(null, "Invalid JSON: ${e.message?.lineSequence()?.firstOrNull() ?: "cannot be read"}")
    }
}

// --- patches ----------------------------------------------------------------------------------

/** Builds the nested patch object from dotted paths. */
fun buildPatch(entries: List<Pair<String, JsonElement>>): JsonObject? {
    if (entries.isEmpty()) return null
    val root = LinkedHashMap<String, Any>()
    for ((path, value) in entries) {
        val keys = path.split('.')
        var cur = root
        for (k in keys.dropLast(1)) {
            @Suppress("UNCHECKED_CAST")
            cur = cur.getOrPut(k) { LinkedHashMap<String, Any>() } as LinkedHashMap<String, Any>
        }
        cur[keys.last()] = value
    }
    fun toJson(m: Map<String, Any>): JsonObject = JsonObject(
        m.mapValues { (_, v) ->
            @Suppress("UNCHECKED_CAST")
            if (v is JsonElement) v else toJson(v as Map<String, Any>)
        },
    )
    return toJson(root)
}
