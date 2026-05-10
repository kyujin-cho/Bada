/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import java.io.IOException
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class AndroidNsdRobolectricTest {
    @Test
    fun `browser resolve queue drains after a hanging resolve times out`() =
        runBlocking(Dispatchers.Default) {
            val manager = Shadow.newInstanceOf(NsdManager::class.java)
            val shadowNsd = Shadow.extract<TestShadowNsdManager>(manager)
            shadowNsd.reset()
            val browser =
                AndroidNsdBrowser(
                    nsdManager = manager,
                    resolveTimeoutMillis = 75L,
                )
            val events = Collections.synchronizedList(mutableListOf<NsdBrowserEvent>())
            val job =
                launch {
                    browser
                        .discover(QuickShareMdns.SERVICE_TYPE_NSD)
                        .collect { event -> events += event }
                }

            try {
                waitUntil("discovery listener registered") {
                    shadowNsd.discoveryListenerCount == 1
                }

                shadowNsd.emitServiceFound(nsdServiceInfo(name = "peer-a", port = 0))
                waitUntil("first resolve started") {
                    shadowNsd.resolveCallCount == 1
                }

                shadowNsd.emitServiceFound(nsdServiceInfo(name = "peer-b", port = 0))
                waitUntil("both found events emitted") {
                    events.filterIsInstance<NsdBrowserEvent.Found>().map { it.instanceName } ==
                        listOf("peer-a", "peer-b")
                }
                assertThat(shadowNsd.resolveCallCount).isEqualTo(1)

                waitUntil("first resolve timed out") {
                    events.any {
                        it is NsdBrowserEvent.Error &&
                            it.instanceName == "peer-a" &&
                            it.message.contains("timed out")
                    }
                }
                waitUntil("second resolve started after timeout") {
                    shadowNsd.resolveCallCount == 2
                }

                shadowNsd.resolveByName(
                    requestedName = "peer-b",
                    resolved = nsdServiceInfo(name = "peer-b", port = 42_424),
                )
                waitUntil("second peer resolved") {
                    events.any {
                        it is NsdBrowserEvent.Resolved &&
                            it.instanceName == "peer-b" &&
                            it.port == 42_424
                    }
                }
            } finally {
                job.cancelAndJoin()
            }
        }

    @Test
    fun `registrar returns the platform registered name and unregisters on close`() =
        runBlocking {
            val manager = Shadow.newInstanceOf(NsdManager::class.java)
            val shadowNsd = Shadow.extract<TestShadowNsdManager>(manager)
            shadowNsd.reset()
            shadowNsd.nextRegisteredName.set("Bada (1)")
            val payload = byteArrayOf(0x00, 0x7F, 0x80.toByte(), 0xFF.toByte())
            val registrar = AndroidNsdRegistrar(manager)

            val handle =
                registrar.register(
                    serviceType = QuickShareMdns.SERVICE_TYPE_NSD,
                    instanceName = "Bada",
                    port = 53_601,
                    attributes = mapOf(QuickShareMdns.TXT_KEY_ENDPOINT_INFO to payload),
                )

            assertThat(handle.instanceName).isEqualTo("Bada (1)")
            assertThat(handle.isActive).isTrue()
            assertThat(shadowNsd.registrationRequests).hasSize(1)
            val registeredInfo = shadowNsd.registrationRequests.single().serviceInfo
            assertThat(registeredInfo.serviceName).isEqualTo("Bada")
            assertThat(registeredInfo.serviceType).isEqualTo(QuickShareMdns.SERVICE_TYPE_NSD)
            assertThat(registeredInfo.port).isEqualTo(53_601)
            assertThat(registeredInfo.attributes[QuickShareMdns.TXT_KEY_ENDPOINT_INFO])
                .isEqualTo(payload)

            handle.close()

            assertThat(handle.isActive).isFalse()
            assertThat(shadowNsd.unregisterCallCount.get()).isEqualTo(1)
        }

    @Test
    fun `registrar surfaces platform registration failure as IOException`() =
        runBlocking {
            val manager = Shadow.newInstanceOf(NsdManager::class.java)
            val shadowNsd = Shadow.extract<TestShadowNsdManager>(manager)
            shadowNsd.reset()
            shadowNsd.nextRegistrationFailure.set(NsdManager.FAILURE_ALREADY_ACTIVE)
            val registrar = AndroidNsdRegistrar(manager)

            val failure =
                try {
                    registrar.register(
                        serviceType = QuickShareMdns.SERVICE_TYPE_NSD,
                        instanceName = "Bada",
                        port = 12_345,
                        attributes = emptyMap(),
                    )
                    null
                } catch (e: IOException) {
                    e
                }

            assertThat(failure).isNotNull()
            assertThat(failure?.message).contains("errorCode=${NsdManager.FAILURE_ALREADY_ACTIVE}")
        }

    @Test
    fun `pre API 33 reflective attribute setter unwraps platform validation failures`() =
        runBlocking {
            val manager = Shadow.newInstanceOf(NsdManager::class.java)
            val shadowNsd = Shadow.extract<TestShadowNsdManager>(manager)
            shadowNsd.reset()
            val registrar = AndroidNsdRegistrar(manager)

            val failure =
                try {
                    registrar.register(
                        serviceType = QuickShareMdns.SERVICE_TYPE_NSD,
                        instanceName = "Bada",
                        port = 12_345,
                        attributes = mapOf("bad=key" to byteArrayOf(1)),
                    )
                    null
                } catch (e: IllegalStateException) {
                    e
                }

            assertThat(failure).isNotNull()
            assertThat(failure?.message).contains("reflective invoke failed")
            assertThat(failure?.message).contains("bad=key")
        }

    private suspend fun waitUntil(
        description: String,
        predicate: () -> Boolean,
    ) {
        try {
            withTimeout(WAIT_TIMEOUT_MILLIS) {
                while (!predicate()) {
                    kotlinx.coroutines.delay(WAIT_POLL_MILLIS)
                }
            }
        } catch (_: TimeoutCancellationException) {
            error("Timed out waiting for $description")
        }
    }
}

