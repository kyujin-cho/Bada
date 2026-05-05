/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.send

import dev.bluehouse.libredrop.discovery.NearbyPeer
import dev.bluehouse.libredrop.discovery.NearbyPeerRoute
import dev.bluehouse.libredrop.protocol.endpoint.DeviceType
import dev.bluehouse.libredrop.protocol.endpoint.EndpointInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class SendBootstrapPlanTest {
    @Test
    fun `Wi-Fi LAN route wins before BLE bootstrap routes`() {
        val peer =
            peer(
                lanAddress = "192.168.1.20",
                lanPort = 7654,
                bluetoothMac = "11:22:33:44:55:66",
                bleAddress = "AA:BB:CC:DD:EE:FF",
                blePsm = 0x1234,
            )

        val plan = SendBootstrapPlan.resolve(peer = peer)

        assertTrue(plan.isConnectable)
        assertEquals(
            NearbyPeerRoute.Lan(InetAddress.getByName("192.168.1.20"), 7654),
            (plan.action as SendBootstrapPlan.Action.Direct).route,
        )
    }

    @Test
    fun `peer with malformed endpoint info stays unavailable`() {
        val peer =
            peer(
                lanAddress = "192.168.1.20",
                endpointInfoPresent = false,
            )

        val plan = SendBootstrapPlan.resolve(peer = peer)

        assertFalse(plan.isConnectable)
        assertTrue(plan.failureReason!!.contains("peer endpoint info could not be parsed"))
        assertTrue(plan.rejectedCandidates.contains("wifi-lan=endpoint-info-unparseable"))
    }

    @Test
    fun `BLE peer stays unavailable when GATT is not connectable`() {
        val peer =
            peer(
                bleAddress = "AA:BB:CC:DD:EE:FF",
                blePsm = null,
                bleGattConnectable = false,
            )

        val plan = SendBootstrapPlan.resolve(peer = peer)

        assertFalse(plan.isConnectable)
        assertTrue(plan.failureReason!!.contains("no shared Wi-Fi LAN route"))
        assertTrue(plan.failureReason!!.contains("receiver BLE advertisement has no L2CAP PSM"))
        assertTrue(plan.failureReason!!.contains("receiver BLE GATT bootstrap is not verified"))
        assertTrue(plan.rejectedCandidates.contains("ble-gatt=not-verified"))
    }

    @Test
    fun `verified BLE peer without PSM uses GATT regardless of visibility bit`() {
        val peer =
            peer(
                bleAddress = "AA:BB:CC:DD:EE:FF",
                blePsm = null,
                endpointInfoHidden = true,
            )

        val plan = SendBootstrapPlan.resolve(peer = peer)

        assertTrue(plan.isConnectable)
        assertEquals(
            NearbyPeerRoute.BleGatt("AA:BB:CC:DD:EE:FF"),
            (plan.action as SendBootstrapPlan.Action.Direct).route,
        )
        assertTrue(plan.subtitle.contains("BLE GATT"))
        assertTrue(plan.rejectedCandidates.contains("ble-l2cap=peer-psm-missing"))
    }

    @Test
    fun `Bluetooth Classic candidate is ignored when BLE GATT bootstrap is unverified`() {
        val peer =
            peer(
                bluetoothMac = "11:22:33:44:55:66",
                bleAddress = "AA:BB:CC:DD:EE:FF",
                blePsm = null,
                bleGattConnectable = false,
            )

        val plan = SendBootstrapPlan.resolve(peer = peer)

        assertFalse(plan.isConnectable)
        assertEquals(SendBootstrapPlan.Action.Unavailable, plan.action)
        assertTrue(plan.rejectedCandidates.contains("ble-gatt=not-verified"))
        assertTrue(plan.rejectedCandidates.contains("bluetooth-classic=disabled"))
    }

    @Test
    fun `BLE L2CAP route requires parsed endpoint info`() {
        val peer =
            peer(
                bleAddress = "AA:BB:CC:DD:EE:FF",
                blePsm = 0x1234,
                endpointInfoPresent = false,
            )

        val plan = SendBootstrapPlan.resolve(peer = peer)

        assertFalse(plan.isConnectable)
        assertTrue(plan.rejectedCandidates.contains("ble-l2cap=no-endpoint-info"))
        assertTrue(plan.rejectedCandidates.contains("ble-gatt=no-endpoint-info"))
    }

    @Test
    fun `peer without any route stays unavailable`() {
        val peer = peer()

        val plan = SendBootstrapPlan.resolve(peer = peer)

        assertFalse(plan.isConnectable)
        assertTrue(plan.failureReason!!.contains("no shared Wi-Fi LAN route"))
    }

    @Suppress("LongParameterList")
    private fun peer(
        lanAddress: String? = null,
        lanPort: Int = 7654,
        bluetoothMac: String? = null,
        bleAddress: String? = null,
        blePsm: Int? = null,
        bleGattConnectable: Boolean = true,
        endpointInfoPresent: Boolean = true,
        endpointInfoHidden: Boolean = false,
    ): NearbyPeer =
        NearbyPeer(
            stableId = "peer-1",
            endpointId = "ABCD",
            endpointInfo =
                if (endpointInfoPresent) {
                    EndpointInfo(
                        version = 1,
                        hidden = endpointInfoHidden,
                        deviceType = DeviceType.PHONE,
                        reserved = false,
                        metadata = ByteArray(EndpointInfo.METADATA_LEN),
                        deviceName = if (endpointInfoHidden) null else "Galaxy S26 Ultra",
                    )
                } else {
                    null
                },
            lanEndpoint =
                lanAddress?.let {
                    NearbyPeer.LanEndpoint(
                        instanceNames = setOf("ABCD"),
                        addresses = listOf(InetAddress.getByName(it)),
                        port = lanPort,
                    )
                },
            bluetoothEndpoint =
                bluetoothMac?.let {
                    NearbyPeer.BluetoothEndpoint(
                        macAddress = it,
                        advertisedName = "Nearby-$it",
                    )
                },
            bleAdvertisement =
                bleAddress?.let {
                    NearbyPeer.BleAdvertisement(
                        advertiserAddress = it,
                        rssi = -42,
                        l2capPsm = blePsm,
                        gattConnectable = bleGattConnectable,
                        displayName = "Galaxy S26 Ultra",
                        displayNameSource = "fast-advertisement-endpoint-info",
                    )
                },
        )
}
