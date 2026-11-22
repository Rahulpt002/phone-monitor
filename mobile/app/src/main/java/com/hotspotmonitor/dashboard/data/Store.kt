package com.hotspotmonitor.dashboard.data

import com.hotspotmonitor.agent.protocol.FinderLive
import com.hotspotmonitor.agent.protocol.FinderSpot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Last live telemetry message, so open charts can append it. */
data class LiveTelemetry(val deviceId: String, val ts: String, val tier: String, val groups: JsonObject)

/**
 * Everything the dashboard socket and the REST loaders deliver (port of web/src/store/reducer.ts).
 * Lists are newest first and capped; command records never move backwards in their lifecycle.
 */
data class DataState(
    val devices: List<DeviceSummary> = emptyList(),
    val snapshots: Map<String, DeviceSnapshot> = emptyMap(),
    val events: List<StoredEvent> = emptyList(),
    val commands: List<CommandRecord> = emptyList(),
    val alerts: List<AlertRecord> = emptyList(),
    val sms: List<SmsRecord> = emptyList(),
    val spots: Map<String, List<FinderSpot>> = emptyMap(),
    val finderLive: Map<String, LiveFinder> = emptyMap(),
    val settings: Map<String, SettingsResponse> = emptyMap(),
    val lastTelemetry: LiveTelemetry? = null,
) {
    /** Newest record per command name for a device ("last result" on each control). */
    fun lastCommandByName(deviceId: String?): Map<String, CommandRecord> {
        val out = LinkedHashMap<String, CommandRecord>()
        for (c in commands) {
            if (deviceId != null && c.deviceId != deviceId) continue
            if (!out.containsKey(c.name)) out[c.name] = c
        }
        return out
    }

    /** Commands armed for auto-revert, from the snapshot and live command updates. */
    fun pendingReverts(deviceId: String?): List<CommandRecord> {
        if (deviceId == null) return emptyList()
        val map = LinkedHashMap<String, CommandRecord>()
        snapshots[deviceId]?.pendingReverts?.forEach { map[it.id] = it }
        for (c in commands) {
            if (c.deviceId != deviceId) continue
            if (c.status == "revert_armed") map[c.id] = c else map.remove(c.id)
        }
        return map.values.sortedBy { isoMs(it.revertDeadline) ?: Long.MAX_VALUE }
    }
}

object Caps {
    const val EVENTS = 500
    const val COMMANDS = 500
    const val ALERTS = 300
    const val SMS = 500
}

/** Commands whose arguments hold secrets (CommandSpec.secret_args): never stored. */
private val SECRET_ARGS: Map<String, List<String>> = mapOf("hotspot" to listOf("password"))

private fun sanitize(c: CommandRecord): CommandRecord {
    val secret = SECRET_ARGS[c.name] ?: return c
    if (secret.none { it in c.args }) return c
    return c.copy(args = JsonObject(c.args.filterKeys { it !in secret }))
}

// --- command lifecycle (shared/src/commands.ts) ------------------------------------------------

private fun allowedFrom(next: String, delivered: Boolean): List<String> {
    val soft = if (delivered) listOf("expired") else emptyList()
    return when (next) {
        "sent" -> listOf("queued")
        "expired" -> listOf("queued", "sent", "acked", "revert_armed")
        "acked", "rejected" -> listOf("queued", "sent") + soft
        "done", "failed", "revert_armed" -> listOf("queued", "sent", "acked") + soft
        "kept", "reverted" -> listOf("queued", "sent", "acked", "done", "failed", "revert_armed") + soft
        else -> emptyList()
    }
}

private val LATE_ORDER = mapOf("sent" to 1, "acked" to 2, "rejected" to 2, "done" to 3, "failed" to 3, "revert_armed" to 3, "kept" to 4, "reverted" to 4)

fun commandMayMove(cur: CommandRecord, next: String): Boolean {
    if (cur.status !in allowedFrom(next, cur.deliveredAt != null)) return false
    if (cur.status == "expired") {
        val before = when {
            cur.revertDeadline != null -> 3
            cur.ackedAt != null -> 2
            else -> 1
        }
        if ((LATE_ORDER[next] ?: 0) <= before) return false
    }
    return true
}

private fun notBehind(cur: CommandRecord, next: CommandRecord) = next.status == cur.status || commandMayMove(cur, next.status)

