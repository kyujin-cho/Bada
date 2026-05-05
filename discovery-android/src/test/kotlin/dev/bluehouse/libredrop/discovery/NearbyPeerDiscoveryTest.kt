/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.discovery

import com.google.common.truth.Truth.assertThat
import dev.bluehouse.libredrop.discovery.ble.BleFastAdvertisementScanner
import dev.bluehouse.libredrop.discovery.classic.BluetoothClassicPeerScanner
import dev.bluehouse.libredrop.protocol.endpoint.DeviceType
import dev.bluehouse.libredrop.protocol.endpoint.EndpointInfo
import dev.bluehouse.libredrop.protocol.medium.Medium
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.net.InetAddress

@OptIn(ExperimentalCoroutinesApi::class)
class NearbyPeerDiscoveryTest {
    @Test
    fun `Bluetooth metadata stays hidden until LAN route arrives`() =
        runTest {
            val lanEvents = MutableSharedFlow<DiscoveryEvent>()
            val bleEvents = MutableSharedFlow<BleFastAdvertisementScanner.Observation>()
            val bluetoothEvents = MutableSharedFlow<BluetoothClassicPeerScanner.Observation>()
            val discovery =
                NearbyPeerDiscovery.forTesting(
                    lanEvents = lanEvents,
                    bleEvents = bleEvents,
                    bluetoothEvents = bluetoothEvents,
                )
            val seen = mutableListOf<NearbyPeerEvent>()
            val collector =
                backgroundScope.launch {
                    discovery.browse().collect { seen += it }
                }
            runCurrent()

            val endpointInfo = endpointInfo(name = "Pixel 9")
            bluetoothEvents.emit(
                BluetoothClassicPeerScanner.Observation(
                    endpointId = "ABCD",
                    endpointInfo = endpointInfo,
                    macAddress = "AA:BB:CC:DD:EE:FF",
                    advertisedName = "nearby-bt",
                ),
            )
            runCurrent()
            assertThat(seen).isEmpty()

            lanEvents.emit(
                DiscoveryEvent.Resolved(
                    DiscoveredService(
                        instanceName = "instance-1",
                        endpointId = "ABCD".toByteArray(Charsets.US_ASCII),
                        addresses = listOf(InetAddress.getByName("192.168.1.44")),
                        port = 54321,
                        endpointInfo = endpointInfo,
                    ),
                ),
            )
            runCurrent()

            val resolved = seen.single() as NearbyPeerEvent.Resolved
            assertThat(resolved.peer.stableId).isEqualTo("endpoint:ABCD")
            assertThat(resolved.peer.candidateMediums).containsExactly(Medium.WIFI_LAN)
            assertThat(resolved.peer.preferredRoute()).isEqualTo(
                NearbyPeerRoute.Lan(
                    address = InetAddress.getByName("192.168.1.44"),
                    port = 54321,
                ),
            )

            collector.cancel()
        }

