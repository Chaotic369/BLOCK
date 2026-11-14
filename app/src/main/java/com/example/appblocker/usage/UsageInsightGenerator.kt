package com.example.appblocker.usage

import com.example.appblocker.data.model.AppUsage
import com.example.appblocker.util.TimeUtils

object UsageInsightGenerator {

    fun generate(topApps: List<AppUsage>, averageDailyMs: Long, todayMs: Long): String {
        val top = topApps.firstOrNull()
            ?: return "No usage recorded yet. Use your phone for a while and check back."

        val builder = StringBuilder()
        builder.append("Your most used app is ${top.label} with ${TimeUtils.formatDuration(top.totalMs)} this week. ")
        builder.append("Your daily average is ${TimeUtils.formatDuration(averageDailyMs)}. ")

        if (averageDailyMs > 0) {
            val diff = todayMs - averageDailyMs
            when {
                diff > averageDailyMs / 5 ->
                    builder.append("Today you are ${TimeUtils.formatDuration(diff)} above your average.")
                diff < -averageDailyMs / 5 ->
                    builder.append("Today you are ${TimeUtils.formatDuration(-diff)} below your average.")
                else -> builder.append("Today is close to your average.")
            }
        }
        return builder.toString().trim()
    }

}
