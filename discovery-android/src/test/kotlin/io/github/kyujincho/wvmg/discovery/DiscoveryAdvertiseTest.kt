/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery

import com.google.common.truth.Truth.assertThat
import io.github.kyujincho.wvmg.protocol.endpoint.Base64Url
import io.github.kyujincho.wvmg.protocol.endpoint.DeviceType
import io.github.kyujincho.wvmg.protocol.endpoint.EndpointInfo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicInteger

/**
 * Smoke tests for [Discovery.advertise] running on the host JVM.
 *
 * These tests inject a [FakeNsd] (or a [CountingNsdRegistrar]) so the
 * advertise path can be exercised without a real Android `NsdManager`.
 * They cover the publish path's contract — port validation, instance
 * name surfacing (including auto-suffix on collision), TXT round-trip
 * including binary payloads, network-change re-registration, and
 * lifecycle idempotency.
 */
class DiscoveryAdvertiseTest {
    @Test
    fun `port outside 1 to 65535 is rejected`() {
        val discovery =
            Discovery.forTesting(
                registrar = CountingNsdRegistrar(),
                browser = NoopNsdBrowser,
            )
        assertThrows<IllegalArgumentException> {
            discovery.advertise(sampleEndpointInfo(), port = 0)
        }
        assertThrows<IllegalArgumentException> {
            discovery.advertise(sampleEndpointInfo(), port = 70_000)
        }
    }

    @Test
    fun `advertise publishes through the registrar and surfaces a usable handle`() {
        val nsd = FakeNsd()
        val discovery =
            Discovery.forTesting(
                registrar = nsd.registrar,
                browser = nsd.browser,
            )

        val endpointInfo = sampleEndpointInfo()
        val handle = discovery.advertise(endpointInfo, port = 12_345)
        try {
            assertThat(handle.instanceName).isNotEmpty()
            assertThat(handle.port).isEqualTo(12_345)
            assertThat(handle.isActive).isTrue()
            assertThat(nsd.publishedNames()).contains(handle.instanceName)
        } finally {
            handle.close()
        }

        assertThat(nsd.publishedNames()).doesNotContain(handle.instanceName)
        assertThat(handle.isActive).isFalse()
    }

    @Test
    fun `closing the advertise handle is idempotent`() {
        val nsd = FakeNsd()
        val discovery =
            Discovery.forTesting(
                registrar = nsd.registrar,
                browser = nsd.browser,
            )

        val handle = discovery.advertise(sampleEndpointInfo(), port = 34_567)
        handle.close()
        handle.close() // second close must not double-unregister.
        assertThat(handle.isActive).isFalse()
        assertThat(nsd.publishedNames()).isEmpty()
    }

    @Test
    fun `instance-name collision surfaces the platform auto-suffix`() {
        val nsd = FakeNsd()
        val firstDiscovery =
            Discovery.forTesting(
                registrar = nsd.registrar,
                browser = nsd.browser,
            )
        val secondDiscovery =
            Discovery.forTesting(
                registrar = nsd.registrar,
                browser = nsd.browser,
            )

        // Force an exact instance-name collision by registering the
        // same name twice. The fake mirrors NsdManager's auto-suffix
        // behaviour ("name", "name (1)", …); the second handle must
        // observe the suffixed name.
        val first = firstDiscovery.advertise(sampleEndpointInfo(), port = 22_001)
        val firstName = first.instanceName

        // Pin the second registrar's "requested" name to the first one
        // so we deterministically hit the collision path.
        val pinningRegistrar =
            object : NsdRegistrar by nsd.registrar {
                override suspend fun register(
                    serviceType: String,
                    instanceName: String,
                    port: Int,
                    attributes: Map<String, ByteArray>,
                ): NsdRegistrationHandle = nsd.registrar.register(serviceType, firstName, port, attributes)
            }
        val pinned =
            Discovery.forTesting(
                registrar = pinningRegistrar,
                browser = nsd.browser,
            )
        val second = pinned.advertise(sampleEndpointInfo(), port = 22_002)
        try {
            assertThat(second.instanceName).isNotEqualTo(firstName)
            assertThat(second.instanceName).startsWith(firstName)
        } finally {
            second.close()
            first.close()
            // suppress unused warnings while keeping discovery refs alive
            secondDiscovery.snapshot()
        }
    }

