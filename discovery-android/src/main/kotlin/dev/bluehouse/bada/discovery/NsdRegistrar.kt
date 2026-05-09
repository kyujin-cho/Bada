/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery

import java.io.Closeable
import java.net.InetAddress

/**
 * Narrow abstraction over Android's [android.net.nsd.NsdManager] publish
 * surface, used by [Discovery.advertise].
 *
 * This interface exists for two reasons:
 *
 *  1. **JVM testability.** `:core-protocol` is JVM-only and the wider
 *     test strategy keeps `:discovery-android`'s logic exercisable
 *     without spinning up a Robolectric harness. A fake [NsdRegistrar]
 *     lets unit tests drive [Discovery.advertise] end-to-end.
 *  2. **API-level isolation.** Binary TXT-record support flips form
 *     between API 33+ (public `setAttribute(String, ByteArray)`) and
 *     API 24-32 (the same `setAttribute(String, byte[])` method exists
 *     but is annotated `@hide @UnsupportedAppUsage`, so it is invoked
 *     reflectively). The public String overload is **never** used —
 *     it UTF-8-re-encodes its value internally, which would corrupt
 *     any attribute byte >= 0x80. The branch lives entirely inside
 *     [AndroidNsdRegistrar]; the rest of the discovery code never has
 *     to think about it.
 *
 * Implementations are expected to be thread-safe.
 */
internal interface NsdRegistrar {
    /**
     * Publish a Quick Share service.
     *
     * @param serviceType the bare DNS-SD service type (e.g.
     *   `_FC9F5ED42C8A._tcp` — note: no trailing protocol-domain suffix
     *   that JmDNS uses; see [QuickShareMdns.SERVICE_TYPE_NSD] for the
     *   constant used in production).
     * @param instanceName the requested service-instance name. Android's
     *   NsdManager may auto-suffix this on collision; the actual
     *   registered name is exposed via [NsdRegistrationHandle.instanceName].
     * @param port the TCP port the receiver server is listening on.
     * @param attributes raw, binary TXT-record attributes. Values are
     *   passed through as-is — Quick Share's `n=` key carries packed
     *   binary [dev.bluehouse.bada.protocol.endpoint.EndpointInfo]
     *   bytes that must round-trip without UTF-8 mangling.
     * @return a registration handle that, when closed, unpublishes the
     *   service. Implementations MUST NOT return until registration has
     *   either succeeded (callback observed) or failed (exception thrown).
     */
    suspend fun register(
        serviceType: String,
        instanceName: String,
        port: Int,
        attributes: Map<String, ByteArray>,
    ): NsdRegistrationHandle
}

/**
 * Lifecycle handle returned by [NsdRegistrar.register].
 *
 * Carries the **actually-registered** instance name (which may differ
 * from the requested one if Android auto-suffixed on collision) and the
 * host address the system reported as the publish source, when known.
 */
internal interface NsdRegistrationHandle : Closeable {
    /** The instance name actually published. */
    val instanceName: String

    /**
     * The host address the platform reports for the published service,
     * if available. Only populated on API levels where the registration
     * callback exposes it — older APIs simply leave this null and the
     * snapshot falls back to "unknown".
     */
    val hostAddress: InetAddress?

    /** True until [close] has run successfully. */
    val isActive: Boolean

    /** Unregister the service. Idempotent. */
    override fun close()
}