private fun <T> mergeById(
    list: List<T>,
    incoming: List<T>,
    key: (T) -> String,
    time: (T) -> String?,
    cap: Int,
    replaces: ((T, T) -> Boolean)? = null,
): List<T> {
    if (incoming.isEmpty()) return list
    val map = LinkedHashMap<String, T>()
    for (x in list) map[key(x)] = x
    for (x in incoming) {
        val cur = map[key(x)]
        if (cur == null || replaces == null || replaces(cur, x)) map[key(x)] = x
    }
    return map.values.sortedByDescending { isoMs(time(it)) ?: Long.MIN_VALUE }.take(cap)
}

private fun mergeCommandList(list: List<CommandRecord>, incoming: List<CommandRecord>) =
    mergeById(list, incoming.map(::sanitize), { it.id }, { it.createdAt }, Caps.COMMANDS, ::notBehind)

private fun laterIso(a: String?, b: String?): String? = if ((isoMs(a) ?: Long.MIN_VALUE) >= (isoMs(b) ?: Long.MIN_VALUE)) a ?: b else b

private fun upsertSummary(list: List<DeviceSummary>, d: DeviceSummary): List<DeviceSummary> {
    val i = list.indexOfFirst { it.deviceId == d.deviceId }
    return if (i < 0) list + d else list.toMutableList().also { it[i] = d }
}

private fun patchSummary(list: List<DeviceSummary>, id: String, f: (DeviceSummary) -> DeviceSummary): List<DeviceSummary> {
    val i = list.indexOfFirst { it.deviceId == id }
    return if (i < 0) list else list.toMutableList().also { it[i] = f(it[i]) }
}

private fun upsertSpot(spots: List<FinderSpot>, spot: FinderSpot): List<FinderSpot> {
    val rest = spots.filter { it.id != spot.id }
    val normalized = if (spot.baseline) rest.map { if (it.baseline) it.copy(baseline = false) else it } else rest
    return normalized + spot
}

private fun withPendingRevert(snap: DeviceSnapshot, cmd: CommandRecord): DeviceSnapshot {
    val others = snap.pendingReverts.filter { it.id != cmd.id }
    if (cmd.status == "revert_armed") return snap.copy(pendingReverts = others + cmd)
    return if (others.size == snap.pendingReverts.size) snap else snap.copy(pendingReverts = others)
}

/** Applies one telemetry message; older readings never overwrite newer ones. */
private fun applyTelemetry(snap: DeviceSnapshot, t: LiveTelemetry): DeviceSnapshot {
    val at = isoMs(t.ts) ?: return snap
    val state = snap.state.toMutableMap()
    val ts = snap.stateTs.toMutableMap()
    var changed = false
    for (key in GROUP_KEYS) {
        val v = t.groups[key] ?: continue
        if (v is kotlinx.serialization.json.JsonNull) continue
        if ((isoMs(ts[key]) ?: Long.MIN_VALUE) > at) continue
        state[key] = v
        ts[key] = t.ts
        changed = true
    }
    if (!changed) return snap
    val newDevice = t.groups["device"]
    val info = if (newDevice is JsonObject && (isoMs(snap.stateTs["device"]) ?: Long.MIN_VALUE) <= at) newDevice else snap.info
    return snap.copy(state = JsonObject(state), stateTs = ts, info = info, lastSeen = laterIso(snap.lastSeen, t.ts))
}

class Store {
    private val _state = MutableStateFlow(DataState())
    val state: StateFlow<DataState> = _state.asStateFlow()
    val current: DataState get() = _state.value

    fun reset() {
        _state.value = DataState()
    }

    /** One Server -> Dashboard socket message. Unknown or malformed messages are ignored. */
    fun apply(msg: JsonObject, nowMs: Long = System.currentTimeMillis()) {
        val t = (msg["t"] as? JsonPrimitive)?.contentOrNull ?: return
        _state.update { s ->
            try {
                applyMessage(s, t, msg, nowMs)
            } catch (e: Exception) {
                s
            }
        }
    }

