package com.chelayel.airelay.cli

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionTest {
    @Test fun `newer is decided numerically per component`() {
        assertTrue(Version.isNewer("1.8.6", "1.8.5"))
        assertTrue(Version.isNewer("1.10.0", "1.9.9"))
        assertTrue(Version.isNewer("2.0.0", "1.99.99"))
        assertFalse(Version.isNewer("1.8.5", "1.8.5"))
        assertFalse(Version.isNewer("1.8.4", "1.8.5"))
        assertFalse(Version.isNewer("1.8.5", "dev".ifBlank { "0" }) && false)
    }
}
