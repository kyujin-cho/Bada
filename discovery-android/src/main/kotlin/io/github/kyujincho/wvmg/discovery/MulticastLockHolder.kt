/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
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
 *
 * Tests inject a fake [MulticastLockGate] via the package-private
 * constructor; production code uses the [Context] constructor which
 * resolves a real [WifiManager.MulticastLock] under the hood.
 */
internal class MulticastLockHolder internal constructor(
    private val gate: MulticastLockGate,
    private val tag: String,
) {
    constructor(
        context: Context,
        tag: String = DEFAULT_TAG,
    ) : this(
        gate = systemMulticastLockGate(context, tag),
        tag = tag,
    )

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
            gate.acquire()
            Log.i(TAG, "MulticastLockHolder($tag) acquired (refCount=1, isHeld=${gate.isHeld()})")
        } else {
            Log.d(TAG, "MulticastLockHolder($tag) acquire (refCount=${refCount.get()})")
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
        if (previous == 1 && gate.isHeld()) {
            gate.release()
            Log.i(TAG, "MulticastLockHolder($tag) released (refCount=0)")
        } else {
            Log.d(TAG, "MulticastLockHolder($tag) release (refCount=${refCount.get()})")
        }
    }

    /** Test/diagnostic accessor — the live reference count. */
    @Synchronized
    fun refCountForTest(): Int = refCount.get()

    /**
     * Diagnostic accessor — `true` while the underlying
     * multicast lock is held. Exposed for the [Discovery]
     * snapshot API so on-device logs can show whether the lock
     * was actually held when discovery appeared to silently fail.
     */
    @Synchronized
    fun isHeld(): Boolean = gate.isHeld()

    internal companion object {
        const val DEFAULT_TAG: String = "wvmg-discovery-mdns"
        private const val TAG: String = "WvmgDiscovery"

        private fun systemMulticastLockGate(
            context: Context,
            tag: String,
        ): MulticastLockGate {
            val wifi =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock =
                wifi.createMulticastLock(tag).apply {
                    // `setReferenceCounted(false)` makes a single release() call always
                    // fully release the lock, regardless of how many times the system
                    // thinks it was acquired. Combined with our own counter this
                    // gives unambiguous acquire/release semantics.
                    setReferenceCounted(false)
                }
            return SystemMulticastLockGate(lock)
        }
    }
}

/**
 * Tiny interface that the [MulticastLockHolder] uses to talk to a
 * platform multicast lock. Exists so JVM unit tests can drop in a fake
 * without depending on a real [android.net.wifi.WifiManager].
 */
internal interface MulticastLockGate {
    fun acquire()

    fun release()

    fun isHeld(): Boolean
}

/** Production [MulticastLockGate] backed by a real [WifiManager.MulticastLock]. */
private class SystemMulticastLockGate(
    private val lock: WifiManager.MulticastLock,
) : MulticastLockGate {
    override fun acquire(): Unit = lock.acquire()

    override fun release(): Unit = lock.release()

    override fun isHeld(): Boolean = lock.isHeld
}
