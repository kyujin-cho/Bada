/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
@file:android.annotation.SuppressLint("MissingPermission")

package dev.bluehouse.bada.discovery.bootstrap

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import dev.bluehouse.bada.discovery.medium.BluetoothRfcommTransport
import dev.bluehouse.bada.protocol.endpoint.BluetoothDeviceName
import dev.bluehouse.bada.protocol.endpoint.EndpointInfo
import dev.bluehouse.bada.protocol.endpoint.NearbyServiceId
import dev.bluehouse.bada.protocol.transport.ConnectedTransport
import dev.bluehouse.bada.protocol.transport.InitialControlServer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Bluetooth Classic initial-control bootstrap for stock Nearby senders.
 *
 * The Nearby Connections Bluetooth discovery path publishes endpoint identity
 * in the local adapter name, then listens for an insecure RFCOMM connection on
 * a service UUID derived from the Nearby service id. Once a sender connects,
 * the resulting byte stream is injected into the same inbound protocol stack
 * used by the Wi-Fi LAN TCP listener.
 *
 * This server is visibility-gated through the [InitialControlServer]
 * lifecycle: while the receiver is published, the adapter name is temporarily
 * replaced with a Nearby-compatible payload and restored on stop if no one
 * else changed it in the meantime.
 */
public class BluetoothClassicBootstrapServer internal constructor(
    private val bluetooth: BluetoothClassicBootstrapIo,
    private val endpointIdProvider: () -> ByteArray,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val serviceName: String = NearbyServiceId.VALUE,
    private val serviceUuid: UUID = NearbyServiceId.bluetoothServiceUuid,
) : InitialControlServer {
    public constructor(
        context: Context,
        endpointIdProvider: () -> ByteArray,
    ) : this(
        bluetooth = DefaultBluetoothClassicBootstrapIo(context.applicationContext),
        endpointIdProvider = endpointIdProvider,
    )

    private val active: AtomicReference<ActiveState?> = AtomicReference(null)
    private val lifecycleLock: Any = Any()

    override val isActive: Boolean
        get() = active.get() != null

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    override fun start(
        endpointInfo: EndpointInfo,
        acceptTransport: (ConnectedTransport) -> Unit,
    ): Boolean =
        synchronized(lifecycleLock) {
            startLocked(endpointInfo, acceptTransport)
        }

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun startLocked(
        endpointInfo: EndpointInfo,
        acceptTransport: (ConnectedTransport) -> Unit,
    ): Boolean {
        if (isActive) return true
        if (!bluetooth.isAvailable()) {
            Log.i(TAG, "Bluetooth Classic bootstrap unavailable")
            return false
        }

        val advertisedName =
            try {
                BluetoothDeviceName.encode(
                    endpointId = endpointIdProvider(),
                    endpointInfo = endpointInfo,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Cannot encode Bluetooth device-name advertisement", t)
                return false
            }

        val originalName = runCatching { bluetooth.currentName() }.getOrNull()
        val nameSet =
            try {
                bluetooth.setName(advertisedName)
            } catch (t: Throwable) {
                Log.w(TAG, "Cannot set Bluetooth adapter name", t)
                false
            }
        if (!nameSet) {
            Log.w(TAG, "Bluetooth adapter rejected Nearby device-name advertisement")
            return false
        }

        val server =
            try {
                bluetooth.listen(serviceName, serviceUuid)
            } catch (t: Throwable) {
                Log.w(TAG, "Cannot open Bluetooth Classic bootstrap RFCOMM listener", t)
                restoreName(originalName, advertisedName)
                return false
            }
        if (server == null) {
            restoreName(originalName, advertisedName)
            return false
        }

        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val state =
            ActiveState(
                originalName = originalName,
                advertisedName = advertisedName,
                server = server,
                scope = scope,
            )
        if (!active.compareAndSet(null, state)) {
            runCatching { server.close() }
            scope.cancel()
            restoreName(originalName, advertisedName)
            return true
        }

        scope.launch {
            runAcceptLoop(state, acceptTransport)
        }
        Log.i(TAG, "Bluetooth Classic bootstrap active serviceUuid=$serviceUuid")
        return true
    }

    override fun stop() {
        synchronized(lifecycleLock) {
            stopLocked()
        }
    }

    private fun stopLocked() {
        val state = active.getAndSet(null) ?: return
        runCatching { state.server.close() }
        state.scope.cancel()
        restoreName(state.originalName, state.advertisedName)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun runAcceptLoop(
        state: ActiveState,
        acceptTransport: (ConnectedTransport) -> Unit,
    ) {
        while (active.get() === state) {
            val transport =
                try {
                    state.server.accept()
                } catch (io: IOException) {
                    if (active.get() === state) {
                        Log.w(TAG, "Bluetooth Classic bootstrap accept failed", io)
                        stop()
                    }
                    return
                } catch (t: Throwable) {
                    if (active.get() === state) {
                        Log.w(TAG, "Bluetooth Classic bootstrap accept crashed", t)
                        stop()
                    }
                    return
                } ?: continue
            acceptTransport(transport)
        }
    }

    private fun restoreName(
        originalName: String?,
        advertisedName: String,
    ) {
        if (originalName == null) return
        runCatching {
            if (bluetooth.currentName() == advertisedName) {
                bluetooth.setName(originalName)
            }
        }
    }

    private data class ActiveState(
        val originalName: String?,
        val advertisedName: String,
        val server: BluetoothClassicServerSocket,
        val scope: CoroutineScope,
    )

    public companion object {
        private const val TAG: String = "LibreDropBtBootstrap"
    }
}

internal interface BluetoothClassicBootstrapIo {
    fun isAvailable(): Boolean

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun currentName(): String?

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun setName(name: String): Boolean

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun listen(
        name: String,
        uuid: UUID,
    ): BluetoothClassicServerSocket?
}

internal interface BluetoothClassicServerSocket : Closeable {
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun accept(): ConnectedTransport?
}

private class DefaultBluetoothClassicBootstrapIo(
    private val context: Context,
) : BluetoothClassicBootstrapIo {
    @Suppress("ReturnCount")
    override fun isAvailable(): Boolean {
        val pm = context.packageManager
        if (!pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) return false
        if (!hasConnectPermission()) return false
        val adapter = adapter() ?: return false
        return adapter.isEnabled
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun currentName(): String? = adapter()?.name

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun setName(name: String): Boolean {
        val adapter = adapter() ?: return false
        return adapter.setName(name)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun listen(
        name: String,
        uuid: UUID,
    ): BluetoothClassicServerSocket? {
        val adapter = adapter() ?: return null
        return AndroidBluetoothClassicServerSocket(
            adapter.listenUsingInsecureRfcommWithServiceRecord(name, uuid),
        )
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

private class AndroidBluetoothClassicServerSocket(
    private val server: BluetoothServerSocket,
) : BluetoothClassicServerSocket {
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun accept(): ConnectedTransport? = server.accept()?.let(::BluetoothRfcommTransport)

    override fun close() {
        server.closeQuietly()
    }
}

private fun Closeable.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
        // Cleanup only.
    }
}
