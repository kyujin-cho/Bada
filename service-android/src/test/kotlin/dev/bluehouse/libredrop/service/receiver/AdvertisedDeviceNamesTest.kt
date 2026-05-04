/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.service.receiver

import com.google.common.truth.Truth.assertThat
import dev.bluehouse.libredrop.protocol.endpoint.DeviceType
import dev.bluehouse.libredrop.protocol.endpoint.EndpointInfo
import dev.bluehouse.libredrop.protocol.endpoint.TlvRecord
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

class AdvertisedDeviceNamesTest {
    @Test
    fun `resolver prefers the documented fallback order`() {
        val resolved =
            AdvertisedDeviceNameResolver(
                customName = null,
                systemDeviceName = "  Pixel 9 Pro  ",
                bluetoothName = "Bluetooth Name",
                modelName = "Model Name",
                appLabel = "LibreDrop",
            ).resolve()

        assertThat(resolved).isEqualTo("Pixel 9 Pro")
    }

    @Test
    fun `resolver skips blank candidates and falls through to later sources`() {
        val resolved =
            AdvertisedDeviceNameResolver(
                customName = " ",
                systemDeviceName = "\n\t",
                bluetoothName = null,
                modelName = "Galaxy S25",
                appLabel = "LibreDrop",
            ).resolve()

        assertThat(resolved).isEqualTo("Galaxy S25")
    }

    @Test
    fun `policy short-circuits platform reads when a custom name is stored`() {
        val prefs =
            AdvertisedDeviceNamePreferences(FakeSharedPreferences()).apply {
                setCustomName("Custom Pixel")
            }
        var systemReads = 0
        var modelReads = 0
        var appLabelReads = 0
        val bluetooth = FakeBluetoothNameGateway(readValue = "Nearby BT")
        val policy =
            AndroidAdvertisedDeviceNamePolicy(
                preferences = prefs,
                sdkInt = 36,
                globalDeviceNameReader =
                    GlobalDeviceNameReader {
                        systemReads += 1
                        "System Pixel"
                    },
                bluetoothNameGateway = bluetooth,
                modelName = {
                    modelReads += 1
                    "Pixel Model"
                },
                appLabel = {
                    appLabelReads += 1
                    "LibreDrop"
                },
            )

        val resolved = policy.resolve()

        assertThat(resolved).isEqualTo("Custom Pixel")
        assertThat(systemReads).isEqualTo(0)
        assertThat(bluetooth.readCalls).isEqualTo(0)
        assertThat(modelReads).isEqualTo(0)
        assertThat(appLabelReads).isEqualTo(0)
    }

    @Test
    fun `policy does not query Settings Global below API 25`() {
        val prefs = AdvertisedDeviceNamePreferences(FakeSharedPreferences())
        var systemReads = 0
        val bluetooth = FakeBluetoothNameGateway(readValue = "Nearby BT")
        val policy =
            AndroidAdvertisedDeviceNamePolicy(
                preferences = prefs,
                sdkInt = 24,
                globalDeviceNameReader =
                    GlobalDeviceNameReader {
                        systemReads += 1
                        "Pixel System"
                    },
                bluetoothNameGateway = bluetooth,
                modelName = { "Pixel Model" },
                appLabel = { "LibreDrop" },
            )

        val resolved = policy.resolve()

        assertThat(resolved).isEqualTo("Nearby BT")
        assertThat(systemReads).isEqualTo(0)
    }

    @Test
    fun `policy does not read bluetooth fallback when system device name resolves`() {
        val prefs = AdvertisedDeviceNamePreferences(FakeSharedPreferences())
        var modelReads = 0
        var appLabelReads = 0
        val bluetooth = FakeBluetoothNameGateway(readValue = "Nearby BT")
        val policy =
            AndroidAdvertisedDeviceNamePolicy(
                preferences = prefs,
                sdkInt = 36,
                globalDeviceNameReader = GlobalDeviceNameReader { "  Pixel 9 Pro  " },
                bluetoothNameGateway = bluetooth,
                modelName = {
                    modelReads += 1
                    "Pixel Model"
                },
                appLabel = {
                    appLabelReads += 1
                    "LibreDrop"
                },
            )

        val resolved = policy.resolve()

        assertThat(resolved).isEqualTo("Pixel 9 Pro")
        assertThat(bluetooth.readCalls).isEqualTo(0)
        assertThat(modelReads).isEqualTo(0)
        assertThat(appLabelReads).isEqualTo(0)
    }

