package com.example.appblocker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.appblocker.data.model.BlockedApp
import com.example.appblocker.data.model.BlockedDomain
import com.example.appblocker.data.model.UsageSnapshot

@Database(
    entities = [BlockedApp::class, BlockedDomain::class, UsageSnapshot::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun blockedAppDao(): BlockedAppDao
    abstract fun blockedDomainDao(): BlockedDomainDao
    abstract fun usageSnapshotDao(): UsageSnapshotDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE blocked_apps ADD COLUMN allowanceMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE blocked_apps ADD COLUMN sinceMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE blocked_domains ADD COLUMN allowanceMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE blocked_domains ADD COLUMN usedMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE blocked_domains ADD COLUMN usedDay INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "appblocker.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build()
    }

}
