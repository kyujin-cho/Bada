/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Smoke test confirming the JVM test pipeline works end-to-end before the
 * real protocol tests (KAT vectors, framing fuzzers, etc.) land in #27.
 */
class ProtocolInfoTest {
    @Test
    fun `mDNS service type matches the value documented in NearDrop`() {
        assertThat(ProtocolInfo.MDNS_SERVICE_TYPE).isEqualTo("_FC9F5ED42C8A._tcp.")
    }

    @Test
    fun `module name identifies the core-protocol module`() {
        assertThat(ProtocolInfo.NAME).isEqualTo("LibreDrop/core-protocol")
    }

    @Test
    fun `mDNS service type follows the Bonjour service-protocol form`() {
        // Sanity-check the literal against the structural shape Quick Share / Bonjour expects.
        assertThat(ProtocolInfo.MDNS_SERVICE_TYPE).matches("^_[A-Z0-9]+\\._tcp\\.$")
    }
}
