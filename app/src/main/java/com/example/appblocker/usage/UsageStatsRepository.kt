package com.example.appblocker.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import com.example.appblocker.data.model.AppUsage
import java.util.Calendar

class UsageStatsRepository(private val context: Context) {

    private val manager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    fun hasPermission(): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Foreground time per app between [start] and [end], built from usage events. */
    fun usageBetween(start: Long, end: Long): Map<String, Long> {
        val events = manager.queryEvents(start, end)
        val event = UsageEvents.Event()
        val resumedAt = HashMap<String, Long>()
        val totals = HashMap<String, Long>()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> resumedAt[pkg] = event.timeStamp
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    val began = resumedAt.remove(pkg) ?: continue
                    totals.merge(pkg, (event.timeStamp - began).coerceAtLeast(0L), Long::plus)
                }
            }
        }
        // Apps still in the foreground at the end of the window.
        resumedAt.forEach { (pkg, began) ->
            totals.merge(pkg, (end - began).coerceAtLeast(0L), Long::plus)
        }
        return totals
    }

    /**
     * Time left in a daily limit: allowance minus foreground time since the limit was set.
     * Plain locks (no allowance) return 0. Without usage access it cannot be measured, so the
     * full allowance is reported rather than locking the app early.
     */
    fun remainingMs(app: com.example.appblocker.data.model.BlockedApp, maxAgeMs: Long = 0L): Long {
        if (app.allowanceMs <= 0L) return 0L
        val now = System.currentTimeMillis()
        if (maxAgeMs > 0L) {
            synchronized(remainingCache) {
                remainingCache[app.packageName]?.let { (at, value) ->
                    if (now - at <= maxAgeMs) return value
                }
            }
        }
        if (!hasPermission()) return app.allowanceMs
        val used = runCatching { usageBetween(app.sinceMs, now)[app.packageName] ?: 0L }.getOrDefault(0L)
        val left = (app.allowanceMs - used).coerceAtLeast(0L)
        synchronized(remainingCache) { remainingCache[app.packageName] = now to left }
        return left
    }

    private val remainingCache = HashMap<String, Pair<Long, Long>>()

    /**
     * Package currently in the foreground, from the latest usage events. Used to keep blocking
     * apps when the accessibility service has been switched off. Needs usage access.
     */
    fun foregroundPackage(windowMs: Long = 10 * 60_000L): String? {
        val end = System.currentTimeMillis()
        val events = manager.queryEvents(end - windowMs, end)
        val event = UsageEvents.Event()
        var current: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> current = event.packageName
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED ->
                    if (event.packageName == current) current = null
            }
        }
        return current
    }

    /** (day label, total ms) for the last [days] days, oldest first, ending today. */
    fun dailyTotals(days: Int): List<Pair<String, Long>> {
        val labels = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val now = System.currentTimeMillis()
        return (days - 1 downTo 0).map { offset ->
            val start = dayStart(offset)
            val end = if (offset == 0) now else dayStart(offset - 1)
            val cal = Calendar.getInstance().apply { timeInMillis = start }
            val label = labels[cal.get(Calendar.DAY_OF_WEEK) - 1]
            label to usageBetween(start, end).filterKeys { it != context.packageName }.values.sum()
        }
    }

    /** One day of foreground time per app, excluding this app. */
    data class DayUsage(val dayStart: Long, val label: String, val perApp: Map<String, Long>)

    /** Per-app usage for each of the last [days] days, oldest first, ending today. */
    fun dailyBreakdown(days: Int): List<DayUsage> {
        val labels = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val now = System.currentTimeMillis()
        return (days - 1 downTo 0).map { offset ->
            val start = dayStart(offset)
            val end = if (offset == 0) now else dayStart(offset - 1)
            val cal = Calendar.getInstance().apply { timeInMillis = start }
            val perApp = usageBetween(start, end)
                .filterKeys { it != context.packageName }
                .filterValues { it > 0 }
            DayUsage(start, labels[cal.get(Calendar.DAY_OF_WEEK) - 1], perApp)
        }
    }

    /** Most used apps over the last [days] days. */
    fun topApps(days: Int, limit: Int): List<AppUsage> {
        val end = System.currentTimeMillis()
        val start = dayStart(days - 1)
        val pm = context.packageManager
        return usageBetween(start, end)
            .filterKeys { it != context.packageName }
            .filterValues { it > 0 }
            .map { (pkg, ms) ->
                val label = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg)
                AppUsage(pkg, label, ms)
            }
            .sortedByDescending { it.totalMs }
            .take(limit)
    }

    /** Local midnight [daysAgo] days back (0 = today). */
    fun dayStart(daysAgo: Int): Long =
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -daysAgo)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

}
