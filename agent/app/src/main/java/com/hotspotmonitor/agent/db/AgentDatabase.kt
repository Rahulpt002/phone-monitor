package com.hotspotmonitor.agent.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The Agent's local database. Schema JSON is exported to agent/app/schemas; any entity change
 * must bump [version] and add a Migration in [build] (the outbox may hold unsent data).
 */
@Database(
    entities = [
        OutboxEntity::class,
        ArmedRevertEntity::class,
        RebootLogEntity::class,
        FinderSpotEntity::class,
        CommandLogEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun outboxDao(): OutboxDao
    abstract fun armedRevertDao(): ArmedRevertDao
    abstract fun rebootLogDao(): RebootLogDao
    abstract fun finderSpotDao(): FinderSpotDao
    abstract fun commandLogDao(): CommandLogDao

    companion object {
        const val NAME = "agent.db"

        fun build(context: Context): AgentDatabase =
            Room.databaseBuilder(context.applicationContext, AgentDatabase::class.java, NAME)
                // WAL keeps the single outbox writer from blocking readers (UI counts, sender peeks).
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
    }
}
