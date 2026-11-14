package com.example.appblocker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.appblocker.admin.UninstallProtectionManager
import com.example.appblocker.di.AppModule
import com.example.appblocker.scheduler.BlockScheduler
import com.example.appblocker.service.BlockDnsVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BlockExpiryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = AppModule.blockRepository
                val now = System.currentTimeMillis()
                repository.purgeExpired(now)
                if (repository.hasActiveBlocks(now)) {
                    repository.nextExpiry(now)?.let { BlockScheduler.scheduleExpiry(context, it) }
                } else {
                    UninstallProtectionManager.update(context, locking = false)
                    BlockDnsVpnService.stop(context)
                }
            } finally {
                pending.finish()
            }
        }
    }

}
