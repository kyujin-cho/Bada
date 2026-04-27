/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol

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
    fun `module name is set`() {
        assertThat(ProtocolInfo.NAME).isNotEmpty()
    }
}
