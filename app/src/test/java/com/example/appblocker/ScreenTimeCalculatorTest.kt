package com.example.appblocker

import com.example.appblocker.usage.ScreenTimeCalculator
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenTimeCalculatorTest {

    @Test
    fun average_ignoresDaysWithoutUsage() {
        assertEquals(2_000L, ScreenTimeCalculator.average(listOf(1_000L, 0L, 3_000L)))
    }

    @Test
    fun average_emptyReturnsZero() {
        assertEquals(0L, ScreenTimeCalculator.average(emptyList()))
        assertEquals(0L, ScreenTimeCalculator.average(listOf(0L, 0L)))
    }

    @Test
    fun total_sumsAllDays() {
        assertEquals(6_000L, ScreenTimeCalculator.total(listOf(1_000L, 2_000L, 3_000L)))
    }
}
