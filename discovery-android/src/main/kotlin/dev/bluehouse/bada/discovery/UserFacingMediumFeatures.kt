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
     * Bluetooth Classic RFCOMM *discovery* (inquiry / device-name scanning
     * via [BluetoothClassicPeerScanner]) remains implemented but hidden:
     * normal discovery and picker surfaces must not expose peers found
     * through it.
     */
    public const val BLUETOOTH_CLASSIC_USER_FACING_ENABLED: Boolean = false

    /**
     * Bluetooth Classic RFCOMM as a *connect route* for the sender
     * bootstrap (#214). Stock GMS receivers bootstrap off-LAN over RFCOMM
     * (verified by HCI snoop of stock-to-stock transfers); their BLE
     * GATT/L2CAP server paths are unreliable because stock senders never
     * exercise them. The peer's BR/EDR MAC arrives via already-supported
     * discovery surfaces (regular 0xFEF3 advertisement body, mDNS TXT),
     * so enabling the route does not surface any new discovery medium.
     */
    public const val BLUETOOTH_CLASSIC_BOOTSTRAP_ROUTE_ENABLED: Boolean = true
}
