/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
@file:android.annotation.SuppressLint("MissingPermission")
@file:Suppress("DEPRECATION", "MagicNumber", "ReturnCount", "TooGenericExceptionCaught")

package dev.bluehouse.bada.discovery.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import dev.bluehouse.bada.discovery.bootstrap.BleGattInitialControlServer
import dev.bluehouse.bada.protocol.endpoint.BleAdvertisement
import dev.bluehouse.bada.protocol.endpoint.BleServiceData
import dev.bluehouse.bada.protocol.endpoint.EndpointInfo
import dev.bluehouse.bada.protocol.endpoint.NearbyServiceId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Probes Nearby BLE v2 GATT bootstrap service and reads any
 * advertisement slots referenced by a `0x55` advertisement header.
 */
internal class BleGattAdvertisementReader(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun read(
        macAddress: String,
        slotCount: Int,
    ): ReadResult =
        withContext(dispatcher) {
            if (!isAvailable()) return@withContext ReadResult.Empty
            val adapter =
                context
                    .applicationContext
                    .getSystemService(BluetoothManager::class.java)
                    ?.adapter
                    ?: return@withContext ReadResult.Empty
            cancelDiscoveryBeforeGattConnect(adapter, macAddress)
            val device =
                try {
                    adapter.getRemoteDevice(macAddress)
                } catch (badAddress: IllegalArgumentException) {
                    Log.w(TAG, "invalid BLE GATT advertisement address=$macAddress", badAddress)
                    return@withContext ReadResult.Empty
                }
            val callback = Callback(slotCount.coerceAtMost(MAX_SLOT_COUNT))
            val gatt =
                device.connectGatt(
                    context.applicationContext,
                    false,
                    callback,
                    BluetoothDevice.TRANSPORT_LE,
                ) ?: return@withContext ReadResult.Empty
            callback.attach(gatt)
            try {
                val result = withTimeoutOrNull(READ_TIMEOUT_MILLIS) { callback.result.await() }
                if (result == null) {
                    Log.w(TAG, "slot-read timed out address=$macAddress slots=$slotCount")
                    ReadResult.Empty
                } else {
                    result
                }
            } finally {
                runCatching { gatt.disconnect() }
                runCatching { gatt.close() }
            }
        }

    private fun cancelDiscoveryBeforeGattConnect(
        adapter: android.bluetooth.BluetoothAdapter,
        macAddress: String,
    ) {
        if (!adapter.isDiscovering) return
        val cancelled =
            runCatching { adapter.cancelDiscovery() }
                .onFailure { e ->
                    Log.w(TAG, "slot-read cancelDiscovery failed address=$macAddress", e)
                }.getOrDefault(false)
        Log.i(TAG, "slot-read cancelDiscovery before GATT connect address=$macAddress cancelled=$cancelled")
    }

    private fun isAvailable(): Boolean {
        val appContext = context.applicationContext
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasConnectPermission(appContext)) return false
        val manager = appContext.getSystemService(BluetoothManager::class.java) ?: return false
        val adapter = manager.adapter ?: return false
        return adapter.isEnabled
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun hasConnectPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED

    private class Callback(
        private val slotCount: Int,
    ) : BluetoothGattCallback() {
        val result = CompletableDeferred<ReadResult>()
        private val rawSlots = mutableListOf<ByteArray>()
        private var gatt: BluetoothGatt? = null
        private var slots: List<BluetoothGattCharacteristic> = emptyList()
        private var nextSlotIndex: Int = 0
        private var socketServiceAvailable: Boolean = false

        fun attach(gatt: BluetoothGatt) {
            this.gatt = gatt
        }

        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int,
        ) {
            Log.i(TAG, "slot-read state status=$status newState=$newState device=${gatt.device.safeAddress()}")
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                if (!gatt.discoverServices()) {
                    Log.w(TAG, "slot-read discoverServices returned false device=${gatt.device.safeAddress()}")
                    complete()
                }
            } else {
                complete()
            }
        }

        override fun onServicesDiscovered(
            gatt: BluetoothGatt,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "slot-read service discovery failed status=$status")
                complete()
                return
            }
            val service = gatt.getService(BleGattInitialControlServer.SERVICE_UUID)
            if (service == null) {
                Log.w(
                    TAG,
                    "slot-read service missing services=${gatt.describeServices()}",
                )
                complete()
                return
            }
            socketServiceAvailable =
                service.getCharacteristic(BleGattInitialControlServer.TO_PERIPHERAL_UUID) != null &&
                service.getCharacteristic(BleGattInitialControlServer.FROM_PERIPHERAL_UUID) != null
            val characteristicUuids =
                service.characteristics.joinToString(",") { characteristic ->
                    characteristic.uuid.toString()
                }
            slots =
                (0 until slotCount)
                    .mapNotNull { slot ->
                        service.getCharacteristic(BleGattInitialControlServer.advertisementSlotUuid(slot))
                    }
            Log.i(
                TAG,
                "slot-read service discovered socket=$socketServiceAvailable " +
                    "slots=${slots.size}/$slotCount chars=$characteristicUuids",
            )
            readNextSlot()
        }

        @Deprecated("Android calls this overload before API 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            handleCharacteristicRead(characteristic.value ?: ByteArray(0), status)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            handleCharacteristicRead(value, status)
        }

        private fun handleCharacteristicRead(
            value: ByteArray,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS && value.isNotEmpty()) {
                rawSlots += value.copyOf()
                Log.i(TAG, "slot-read value len=${value.size} header=${value.firstByteHex()}")
            } else {
                Log.i(TAG, "slot-read empty or failed status=$status")
            }
            nextSlotIndex += 1
            readNextSlot()
        }

        private fun readNextSlot() {
            val openGatt = gatt
            if (openGatt == null || nextSlotIndex >= slots.size) {
                complete()
                return
            }
            if (!openGatt.readCharacteristic(slots[nextSlotIndex])) {
                Log.w(TAG, "readCharacteristic returned false slot=$nextSlotIndex")
                nextSlotIndex += 1
                readNextSlot()
            }
        }

        private fun complete() {
            if (!result.isCompleted) {
                result.complete(
                    ReadResult(
                        socketServiceAvailable = socketServiceAvailable,
                        slotAdvertisements = rawSlots.mapNotNull(::parseSlotAdvertisement),
                    ),
                )
            }
        }

        private fun parseSlotAdvertisement(raw: ByteArray): SlotAdvertisement? {
            val advertisement = BleAdvertisement.parse(raw)
            val serviceData =
                when {
                    advertisement?.serviceIdHash?.contentEquals(NearbyServiceId.hashPrefix) == true ->
                        advertisement.data

                    advertisement?.fastAdvertisement == true ->
                        raw

                    advertisement == null ->
                        raw

                    else ->
                        return null
                }
            val endpoint = BleServiceData.parse(serviceData) ?: return null
            return SlotAdvertisement(
                endpointId = String(endpoint.endpointId, Charsets.US_ASCII),
                endpointInfo = endpoint.endpointInfo,
                l2capPsm = advertisement?.psm?.takeIf { it > 0 },
            )
        }
    }

    data class SlotAdvertisement(
        val endpointId: String,
        val endpointInfo: EndpointInfo,
        val l2capPsm: Int?,
    )

    data class ReadResult(
        val socketServiceAvailable: Boolean,
        val slotAdvertisements: List<SlotAdvertisement>,
    ) {
        companion object {
            val Empty: ReadResult =
                ReadResult(
                    socketServiceAvailable = false,
                    slotAdvertisements = emptyList(),
                )
        }
    }

    private companion object {
        private const val TAG: String = "BadaBleFastScan"
        private const val MAX_SLOT_COUNT: Int = 16
        private const val READ_TIMEOUT_MILLIS: Long = 7_000L
    }
}

private fun BluetoothDevice.safeAddress(): String = runCatching { address }.getOrNull() ?: "<unknown>"

private fun BluetoothGatt.describeServices(): String =
    services.joinToString(",") { service ->
        val characteristics = service.characteristics.joinToString("|") { it.uuid.toString() }
        "${service.uuid}[$characteristics]"
    }

private fun ByteArray.firstByteHex(): String = firstOrNull()?.let { "%02x".format(it) } ?: "--"
