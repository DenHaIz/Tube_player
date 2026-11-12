package com.tube.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun versionComparison() {
        assertTrue(UpdateChecker.isNewer("0.2", "0.1"))
        assertTrue(UpdateChecker.isNewer("0.10", "0.9"))
        assertTrue(UpdateChecker.isNewer("1.0", "0.9.9"))
        assertFalse(UpdateChecker.isNewer("0.1", "0.1"))
        assertFalse(UpdateChecker.isNewer("0.1", "0.2"))
        assertFalse(UpdateChecker.isNewer("0.1.0", "0.1"))
    }
}
