/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery

/**
 * Feature gates for discovery / bootstrap mediums that are exposed through
 * normal user-facing app flows.
 */
public object UserFacingMediumFeatures {
    /**
     * Bluetooth Classic RFCOMM remains implemented for future re-enable
     * work, but normal discovery, picker routing, and bootstrap flows must
     * not expose it to users.
     */
    public const val BLUETOOTH_CLASSIC_USER_FACING_ENABLED: Boolean = false
}
