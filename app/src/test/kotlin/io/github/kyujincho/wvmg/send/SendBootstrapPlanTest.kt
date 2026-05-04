/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.send

import io.github.kyujincho.wvmg.discovery.NearbyPeer
import io.github.kyujincho.wvmg.discovery.NearbyPeerRoute
import io.github.kyujincho.wvmg.protocol.endpoint.DeviceType
import io.github.kyujincho.wvmg.protocol.endpoint.EndpointInfo
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
    fun `visible BLE peer stays unavailable when GATT is not connectable`() {
        val peer =
            peer(
                bleAddress = "AA:BB:CC:DD:EE:FF",
                blePsm = null,
                bleVisible = true,
                bleGattConnectable = false,
            )

        val plan = SendBootstrapPlan.resolve(peer = peer)

        assertFalse(plan.isConnectable)
        assertTrue(plan.failureReason!!.contains("no shared Wi-Fi LAN route"))
        assertTrue(plan.failureReason!!.contains("receiver BLE advertisement has no L2CAP PSM"))
        assertTrue(plan.failureReason!!.contains("receiver BLE GATT bootstrap is not verified"))
    }

    @Test
    fun `verified visible BLE peer without PSM uses direct GATT bootstrap`() {
        val peer =
            peer(
                bleAddress = "AA:BB:CC:DD:EE:FF",
                blePsm = null,
                bleVisible = true,
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
    fun `hidden BLE observation stays unavailable without verified bootstrap metadata`() {
        val peer =
            peer(
                bleAddress = "AA:BB:CC:DD:EE:FF",
                blePsm = null,
                bleVisible = false,
            )

        val plan = SendBootstrapPlan.resolve(peer = peer)

        assertFalse(plan.isConnectable)
        assertTrue(plan.failureReason!!.contains("visible receiver"))
        assertTrue(plan.rejectedCandidates.contains("ble-gatt=peer-hidden"))
    }

    @Test
    fun `BLE GATT route wins before Bluetooth Classic when receiver is visible`() {
        val peer =
            peer(
                bluetoothMac = "11:22:33:44:55:66",
                bleAddress = "AA:BB:CC:DD:EE:FF",
                blePsm = null,
                bleVisible = true,
            )

        val plan = SendBootstrapPlan.resolve(peer = peer)

        assertTrue(plan.isConnectable)
        assertEquals(
            NearbyPeerRoute.BleGatt("AA:BB:CC:DD:EE:FF"),
            (plan.action as SendBootstrapPlan.Action.Direct).route,
        )
    }

    @Test
    fun `Bluetooth Classic is used when visible BLE GATT bootstrap is unverified`() {
        val peer =
            peer(
                bluetoothMac = "11:22:33:44:55:66",
                bleAddress = "AA:BB:CC:DD:EE:FF",
                blePsm = null,
                bleVisible = true,
                bleGattConnectable = false,
            )

        val plan = SendBootstrapPlan.resolve(peer = peer)

        assertTrue(plan.isConnectable)
        assertEquals(
            NearbyPeerRoute.BluetoothClassic("11:22:33:44:55:66"),
            (plan.action as SendBootstrapPlan.Action.Direct).route,
        )
        assertTrue(plan.subtitle.contains("Bluetooth Classic"))
        assertTrue(plan.rejectedCandidates.contains("ble-gatt=not-verified"))
    }

    @Test
    fun `Samsung peer reachable only via BLE GATT carries the Samsung caveat`() {
        // Reproduces the empirical case the cert-gate research doc
        // captures: a Samsung Galaxy with no Wi-Fi LAN exposure, only
        // a BLE GATT route, will time out at 15s on the Weave
        // handshake. The picker uses `samsungBleGattCaveat` to surface
        // a "Wi-Fi recommended" subtitle and a confirmation dialog so
        // the user understands why before they tap.
        val peer =
            peer(
                bleAddress = "AA:BB:CC:DD:EE:FF",
                blePsm = null,
                bleVisible = true,
            )

        val plan = SendBootstrapPlan.resolve(peer = peer)

        assertTrue(plan.isConnectable)
        assertEquals(
            NearbyPeerRoute.BleGatt("AA:BB:CC:DD:EE:FF"),
            (plan.action as SendBootstrapPlan.Action.Direct).route,
        )
        assertTrue(plan.samsungBleGattCaveat)
        assertTrue(plan.subtitle.contains("Wi-Fi recommended for Samsung"))
    }

    @Test
    fun `Samsung peer reachable via Wi-Fi LAN does not carry the caveat`() {
        // The cert-gate is BLE-GATT-only. When Wi-Fi LAN is available,
        // the picker picks LAN and Samsung's Wi-Fi LAN acceptance path
        // works without the cert lookup. No caveat needed.
        val peer =
            peer(
                lanAddress = "192.168.1.20",
                lanPort = 7654,
                bleAddress = "AA:BB:CC:DD:EE:FF",
                bleVisible = true,
            )

        val plan = SendBootstrapPlan.resolve(peer = peer)

        assertFalse(plan.samsungBleGattCaveat)
        assertFalse(plan.subtitle.contains("Samsung"))
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
        bleVisible: Boolean? = null,
        bleGattConnectable: Boolean = true,
    ): NearbyPeer =
        NearbyPeer(
            stableId = "peer-1",
            endpointId = "ABCD",
            endpointInfo =
                bleVisible?.let { visible ->
                    EndpointInfo(
                        version = 1,
                        hidden = !visible,
                        deviceType = DeviceType.PHONE,
                        reserved = false,
                        metadata = ByteArray(EndpointInfo.METADATA_LEN),
                        deviceName = if (visible) "Galaxy S26 Ultra" else null,
                    )
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
