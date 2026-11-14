package com.example.appblocker.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.appblocker.admin.UninstallProtectionManager
import com.example.appblocker.data.model.BlockedApp
import com.example.appblocker.data.repository.BlockRepository
import com.example.appblocker.di.AppModule
import com.example.appblocker.receiver.PackageRemovedReceiver
import com.example.appblocker.ui.screens.BlockOverlayActivity
import com.example.appblocker.util.NotificationHelper
import com.example.appblocker.util.PermissionUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps locks enforced while they are active:
 * - expires finished locks and drops entries for uninstalled apps,
 * - restarts the DNS filter if it dies and asks for VPN approval if it is missing,
 * - warns when accessibility or device admin gets switched off mid-lock,
 * - blocks locked apps through usage stats whenever the accessibility service is not running.
 */
class BlockerForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchJob: Job? = null
    private var fallbackJob: Job? = null
    private var cacheJob: Job? = null
    private var removedReceiver: PackageRemovedReceiver? = null
    private val lastAlertAt = HashMap<Int, Long>()
    private var lastFallbackBlockAt = 0L

    @Volatile private var lockedApps: List<BlockedApp> = emptyList()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NotificationHelper.BLOCKER_NOTIFICATION_ID,
            NotificationHelper.blockerNotification(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        registerRemovedReceiver()
        val repository = AppModule.blockRepository
        if (cacheJob?.isActive != true) {
            cacheJob = scope.launch { repository.apps.collect { lockedApps = it } }
        }
        if (watchJob?.isActive != true) {
            watchJob = scope.launch { watchLoop(repository) }
        }
        if (fallbackJob?.isActive != true) {
            fallbackJob = scope.launch { fallbackLoop() }
        }
        return START_STICKY
    }

    private suspend fun watchLoop(repository: BlockRepository) {
        while (true) {
            val now = System.currentTimeMillis()
            repository.pruneUninstalled()
            repository.purgeExpired(now)
            if (!repository.hasActiveBlocks(now)) {
                BlockDnsVpnService.stop(this)
                stopSelf()
                break
            }
            superviseVpn(repository, now)
            superviseIntegrity()
            delay(CHECK_INTERVAL_MS)
        }
    }

    private suspend fun superviseVpn(repository: BlockRepository, now: Long) {
        val needed = repository.hasActiveDomains(now)
        when {
            needed && !BlockDnsVpnService.isRunning -> {
                if (PermissionUtils.isVpnConsentGranted(this)) {
                    BlockDnsVpnService.start(this)
                } else {
                    alert(
                        NotificationHelper.ALERT_VPN_CONSENT,
                        "Approve website blocking",
                        "Open the app and approve the VPN prompt so blocked websites are filtered in every browser."
                    )
                }
            }
            !needed && BlockDnsVpnService.isRunning -> BlockDnsVpnService.stop(this)
        }
    }

    private fun superviseIntegrity() {
        if (!BlockerAccessibilityService.connected) {
            alert(
                NotificationHelper.ALERT_ACCESSIBILITY_OFF,
                "Accessibility is off",
                "Blocking is running in a weaker mode. Tap to turn the service back on.",
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            )
        }
        if (!UninstallProtectionManager.isAdminActive(this) &&
            !UninstallProtectionManager.isOwner(this)
        ) {
            alert(
                NotificationHelper.ALERT_ADMIN_OFF,
                "Uninstall protection is off",
                "Device administrator is not active, so the app can be removed. Tap to turn it back on."
            )
        }
    }

    /**
     * Backup enforcement for when the accessibility service has been disabled: watch the
     * foreground app through usage stats and cover it with the block screen. Slower than
     * accessibility events, but it keeps app locks working.
     */
    private suspend fun fallbackLoop() {
        val usage = AppModule.usageRepository
        while (true) {
            delay(FALLBACK_INTERVAL_MS)
            if (BlockerAccessibilityService.connected) continue
            val now = System.currentTimeMillis()
            val locked = lockedApps.filter { it.blockedUntil > now }
            if (locked.isEmpty() || !usage.hasPermission() || !PermissionUtils.canDrawOverlays(this)) {
                continue
            }
            val foreground = usage.foregroundPackage() ?: continue
            val hit = locked.firstOrNull {
                it.packageName == foreground && (it.allowanceMs <= 0L || usage.remainingMs(it) <= 0L)
            } ?: continue
            if (now - lastFallbackBlockAt < FALLBACK_THROTTLE_MS) continue
            lastFallbackBlockAt = now
            runCatching {
                startActivity(BlockOverlayActivity.intent(this, hit.label, hit.blockedUntil))
            }
        }
    }

    private fun alert(id: Int, title: String, text: String, target: Intent? = null) {
        val now = SystemClock.elapsedRealtime()
        val last = lastAlertAt[id]
        if (last != null && now - last < ALERT_INTERVAL_MS) return
        lastAlertAt[id] = now
        NotificationHelper.notifyAlert(this, id, title, text, target)
    }

    /** Registered at runtime because manifest receivers for package broadcasts are not reliable on newer Android. */
    private fun registerRemovedReceiver() {
        if (removedReceiver != null) return
        val receiver = PackageRemovedReceiver()
        val filter = IntentFilter(Intent.ACTION_PACKAGE_FULLY_REMOVED).apply {
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        removedReceiver = receiver
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removedReceiver?.let { runCatching { unregisterReceiver(it) } }
        removedReceiver = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHECK_INTERVAL_MS = 15_000L
        private const val FALLBACK_INTERVAL_MS = 1_000L
        private const val FALLBACK_THROTTLE_MS = 1_500L
        private const val ALERT_INTERVAL_MS = 5 * 60_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BlockerForegroundService::class.java)
            )
        }
    }

}
