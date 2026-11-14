package com.example.appblocker.util

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.appblocker.admin.BlockerDeviceAdminReceiver
import com.example.appblocker.admin.UninstallProtectionManager
import com.example.appblocker.di.AppModule
import com.example.appblocker.service.BlockerAccessibilityService

object PermissionUtils {

    fun hasUsageAccess(context: Context): Boolean = AppModule.usageRepository.hasPermission()

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, BlockerAccessibilityService::class.java)
            .flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun isDeviceAdminActive(context: Context): Boolean =
        UninstallProtectionManager.isAdminActive(context)

    /** Required for the app to work at all. VPN consent and notifications are optional extras. */
    fun allCoreGranted(context: Context): Boolean =
        hasUsageAccess(context) &&
            isAccessibilityServiceEnabled(context) &&
            canDrawOverlays(context) &&
            isDeviceAdminActive(context)

    /**
     * Intent for the system VPN consent dialog, or null if this app is already the prepared
     * VPN. Consent is per-app and is lost whenever the user switches to another VPN app.
     */
    fun vpnConsentIntent(context: Context): Intent? = VpnService.prepare(context)

    fun isVpnConsentGranted(context: Context): Boolean = vpnConsentIntent(context) == null

    /**
     * Strict Private DNS ("hostname" mode) sends DNS over TLS to a fixed host and ignores the
     * local DNS filter, so website blocking falls back to the browser URL-bar check only.
     */
    fun isStrictPrivateDns(context: Context): Boolean =
        runCatching {
            Settings.Global.getString(context.contentResolver, "private_dns_mode") == "hostname"
        }.getOrDefault(false)

    fun notificationsGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun usageAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    fun accessibilitySettingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    fun overlayIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

    fun deviceAdminIntent(context: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(
                DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                BlockerDeviceAdminReceiver.component(context)
            )
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Needed to stop this app from being uninstalled while a lock is active."
            )
        }

}
