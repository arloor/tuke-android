package com.arloor.tuke.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppReleaseRepositoryTest {
    @Test
    fun parsesVersionCodeReleaseTag() {
        val parsed = parseReleaseVersion("v1.2.3+code.42")

        assertEquals("1.2.3", parsed?.versionName)
        assertEquals(42L, parsed?.versionCode)
        assertEquals(listOf(1L, 2L, 3L), parsed?.semanticParts)
    }

    @Test
    fun rejectsNonNumericVersionTag() {
        assertNull(parseReleaseVersion("nightly"))
    }

    @Test
    fun prefersVersionCodeWhenReleaseProvidesIt() {
        val latest = requireNotNull(parseReleaseVersion("v0.1.0+code.2"))

        assertTrue(isReleaseNewer("9.9.9", 1L, latest))
        assertFalse(isReleaseNewer("0.0.1", 2L, latest))
    }

    @Test
    fun fallsBackToSemanticVersionComparison() {
        val newer = requireNotNull(parseReleaseVersion("v0.2.0"))
        val same = requireNotNull(parseReleaseVersion("v0.1.0"))

        assertTrue(isReleaseNewer("0.1.9", 99L, newer))
        assertFalse(isReleaseNewer("0.1.0", 1L, same))
    }
}
