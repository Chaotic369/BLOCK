package com.example.appblocker.admin

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.example.appblocker.di.AppModule
import com.example.appblocker.util.NotificationHelper

class BlockerDeviceAdminReceiver : DeviceAdminReceiver() {

    /**
     * Runs on the main thread and must answer synchronously, so it reads the cached lock
     * state from SharedPreferences instead of querying the database.
     */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence? {
        return if (AppModule.settingsRepository.isLockActive) {
            "A lock is active. Disabling administrator access now will make this blocker " +
                "removable before your timer ends."
        } else {
            null
        }
    }

    /** Admin was switched off. If that happened mid-lock, tell the user straight away. */
    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        if (AppModule.settingsRepository.isLockActive) {
            NotificationHelper.notifyAlert(
                context,
                NotificationHelper.ALERT_ADMIN_OFF,
                "Uninstall protection is off",
                "Device administrator was disabled during an active lock. Tap to turn it back on."
            )
        }
    }

    companion object {
        fun component(context: Context) =
            ComponentName(context.applicationContext, BlockerDeviceAdminReceiver::class.java)
    }

}
