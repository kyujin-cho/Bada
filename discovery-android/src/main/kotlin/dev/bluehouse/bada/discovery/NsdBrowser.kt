/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery

import kotlinx.coroutines.flow.Flow
import java.net.InetAddress

/**
 * Narrow abstraction over Android's [android.net.nsd.NsdManager] discover
 * surface, used by [Discovery.browse].
 *
 * Implementations expose a cold [Flow] of [NsdBrowserEvent]s. Collecting
 * the flow starts a discovery; cancelling the collector tears the
 * discovery (and any in-flight resolve callbacks) back down.
 *
 * Why this is its own interface (rather than folding the logic directly
 * into [Discovery]):
 *
 *  - Production [AndroidNsdBrowser] needs to serialize [NsdManager.resolveService]
 *    calls behind a queue (pre-API-30 only allows one in-flight resolve).
 *    Keeping the queueing logic here makes [Discovery]'s body a thin
 *    facade.
 *  - JVM unit tests inject a fake `NsdBrowser` that emits scripted
 *    events synchronously, including the platform's "Found before
 *    Resolved" ordering, without needing the platform.
 */
internal interface NsdBrowser {
    /**
     * Start a discovery session.
     *
     * @param serviceType the bare DNS-SD service type
     *   (`_FC9F5ED42C8A._tcp`); see [QuickShareMdns.SERVICE_TYPE_NSD].
     * @return a cold flow of [NsdBrowserEvent]s. Each peer that resolves
     *   produces at least a `Found` then a `Resolved` event in order; a
     *   peer going away produces a `Lost` event.
     */
    fun discover(serviceType: String): Flow<NsdBrowserEvent>
}

/**
 * One observation from an [NsdBrowser] discovery session.
 *
 * Modelled as a sealed hierarchy mirroring NsdManager's callback
 * structure: the platform may report a peer's existence (via
 * `onServiceFound`) before the address+TXT data has been resolved, and
 * test fakes need to be able to emit that intermediate state.
 */
internal sealed class NsdBrowserEvent {
    /**
     * The platform reported a peer matching the service type but the
     * address / TXT data has not been resolved yet. Carries the bare
     * instance name only.
     */
    data class Found(
        val instanceName: String,
    ) : NsdBrowserEvent()

    /**
     * A peer's address + TXT data has been resolved.
     */
    data class Resolved(
        val instanceName: String,
        val addresses: List<InetAddress>,
        val port: Int,
        val attributes: Map<String, ByteArray>,
    ) : NsdBrowserEvent()

    /**
     * A previously-discovered peer is no longer being advertised.
     */
    data class Lost(
        val instanceName: String,
    ) : NsdBrowserEvent()

    /**
     * A non-fatal error from the platform (e.g. resolve failed for one
     * peer, but the discovery session itself is still running). The
     * [Discovery] facade logs these as diagnostic events; they do not
     * stop the flow.
     */
    data class Error(
        val instanceName: String?,
        val message: String,
    ) : NsdBrowserEvent()
}
