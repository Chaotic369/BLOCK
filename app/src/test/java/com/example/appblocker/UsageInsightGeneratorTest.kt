package com.example.appblocker

import com.example.appblocker.data.model.AppUsage
import com.example.appblocker.usage.UsageInsightGenerator
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageInsightGeneratorTest {

    @Test
    fun generate_withoutData_returnsEmptyMessage() {
        val text = UsageInsightGenerator.generate(emptyList(), 0L, 0L)
        assertTrue(text.startsWith("No usage recorded"))
    }

    @Test
    fun generate_namesMostUsedApp() {
        val top = listOf(
            AppUsage("com.a", "Alpha", 3_600_000L),
            AppUsage("com.b", "Beta", 1_800_000L)
        )
        val text = UsageInsightGenerator.generate(top, 3_600_000L, 3_600_000L)
        assertTrue(text.contains("Alpha"))
        assertTrue(text.contains("close to your average"))
    }

    @Test
    fun generate_reportsAboveAverage() {
        val top = listOf(AppUsage("com.a", "Alpha", 3_600_000L))
        val text = UsageInsightGenerator.generate(top, 3_600_000L, 7_200_000L)
        assertTrue(text.contains("above your average"))
    }

}
