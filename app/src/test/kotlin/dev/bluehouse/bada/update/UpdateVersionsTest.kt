/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the shared release-version comparison used by both
 * [UpdateRepository] and [UpdateCheckWorker]. The project's versionName
 * scheme is a fixed-width `YYYYMMDD.NN`, so plain lexicographic ordering
 * must be monotonic with release order.
 */
class UpdateVersionsTest {
    @Test
    fun `newer date is strictly newer`() {
        assertTrue(UpdateVersions.isNewer("20260701.01", "20260614.01"))
    }

    @Test
    fun `newer same-day sequence number is strictly newer`() {
        assertTrue(UpdateVersions.isNewer("20260614.02", "20260614.01"))
    }

    @Test
    fun `equal versions are not newer`() {
        assertFalse(UpdateVersions.isNewer("20260614.01", "20260614.01"))
    }

    @Test
    fun `older version is not newer`() {
        assertFalse(UpdateVersions.isNewer("20260601.09", "20260614.01"))
        assertFalse(UpdateVersions.isNewer("20251231.99", "20260101.01"))
    }
}
