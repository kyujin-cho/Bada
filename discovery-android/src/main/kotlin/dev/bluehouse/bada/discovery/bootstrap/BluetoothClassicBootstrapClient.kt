/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
@file:Suppress("ReturnCount")

@file:android.annotation.SuppressLint("MissingPermission")

package dev.bluehouse.bada.discovery.bootstrap

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import dev.bluehouse.bada.protocol.endpoint.NearbyServiceId
import dev.bluehouse.bada.protocol.medium.Medium
import dev.bluehouse.bada.protocol.transport.ConnectedTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Sender-side Bluetooth Classic initial-control bootstrap.
 *
 * Connects to the stock Nearby / Quick Share RFCOMM service advertised
 * under [NearbyServiceId.bluetoothServiceUuid] and returns the resulting
 * byte stream as a [ConnectedTransport] so the existing sender protocol
 * stack can run unchanged above it.
 */
public class BluetoothClassicBootstrapClient internal constructor(
    private val bluetooth: BluetoothClassicBootstrapClientIo,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val serviceUuid: UUID = NearbyServiceId.bluetoothServiceUuid,
) {
    public constructor(context: Context) : this(
        bluetooth = DefaultBluetoothClassicBootstrapClientIo(context.applicationContext),
    )

    private val pendingSocket: AtomicReference<BluetoothClassicClientSocket?> = AtomicReference(null)

    /**
     * Open an insecure RFCOMM socket to [macAddress] using the stock Nearby
     * service UUID. Returns `null` when Bluetooth is unavailable, the
     * permission gate is missing, or the connect fails.
     */
    @Suppress("TooGenericExceptionCaught")
    public suspend fun connect(macAddress: String): ConnectedTransport? =
        withContext(dispatcher) {
            if (!bluetooth.isAvailable()) {
                Log.i(TAG, "Bluetooth Classic bootstrap connect unavailable")
                return@withContext null
            }
            val socket =
                try {
                    bluetooth.openSocket(macAddress, serviceUuid)
                } catch (t: Throwable) {
                    Log.w(TAG, "Cannot open Bluetooth Classic bootstrap socket", t)
                    null
                } ?: return@withContext null
            if (!pendingSocket.compareAndSet(null, socket)) {
                runCatching { socket.close() }
                Log.w(TAG, "Bluetooth Classic bootstrap connect already in progress")
                return@withContext null
            }
            try {
                socket.connect()
                BluetoothClassicConnectedTransport(socket)
            } catch (t: Throwable) {
                Log.w(TAG, "Bluetooth Classic bootstrap connect failed", t)
                runCatching { socket.close() }
                null
            } finally {
                pendingSocket.compareAndSet(socket, null)
            }
        }

    /**
     * Best-effort cancellation for a pending RFCOMM connect. Closing the
     * socket is the only reliable way to unblock `BluetoothSocket.connect()`.
     */
    public fun cancelPendingConnect() {
        pendingSocket.getAndSet(null)?.let { socket ->
            runCatching { socket.close() }
        }
    }

    public companion object {
        private const val TAG: String = "BadaBtBootstrap"
    }
}

internal interface BluetoothClassicBootstrapClientIo {
    fun isAvailable(): Boolean

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun openSocket(
        macAddress: String,
        uuid: UUID,
    ): BluetoothClassicClientSocket?
}

internal interface BluetoothClassicClientSocket : Closeable {
    val inputStream: InputStream
    val outputStream: OutputStream

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connect()
}

private class DefaultBluetoothClassicBootstrapClientIo(
    private val context: Context,
) : BluetoothClassicBootstrapClientIo {
    @Suppress("ReturnCount")
    override fun isAvailable(): Boolean {
        val pm = context.packageManager
        if (!pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) return false
        if (!hasConnectPermission()) return false
        val adapter = adapter() ?: return false
        return adapter.isEnabled
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun openSocket(
        macAddress: String,
        uuid: UUID,
    ): BluetoothClassicClientSocket? {
        val adapter = adapter() ?: return null
        runCatching { adapter.cancelDiscovery() }
        val device = adapter.getRemoteDevice(macAddress) ?: return null
        val socket = device.createInsecureRfcommSocketToServiceRecord(uuid) ?: return null
        return AndroidBluetoothClassicClientSocket(socket)
    }

    private fun adapter(): BluetoothAdapter? {
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return null
        return manager.adapter
    }

    private fun hasConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return checkSPermission()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun checkSPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
}

private class AndroidBluetoothClassicClientSocket(
    private val socket: BluetoothSocket,
) : BluetoothClassicClientSocket {
    override val inputStream: InputStream
        get() = socket.inputStream

    override val outputStream: OutputStream
        get() = socket.outputStream

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun connect() {
        socket.connect()
    }

    override fun close() {
        closeQuietly(socket)
    }
}

private class BluetoothClassicConnectedTransport(
    private val socket: BluetoothClassicClientSocket,
) : ConnectedTransport {
    override val medium: Medium = Medium.BLUETOOTH

    override val inputStream: InputStream
        get() = socket.inputStream

    override val outputStream: OutputStream
        get() = socket.outputStream

    override fun close() {
        closeQuietly(socket)
    }
}

private fun closeQuietly(closeable: Closeable) {
    try {
        closeable.close()
    } catch (_: IOException) {
        // Cleanup only.
    }
}