    @Test
    fun `LAN loss removes peer once only disabled metadata remains`() =
        runTest {
            val lanEvents = MutableSharedFlow<DiscoveryEvent>()
            val bleEvents = MutableSharedFlow<BleFastAdvertisementScanner.Observation>()
            val bluetoothEvents = MutableSharedFlow<BluetoothClassicPeerScanner.Observation>()
            val discovery =
                NearbyPeerDiscovery.forTesting(
                    lanEvents = lanEvents,
                    bleEvents = bleEvents,
                    bluetoothEvents = bluetoothEvents,
                )
            val seen = mutableListOf<NearbyPeerEvent>()
            val collector =
                backgroundScope.launch {
                    discovery.browse().collect { seen += it }
                }
            runCurrent()

            val endpointInfo = endpointInfo(name = "Nearby peer")
            bluetoothEvents.emit(
                BluetoothClassicPeerScanner.Observation(
                    endpointId = "WXYZ",
                    endpointInfo = endpointInfo,
                    macAddress = "11:22:33:44:55:66",
                    advertisedName = "nearby-bt",
                ),
            )
            lanEvents.emit(
                DiscoveryEvent.Resolved(
                    DiscoveredService(
                        instanceName = "instance-2",
                        endpointId = "WXYZ".toByteArray(Charsets.US_ASCII),
                        addresses = listOf(InetAddress.getByName("192.168.1.45")),
                        port = 11111,
                        endpointInfo = endpointInfo,
                    ),
                ),
            )
            lanEvents.emit(DiscoveryEvent.Lost("instance-2"))
            runCurrent()

            assertThat(seen).containsExactly(
                NearbyPeerEvent.Resolved(
                    NearbyPeer(
                        stableId = "endpoint:WXYZ",
                        endpointId = "WXYZ",
                        endpointInfo = endpointInfo,
                        lanEndpoint =
                            NearbyPeer.LanEndpoint(
                                instanceNames = setOf("instance-2"),
                                addresses = listOf(InetAddress.getByName("192.168.1.45")),
                                port = 11111,
                            ),
                        bluetoothEndpoint =
                            NearbyPeer.BluetoothEndpoint(
                                macAddress = "11:22:33:44:55:66",
                                advertisedName = "nearby-bt",
                            ),
                    ),
                ),
                NearbyPeerEvent.Lost("endpoint:WXYZ"),
            )

            collector.cancel()
        }

    @Test
    fun `non-negotiable BLE metadata stays hidden`() =
        runTest {
            val lanEvents = MutableSharedFlow<DiscoveryEvent>()
            val bleEvents = MutableSharedFlow<BleFastAdvertisementScanner.Observation>()
            val bluetoothEvents = MutableSharedFlow<BluetoothClassicPeerScanner.Observation>()
            val discovery =
                NearbyPeerDiscovery.forTesting(
                    lanEvents = lanEvents,
                    bleEvents = bleEvents,
                    bluetoothEvents = bluetoothEvents,
                )
            val seen = mutableListOf<NearbyPeerEvent>()
            val collector =
                backgroundScope.launch {
                    discovery.browse().collect { seen += it }
                }
            runCurrent()

            val endpointInfo = endpointInfo(name = "Galaxy")
            bleEvents.emit(
                BleFastAdvertisementScanner.Observation(
                    endpointId = "RINE",
                    endpointInfo = endpointInfo,
                    advertiserAddress = "77:88:99:AA:BB:CC",
                    rssi = -47,
                    l2capPsm = null,
                    gattConnectable = false,
                ),
            )
            bluetoothEvents.emit(
                BluetoothClassicPeerScanner.Observation(
                    endpointId = "RINE",
                    endpointInfo = endpointInfo,
                    macAddress = "66:55:44:33:22:11",
                    advertisedName = "nearby-bt",
                ),
            )
            runCurrent()

            assertThat(seen).isEmpty()

            collector.cancel()
        }

    @Test
    fun `BLE fast advertisement with PSM is connectable over L2CAP`() =
        runTest {
            val lanEvents = MutableSharedFlow<DiscoveryEvent>()
            val bleEvents = MutableSharedFlow<BleFastAdvertisementScanner.Observation>()
            val bluetoothEvents = MutableSharedFlow<BluetoothClassicPeerScanner.Observation>()
            val discovery =
                NearbyPeerDiscovery.forTesting(
                    lanEvents = lanEvents,
                    bleEvents = bleEvents,
                    bluetoothEvents = bluetoothEvents,
                )
            val seen = mutableListOf<NearbyPeerEvent>()
            val collector =
                backgroundScope.launch {
                    discovery.browse().collect { seen += it }
                }
            runCurrent()

            val endpointInfo = endpointInfo(name = "Galaxy")
            bleEvents.emit(
                BleFastAdvertisementScanner.Observation(
                    endpointId = "RINE",
                    endpointInfo = endpointInfo,
                    advertiserAddress = "77:88:99:AA:BB:CC",
                    rssi = -47,
                    l2capPsm = 0x1234,
                    gattConnectable = true,
                ),
            )
            runCurrent()

            val resolved = seen.single() as NearbyPeerEvent.Resolved
            assertThat(resolved.peer.isConnectable).isTrue()
            assertThat(resolved.peer.candidateMediums).containsExactly(Medium.BLE, Medium.BLE_L2CAP)
            assertThat(resolved.peer.preferredRoute()).isEqualTo(
                NearbyPeerRoute.BleL2cap(
                    macAddress = "77:88:99:AA:BB:CC",
                    psm = 0x1234,
                ),
            )

            collector.cancel()
        }

