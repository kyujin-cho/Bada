/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.protocol.medium

import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.BandwidthUpgradeNegotiationFrame.UpgradePathInfo
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.ConnectionRequestFrame

/**
 * Pure-Kotlin domain enum mirroring the wire-level `Medium` enums used
 * by `ConnectionRequestFrame.Medium` and
 * `BandwidthUpgradeNegotiationFrame.UpgradePathInfo.Medium`.
 *
 * Every Quick Share medium that the framework is willing to surface to
 * adapters has exactly one entry here. The two proto enums are
 * structurally identical (same wire numbers and names) but live in
 * different message scopes; routing both through this single domain enum
 * keeps the adapter contract — [MediumProvider] — independent of which
 * proto field a value is being read from or written to.
 *
 * Adapters in `:discovery-android` (Phase 4 sub-issues #49–#53) declare
 * their support against this enum; the framework converts to the
 * appropriate proto enum at the wire boundary.
 *
 * The wire-numbers field is asserted by [MediumWireMappingTest] so the
 * mapping cannot drift if a future proto vendoring renumbers an entry.
 *
 * @property wireNumber Wire number shared by both proto enums for this
 *   medium. Stable across the proto's history (see Quick Share's
 *   `LINT.IfChange` annotations on the enum).
 */
public enum class Medium(
    public val wireNumber: Int,
) {
    /** Bluetooth RFCOMM / OBEX. Wire number 2. */
    BLUETOOTH(2),

    /** Wi-Fi Hotspot (soft-AP). Wire number 3. */
    WIFI_HOTSPOT(3),

    /** BLE GATT (used for discovery, not bulk transfer). Wire number 4. */
    BLE(4),

    /** Wi-Fi LAN — the default medium for every connection today. Wire number 5. */
    WIFI_LAN(5),

    /** Wi-Fi Aware (NAN). Wire number 6. */
    WIFI_AWARE(6),

    /** Wi-Fi Direct (P2P). Wire number 8. */
    WIFI_DIRECT(8),

    /** WebRTC (data channel; non-cellular). Wire number 9. */
    WEB_RTC(9),

    /** BLE L2CAP CoC. Wire number 10. */
    BLE_L2CAP(10),
    ;

    /**
     * Project this domain entry onto the wire-level
     * [ConnectionRequestFrame.Medium] used by the opening handshake.
     */
    public fun toConnectionRequestMedium(): ConnectionRequestFrame.Medium =
        // forNumber returns null only if a future proto removes the
        // value; we vendor the proto verbatim so this is dead in
        // practice. Treat as a programmer error if it ever fires.
        ConnectionRequestFrame.Medium.forNumber(wireNumber)
            ?: error("ConnectionRequestFrame.Medium has no value with wire number $wireNumber for $name")

    /**
     * Project this domain entry onto the wire-level
     * [UpgradePathInfo.Medium] used by `BANDWIDTH_UPGRADE_NEGOTIATION`.
     *
     * Returns `null` for mediums whose wire number is reserved on
     * `UpgradePathInfo.Medium` and therefore cannot appear as a
     * bandwidth-upgrade target. Today only [BLE_L2CAP] (wire number
     * 10, declared `// 10 is reserved.` in the proto) hits this path;
     * the codec layer treats a `null` here as "this medium cannot be
     * advertised as an upgrade target" and the caller must fall
     * through to the next ladder rung.
     */
    public fun toUpgradePathMediumOrNull(): UpgradePathInfo.Medium? = UpgradePathInfo.Medium.forNumber(wireNumber)

    /**
     * Strict variant of [toUpgradePathMediumOrNull]. Throws if the
     * medium has no `UpgradePathInfo.Medium` value — used by the FSM
     * and the frame builders, which reach this path only after the
     * registry's selection has already ruled out non-upgradable
     * mediums (BLE/BLE_L2CAP cannot be picked because they decode to
     * `null` on [toUpgradePathMediumOrNull] in the round-trip; the
     * registry's `selectBestUpgrade` is the right gate).
     */
    public fun toUpgradePathMedium(): UpgradePathInfo.Medium =
        toUpgradePathMediumOrNull()
            ?: error("UpgradePathInfo.Medium has no value with wire number $wireNumber for $name")

    public companion object {
        /**
         * Resolve a [ConnectionRequestFrame.Medium] back to a domain
         * [Medium]. Returns `null` for `UNKNOWN_MEDIUM`, the legacy
         * `MDNS` (1), `NFC` (7), `USB` (11), `WEB_RTC_NON_CELLULAR` (12),
         * and `AWDL` (13) — none of which are in scope for this Android
         * port (Apple-side interop is explicitly excluded; #5).
         */
        public fun fromConnectionRequestMedium(value: ConnectionRequestFrame.Medium): Medium? =
            entries.firstOrNull { it.wireNumber == value.number }

        /**
         * Resolve an [UpgradePathInfo.Medium] back to a domain [Medium].
         * Same semantics as [fromConnectionRequestMedium] — returns
         * `null` for unsupported values rather than throwing, so
         * receiver-side handling of an unknown-medium upgrade frame is
         * a clean drop rather than a protocol error.
         */
        public fun fromUpgradePathMedium(value: UpgradePathInfo.Medium): Medium? =
            entries.firstOrNull { it.wireNumber == value.number }
    }
}
