package com.example.appblocker.usage

import android.content.Context
import com.example.appblocker.data.model.UsageSnapshot
import com.example.appblocker.di.AppModule
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the JSON the Screen time page renders:
 * - daily: 7 (label, ms) pairs, oldest first, ending today
 * - weeks: totals of the 4 full 7-day windows before those 7 days, oldest first
 * - top: most used apps over the 7 days
 * - det: per-day top apps, keyed by index into daily
 *
 * Android only keeps a few days of raw usage events, so older weeks come from the daily
 * snapshots the app stores (see UsageSyncWorker). Days are stored here too whenever the page opens.
 */
object UsageReport {

    const val NO_PERMISSION = """{"hasPermission":false}"""
    const val LOADING = """{"loading":true}"""
    const val EMPTY =
        """{"hasPermission":true,"daily":[["-",0],["-",0],["-",0],["-",0],["-",0],["-",0],["-",0]],"weeks":[0,0,0,0],"top":[],"det":{}}"""

    private const val DETAIL_APPS = 6
    private const val TOP_APPS = 10

    suspend fun build(context: Context): String {
        val usage = AppModule.usageRepository
        if (!usage.hasPermission()) return NO_PERMISSION

        val pm = context.packageManager
        val labels = HashMap<String, String>()
        fun labelOf(pkg: String): String = labels.getOrPut(pkg) {
            runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
        }

        val days = usage.dailyBreakdown(7)
        val dao = AppModule.database.usageSnapshotDao()

        days.forEach { day ->
            val total = day.perApp.values.sum()
            if (total > 0) {
                val top = day.perApp.maxByOrNull { it.value }
                dao.upsert(UsageSnapshot(day.dayStart, total, top?.key, top?.value ?: 0L))
            }
        }

        val history = dao.since(usage.dayStart(34)).associate { it.dayStart to it.totalMs }
        val weeks = JSONArray()
        for (k in 4 downTo 1) {
            val from = usage.dayStart(7 * k + 6)
            val until = usage.dayStart(7 * k - 1)
            weeks.put(history.filterKeys { it >= from && it < until }.values.sum())
        }

        val daily = JSONArray()
        days.forEach { daily.put(JSONArray().put(it.label).put(it.perApp.values.sum())) }

        val combined = HashMap<String, Long>()
        days.forEach { day -> day.perApp.forEach { (pkg, ms) -> combined.merge(pkg, ms, Long::plus) } }
        val top = JSONArray()
        combined.entries.sortedByDescending { it.value }.take(TOP_APPS).forEach {
            top.put(JSONObject().put("label", labelOf(it.key)).put("ms", it.value))
        }

        val det = JSONObject()
        days.forEachIndexed { index, day ->
            if (day.perApp.isNotEmpty()) {
                val apps = JSONArray()
                day.perApp.entries.sortedByDescending { it.value }.take(DETAIL_APPS).forEach {
                    apps.put(JSONObject().put("label", labelOf(it.key)).put("ms", it.value))
                }
                det.put(index.toString(), apps)
            }
        }

        return JSONObject()
            .put("hasPermission", true)
            .put("daily", daily)
            .put("weeks", weeks)
            .put("top", top)
            .put("det", det)
            .toString()
    }
}
