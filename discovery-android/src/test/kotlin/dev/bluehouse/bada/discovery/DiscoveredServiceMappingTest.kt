/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery

import com.google.common.truth.Truth.assertThat
import dev.bluehouse.bada.protocol.endpoint.DeviceType
import dev.bluehouse.bada.protocol.endpoint.EndpointInfo
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
    fun `TXT b record decodes the published Bluetooth Classic MAC`() {
        // Literal value captured from a stock GMS 26.20.31 record:
        // base64("B8:A2:5D:EF:63:73").
        val event =
            NsdBrowserEvent.Resolved(
                instanceName = "IzAxMjP8n14AAA",
                addresses = listOf(InetAddress.getByName("192.168.1.42")),
                port = 54_321,
                attributes =
                    mapOf(
                        QuickShareMdns.TXT_KEY_BLUETOOTH_MAC to
                            "Qjg6QTI6NUQ6RUY6NjM6NzM".toByteArray(Charsets.US_ASCII),
                    ),
            )

        val result = newDiscovery().toDiscoveredService(event)
        assertThat(result).isNotNull()
        assertThat(result!!.bluetoothMacAddress).isEqualTo("B8:A2:5D:EF:63:73")
    }

    @Test
    fun `TXT b record tolerates a raw MAC string and normalizes case`() {
        val event =
            NsdBrowserEvent.Resolved(
                instanceName = "IzAxMjP8n14AAA",
                addresses = listOf(InetAddress.getByName("192.168.1.42")),
                port = 54_321,
                attributes =
                    mapOf(
                        QuickShareMdns.TXT_KEY_BLUETOOTH_MAC to
                            "08:b3:39:10:c2:6a".toByteArray(Charsets.US_ASCII),
                    ),
            )

        val result = newDiscovery().toDiscoveredService(event)
        assertThat(result).isNotNull()
        assertThat(result!!.bluetoothMacAddress).isEqualTo("08:B3:39:10:C2:6A")
    }

    @Test
    fun `garbage TXT b record yields a null MAC without crashing`() {
        val event =
            NsdBrowserEvent.Resolved(
                instanceName = "IzAxMjP8n14AAA",
                addresses = listOf(InetAddress.getByName("192.168.1.42")),
                port = 54_321,
                attributes =
                    mapOf(
                        QuickShareMdns.TXT_KEY_BLUETOOTH_MAC to byteArrayOf(0xFF.toByte(), 0x00),
                    ),
            )

        val result = newDiscovery().toDiscoveredService(event)
        assertThat(result).isNotNull()
        assertThat(result!!.bluetoothMacAddress).isNull()
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

    @Test
    fun `peer whose resolved address belongs to this device is filtered out`() {
        val localAddress = InetAddress.getByName("192.168.1.10")
        val event =
            NsdBrowserEvent.Resolved(
                instanceName = "IzAxMjP8n14AAA",
                addresses = listOf(localAddress, InetAddress.getByName("192.168.1.42")),
                port = 54_326,
                attributes = emptyMap(),
            )
        val result =
            newDiscovery(
                localAddresses = setOf(localAddress),
            ).toDiscoveredService(event)
        assertThat(result).isNull()
    }

    @Test
    fun `blank instance name is dropped — defensive guard against misbehaving mDNS responders`() {
        // A handful of OEM mDNS responder implementations have been observed
        // surfacing a "resolved" callback with an empty or blank service name.
        // Discovery.toDiscoveredService guards against this by returning null
        // before any downstream caller can interpret the blank name as a real
        // peer identity.
        val event =
            NsdBrowserEvent.Resolved(
                instanceName = "   ", // whitespace-only, isBlank() == true
                addresses = listOf(InetAddress.getByName("192.168.1.42")),
                port = 54_327,
                attributes = emptyMap(),
            )
        val result = newDiscovery().toDiscoveredService(event)
        assertThat(result).isNull()
    }

    @Test
    fun `port zero is dropped — defensive guard against misbehaving mDNS responders`() {
        // Port 0 is not a dialable TCP port. An NsdBrowserEvent.Resolved event
        // that carries port=0 indicates a partially-resolved record from a
        // misbehaving system responder. Guard: Discovery.toDiscoveredService
        // must return null rather than emit a DiscoveredService with an
        // undialable port.
        val event =
            NsdBrowserEvent.Resolved(
                instanceName = "IzAxMjP8n14AAA",
                addresses = listOf(InetAddress.getByName("192.168.1.42")),
                port = 0,
                attributes = emptyMap(),
            )
        val result = newDiscovery().toDiscoveredService(event)
        assertThat(result).isNull()
    }

    @Test
    fun `port above MAX_PORT is dropped`() {
        val event =
            NsdBrowserEvent.Resolved(
                instanceName = "IzAxMjP8n14AAA",
                addresses = listOf(InetAddress.getByName("192.168.1.42")),
                port = Discovery.MAX_PORT + 1,
                attributes = emptyMap(),
            )
        val result = newDiscovery().toDiscoveredService(event)
        assertThat(result).isNull()
    }

    private fun newDiscovery(localAddresses: Set<InetAddress> = emptySet()): Discovery =
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
            localAddressProvider = { localAddresses },
        )
}
