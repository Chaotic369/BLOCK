package com.example.appblocker

import android.app.Application
import com.example.appblocker.di.AppModule
import com.example.appblocker.scheduler.UsageSyncWorker
import com.example.appblocker.util.NotificationHelper

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        AppModule.init(this)
        NotificationHelper.createChannels(this)
        UsageSyncWorker.schedule(this)
    }
}