    @Test
    fun `policy skips bluetooth fallback on Android 12 plus without connect permission`() {
        val prefs = AdvertisedDeviceNamePreferences(FakeSharedPreferences())
        val bluetooth = FakeBluetoothNameGateway(hasConnectPermission = false, readValue = "Nearby BT")
        val policy =
            AndroidAdvertisedDeviceNamePolicy(
                preferences = prefs,
                sdkInt = 31,
                globalDeviceNameReader = GlobalDeviceNameReader { null },
                bluetoothNameGateway = bluetooth,
                modelName = { "Pixel Fold" },
                appLabel = { "LibreDrop" },
            )

        val resolved = policy.resolve()

        assertThat(resolved).isEqualTo("Pixel Fold")
        assertThat(bluetooth.readCalls).isEqualTo(0)
    }

    @Test
    fun `policy swallows bluetooth lookup failures and falls back safely`() {
        val prefs = AdvertisedDeviceNamePreferences(FakeSharedPreferences())
        val bluetooth = FakeBluetoothNameGateway(throwOnRead = SecurityException("denied"))
        val policy =
            AndroidAdvertisedDeviceNamePolicy(
                preferences = prefs,
                sdkInt = 31,
                globalDeviceNameReader = GlobalDeviceNameReader { null },
                bluetoothNameGateway = bluetooth,
                modelName = { "Pixel Tablet" },
                appLabel = { "LibreDrop" },
            )

        val resolved = policy.resolve()

        assertThat(resolved).isEqualTo("Pixel Tablet")
        assertThat(bluetooth.readCalls).isEqualTo(1)
    }

    @Test
    fun `policy builds a visible EndpointInfo while preserving stable metadata and tlvs`() {
        val prefs =
            AdvertisedDeviceNamePreferences(FakeSharedPreferences()).apply {
                setCustomName("abcdefghijklmno😀x")
            }
        val previous =
            EndpointInfo(
                version = 1,
                hidden = false,
                deviceType = DeviceType.PHONE,
                reserved = false,
                metadata = ByteArray(EndpointInfo.METADATA_LEN) { index -> index.toByte() },
                deviceName = "Old Name",
                tlvRecords = listOf(TlvRecord(EndpointInfo.TLV_TYPE_VENDOR_ID, byteArrayOf(0x01))),
            )
        val policy =
            AndroidAdvertisedDeviceNamePolicy(
                preferences = prefs,
                sdkInt = 36,
                globalDeviceNameReader = GlobalDeviceNameReader { "System Name" },
                bluetoothNameGateway = FakeBluetoothNameGateway(readValue = "Nearby BT"),
                modelName = { "Pixel Model" },
                appLabel = { "LibreDrop" },
            )

        val endpointInfo = policy.createEndpointInfo(previous)

        assertThat(endpointInfo.hidden).isFalse()
        assertThat(endpointInfo.deviceName).isEqualTo("abcdefghijklmno😀")
        assertThat(endpointInfo.metadata).isEqualTo(previous.metadata)
        assertThat(endpointInfo.tlvRecords).isEqualTo(previous.tlvRecords)
        assertThat(endpointInfo.deviceName!!.toByteArray(StandardCharsets.UTF_8).size)
            .isAtMost(AdvertisedDeviceNames.MAX_DEVICE_NAME_BYTES)
    }
}

private class FakeBluetoothNameGateway(
    private val hasConnectPermission: Boolean = true,
    private val readValue: String? = null,
    private val throwOnRead: Throwable? = null,
) : BluetoothNameGateway {
    var readCalls: Int = 0

    override fun hasConnectPermission(): Boolean = hasConnectPermission

    override fun read(): String? {
        readCalls += 1
        throwOnRead?.let { throw it }
        return readValue
    }
}
