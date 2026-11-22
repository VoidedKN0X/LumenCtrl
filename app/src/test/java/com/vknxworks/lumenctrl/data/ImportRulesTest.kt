package com.vknxworks.lumenctrl.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportRulesTest {
    private val order = listOf("ALL", "NO_LOCATION", "NONE")

    @Test
    fun switchThatIsOnStaysOn() {
        assertTrue(ImportRules.keepOn(current = true, incoming = false))
        assertTrue(ImportRules.keepOn(current = true, incoming = true))
    }

    @Test
    fun fileCanTurnSwitchOn() {
        assertTrue(ImportRules.keepOn(current = false, incoming = true))
        assertFalse(ImportRules.keepOn(current = false, incoming = false))
    }

    @Test
    fun metadataOnlyMovesTowardsPrivate() {
        assertEquals("NO_LOCATION", ImportRules.stricterOf("NO_LOCATION", "ALL", order))
        assertEquals("NONE", ImportRules.stricterOf("NONE", "NO_LOCATION", order))
        assertEquals("NONE", ImportRules.stricterOf("NO_LOCATION", "NONE", order))
        assertEquals("NO_LOCATION", ImportRules.stricterOf("ALL", "NO_LOCATION", order))
        assertEquals("ALL", ImportRules.stricterOf("ALL", "ALL", order))
    }

    @Test
    fun unknownIncomingValueIsIgnored() {
        assertEquals("ALL", ImportRules.stricterOf("ALL", "SOMETHING", order))
    }
}
