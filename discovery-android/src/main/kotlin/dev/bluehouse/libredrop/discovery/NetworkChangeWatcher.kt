/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watches Wi-Fi network availability and triggers a re-registration
 * callback whenever the active Wi-Fi network changes.
 *
 * Quick Share peers cache mDNS service records keyed by the publishing
 * IP address; when the device moves to a new Wi-Fi network the JmDNS
 * instance is now bound to a stale interface and any further
 * advertisements would be silently invisible to peers on the new
 * network. Re-creating the JmDNS instance on `onAvailable` /
 * `onLost` puts the advertisement back on the wire with the correct
 * source address.
 *
 * Threading: the underlying [ConnectivityManager.NetworkCallback]
 * fires on the framework's executor (an internal handler thread). The
 * callback is invoked verbatim on that thread; the [Discovery]
 * advertise path takes care of synchronizing the actual JmDNS
 * re-creation under its own lock.
 */
internal class NetworkChangeWatcher(
    context: Context,
    private val onChanged: () -> Unit,
) : NetworkWatcher {
    private val cm: ConnectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // Restrict the callback to Wi-Fi only — cellular changes are not
    // relevant for Quick Share LAN discovery and would just churn the
    // re-register path.
    private val request: NetworkRequest =
        NetworkRequest
            .Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onChanged()
            }

            override fun onLost(network: Network) {
                // We don't need to re-register on `onLost` alone (the JmDNS
                // instance just goes silent); but firing the callback lets
                // the consumer release any per-network resources. The
                // following `onAvailable` will rebuild things.
                onChanged()
            }
        }

    private val started = AtomicBoolean(false)

    /** Begin observing Wi-Fi network changes. Idempotent. */
    override fun start() {
        if (started.compareAndSet(false, true)) {
            cm.registerNetworkCallback(request, callback)
        }
    }

    /**
     * Stop observing Wi-Fi network changes. Idempotent. Wraps a
     * `try`/`catch` because `unregisterNetworkCallback` throws if the
     * callback was already unregistered (e.g. due to a system race
     * during shutdown), and the failure mode here is benign.
     */
    override fun stop() {
        if (started.compareAndSet(true, false)) {
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (
                @Suppress("TooGenericExceptionCaught") _: Throwable,
            ) {
                // Already unregistered or never observed — ignore.
            }
        }
    }
}