    @Test
    fun `network change triggers re-registration through NsdRegistrar`() {
        val opened = AtomicInteger(0)
        val countingRegistrar =
            CountingNsdRegistrar(onRegister = { _, _, _ -> opened.incrementAndGet() })
        val fakeWatcher = FakeNetworkWatcher()
        val factory =
            object : NetworkWatcherFactory {
                override fun create(onChanged: () -> Unit): NetworkWatcher {
                    fakeWatcher.onChanged = onChanged
                    return fakeWatcher
                }
            }
        val discovery =
            Discovery.forTesting(
                registrar = countingRegistrar,
                browser = NoopNsdBrowser,
                networkWatcherFactory = factory,
            )
        val handle = discovery.advertise(sampleEndpointInfo(), port = 45_678)
        try {
            assertThat(opened.get()).isEqualTo(1)
            assertThat(fakeWatcher.started).isTrue()

            fakeWatcher.fire()
            assertThat(opened.get()).isEqualTo(2)

            fakeWatcher.fire()
            assertThat(opened.get()).isEqualTo(3)
        } finally {
            handle.close()
        }
        assertThat(fakeWatcher.stopped).isTrue()
    }

    @Test
    fun `endpoint info is base64-url-encoded under TXT key n`() {
        // Quick Share's `n=` TXT key carries the URL-safe-base64 (no
        // padding) ASCII encoding of the packed binary EndpointInfo —
        // not the raw bytes. google/nearby's `WifiLanServiceInfo` calls
        // `Base64Utils::Decode(txt_endpoint_info_name)` before parsing,
        // so a Galaxy peer drops mDNS-only records that publish the
        // bytes raw (`EndpointParsingFailure` on the GMS side).
        // Pin the round-trip so we don't regress to raw or to a UTF-8
        // re-encoding.
        val countingRegistrar = CountingNsdRegistrar()
        val discovery =
            Discovery.forTesting(
                registrar = countingRegistrar,
                browser = NoopNsdBrowser,
            )
        val endpointInfo = sampleEndpointInfo()
        val expectedAscii =
            Base64Url
                .encode(endpointInfo.serialize())
                .toByteArray(Charsets.US_ASCII)
        val handle = discovery.advertise(endpointInfo, port = 51_001)
        try {
            val attrs = countingRegistrar.lastRegisteredAttrs.get()
            assertThat(attrs).containsKey(QuickShareMdns.TXT_KEY_ENDPOINT_INFO)
            assertThat(attrs[QuickShareMdns.TXT_KEY_ENDPOINT_INFO]).isEqualTo(expectedAscii)
            // And the round-trip back through Base64Url.decode lands on
            // the original bytes — so an interop peer reading the TXT
            // value as ASCII and decoding gets exactly the EndpointInfo
            // we serialized.
            val asAscii = String(attrs[QuickShareMdns.TXT_KEY_ENDPOINT_INFO]!!, Charsets.US_ASCII)
            assertThat(Base64Url.decode(asAscii)).isEqualTo(endpointInfo.serialize())
        } finally {
            handle.close()
        }
    }

    private fun sampleEndpointInfo(): EndpointInfo =
        EndpointInfo(
            version = 1,
            hidden = false,
            deviceType = DeviceType.PHONE,
            reserved = false,
            // Fill metadata with a full 0x00..0xFF round-trip pattern so
            // a regression that mangles high-bit bytes shows up.
            metadata = ByteArray(EndpointInfo.METADATA_LEN) { (it * 17).toByte() },
            deviceName = "Test Device",
            tlvRecords = emptyList(),
        )

    /**
     * A test-only [NetworkWatcher] that records start/stop and lets the
     * test trigger the change callback synchronously via [fire].
     */
    private class FakeNetworkWatcher : NetworkWatcher {
        var onChanged: () -> Unit = {}
        var started: Boolean = false
            private set
        var stopped: Boolean = false
            private set

        override fun start() {
            started = true
        }

        override fun stop() {
            stopped = true
        }

        fun fire() {
            onChanged()
        }
    }
}
