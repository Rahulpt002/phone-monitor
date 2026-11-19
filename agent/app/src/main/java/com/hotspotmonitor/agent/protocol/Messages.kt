@file:UseSerializers(LenientIntSerializer::class, LenientLongSerializer::class)

package com.hotspotmonitor.agent.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonObject

/*
 * WebSocket messages of WS /ws/agent (shared/src/protocol.ts, docs/protocol.md section 3).
 * The `t` field is the class discriminator (AgentJson.classDiscriminator), so it is not a property
 * here: encode through Wire.encode / Wire.encodeBuffered, which use the sealed base serializers.
 */

// ---------------------------------------------------------------------------------------------
// Agent -> Server
// ---------------------------------------------------------------------------------------------

@Serializable
sealed interface AgentToServer

/**
 * Messages that go through the local outbox and carry `seq` (protocol 3.2). Create them with
 * seq = 0; OutboxStore.enqueue assigns the real seq.
 */
@Serializable
sealed interface BufferedMsg : AgentToServer {
    val seq: Long
    fun withSeq(seq: Long): BufferedMsg
}

/** Telemetry groups are flattened at the top level, like TelemetryGroups in TypeScript. */
@Serializable
@SerialName("telemetry")
data class TelemetryMsg(
    override val seq: Long = 0,
    /** When the reading was taken (not when it was sent). */
    val ts: String,
    val tier: TelemetryTier,
    val radio: RadioInfo? = null,
    val net: NetInfo? = null,
    val traffic: TrafficInfo? = null,
    val tether: TetherInfo? = null,
    val power: PowerInfo? = null,
    val heat: HeatInfo? = null,
    val system: SystemInfo? = null,
    val position: PositionInfo? = null,
    val device: DeviceInfo? = null,
    val capabilities: Map<String, CapabilityEntry>? = null,
) : BufferedMsg {
    override fun withSeq(seq: Long) = copy(seq = seq)
}

@Serializable
@SerialName("event")
data class EventMsg(
    override val seq: Long = 0,
    val event: AgentEvent,
) : BufferedMsg {
    override fun withSeq(seq: Long) = copy(seq = seq)
}

/** Insert or replace by spot.id. */
@Serializable
@SerialName("finder_spot")
data class FinderSpotMsg(
    override val seq: Long = 0,
    val spot: FinderSpot,
) : BufferedMsg {
    override fun withSeq(seq: Long) = copy(seq = seq)
}

@Serializable
@SerialName("finder_spot_deleted")
data class FinderSpotDeletedMsg(
    override val seq: Long = 0,
    @SerialName("spot_id") val spotId: String,
) : BufferedMsg {
    override fun withSeq(seq: Long) = copy(seq = seq)
}

@Serializable
enum class SmsDirection {
    @SerialName("in") IN,
    @SerialName("out") OUT,
}

@Serializable
data class SmsRecord(
    /** "m_" + random (Agent). */
    val id: String,
    val direction: SmsDirection,
    /** Phone number or sender id. */
    val peer: String,
    val body: String,
    val at: String,
)

@Serializable
@SerialName("sms")
data class SmsMsg(
    override val seq: Long = 0,
    val sms: SmsRecord,
) : BufferedMsg {
    override fun withSeq(seq: Long) = copy(seq = seq)
}

@Serializable
@SerialName("cmd_ack")
data class CmdAckMsg(
    override val seq: Long = 0,
    val id: String,
    val accepted: Boolean,
    val reason: CmdRejectReason? = null,
    val ts: String,
) : BufferedMsg {
    override fun withSeq(seq: Long) = copy(seq = seq)
}

@Serializable
@SerialName("cmd_result")
data class CmdResultMsg(
    override val seq: Long = 0,
    val id: String,
    val ok: Boolean,
    /** stdout or a human summary, may be "". Truncate to ProtocolLimits.MAX_OUTPUT_BYTES. */
    val output: String = "",
    val error: String? = null,
    @SerialName("duration_ms") val durationMs: Long,
    /** Set when auto-revert is armed: the Agent undoes the change at this time unless confirmed. */
    @SerialName("revert_deadline") val revertDeadline: String? = null,
    val ts: String,
) : BufferedMsg {
    override fun withSeq(seq: Long) = copy(seq = seq)
}

@Serializable
enum class RevertState {
    @SerialName("kept") KEPT,
    @SerialName("reverted") REVERTED,
}

