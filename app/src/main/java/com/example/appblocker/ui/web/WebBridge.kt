package com.example.appblocker.ui.web

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.webkit.JavascriptInterface
import androidx.activity.ComponentActivity
import com.example.appblocker.admin.UninstallProtectionManager
import com.example.appblocker.di.AppModule
import com.example.appblocker.scheduler.BlockScheduler
import com.example.appblocker.service.BlockDnsVpnService
import com.example.appblocker.service.BlockerForegroundService
import com.example.appblocker.service.DomainMatcher
import com.example.appblocker.usage.UsageReport
import com.example.appblocker.util.PermissionUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** A launchable app on the device. */
internal data class InstalledApp(val pkg: String, val label: String)

/** What the block screen shows. */
data class OverlayInfo(val label: String, val until: Long, val isDomain: Boolean)

/**
 * Everything the HTML UI can ask of the device, exposed to the page as `window.AndroidBridge`.
 * Methods run on a WebView background thread and return JSON strings (or primitives), so the
 * database reads below may block safely. Changes are pushed back to the page through [emit].
 *
 * @param emit sends an event to window.onNativeEvent(kind, payload) in the page.
 * @param requestPermission opens the system screen or prompt for a permission id (main thread).
 * @param overlay set only for the block screen.
 */
class WebBridge(
    private val activity: ComponentActivity,
    private val emit: (kind: String, payload: String?) -> Unit,
    private val requestPermission: (id: String) -> Unit = {},
    private val onExport: (name: String, text: String) -> Unit = { _, _ -> },
    private val overlay: OverlayInfo? = null
) {

    private val context: Context = activity.applicationContext
    private val repository get() = AppModule.blockRepository
    private val settings get() = AppModule.settingsRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var appsSnap: List<com.example.appblocker.data.model.BlockedApp>? = null
    @Volatile private var domainsSnap: List<com.example.appblocker.data.model.BlockedDomain>? = null

    init {
        // Keep the latest lists in memory so the page never waits on a database read.
        scope.launch { repository.apps.collect { appsSnap = it } }
        scope.launch { repository.domains.collect { domainsSnap = it } }
    }

    private fun currentApps() = appsSnap ?: runBlocking { repository.apps.first() }
    private fun currentDomains() = domainsSnap ?: runBlocking { repository.domains.first() }
    private fun invalidateLists() { appsSnap = null; domainsSnap = null }

    /** Loads the app list and icons off the main thread, ahead of the first visit to the page. */
    fun warm() {
        scope.launch {
            val apps = installedApps()
            listAppsJson()
            WebHost.warmIcons(context, apps.map { it.pkg })
        }
    }

    fun destroy() {
        scope.cancel()
    }

    // ---- apps ----------------------------------------------------------------------------

    @JavascriptInterface
    fun listApps(): String = listAppsJson()

    private fun listAppsJson(): String {
        val apps = installedApps()
        synchronized(installedLock) {
            if (installedJson != null && installedJsonFor === apps) return installedJson!!
            val out = JSONArray()
            apps.forEach { out.put(JSONObject().put("pkg", it.pkg).put("label", it.label)) }
            installedJson = out.toString()
            installedJsonFor = apps
            return installedJson!!
        }
    }

    @JavascriptInterface
    fun lockedApps(): String {
        val now = System.currentTimeMillis()
        val apps = currentApps()
        val out = JSONArray()
        apps.filter { it.blockedUntil > now }
            .sortedByDescending { it.blockedUntil }
            .forEach {
                out.put(
                    JSONObject()
                        .put("pkg", it.packageName)
                        .put("label", it.label)
                        .put("until", it.blockedUntil)
                        .put("allow", it.allowanceMs)
                        .put("left", AppModule.usageRepository.remainingMs(it, maxAgeMs = 2_000L))
                )
            }
        return out.toString()
    }

    /** Daily limit: [ms] of use today, then the app stays locked until local midnight. */
    @JavascriptInterface
    fun startAllowance(pkg: String, ms: String) {
        val allowance = ms.toLongOrNull() ?: return
        if (allowance <= 0 || pkg.isBlank()) return
        val now = System.currentTimeMillis()
        val midnight = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            add(java.util.Calendar.DAY_OF_YEAR, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val label = labelFor(pkg)
        runBlocking { repository.blockApp(pkg, label, midnight, allowance, now) }
        invalidateLists()
        scheduleNextExpiry()
        BlockerForegroundService.start(context)
    }

    @JavascriptInterface
    fun startBlock(pkg: String, ms: String) {
        val duration = ms.toLongOrNull() ?: return
        if (duration <= 0 || pkg.isBlank()) return
        val until = System.currentTimeMillis() + duration
        val label = labelFor(pkg)
        runBlocking { repository.blockApp(pkg, label, until) }
        invalidateLists()
        scheduleNextExpiry()
        BlockerForegroundService.start(context)
    }

    // ---- websites ------------------------------------------------------------------------

    @JavascriptInterface
    fun domains(): String {
        val out = JSONArray()
        val now = System.currentTimeMillis()
        currentDomains().forEach {
            val row = JSONObject().put("domain", it.domain).put("until", it.blockedUntil)
            if (it.allowanceMs > 0L) {
                row.put("allow", it.allowanceMs).put("used", it.usedToday(now))
            }
            out.put(row)
        }
        return out.toString()
    }

    /** Daily limit for a website: [ms] of use per day, then blocked until local midnight. */
    @JavascriptInterface
    fun addDomainAllowance(input: String, ms: String) {
        val domain = DomainMatcher.normalize(input)
        val allowance = ms.toLongOrNull() ?: return
        if (domain.isBlank() || allowance <= 0) return
        val now = System.currentTimeMillis()
        val midnight = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            add(java.util.Calendar.DAY_OF_YEAR, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        runBlocking { repository.blockDomainAllowance(domain, midnight, allowance) }
        invalidateLists()
        scheduleNextExpiry()
        BlockerForegroundService.start(context)
        BlockDnsVpnService.start(context)
    }

    @JavascriptInterface
    fun addDomain(input: String, ms: String) {
        val domain = DomainMatcher.normalize(input)
        val duration = ms.toLongOrNull() ?: return
        if (domain.isBlank() || duration <= 0) return
        val until = System.currentTimeMillis() + duration
        runBlocking { repository.blockDomain(domain, until) }
        invalidateLists()
        scheduleNextExpiry()
        BlockerForegroundService.start(context)
        BlockDnsVpnService.start(context)
    }

    @JavascriptInterface
    fun removeDomain(domain: String) {
        runBlocking { repository.removeDomain(domain) }
        invalidateLists()
    }

    @JavascriptInterface
    fun strictDns(): Boolean = PermissionUtils.isStrictPrivateDns(context)

    // ---- permissions and uninstall protection --------------------------------------------

    @JavascriptInterface
    fun perms(): String {
        val nowMs = SystemClock.elapsedRealtime()
        permsJson?.let { if (nowMs - permsAt < PERMS_TTL_MS) return it }
        val out = JSONArray()
        fun add(id: String, granted: Boolean) {
            out.put(JSONObject().put("id", id).put("granted", granted))
        }
        add("usage", PermissionUtils.hasUsageAccess(context))
        add("a11y", PermissionUtils.isAccessibilityServiceEnabled(context))
        add("overlay", PermissionUtils.canDrawOverlays(context))
        add("admin", PermissionUtils.isDeviceAdminActive(context))
        add("vpn", PermissionUtils.isVpnConsentGranted(context))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add("notif", PermissionUtils.notificationsGranted(context))
        }
        return out.toString().also { permsJson = it; permsAt = nowMs }
    }

    @JavascriptInterface
    fun requestPerm(id: String) {
        permsJson = null
        activity.runOnUiThread { requestPermission(id) }
    }

    @JavascriptInterface
    fun anyLockActive(): Boolean {
        val now = System.currentTimeMillis()
        return currentApps().any { it.blockedUntil > now } || currentDomains().any { it.blockedUntil > now }
    }

    @JavascriptInterface
    fun isOwner(): Boolean = UninstallProtectionManager.isOwner(context)

    @JavascriptInterface
    fun releaseOwner(): Boolean = UninstallProtectionManager.releaseOwnership(context)

    // ---- screen time ---------------------------------------------------------------------

    @JavascriptInterface
    fun usage(): String {
        if (!AppModule.usageRepository.hasPermission()) return UsageReport.NO_PERMISSION
        return cachedUsage ?: UsageReport.LOADING
    }

    @JavascriptInterface
    fun refreshUsage() {
        if (!AppModule.usageRepository.hasPermission()) {
            emit("usage", null)
            return
        }
        scope.launch {
            cachedUsage = runCatching { UsageReport.build(context) }.getOrDefault(UsageReport.EMPTY)
            emit("usage", null)
        }
    }

    // ---- settings ------------------------------------------------------------------------

    @JavascriptInterface
    fun getPrefs(): String {
        val out = runCatching { JSONObject(settings.extraPrefs) }.getOrDefault(JSONObject())
        return out
            .put("lsOn", if (settings.lockScreenEnabled) 1 else 0)
            .put("lsImg", settings.lockScreenImage)
            .toString()
    }

    @JavascriptInterface
    fun setPrefs(json: String) {
        runCatching {
            val prefs = JSONObject(json)
            settings.lockScreenEnabled = prefs.optInt("lsOn", 0) == 1
            settings.lockScreenImage = prefs.optString("lsImg", "")
            prefs.remove("lsOn")
            prefs.remove("lsImg")
            settings.extraPrefs = prefs.toString()
        }
    }

    /** Saves a config file the user picks a location for (Storage Access Framework). */
    @JavascriptInterface
    fun exportFile(name: String, text: String) {
        activity.runOnUiThread { onExport(name, text) }
    }

    @JavascriptInterface
    fun systemDark(): Boolean = WebHost.isNight(activity)

    // ---- block screen --------------------------------------------------------------------

    @JavascriptInterface
    fun overlayInfo(): String = overlay?.let {
        JSONObject().put("label", it.label).put("until", it.until).put("isDomain", it.isDomain).toString()
    } ?: "{}"

    @JavascriptInterface
    fun goHome() {
        activity.runOnUiThread {
            val home = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(home)
            activity.finish()
        }
    }

    @JavascriptInterface
    fun closeOverlay() {
        activity.runOnUiThread { activity.finish() }
    }

    // ---- helpers -------------------------------------------------------------------------

    private fun scheduleNextExpiry() {
        runBlocking { repository.nextExpiry(System.currentTimeMillis()) }
            ?.let { BlockScheduler.scheduleExpiry(context, it) }
    }

    private fun labelFor(pkg: String): String =
        installedApps().firstOrNull { it.pkg == pkg }?.label
            ?: runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)

    

    private fun installedApps(): List<InstalledApp> {
        val now = SystemClock.elapsedRealtime()
        synchronized(installedLock) {
            if (installed.isEmpty() || now - installedAt > INSTALLED_TTL_MS) {
                installed = loadInstalledApps()
                installedAt = now
            }
            return installed
        }
    }

    private fun loadInstalledApps(): List<InstalledApp> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != context.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    private companion object {
        const val INSTALLED_TTL_MS = 5 * 60_000L
        const val PERMS_TTL_MS = 1_000L
        @Volatile var permsJson: String? = null
        @Volatile var permsAt = 0L
        @Volatile var installedJson: String? = null
        @Volatile var installedJsonFor: List<InstalledApp>? = null
        val installedLock = Any()
        @Volatile var installed: List<InstalledApp> = emptyList()
        @Volatile var installedAt = 0L

        /** Last report built; shown right away on the next visit while a fresh one loads. */
        @Volatile var cachedUsage: String? = null
    }
}
