/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery

import com.google.common.truth.Truth.assertThat
import io.github.kyujincho.wvmg.discovery.ble.BleFastAdvertisementScanner
import io.github.kyujincho.wvmg.discovery.classic.BluetoothClassicPeerScanner
import io.github.kyujincho.wvmg.protocol.endpoint.DeviceType
import io.github.kyujincho.wvmg.protocol.endpoint.EndpointInfo
import io.github.kyujincho.wvmg.protocol.medium.Medium
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
    fun `bluetooth and LAN sightings merge into one LAN-first peer`() =
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

            val resolved = seen.filterIsInstance<NearbyPeerEvent.Resolved>().last().peer
            assertThat(seen.filterIsInstance<NearbyPeerEvent.Resolved>()).hasSize(2)
            assertThat(resolved.stableId).isEqualTo("endpoint:ABCD")
            assertThat(resolved.candidateMediums).containsExactly(Medium.WIFI_LAN, Medium.BLUETOOTH)
            assertThat(resolved.preferredRoute()).isEqualTo(
                NearbyPeerRoute.Lan(
                    address = InetAddress.getByName("192.168.1.44"),
                    port = 54321,
                ),
            )

            collector.cancel()
        }

    @Test
    fun `LAN loss keeps bluetooth-discovered peer alive`() =
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

            assertThat(seen.filterIsInstance<NearbyPeerEvent.Lost>()).isEmpty()
            val resolved = seen.filterIsInstance<NearbyPeerEvent.Resolved>().last().peer
            assertThat(resolved.candidateMediums).containsExactly(Medium.BLUETOOTH)
            assertThat(resolved.preferredRoute()).isEqualTo(
                NearbyPeerRoute.BluetoothClassic("11:22:33:44:55:66"),
            )

            collector.cancel()
        }

    @Test
    fun `BLE fast advertisement merges into bluetooth bootstrap candidate`() =
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

            val resolved = seen.filterIsInstance<NearbyPeerEvent.Resolved>().last().peer
            assertThat(resolved.candidateMediums).containsExactly(Medium.BLE, Medium.BLUETOOTH)
            assertThat(resolved.bleAdvertisement?.advertiserAddress).isEqualTo("77:88:99:AA:BB:CC")
            assertThat(resolved.preferredRoute()).isEqualTo(
                NearbyPeerRoute.BluetoothClassic("66:55:44:33:22:11"),
            )

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

            val resolved = seen.filterIsInstance<NearbyPeerEvent.Resolved>().last().peer
            assertThat(resolved.isConnectable).isTrue()
            assertThat(resolved.candidateMediums).containsExactly(Medium.BLE, Medium.BLE_L2CAP)
            assertThat(resolved.preferredRoute()).isEqualTo(
                NearbyPeerRoute.BleL2cap(
                    macAddress = "77:88:99:AA:BB:CC",
                    psm = 0x1234,
                ),
            )

            collector.cancel()
        }

    @Test
    fun `BLE GATT advertisement is connectable without Classic or L2CAP`() =
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

            bleEvents.emit(
                BleFastAdvertisementScanner.Observation(
                    endpointId = null,
                    endpointInfo = null,
                    advertiserAddress = "28:1B:3E:BA:B1:1B",
                    rssi = -41,
                    l2capPsm = null,
                    gattConnectable = true,
                ),
            )
            runCurrent()

            val resolved = seen.filterIsInstance<NearbyPeerEvent.Resolved>().last().peer
            assertThat(resolved.isConnectable).isTrue()
            assertThat(resolved.candidateMediums).containsExactly(Medium.BLE)
            assertThat(resolved.displayName()).isEqualTo("Quick Share BLE device")
            assertThat(resolved.displayNameSource()).isEqualTo("ble-gatt-fallback")
            assertThat(resolved.preferredRoute()).isEqualTo(
                NearbyPeerRoute.BleGatt(macAddress = "28:1B:3E:BA:B1:1B"),
            )

            collector.cancel()
        }

    @Test
    fun `BLE GATT advertisement uses parsed BLE display name before endpoint fallback`() =
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

            bleEvents.emit(
                BleFastAdvertisementScanner.Observation(
                    endpointId = "RINE",
                    endpointInfo = null,
                    advertiserAddress = "28:1B:3E:BA:B1:1B",
                    rssi = -41,
                    l2capPsm = null,
                    gattConnectable = true,
                    displayName = "Galaxy S26",
                    displayNameSource =
                        BleFastAdvertisementScanner.DisplayNameSource.BLE_LOCAL_NAME,
                ),
            )
            runCurrent()

            val resolved = seen.filterIsInstance<NearbyPeerEvent.Resolved>().last().peer
            assertThat(resolved.displayName()).isEqualTo("Galaxy S26")
            assertThat(resolved.displayNameSource()).isEqualTo("ble-local-name")
            assertThat(resolved.bleAdvertisement?.displayName).isEqualTo("Galaxy S26")
            assertThat(resolved.preferredRoute()).isEqualTo(
                NearbyPeerRoute.BleGatt(macAddress = "28:1B:3E:BA:B1:1B"),
            )

            collector.cancel()
        }

    @Test
    fun `BLE GATT advertisement falls back to endpoint id before generic label`() =
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

            bleEvents.emit(
                BleFastAdvertisementScanner.Observation(
                    endpointId = "RINE",
                    endpointInfo = null,
                    advertiserAddress = "28:1B:3E:BA:B1:1B",
                    rssi = -41,
                    l2capPsm = null,
                    gattConnectable = true,
                ),
            )
            runCurrent()

            val resolved = seen.filterIsInstance<NearbyPeerEvent.Resolved>().last().peer
            assertThat(resolved.displayName()).isEqualTo("Quick Share device (RINE)")
            assertThat(resolved.displayNameSource()).isEqualTo("endpoint-id")

            collector.cancel()
        }

    private fun endpointInfo(name: String?): EndpointInfo =
        EndpointInfo(
            version = 1,
            hidden = false,
            deviceType = DeviceType.PHONE,
            reserved = false,
            metadata = ByteArray(EndpointInfo.METADATA_LEN) { it.toByte() },
            deviceName = name,
        )
}