    @Test
    fun `verified BLE GATT route ignores visibility bit`() =
        runTest {
            val lanEvents = MutableSharedFlow<DiscoveryEvent>()
            val bleEvents = MutableSharedFlow<BleFastAdvertisementScanner.Observation>()
            val bluetoothEvents = MutableSharedFlow<BluetoothClassicPeerScanner.Observation>()
            val discovery =
                NearbyPeerDiscovery.forTesting(
                    lanEvents = lanEvents,
                    bleEvents = bleEvents,
                    bluetoothEvents = bluetoothEvents,
                )
            val seen = mutableListOf<NearbyPeerEvent>()
            val collector =
                backgroundScope.launch {
                    discovery.browse().collect { seen += it }
                }
            runCurrent()

            val endpointInfo = endpointInfo(name = null, hidden = true)
            bleEvents.emit(
                BleFastAdvertisementScanner.Observation(
                    endpointId = "RINE",
                    endpointInfo = endpointInfo,
                    advertiserAddress = "77:88:99:AA:BB:CC",
                    rssi = -47,
                    l2capPsm = null,
                    gattConnectable = true,
                ),
            )
            runCurrent()

            val resolved = seen.single() as NearbyPeerEvent.Resolved
            assertThat(resolved.peer.isConnectable).isTrue()
            assertThat(resolved.peer.candidateMediums).containsExactly(Medium.BLE)
            assertThat(resolved.peer.preferredRoute()).isEqualTo(
                NearbyPeerRoute.BleGatt(
                    macAddress = "77:88:99:AA:BB:CC",
                ),
            )

            collector.cancel()
        }

    @Test
    fun `BLE advertisement without PSM stays hidden until verification succeeds`() =
        runTest {
            val lanEvents = MutableSharedFlow<DiscoveryEvent>()
            val bleEvents = MutableSharedFlow<BleFastAdvertisementScanner.Observation>()
            val bluetoothEvents = MutableSharedFlow<BluetoothClassicPeerScanner.Observation>()
            val discovery =
                NearbyPeerDiscovery.forTesting(
                    lanEvents = lanEvents,
                    bleEvents = bleEvents,
                    bluetoothEvents = bluetoothEvents,
                )
            val seen = mutableListOf<NearbyPeerEvent>()
            val collector =
                backgroundScope.launch {
                    discovery.browse().collect { seen += it }
                }
            runCurrent()

            val endpointInfo = endpointInfo(name = "Galaxy")
            bleEvents.emit(
                BleFastAdvertisementScanner.Observation(
                    endpointId = "RINE",
                    endpointInfo = endpointInfo,
                    advertiserAddress = "77:88:99:AA:BB:CC",
                    rssi = -47,
                    l2capPsm = null,
                    gattConnectable = false,
                ),
            )
            runCurrent()
            assertThat(seen).isEmpty()

            bleEvents.emit(
                BleFastAdvertisementScanner.Observation(
                    endpointId = "RINE",
                    endpointInfo = endpointInfo,
                    advertiserAddress = "77:88:99:AA:BB:CC",
                    rssi = -46,
                    l2capPsm = null,
                    gattConnectable = true,
                ),
            )
            runCurrent()

            val resolved = seen.single() as NearbyPeerEvent.Resolved
            assertThat(resolved.peer.preferredRoute()).isEqualTo(
                NearbyPeerRoute.BleGatt(
                    macAddress = "77:88:99:AA:BB:CC",
                ),
            )

            collector.cancel()
        }

