package com.example.appblocker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.appblocker.di.AppModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Removes lock entries for apps that were uninstalled by the user. Registered at runtime by
 * BlockerForegroundService; BlockRepository.pruneUninstalled() covers anything missed.
 */
class PackageRemovedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_FULLY_REMOVED) return
        val pkg = intent.data?.schemeSpecificPart ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AppModule.blockRepository.removeApp(pkg)
            } finally {
                pending.finish()
            }
        }
    }

}
