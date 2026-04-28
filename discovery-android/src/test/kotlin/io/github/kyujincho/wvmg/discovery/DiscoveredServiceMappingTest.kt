/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery

import com.google.common.truth.Truth.assertThat
import io.github.kyujincho.wvmg.protocol.endpoint.DeviceType
import io.github.kyujincho.wvmg.protocol.endpoint.EndpointInfo
import org.junit.jupiter.api.Test
import java.net.InetAddress

/**
 * Unit tests for [Discovery.toDiscoveredService] that exercise the TXT
 * record + instance-name parsing logic in isolation, without spinning up
 * any actual `NsdManager` infrastructure. We hand-craft
 * [NsdBrowserEvent.Resolved] events directly.
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
        val txtBytes = endpointInfo.serialize()
        val event =
            NsdBrowserEvent.Resolved(
                instanceName = "IzAxMjP8n14AAA",
                addresses = listOf(InetAddress.getByName("192.168.1.42")),
                port = 54_321,
                attributes = mapOf(QuickShareMdns.TXT_KEY_ENDPOINT_INFO to txtBytes),
            )

        val result = newDiscovery().toDiscoveredService(event)
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
        val event =
            NsdBrowserEvent.Resolved(
                instanceName = "IzAxMjP8n14AAA",
                addresses = listOf(InetAddress.getByName("192.168.1.42")),
                port = 54_322,
                attributes = emptyMap(),
            )
        val result = newDiscovery().toDiscoveredService(event)
        assertThat(result).isNotNull()
        assertThat(result!!.endpointInfo).isNull()
    }

    @Test
    fun `garbage TXT bytes yield null endpointInfo but does not crash`() {
        val event =
            NsdBrowserEvent.Resolved(
                instanceName = "IzAxMjP8n14AAA",
                addresses = listOf(InetAddress.getByName("192.168.1.42")),
                port = 54_323,
                attributes = mapOf(QuickShareMdns.TXT_KEY_ENDPOINT_INFO to byteArrayOf(0xFF.toByte(), 0xFE.toByte())),
            )
        val result = newDiscovery().toDiscoveredService(event)
        assertThat(result).isNotNull()
        assertThat(result!!.endpointInfo).isNull()
    }

    @Test
    fun `non-base64 instance name yields null endpointId`() {
        val event =
            NsdBrowserEvent.Resolved(
                instanceName = "not-a-valid-instance-name",
                addresses = listOf(InetAddress.getByName("192.168.1.42")),
                port = 54_324,
                attributes = emptyMap(),
            )
        val result = newDiscovery().toDiscoveredService(event)
        assertThat(result).isNotNull()
        assertThat(result!!.endpointId).isNull()
        assertThat(result.instanceName).isEqualTo("not-a-valid-instance-name")
    }

    @Test
    fun `loopback-only peer is filtered out`() {
        val event =
            NsdBrowserEvent.Resolved(
                instanceName = "IzAxMjP8n14AAA",
                addresses = listOf(InetAddress.getLoopbackAddress()),
                port = 54_325,
                attributes = emptyMap(),
            )
        val result = newDiscovery().toDiscoveredService(event)
        assertThat(result).isNull()
    }

    private fun newDiscovery(): Discovery =
        Discovery.forTesting(
            registrar =
                object : NsdRegistrar {
                    override suspend fun register(
                        serviceType: String,
                        instanceName: String,
                        port: Int,
                        attributes: Map<String, ByteArray>,
                    ): NsdRegistrationHandle = error("toDiscoveredService should not need a registrar")
                },
            browser =
                object : NsdBrowser {
                    override fun discover(serviceType: String) = error("toDiscoveredService should not need a browser")
                },
        )
}
