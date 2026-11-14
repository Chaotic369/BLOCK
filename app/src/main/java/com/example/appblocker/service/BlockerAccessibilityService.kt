package com.example.appblocker.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.webkit.WebView
import com.example.appblocker.R
import com.example.appblocker.data.model.BlockedApp
import com.example.appblocker.data.model.BlockedDomain
import com.example.appblocker.di.AppModule
import com.example.appblocker.ui.screens.BlockOverlayActivity
import com.example.appblocker.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BlockerAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile private var apps: List<BlockedApp> = emptyList()
    @Volatile private var domains: List<BlockedDomain> = emptyList()
    private var browsers: Set<String> = emptySet()
    private var lastBlockAt = 0L
    private var lastDomainCheckAt = 0L
    private var lastGuardAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var limitApp: BlockedApp? = null
    private val limitCheck = Runnable { checkLimit() }

    // Time tracking for websites that have a daily limit.
    private var trackDomain: String? = null
    private var trackPkg: String? = null
    private var trackLastAt = 0L
    private var trackBase = 0L
    private var trackCount = 0L
    private var trackFlushed = 0L
    private var trackFlushAt = 0L
    private val trackTick = Runnable { trackStep() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        // Load the WebView engine now so the block screen opens without a delay later.
        Handler(Looper.getMainLooper()).post { runCatching { WebView(applicationContext).destroy() } }
        val repository = AppModule.blockRepository
        scope.launch { repository.apps.collect { apps = it } }
        scope.launch { repository.domains.collect { domains = it } }
        browsers = BrowserUrlExtractor.installedBrowsers(this)
        scope.launch {
            if (repository.hasActiveBlocks(System.currentTimeMillis())) {
                runCatching { BlockerForegroundService.start(this@BlockerAccessibilityService) }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        val now = System.currentTimeMillis()

        val activeApps = apps.filter { it.blockedUntil > now }
        val activeDomains = domains.filter { it.blockedUntil > now }
        if (activeApps.isEmpty() && activeDomains.isEmpty()) return

        val stateChanged = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

        if (stateChanged) {
            val hit = activeApps.firstOrNull { it.packageName == pkg }
            if (hit != null) {
                if (hit.allowanceMs <= 0L) {
                    block(hit.label, hit.blockedUntil, isDomain = false)
                    return
                }
                // Daily limit: let it open while time is left, and cover it when time runs out.
                val left = AppModule.usageRepository.remainingMs(hit)
                if (left <= 0L) {
                    block(hit.label, hit.blockedUntil, isDomain = false)
                } else {
                    scheduleLimitCheck(hit, left)
                }
                return
            }
        }

        if (pkg in GUARDED_PACKAGES) {
            // Content events matter here too: the app's name can scroll into view, or show up
            // in a search result, without a new window ever opening.
            if (stateChanged || now - lastGuardAt >= GUARD_INTERVAL_MS) {
                lastGuardAt = now
                guardProtectedScreens()
            }
            return
        }

        if (activeDomains.isNotEmpty() &&
            (BrowserUrlExtractor.isKnownBrowser(pkg) || pkg in browsers)
        ) {
            if (now - lastDomainCheckAt < DOMAIN_CHECK_INTERVAL_MS) return
            lastDomainCheckAt = now
            val root = rootInActiveWindow ?: return
            val url = BrowserUrlExtractor.extract(root, pkg) ?: return
            val host = DomainMatcher.hostFromUrl(url) ?: return
            val hit = activeDomains.firstOrNull { DomainMatcher.matches(host, it.domain) }
            if (hit == null) {
                stopTracking()
            } else if (hit.allowanceMs <= 0L) {
                block(hit.domain, hit.blockedUntil, isDomain = true)
            } else {
                val used = if (trackDomain == hit.domain) trackBase + trackCount else hit.usedToday(now)
                if (used >= hit.allowanceMs) {
                    stopTracking()
                    block(hit.domain, hit.blockedUntil, isDomain = true)
                } else {
                    startTracking(hit, pkg, now)
                }
            }
        } else if (trackDomain != null && pkg != trackPkg) {
            stopTracking()
        }
    }

    private fun startTracking(site: BlockedDomain, pkg: String, now: Long) {
        if (trackDomain == site.domain && trackPkg == pkg) return
        stopTracking()
        trackDomain = site.domain
        trackPkg = pkg
        trackBase = site.usedToday(now)
        trackCount = 0L
        trackFlushed = 0L
        trackLastAt = now
        trackFlushAt = now
        handler.postDelayed(trackTick, TRACK_TICK_MS)
    }

    /** Runs every couple of seconds while a limited site is in front; counts the time and enforces the limit. */
    private fun trackStep() {
        val domain = trackDomain ?: return
        val pkg = trackPkg ?: return
        val now = System.currentTimeMillis()
        val root = rootInActiveWindow
        val host = if (root != null && root.packageName?.toString() == pkg) {
            BrowserUrlExtractor.extract(root, pkg)?.let { DomainMatcher.hostFromUrl(it) }
        } else null
        if (host == null || !DomainMatcher.matches(host, domain)) {
            stopTracking()
            return
        }
        trackCount += (now - trackLastAt).coerceIn(0L, TRACK_MAX_GAP_MS)
        trackLastAt = now
        val site = domains.firstOrNull { it.domain == domain && it.blockedUntil > now }
        if (site == null || site.allowanceMs <= 0L) {
            stopTracking()
            return
        }
        if (trackBase + trackCount >= site.allowanceMs) {
            stopTracking()
            block(site.domain, site.blockedUntil, isDomain = true)
            return
        }
        if (now - trackFlushAt >= TRACK_FLUSH_MS) flushUsage(domain, now)
        handler.postDelayed(trackTick, TRACK_TICK_MS)
    }

    private fun flushUsage(domain: String, now: Long) {
        val delta = trackCount - trackFlushed
        trackFlushAt = now
        if (delta <= 0L) return
        trackFlushed = trackCount
        scope.launch(Dispatchers.IO) {
            runCatching { AppModule.blockRepository.addDomainUsage(domain, delta, now) }
        }
    }

    private fun stopTracking() {
        handler.removeCallbacks(trackTick)
        val domain = trackDomain ?: return
        flushUsage(domain, System.currentTimeMillis())
        trackDomain = null
        trackPkg = null
    }

    private fun scheduleLimitCheck(app: BlockedApp, leftMs: Long) {
        limitApp = app
        handler.removeCallbacks(limitCheck)
        handler.postDelayed(limitCheck, leftMs.coerceAtLeast(500L))
    }

    /** Fires when a limited app should be out of time; covers it only if it is still in front. */
    private fun checkLimit() {
        val app = limitApp ?: return
        val now = System.currentTimeMillis()
        val current = apps.firstOrNull { it.packageName == app.packageName && it.blockedUntil > now }
        if (current == null) { limitApp = null; return }
        val usage = AppModule.usageRepository
        val foreground = usage.foregroundPackage((now - current.sinceMs).coerceAtLeast(60_000L))
        if (foreground != current.packageName) { limitApp = null; return }
        val left = usage.remainingMs(current)
        if (left <= 0L) {
            limitApp = null
            block(current.label, current.blockedUntil, isDomain = false)
        } else {
            scheduleLimitCheck(current, left)
        }
    }

    /** Sends the user home if Settings or an installer is showing this app (admin, uninstall, accessibility). */
    private fun guardProtectedScreens() {
        val root = rootInActiveWindow ?: return
        val found = root.findAccessibilityNodeInfosByText(getString(R.string.app_name))
        if (!found.isNullOrEmpty()) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun block(label: String, until: Long, isDomain: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastBlockAt < BLOCK_THROTTLE_MS) return
        lastBlockAt = now
        startActivity(BlockOverlayActivity.intent(this, label, until, isDomain))
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        stopTracking()
        connected = false
        // The service was switched off. If that happens mid-lock, say so immediately; the
        // foreground service takes over app blocking via usage stats in the meantime.
        if (AppModule.settingsRepository.isLockActive) {
            NotificationHelper.notifyAlert(
                this,
                NotificationHelper.ALERT_ACCESSIBILITY_OFF,
                "Accessibility was turned off",
                "Blocking is running in a weaker mode. Tap to turn the service back on.",
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            )
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopTracking()
        handler.removeCallbacks(limitCheck)
        connected = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** True while the system has this service bound. */
        @Volatile
        var connected: Boolean = false
            private set

        private const val BLOCK_THROTTLE_MS = 800L
        private const val DOMAIN_CHECK_INTERVAL_MS = 400L
        private const val GUARD_INTERVAL_MS = 500L
        private const val TRACK_TICK_MS = 2_000L
        private const val TRACK_MAX_GAP_MS = 5_000L
        private const val TRACK_FLUSH_MS = 10_000L

        private val GUARDED_PACKAGES = setOf(
            "com.android.settings",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.miui.securitycenter"
        )
    }

}
