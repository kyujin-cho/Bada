/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery

import java.io.Closeable

/**
 * Lifecycle handle returned by [Discovery.advertise]. Closing the handle
 * unregisters the published `NsdManager` service for this advertisement.
 *
 * Implements [Closeable] so it slots cleanly into Kotlin's
 * `use { ... }` block and Java try-with-resources.
 *
 * The handle also exposes the encoded service-instance name that was
 * registered. Note: Android's [android.net.nsd.NsdManager] may
 * auto-suffix the requested name on collision (`" (1)"`, `" (2)"`, …);
 * [instanceName] reflects the **actually-published** name.
 */
public interface AdvertiseHandle : Closeable {
    /**
     * URL-safe-base64 service-instance name registered with `NsdManager`.
     * When the platform appended a collision suffix, this value reflects
     * the suffixed name rather than the requested one.
     */
    public val instanceName: String

    /** TCP port the advertisement points peers at. */
    public val port: Int

    /** True until [close] runs successfully. */
    public val isActive: Boolean

    /**
     * Unregister the published service. Safe to call more than once;
     * subsequent calls are no-ops.
     */
    public override fun close()
}
