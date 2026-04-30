/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery.ble

import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-local handoff from the receiver-side BLE L2CAP bootstrap listener to
 * the DCT advertiser.
 */
internal object BleDctPsmHolder {
    private val activePsm: AtomicInteger = AtomicInteger(NO_PSM)

    val currentPsm: Int?
        get() = activePsm.get().takeIf { it > NO_PSM }

    fun set(psm: Int) {
        activePsm.set(psm)
    }

    fun clear(psm: Int) {
        activePsm.compareAndSet(psm, NO_PSM)
    }

    fun clear() {
        activePsm.set(NO_PSM)
    }

    private const val NO_PSM: Int = 0
}