@Serializable
@SerialName("cmd_revert")
data class CmdRevertMsg(
    override val seq: Long = 0,
    val id: String,
    val state: RevertState,
    val detail: String? = null,
    val ts: String,
) : BufferedMsg {
    override fun withSeq(seq: Long) = copy(seq = seq)
}

/** Backlog upload: up to ProtocolLimits.MAX_BATCH_ITEMS buffered messages in seq order. */
@Serializable
@SerialName("batch")
data class BatchMsg(val items: List<BufferedMsg>) : AgentToServer

/** Every heartbeat_s. Not buffered. */
@Serializable
@SerialName("heartbeat")
data class HeartbeatMsg(
    val ts: String,
    @SerialName("uptime_s") val uptimeS: Long,
    @SerialName("agent_uptime_s") val agentUptimeS: Long,
    /** Messages waiting in the local outbox. */
    val outbox: Int,
    /** Last applied settings version (0 = defaults). */
    @SerialName("settings_version") val settingsVersion: Int,
) : AgentToServer

/** Round-trip check used to confirm `agent_link` auto-reverts. Not buffered. */
@Serializable
@SerialName("probe")
data class ProbeMsg(val id: String) : AgentToServer

/** 1/s while rates.mirror = true. Not buffered, not stored. */
@Serializable
@SerialName("finder_live")
data class FinderLiveMsg(val live: FinderLive) : AgentToServer

// ---------------------------------------------------------------------------------------------
// Server -> Agent
// ---------------------------------------------------------------------------------------------

@Serializable
sealed interface ServerToAgent

@Serializable
data class Rates(
    /** Current fast-tier interval chosen by the Server (fast_active_s or fast_idle_s). */
    @SerialName("fast_s") val fastS: Int = TelemetrySettings().fastIdleS,
    /** Stream finder_live 1/s. */
    val mirror: Boolean = false,
)

/** First message after a successful upgrade. */
@Serializable
@SerialName("hello")
data class HelloMsg(
    /** Clock offset = server_time - local now at receipt. */
    @SerialName("server_time") val serverTime: String,
    @SerialName("device_id") val deviceId: String,
    val settings: Settings,
    @SerialName("settings_version") val settingsVersion: Int,
    val rates: Rates = Rates(),
) : ServerToAgent

@Serializable
@SerialName("settings")
data class SettingsMsg(
    val settings: Settings,
    val version: Int,
) : ServerToAgent

@Serializable
@SerialName("rates")
data class RatesMsg(
    @SerialName("fast_s") val fastS: Int,
    val mirror: Boolean,
) : ServerToAgent {
    fun toRates() = Rates(fastS = fastS, mirror = mirror)
}

@Serializable
@SerialName("cmd")
data class CmdMsg(
    val id: String,
    /** A [CommandNames] value; kept as a string so unknown names can be rejected as `unsupported`. */
    val name: String,
    /** Read with the typed helpers in Args.kt (requireBoolean, optInt, ...). */
    val args: JsonObject = JsonObject(emptyMap()),
    /** When the user issued it (Server clock). */
    @SerialName("sent_at") val sentAt: String,
    val user: String? = null,
    /** Set when auto-revert must be armed. */
    @SerialName("revert_after_s") val revertAfterS: Int? = null,
    @SerialName("revert_confirm") val revertConfirm: RevertConfirm? = null,
) : ServerToAgent

/** A user pressed "Keep" for a revert_armed command with revert_confirm = 'dashboard'. */
@Serializable
@SerialName("cmd_keep")
data class CmdKeepMsg(val id: String) : ServerToAgent

/** All buffered messages with seq <= this are stored. */
@Serializable
@SerialName("ack")
data class AckMsg(val seq: Long) : ServerToAgent

@Serializable
@SerialName("probe_ack")
data class ProbeAckMsg(
    val id: String,
    @SerialName("server_time") val serverTime: String,
) : ServerToAgent

@Serializable
@SerialName("error")
data class ErrorMsg(
    /** 'unauthorized' | 'bad_message' | 'replaced' | 'internal' (see [ErrorCodes]). */
    val code: String,
    val message: String = "",
) : ServerToAgent

object ErrorCodes {
    const val UNAUTHORIZED = "unauthorized"
    const val BAD_MESSAGE = "bad_message"
    const val REPLACED = "replaced"
    const val INTERNAL = "internal"
}