    @Test
    fun `LAN peer stays hidden until endpoint info parses`() =
        runTest {
            val lanEvents = MutableSharedFlow<DiscoveryEvent>()
            val bleEvents = MutableSharedFlow<BleFastAdvertisementScanner.Observation>()
            val bluetoothEvents = MutableSharedFlow<BluetoothClassicPeerScanner.Observation>()
            val discovery =
                NearbyPeerDiscovery.forTesting(
                    lanEvents = lanEvents,
                    bleEvents = bleEvents,
                    bluetoothEvents = bluetoothEvents,
                )
            val seen = mutableListOf<NearbyPeerEvent>()
            val collector =
                backgroundScope.launch {
                    discovery.browse().collect { seen += it }
                }
            runCurrent()

            lanEvents.emit(
                DiscoveryEvent.Resolved(
                    DiscoveredService(
                        instanceName = "instance-3",
                        endpointId = "ABCD".toByteArray(Charsets.US_ASCII),
                        addresses = listOf(InetAddress.getByName("192.168.1.50")),
                        port = 54321,
                        endpointInfo = null,
                    ),
                ),
            )
            runCurrent()
            assertThat(seen).isEmpty()

            lanEvents.emit(
                DiscoveryEvent.Resolved(
                    DiscoveredService(
                        instanceName = "instance-3",
                        endpointId = "ABCD".toByteArray(Charsets.US_ASCII),
                        addresses = listOf(InetAddress.getByName("192.168.1.50")),
                        port = 54321,
                        endpointInfo = endpointInfo(name = "Pixel 9"),
                    ),
                ),
            )
            runCurrent()

            val resolved = seen.single() as NearbyPeerEvent.Resolved
            assertThat(resolved.peer.preferredRoute()).isEqualTo(
                NearbyPeerRoute.Lan(
                    address = InetAddress.getByName("192.168.1.50"),
                    port = 54321,
                ),
            )

            collector.cancel()
        }

    @Test
    fun `LAN peer stays hidden until primary address resolves`() =
        runTest {
            val lanEvents = MutableSharedFlow<DiscoveryEvent>()
            val bleEvents = MutableSharedFlow<BleFastAdvertisementScanner.Observation>()
            val bluetoothEvents = MutableSharedFlow<BluetoothClassicPeerScanner.Observation>()
            val discovery =
                NearbyPeerDiscovery.forTesting(
                    lanEvents = lanEvents,
                    bleEvents = bleEvents,
                    bluetoothEvents = bluetoothEvents,
                )
            val seen = mutableListOf<NearbyPeerEvent>()
            val collector =
                backgroundScope.launch {
                    discovery.browse().collect { seen += it }
                }
            runCurrent()

            val endpointInfo = endpointInfo(name = "Pixel 9")
            lanEvents.emit(
                DiscoveryEvent.Resolved(
                    DiscoveredService(
                        instanceName = "instance-4",
                        endpointId = "EFGH".toByteArray(Charsets.US_ASCII),
                        addresses = emptyList(),
                        port = 54321,
                        endpointInfo = endpointInfo,
                    ),
                ),
            )
            runCurrent()
            assertThat(seen).isEmpty()

            lanEvents.emit(
                DiscoveryEvent.Resolved(
                    DiscoveredService(
                        instanceName = "instance-4",
                        endpointId = "EFGH".toByteArray(Charsets.US_ASCII),
                        addresses = listOf(InetAddress.getByName("192.168.1.51")),
                        port = 54321,
                        endpointInfo = endpointInfo,
                    ),
                ),
            )
            runCurrent()

            val resolved = seen.single() as NearbyPeerEvent.Resolved
            assertThat(resolved.peer.preferredRoute()).isEqualTo(
                NearbyPeerRoute.Lan(
                    address = InetAddress.getByName("192.168.1.51"),
                    port = 54321,
                ),
            )

            collector.cancel()
        }

    private fun endpointInfo(
        name: String?,
        hidden: Boolean = false,
    ): EndpointInfo =
        EndpointInfo(
            version = 1,
            hidden = hidden,
            deviceType = DeviceType.PHONE,
            reserved = false,
            metadata = ByteArray(EndpointInfo.METADATA_LEN) { it.toByte() },
            deviceName = name,
        )
}
