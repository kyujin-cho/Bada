/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery

import android.content.Context
import android.net.wifi.WifiManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reference-counted holder for `WifiManager.MulticastLock`.
 *
 * Android's Wi-Fi stack drops multicast traffic (including mDNS at
 * `224.0.0.251`) once the screen is off / the device enters power-save,
 * unless a `WifiManager.MulticastLock` is held. Both publish and browse
 * paths in this module need that lock, but they may run concurrently or
 * independently — and acquiring the lock twice from one consumer is fine,
 * but releasing it once when two consumers each still need it would
 * silently break the other one's traffic.
 *
 * The fix is a small reference counter: every [acquire] bumps the count
 * and the **first** acquire pays the actual `MulticastLock.acquire()`;
 * every [release] decrements and the **last** release calls
 * `MulticastLock.release()`. This matches how `PowerManager.WakeLock`
 * has historically been wrapped on Android.
 *
 * Thread safety: state mutations are guarded by a synchronized block
 * because `MulticastLock` is documented as not thread-safe and we have
 * to guarantee acquire/release pair atomically with the counter
 * increment/decrement.
 */
internal class MulticastLockHolder(
    context: Context,
    private val tag: String = DEFAULT_TAG,
) {
    private val wifi: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val lock: WifiManager.MulticastLock =
        wifi.createMulticastLock(tag).apply {
            // `setReferenceCounted(false)` makes a single release() call always
            // fully release the lock, regardless of how many times the system
            // thinks it was acquired. Combined with our own counter below this
            // gives unambiguous acquire/release semantics.
            setReferenceCounted(false)
        }

    private val refCount = AtomicInteger(0)

    /**
     * Acquires the multicast lock if this is the first outstanding acquirer.
     * Idempotent: subsequent acquires just bump the counter.
     */
    @Synchronized
    fun acquire() {
        if (refCount.getAndIncrement() == 0) {
            // First acquirer pays the actual platform call. We let the
            // SecurityException propagate (it indicates a missing
            // CHANGE_WIFI_MULTICAST_STATE permission) since the failure
            // mode without the lock is silently broken mDNS.
            lock.acquire()
        }
    }

    /**
     * Releases the multicast lock once the last outstanding acquirer
     * relinquishes it. Calling [release] more times than [acquire] is a
     * programmer error and is logged via an exception rather than silently
     * pinning the counter at zero.
     */
    @Synchronized
    fun release() {
        val previous = refCount.getAndDecrement()
        check(previous > 0) {
            "MulticastLockHolder($tag) released more times than acquired"
        }
        if (previous == 1 && lock.isHeld) {
            lock.release()
        }
    }

    /** Test/diagnostic accessor — the live reference count. */
    @Synchronized
    fun refCountForTest(): Int = refCount.get()

    internal companion object {
        const val DEFAULT_TAG: String = "wvmg-discovery-mdns"
    }
}
