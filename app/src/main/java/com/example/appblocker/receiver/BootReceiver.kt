package com.example.appblocker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.appblocker.di.AppModule
import com.example.appblocker.scheduler.BlockScheduler
import com.example.appblocker.service.BlockDnsVpnService
import com.example.appblocker.service.BlockerForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = AppModule.blockRepository
                val now = System.currentTimeMillis()
                repository.purgeExpired(now)
                if (repository.hasActiveBlocks(now)) {
                    BlockerForegroundService.start(context)
                    BlockDnsVpnService.start(context)
                    repository.nextExpiry(now)?.let { BlockScheduler.scheduleExpiry(context, it) }
                }
            } finally {
                pending.finish()
            }
        }
    }

}
