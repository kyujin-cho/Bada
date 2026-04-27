/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery

import java.io.Closeable

/**
 * Lifecycle handle returned by [Discovery.advertise]. Closing the handle
 * unregisters the JmDNS service, releases the multicast lock acquired for
 * publishing, and shuts down the JmDNS instance owned by this advertisement.
 *
 * Implements [Closeable] so it slots cleanly into Kotlin's
 * `use { ... }` block and Java try-with-resources.
 *
 * The handle also exposes the encoded service-instance name that was
 * registered, since the random portion is generated lazily inside
 * `advertise(...)` and callers commonly want to log / display it.
 */
public interface AdvertiseHandle : Closeable {
    /** URL-safe-base64 service-instance name registered with JmDNS. */
    public val instanceName: String

    /** TCP port the advertisement points peers at. */
    public val port: Int

    /** True until [close] runs successfully. */
    public val isActive: Boolean

    /**
     * Unregister the service and release the multicast lock. Safe to call
     * more than once; subsequent calls are no-ops.
     */
    public override fun close()
}
