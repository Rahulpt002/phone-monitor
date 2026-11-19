package com.hotspotmonitor.agent.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One buffered protocol message (docs/protocol.md 3.2). [seq] is the Room autoincrement id and is
 * also written into [json] (OutboxStore does both in one transaction), so [json] can be sent as-is.
 */
@Entity(tableName = "outbox", indices = [Index(value = ["type", "tier"])])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    /** Protocol `t`: telemetry, event, finder_spot, finder_spot_deleted, sms, cmd_ack, cmd_result, cmd_revert. */
    val type: String,
    val json: String,
    /** Telemetry tier (fast/slow/static) for telemetry rows, null otherwise. Used by the trim policy. */
    val tier: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * An armed auto-revert (plan 6.4 rule 1). Persisted before the change is applied so a revert
 * survives an Agent restart or a reboot (protocol 3.5 "Auto-revert").
 */
@Entity(tableName = "armed_revert")
data class ArmedRevertEntity(
    @PrimaryKey @ColumnInfo(name = "command_id") val commandId: String,
    /** Command name of the undo action, e.g. "mobile_data". */
    @ColumnInfo(name = "undo_name") val undoName: String,
    /** JSON object of the undo action's args, e.g. {"enabled":true}. */
    @ColumnInfo(name = "undo_args_json") val undoArgsJson: String,
    /** Epoch ms (Clock.serverNow) after which the undo runs unless confirmed. */
    @ColumnInfo(name = "deadline_ms") val deadlineMs: Long,
    /** RevertConfirm wire value: "agent_link" or "dashboard". */
    @ColumnInfo(name = "confirm_mode") val confirmMode: String,
    /** Epoch ms (Clock.serverNow) when the change was applied. */
    @ColumnInfo(name = "applied_at_ms") val appliedAtMs: Long,
)

/** Every reboot the Agent initiated, from any source (plan 6.4 rule 3). */
@Entity(tableName = "reboot_log", indices = [Index(value = ["at_ms"])])
data class RebootLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "at_ms") val atMs: Long,
    /** CommandSource wire value. */
    val source: String,
)

/** A saved Finder spot; [json] is a FinderSpot encoded with AgentJson. */
@Entity(tableName = "finder_spot")
data class FinderSpotEntity(
    @PrimaryKey val id: String,
    val json: String,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)

/** Local audit of every command the Agent handled (Server, watchdog, local UI). */
@Entity(tableName = "command_log", indices = [Index(value = ["at_ms"])])
data class CommandLogEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "args_json") val argsJson: String,
    /** CommandSource wire value. */
    val source: String,
    /** CommandStatus wire value. */
    val status: String,
    val output: String?,
    @ColumnInfo(name = "at_ms") val atMs: Long,
)