private const val WAIT_TIMEOUT_MILLIS: Long = 2_000L
private const val WAIT_POLL_MILLIS: Long = 10L

private fun nsdServiceInfo(
    name: String,
    port: Int,
    attributes: Map<String, ByteArray> = emptyMap(),
): NsdServiceInfo =
    NsdServiceInfo().apply {
        serviceName = name
        serviceType = QuickShareMdns.SERVICE_TYPE_NSD
        this.port = port
        @Suppress("DEPRECATION")
        host = InetAddress.getByName("192.0.2.44")
        AndroidNsdRegistrar.applyAttributes(this, attributes)
    }

@Implements(NsdManager::class)
internal class TestShadowNsdManager {
    private val discoveryListeners = CopyOnWriteArrayList<NsdManager.DiscoveryListener>()
    private val pendingResolves = CopyOnWriteArrayList<ResolveRequest>()
    private val resolveRequests = CopyOnWriteArrayList<ResolveRequest>()

    val nextRegisteredName: AtomicReference<String?> = AtomicReference(null)
    val nextRegistrationFailure: AtomicReference<Int?> = AtomicReference(null)
    val registrationRequests: CopyOnWriteArrayList<RegistrationRequest> = CopyOnWriteArrayList()
    val unregisterCallCount: AtomicInteger = AtomicInteger(0)

    val discoveryListenerCount: Int
        get() = discoveryListeners.size

    val resolveCallCount: Int
        get() = resolveRequests.size

    fun reset() {
        discoveryListeners.clear()
        pendingResolves.clear()
        resolveRequests.clear()
        nextRegisteredName.set(null)
        nextRegistrationFailure.set(null)
        registrationRequests.clear()
        unregisterCallCount.set(0)
    }

    fun emitServiceFound(serviceInfo: NsdServiceInfo) {
        for (listener in discoveryListeners) {
            listener.onServiceFound(serviceInfo)
        }
    }

    fun resolveByName(
        requestedName: String,
        resolved: NsdServiceInfo,
    ) {
        val request =
            pendingResolves
                .firstOrNull { it.serviceInfo.serviceName == requestedName }
                ?: error("No pending resolve for $requestedName")
        pendingResolves.remove(request)
        request.listener.onServiceResolved(resolved)
    }

    @Implementation
    fun discoverServices(
        serviceType: String,
        protocolType: Int,
        listener: NsdManager.DiscoveryListener,
    ) {
        check(protocolType == NsdManager.PROTOCOL_DNS_SD)
        discoveryListeners += listener
        listener.onDiscoveryStarted(serviceType)
    }

    @Implementation
    fun stopServiceDiscovery(listener: NsdManager.DiscoveryListener) {
        discoveryListeners.remove(listener)
        listener.onDiscoveryStopped(QuickShareMdns.SERVICE_TYPE_NSD)
    }

    @Implementation
    fun resolveService(
        serviceInfo: NsdServiceInfo,
        listener: NsdManager.ResolveListener,
    ) {
        val request = ResolveRequest(serviceInfo, listener)
        resolveRequests += request
        pendingResolves += request
    }

    @Implementation
    fun registerService(
        serviceInfo: NsdServiceInfo,
        protocolType: Int,
        listener: NsdManager.RegistrationListener,
    ) {
        check(protocolType == NsdManager.PROTOCOL_DNS_SD)
        registrationRequests += RegistrationRequest(serviceInfo, protocolType, listener)
        val failureCode = nextRegistrationFailure.getAndSet(null)
        if (failureCode != null) {
            listener.onRegistrationFailed(serviceInfo, failureCode)
            return
        }

        val callbackInfo = serviceInfo.copyForCallback()
        nextRegisteredName.getAndSet(null)?.let { callbackInfo.serviceName = it }
        listener.onServiceRegistered(callbackInfo)
    }

    @Implementation
    fun unregisterService(listener: NsdManager.RegistrationListener) {
        unregisterCallCount.incrementAndGet()
        val serviceInfo =
            registrationRequests
                .lastOrNull { it.listener === listener }
                ?.serviceInfo
                ?: NsdServiceInfo()
        listener.onServiceUnregistered(serviceInfo)
    }

    data class ResolveRequest(
        val serviceInfo: NsdServiceInfo,
        val listener: NsdManager.ResolveListener,
    )

    data class RegistrationRequest(
        val serviceInfo: NsdServiceInfo,
        val protocolType: Int,
        val listener: NsdManager.RegistrationListener,
    )

    private fun NsdServiceInfo.copyForCallback(): NsdServiceInfo =
        NsdServiceInfo().also { copy ->
            copy.serviceName = serviceName
            copy.serviceType = serviceType
            copy.port = port
            @Suppress("DEPRECATION")
            copy.host = host
            AndroidNsdRegistrar.applyAttributes(copy, attributes.orEmpty())
        }
}
