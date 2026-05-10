/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class NetworkChangeWatcherTest {
    @Test
    fun `initial available Wi-Fi callback is suppressed`() {
        val tracker = NetworkChangeTracker(initialNetworks = setOf("home-wifi"))

        assertThat(tracker.recordAvailable("home-wifi")).isFalse()
    }

    @Test
    fun `new Wi-Fi availability is reported once`() {
        val tracker = NetworkChangeTracker(initialNetworks = emptySet<String>())

        assertThat(tracker.recordAvailable("home-wifi")).isTrue()
        assertThat(tracker.recordAvailable("home-wifi")).isFalse()
    }

    @Test
    fun `known Wi-Fi loss is reported once`() {
        val tracker = NetworkChangeTracker(initialNetworks = setOf("home-wifi"))

        assertThat(tracker.recordLost("home-wifi")).isTrue()
        assertThat(tracker.recordLost("home-wifi")).isFalse()
    }
}
