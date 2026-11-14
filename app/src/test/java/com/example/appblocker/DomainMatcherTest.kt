package com.example.appblocker

import com.example.appblocker.service.DomainMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainMatcherTest {

    @Test
    fun normalize_stripsSchemeWwwAndPath() {
        assertEquals("example.com", DomainMatcher.normalize("https://www.Example.com/path?q=1"))
    }

    @Test
    fun normalize_stripsPort() {
        assertEquals("example.com", DomainMatcher.normalize("example.com:8080/home"))
    }

    @Test
    fun matches_exactAndSubdomain() {
        assertTrue(DomainMatcher.matches("example.com", "example.com"))
        assertTrue(DomainMatcher.matches("m.example.com", "example.com"))
    }

    @Test
    fun matches_rejectsLookalikeDomains() {
        assertFalse(DomainMatcher.matches("notexample.com", "example.com"))
        assertFalse(DomainMatcher.matches("example.com.evil.org", "example.com"))
    }

    @Test
    fun hostFromUrl_rejectsSearchText() {
        assertNull(DomainMatcher.hostFromUrl("best pizza near me"))
    }

    @Test
    fun hostFromUrl_extractsHostFromFullUrl() {
        assertEquals("example.com", DomainMatcher.hostFromUrl("https://www.example.com/a?b=1"))
    }

    @Test
    fun hostFromUrl_keepsBareWordsWithoutDots() {
        // Callers that need a real domain (such as the generic address-bar scan) check for a dot.
        assertEquals("pizza", DomainMatcher.hostFromUrl("pizza"))
    }

}
