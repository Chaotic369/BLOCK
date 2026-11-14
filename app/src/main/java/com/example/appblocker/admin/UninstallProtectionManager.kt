package com.example.appblocker.admin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import com.example.appblocker.di.AppModule

/**
 * Keeps the app from being uninstalled while any lock is active. Three layers, strongest first:
 *
 * 1. Device/profile owner (see [OWNER_SETUP_COMMAND]): the system itself refuses uninstall,
 *    and on Android 14+ also refuses force-stop and clearing data. Nothing the user taps can
 *    get around it until the lock ends.
 * 2. Device administrator: Android refuses to uninstall until the admin is deactivated.
 * 3. Accessibility guard: closes the Settings / installer screens that would deactivate the
 *    admin or the accessibility service, with a usage-stats fallback if the service is turned off.
 *
 * Layers 2 and 3 are "soft" because the user can still reach them through paths the guard
 * cannot see. Layer 1 is the only one that is enforced by the system.
 */
object UninstallProtectionManager {

    /** Run once from a computer with USB debugging on, with no accounts signed in on the device. */
    const val OWNER_SETUP_COMMAND =
        "adb shell dpm set-device-owner com.example.appblocker/.admin.BlockerDeviceAdminReceiver"

    private fun dpm(context: Context) =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun isAdminActive(context: Context): Boolean =
        dpm(context).isAdminActive(BlockerDeviceAdminReceiver.component(context))

    fun isOwner(context: Context): Boolean =
        dpm(context).isDeviceOwnerApp(context.packageName) ||
            dpm(context).isProfileOwnerApp(context.packageName)

    fun isLockActive(): Boolean = AppModule.settingsRepository.isLockActive

    /** Call whenever a lock starts or ends. */
    fun update(context: Context, locking: Boolean) {
        if (!isOwner(context)) return
        val manager = dpm(context)
        val admin = BlockerDeviceAdminReceiver.component(context)
        runCatching { manager.setUninstallBlocked(admin, context.packageName, locking) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Stops force-stop and clear-data from the app info screen while locked.
            runCatching {
                manager.setUserControlDisabledPackages(
                    admin,
                    if (locking) listOf(context.packageName) else emptyList()
                )
            }
        }
    }

    /**
     * Gives up device/profile ownership so the app can be uninstalled normally again.
     * Refuses while a lock is active. Returns true if ownership was released.
     */
    @Suppress("DEPRECATION")
    fun releaseOwnership(context: Context): Boolean {
        if (isLockActive() || !isOwner(context)) return false
        val manager = dpm(context)
        update(context, locking = false)
        return runCatching {
            if (manager.isDeviceOwnerApp(context.packageName)) {
                manager.clearDeviceOwnerApp(context.packageName)
            } else {
                manager.clearProfileOwner(BlockerDeviceAdminReceiver.component(context))
            }
        }.isSuccess
    }
}