    private fun str(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.contentOrNull

    private fun applyMessage(s: DataState, t: String, msg: JsonObject, nowMs: Long): DataState = when (t) {
        "snapshot" -> {
            val snap = decodeOrNull(DeviceSnapshot.serializer(), msg["device"])
            if (snap == null) s else applySnapshot(s, snap.copy(pendingReverts = snap.pendingReverts.map(::sanitize)))
        }
        "telemetry" -> {
            val id = str(msg, "device_id")
            val ts = str(msg, "ts")
            if (id == null || ts == null) s else {
                val tele = LiveTelemetry(id, ts, str(msg, "tier") ?: "", msg)
                val prev = s.snapshots[id] ?: DeviceSnapshot.empty(id)
                val next = applyTelemetry(prev, tele)
                s.copy(
                    snapshots = if (next === prev && s.snapshots.containsKey(id)) s.snapshots else s.snapshots + (id to next),
                    devices = if (next.info != prev.info) patchSummary(s.devices, id) { it.copy(info = next.info) } else s.devices,
                    lastTelemetry = tele,
                )
            }
        }
        "event" -> {
            val id = str(msg, "device_id")
            val ev = decodeOrNull(StoredEvent.serializer(), msg["event"])
            if (id == null || ev == null) s else s.copy(events = mergeById(s.events, listOf(ev.copy(deviceId = id)), { it.id }, { it.ts }, Caps.EVENTS))
        }
        "status" -> {
            val id = str(msg, "device_id")
            if (id == null) s else {
                val online = (msg["online"] as? JsonPrimitive)?.booleanOrNull ?: false
                val lastSeen = str(msg, "last_seen")
                val since = str(msg, "connected_since")
                val snap = s.snapshots[id]
                s.copy(
                    snapshots = if (snap != null) s.snapshots + (id to snap.copy(online = online, connectedSince = since, lastSeen = laterIso(snap.lastSeen, lastSeen))) else s.snapshots,
                    devices = patchSummary(s.devices, id) { it.copy(online = online, lastSeen = lastSeen) },
                )
            }
        }
        "command" -> {
            val cmd = decodeOrNull(CommandRecord.serializer(), msg["command"])
            if (cmd == null) s else {
                val commands = mergeCommandList(s.commands, listOf(cmd))
                val current = commands.firstOrNull { it.id == cmd.id } ?: sanitize(cmd)
                val snap = s.snapshots[cmd.deviceId]
                s.copy(commands = commands, snapshots = if (snap != null) s.snapshots + (cmd.deviceId to withPendingRevert(snap, current)) else s.snapshots)
            }
        }
        "finder_live" -> {
            val id = str(msg, "device_id")
            val live = decodeOrNull(FinderLive.serializer(), msg["live"])
            if (id == null || live == null) s else s.copy(finderLive = s.finderLive + (id to LiveFinder(live, nowMs)))
        }
        "finder_spot" -> {
            val id = str(msg, "device_id")
            val spot = decodeOrNull(FinderSpot.serializer(), msg["spot"])
            if (id == null || spot == null) s else {
                val snap = s.snapshots[id]
                val snaps = when {
                    snap == null -> s.snapshots
                    spot.baseline -> s.snapshots + (id to snap.copy(baseline = msg["spot"] as? JsonObject))
                    snap.baselineSpot?.id == spot.id -> s.snapshots + (id to snap.copy(baseline = null))
                    else -> s.snapshots
                }
                s.copy(spots = s.spots + (id to upsertSpot(s.spots[id] ?: emptyList(), spot)), snapshots = snaps)
            }
        }
        "finder_spot_deleted" -> {
            val id = str(msg, "device_id")
            val spotId = str(msg, "spot_id")
            if (id == null || spotId == null) s else {
                val snap = s.snapshots[id]
                s.copy(
                    spots = s.spots + (id to (s.spots[id] ?: emptyList()).filter { it.id != spotId }),
                    snapshots = if (snap != null && snap.baselineSpot?.id == spotId) s.snapshots + (id to snap.copy(baseline = null)) else s.snapshots,
                )
            }
        }
        "alert" -> {
            val a = decodeOrNull(AlertRecord.serializer(), msg["alert"])
            if (a == null) s else s.copy(alerts = mergeById(s.alerts, listOf(a), { it.id.toString() }, { it.ts }, Caps.ALERTS))
        }
        "sms" -> {
            val id = str(msg, "device_id")
            val m = decodeOrNull(SmsRecord.serializer(), msg["sms"])
            if (id == null || m == null) s else s.copy(sms = mergeById(s.sms, listOf(m.copy(deviceId = id)), { it.id }, { it.at }, Caps.SMS))
        }
        "settings" -> {
            val id = str(msg, "device_id")
            val settings = msg["settings"] as? JsonObject
            val version = (msg["version"] as? JsonPrimitive)?.intOrNull
            if (id == null || settings == null || version == null) s else applySettings(s, SettingsResponse(id, settings, version))
        }
        else -> s
    }

    private fun applySnapshot(s: DataState, snap: DeviceSnapshot): DataState {
        val baseline = snap.baselineSpot
        val spots = if (baseline != null && s.spots[snap.deviceId] != null) {
            s.spots + (snap.deviceId to upsertSpot(s.spots[snap.deviceId]!!, baseline))
        } else s.spots
        return s.copy(
            snapshots = s.snapshots + (snap.deviceId to snap),
            devices = upsertSummary(s.devices, snap.summary()),
            commands = mergeCommandList(s.commands, snap.pendingReverts),
            spots = spots,
        )
    }

    private fun applySettings(s: DataState, r: SettingsResponse): DataState {
        val cur = s.settings[r.deviceId]
        if (cur != null && cur.version > r.version) return s
        val snap = s.snapshots[r.deviceId]
        return s.copy(
            settings = s.settings + (r.deviceId to r),
            snapshots = if (snap != null) s.snapshots + (r.deviceId to snap.copy(settingsVersion = r.version)) else s.snapshots,
        )
    }

    // --- REST loader merges ----------------------------------------------------------------------

    fun setDevices(devices: List<DeviceSummary>) = _state.update { s ->
        val known = devices.map { it.deviceId }.toSet()
        s.copy(devices = devices, snapshots = s.snapshots.filterKeys { it in known })
    }

    /** A REST snapshot; keeps any group (and online state) the socket delivered after it was produced. */
    fun setSnapshot(snap: DeviceSnapshot) = _state.update { s ->
        val cur = s.snapshots[snap.deviceId]
        var merged = snap.copy(pendingReverts = snap.pendingReverts.map(::sanitize))
        if (cur != null) {
            val state = merged.state.toMutableMap()
            val ts = merged.stateTs.toMutableMap()
            for (key in GROUP_KEYS) {
                val curTs = isoMs(cur.stateTs[key]) ?: Long.MIN_VALUE
                if (curTs > (isoMs(merged.stateTs[key]) ?: Long.MIN_VALUE) && cur.state[key] != null) {
                    state[key] = cur.state[key]!!
                    ts[key] = cur.stateTs[key]!!
                }
            }
            fun linkAt(x: DeviceSnapshot) = if (x.online) isoMs(x.connectedSince) else isoMs(x.lastSeen)
            val keepLink = (linkAt(cur) ?: Long.MIN_VALUE) > (linkAt(merged) ?: Long.MIN_VALUE)
            merged = merged.copy(
                state = JsonObject(state),
                stateTs = ts,
                online = if (keepLink) cur.online else merged.online,
                connectedSince = if (keepLink) cur.connectedSince else merged.connectedSince,
                lastSeen = laterIso(merged.lastSeen, cur.lastSeen),
            )
        }
        applySnapshot(s, merged)
    }

    fun mergeCommands(list: List<CommandRecord>) = _state.update { it.copy(commands = mergeCommandList(it.commands, list)) }

    fun mergeEvents(list: List<StoredEvent>) =
        _state.update { it.copy(events = mergeById(it.events, list, { e -> e.id }, { e -> e.ts }, Caps.EVENTS)) }

    fun mergeAlerts(list: List<AlertRecord>) =
        _state.update { it.copy(alerts = mergeById(it.alerts, list, { a -> a.id.toString() }, { a -> a.ts }, Caps.ALERTS)) }

    fun mergeSms(deviceId: String, list: List<SmsRecord>) =
        _state.update { it.copy(sms = mergeById(it.sms, list.map { m -> m.copy(deviceId = deviceId) }, { m -> m.id }, { m -> m.at }, Caps.SMS)) }

    fun setSettings(r: SettingsResponse) = _state.update { applySettings(it, r) }

    fun setSpots(deviceId: String, raw: List<JsonElement>, baselineId: String?) = _state.update { s ->
        val spots = decodeOrNull(ListSerializer(FinderSpot.serializer()), kotlinx.serialization.json.JsonArray(raw))
            ?: raw.mapNotNull { decodeOrNull(FinderSpot.serializer(), it) }
        val normalized = spots.map { it.copy(baseline = if (baselineId != null) it.id == baselineId else it.baseline) }
        val baselineRaw = raw.firstOrNull { el ->
            val id = (el as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
            id != null && normalized.any { it.id == id && it.baseline }
        } as? JsonObject
        val snap = s.snapshots[deviceId]
        s.copy(
            spots = s.spots + (deviceId to normalized),
            snapshots = if (snap != null) s.snapshots + (deviceId to snap.copy(baseline = baselineRaw)) else s.snapshots,
        )
    }
}
