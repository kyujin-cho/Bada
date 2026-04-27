/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery

import com.google.common.truth.Truth.assertThat
import io.github.kyujincho.wvmg.protocol.endpoint.Base64Url
import io.github.kyujincho.wvmg.protocol.endpoint.DeviceType
import io.github.kyujincho.wvmg.protocol.endpoint.EndpointInfo
import org.junit.jupiter.api.Test
import javax.jmdns.ServiceInfo

/**
 * Unit tests for [Discovery.toDiscoveredService] that exercise the TXT
 * record + instance-name parsing logic in isolation, without spinning up
 * any actual JmDNS infrastructure. We hand-craft [ServiceInfo] objects
 * via the documented `ServiceInfo.create(...)` factory.
 */
class DiscoveredServiceMappingTest {
    @Test
    fun `valid TXT record produces a fully-populated DiscoveredService`() {
        val endpointInfo =
            EndpointInfo(
                version = 1,
                hidden = false,
                deviceType = DeviceType.LAPTOP,
                reserved = false,
                metadata = ByteArray(EndpointInfo.METADATA_LEN) { (0x10 + it).toByte() },
                deviceName = "Pixel 9",
            )
        val txt = Base64Url.encode(endpointInfo.serialize())
        val info =
            ServiceInfo.create(
                QuickShareMdns.SERVICE_TYPE,
                // 10-byte raw -> 14-char URL-safe base64 (no padding).
                "IzAxMjP8n14AAA",
                54_321,
                0,
                0,
                mapOf(QuickShareMdns.TXT_KEY_ENDPOINT_INFO to txt),
            )

        val result = newDiscovery().toDiscoveredService(info)
        assertThat(result).isNotNull()
        assertThat(result!!.port).isEqualTo(54_321)
        assertThat(result.endpointInfo).isEqualTo(endpointInfo)
        assertThat(result.instanceName).isEqualTo("IzAxMjP8n14AAA")
        // Endpoint ID is bytes 1..4 of the decoded raw buffer.
        assertThat(result.endpointId).isNotNull()
        assertThat(String(result.endpointId!!, Charsets.US_ASCII)).isEqualTo("0123")
    }

    @Test
    fun `missing TXT key yields null endpointInfo but still surfaces the peer`() {
        val info =
            ServiceInfo.create(
                QuickShareMdns.SERVICE_TYPE,
                "IzAxMjP8n14AAA",
                54_322,
                0,
                0,
                // Empty map -> no TXT properties at all.
                emptyMap<String, String>(),
            )
        val result = newDiscovery().toDiscoveredService(info)
        assertThat(result).isNotNull()
        assertThat(result!!.endpointInfo).isNull()
    }

    @Test
    fun `garbage TXT value yields null endpointInfo but does not crash`() {
        val info =
            ServiceInfo.create(
                QuickShareMdns.SERVICE_TYPE,
                "IzAxMjP8n14AAA",
                54_323,
                0,
                0,
                mapOf(QuickShareMdns.TXT_KEY_ENDPOINT_INFO to "@@@@invalid-base64@@@@"),
            )
        val result = newDiscovery().toDiscoveredService(info)
        assertThat(result).isNotNull()
        assertThat(result!!.endpointInfo).isNull()
    }

    @Test
    fun `non-base64 instance name yields null endpointId`() {
        val info =
            ServiceInfo.create(
                QuickShareMdns.SERVICE_TYPE,
                "not-a-valid-instance-name",
                54_324,
                0,
                0,
                emptyMap<String, String>(),
            )
        val result = newDiscovery().toDiscoveredService(info)
        assertThat(result).isNotNull()
        assertThat(result!!.endpointId).isNull()
        assertThat(result.instanceName).isEqualTo("not-a-valid-instance-name")
    }

    private fun newDiscovery(): Discovery =
        Discovery.forTesting(
            locks =
                object : LockController {
                    override fun acquire() = Unit

                    override fun release() = Unit

                    override fun isHeld(): Boolean = false
                },
            jmdnsProvider = { error("toDiscoveredService should not need a JmDNS instance") },
        )
}
