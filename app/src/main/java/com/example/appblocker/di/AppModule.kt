package com.example.appblocker.di

import android.content.Context
import com.example.appblocker.data.db.AppDatabase
import com.example.appblocker.data.repository.BlockRepository
import com.example.appblocker.data.repository.SettingsRepository
import com.example.appblocker.usage.UsageStatsRepository

/** Simple manual service locator. Call [init] once from Application.onCreate. */
object AppModule {

    private lateinit var appContext: Context

    val database: AppDatabase by lazy { AppDatabase.create(appContext) }

    val blockRepository: BlockRepository by lazy {
        BlockRepository(
            appContext,
            database.blockedAppDao(),
            database.blockedDomainDao(),
            settingsRepository
        )
    }

    val usageRepository: UsageStatsRepository by lazy { UsageStatsRepository(appContext) }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

}
