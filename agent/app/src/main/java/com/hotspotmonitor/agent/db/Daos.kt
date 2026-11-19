package com.hotspotmonitor.agent.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Outbox access. An abstract class (not an interface) so [insertAssigningSeq] can run its two
 * statements in one transaction; unit tests subclass it with an in-memory fake.
 */
@Dao
abstract class OutboxDao {
    @Insert
    abstract suspend fun insert(entity: OutboxEntity): Long

    @Query("UPDATE outbox SET json = :json WHERE seq = :seq")
    abstract suspend fun updateJson(seq: Long, json: String)

    @Query("SELECT * FROM outbox ORDER BY seq ASC LIMIT :limit")
    abstract suspend fun peek(limit: Int): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE seq <= :seq")
    abstract suspend fun deleteUpTo(seq: Long): Int

    @Query("SELECT COUNT(*) FROM outbox")
    abstract suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM outbox")
    abstract fun countFlow(): Flow<Int>

    /** Deletes the [limit] oldest rows of one type + tier (trim policy). */
    @Query(
        "DELETE FROM outbox WHERE seq IN " +
            "(SELECT seq FROM outbox WHERE type = :type AND tier = :tier ORDER BY seq ASC LIMIT :limit)",
    )
    abstract suspend fun deleteOldest(type: String, tier: String, limit: Int): Int

    /**
     * Inserts [entity], then rewrites its json with the assigned seq ([encode] receives the seq),
     * atomically. Returns the seq.
     */
    @Transaction
    open suspend fun insertAssigningSeq(entity: OutboxEntity, encode: (Long) -> String): Long {
        val seq = insert(entity)
        updateJson(seq, encode(seq))
        return seq
    }
}

@Dao
interface ArmedRevertDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ArmedRevertEntity)

    @Query("SELECT * FROM armed_revert WHERE command_id = :commandId")
    suspend fun get(commandId: String): ArmedRevertEntity?

    @Query("SELECT * FROM armed_revert ORDER BY deadline_ms ASC")
    suspend fun all(): List<ArmedRevertEntity>

    @Query("SELECT * FROM armed_revert ORDER BY deadline_ms ASC")
    fun allFlow(): Flow<List<ArmedRevertEntity>>

    @Query("SELECT COUNT(*) FROM armed_revert")
    suspend fun count(): Int

    @Query("DELETE FROM armed_revert WHERE command_id = :commandId")
    suspend fun delete(commandId: String): Int
}

@Dao
interface RebootLogDao {
    @Insert
    suspend fun insert(entity: RebootLogEntity): Long

    @Query("SELECT COUNT(*) FROM reboot_log WHERE at_ms >= :sinceMs")
    suspend fun countSince(sinceMs: Long): Int

    @Query("SELECT * FROM reboot_log ORDER BY at_ms DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<RebootLogEntity>

    @Query("DELETE FROM reboot_log WHERE at_ms < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long): Int
}

@Dao
interface FinderSpotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FinderSpotEntity)

    @Query("SELECT * FROM finder_spot WHERE id = :id")
    suspend fun get(id: String): FinderSpotEntity?

    @Query("SELECT * FROM finder_spot ORDER BY updated_at_ms DESC")
    suspend fun all(): List<FinderSpotEntity>

    @Query("SELECT * FROM finder_spot ORDER BY updated_at_ms DESC")
    fun allFlow(): Flow<List<FinderSpotEntity>>

    @Query("DELETE FROM finder_spot WHERE id = :id")
    suspend fun delete(id: String): Int
}

@Dao
interface CommandLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CommandLogEntity)

    @Query("SELECT * FROM command_log WHERE id = :id")
    suspend fun get(id: String): CommandLogEntity?

    @Query("SELECT * FROM command_log ORDER BY at_ms DESC LIMIT :limit")
    fun recentFlow(limit: Int): Flow<List<CommandLogEntity>>

    @Query("SELECT * FROM command_log ORDER BY at_ms DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<CommandLogEntity>

    @Query("DELETE FROM command_log WHERE at_ms < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long): Int
}
