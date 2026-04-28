/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory fake of Android's mDNS responder, shared between the
 * test-side [NsdRegistrar] and [NsdBrowser]. Mirrors enough of the
 * platform's behaviour to drive [Discovery] end-to-end on a plain JVM:
 *
 *  - Auto-suffixes the instance name on collision, exactly as
 *    `NsdManager.registerService` does (`name`, `name (1)`, `name (2)`, …).
 *  - Emits `Found` then `Resolved` events to every active browser when a
 *    new registration appears.
 *  - Emits `Lost` events when a registration is closed.
 *  - Preserves the binary TXT-record bytes verbatim — the production
 *    code's contract is that `attributes: Map<String, ByteArray>`
 *    round-trips byte-for-byte from publish to resolve.
 *
 * The fake is deliberately simple: no DNS-SD wire encoding, no real
 * sockets. The on-the-wire interop tests live in the manual on-device
 * runbook (`docs/testing/`).
 */
internal class FakeNsd(
    internal val advertiseAddress: InetAddress = InetAddress.getByName("192.168.1.42"),
) {
    internal data class PublishedService(
        val serviceType: String,
        val instanceName: String,
        val port: Int,
        val attributes: Map<String, ByteArray>,
        val active: AtomicBoolean = AtomicBoolean(true),
    )

    private val services = ConcurrentHashMap<String, PublishedService>()
    private val browsers = ConcurrentHashMap.newKeySet<(NsdBrowserEvent) -> Unit>()

    /** Number of currently-active discoveries. */
    val activeBrowserCount: Int
        get() = browsers.size

    /** Snapshot of currently-published service names. */
    fun publishedNames(): List<String> = services.values.map { it.instanceName }

    val registrar: NsdRegistrar =
        object : NsdRegistrar {
            override suspend fun register(
                serviceType: String,
                instanceName: String,
                port: Int,
                attributes: Map<String, ByteArray>,
            ): NsdRegistrationHandle {
                val actualName = pickAvailableName(instanceName)
                val service =
                    PublishedService(
                        serviceType = serviceType,
                        instanceName = actualName,
                        port = port,
                        // Defensive copies — keep the test's
                        // round-trip assertions independent of caller
                        // mutation.
                        attributes = attributes.mapValues { (_, v) -> v.copyOf() },
                    )
                services[actualName] = service
                broadcastPublish(service)
                return FakeRegistrationHandle(service, this@FakeNsd)
            }
        }

    val browser: NsdBrowser =
        object : NsdBrowser {
            override fun discover(serviceType: String): Flow<NsdBrowserEvent> =
                callbackFlow {
                    val sink: (NsdBrowserEvent) -> Unit = { trySend(it) }
                    browsers += sink
                    // Replay snapshot of services already published.
                    services.values
                        .filter { it.active.get() && it.serviceType == serviceType }
                        .forEach { svc ->
                            sink(NsdBrowserEvent.Found(svc.instanceName))
                            sink(
                                NsdBrowserEvent.Resolved(
                                    instanceName = svc.instanceName,
                                    addresses = listOf(advertiseAddress),
                                    port = svc.port,
                                    attributes =
                                        svc.attributes.mapValues { (_, v) -> v.copyOf() },
                                ),
                            )
                        }
                    awaitClose { browsers.remove(sink) }
                }
        }

    private fun pickAvailableName(requested: String): String {
        if (services[requested] == null) return requested
        var suffix = 1
        while (true) {
            val candidate = "$requested ($suffix)"
            if (services[candidate] == null) return candidate
            suffix++
        }
    }

    private fun broadcastPublish(service: PublishedService) {
        for (sink in browsers) {
            sink(NsdBrowserEvent.Found(service.instanceName))
            sink(
                NsdBrowserEvent.Resolved(
                    instanceName = service.instanceName,
                    addresses = listOf(advertiseAddress),
                    port = service.port,
                    attributes = service.attributes.mapValues { (_, v) -> v.copyOf() },
                ),
            )
        }
    }

    internal fun unpublish(service: PublishedService) {
        if (!service.active.compareAndSet(true, false)) return
        services.remove(service.instanceName)
        for (sink in browsers) {
            sink(NsdBrowserEvent.Lost(service.instanceName))
        }
    }

    private class FakeRegistrationHandle(
        private val service: PublishedService,
        private val parent: FakeNsd,
    ) : NsdRegistrationHandle {
        private val active = AtomicBoolean(true)
        override val instanceName: String
            get() = service.instanceName
        override val hostAddress: InetAddress?
            get() = parent.advertiseAddress
        override val isActive: Boolean
            get() = active.get()

        override fun close() {
            if (active.compareAndSet(true, false)) {
                parent.unpublish(service)
            }
        }
    }
}

/**
 * Counter-only [NsdRegistrar] for tests that just need to observe
 * register-arity without simulating a full publish/browse path.
 */
internal class CountingNsdRegistrar(
    private val onRegister: ((String, Int, Map<String, ByteArray>) -> Unit)? = null,
) : NsdRegistrar {
    val registerCalls = AtomicInteger(0)
    val lastRegisteredAttrs = AtomicReference<Map<String, ByteArray>>(emptyMap())

    override suspend fun register(
        serviceType: String,
        instanceName: String,
        port: Int,
        attributes: Map<String, ByteArray>,
    ): NsdRegistrationHandle {
        registerCalls.incrementAndGet()
        lastRegisteredAttrs.set(attributes.mapValues { (_, v) -> v.copyOf() })
        onRegister?.invoke(instanceName, port, attributes)
        val active = AtomicBoolean(true)
        return object : NsdRegistrationHandle {
            override val instanceName: String = instanceName
            override val hostAddress: InetAddress? = null
            override val isActive: Boolean
                get() = active.get()

            override fun close() {
                active.set(false)
            }
        }
    }
}

/**
 * No-op [NsdBrowser] — never emits. Lets advertise-focused tests pass a
 * browser without standing up the full fake.
 */
internal object NoopNsdBrowser : NsdBrowser {
    override fun discover(serviceType: String): Flow<NsdBrowserEvent> =
        callbackFlow {
            awaitClose { /* no-op */ }
        }
}
