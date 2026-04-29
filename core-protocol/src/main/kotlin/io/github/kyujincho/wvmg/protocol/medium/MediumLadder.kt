/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.medium

/**
 * Ordered preference list used to pick **one** medium out of the
 * intersection between the local device's supported set and the peer's
 * advertised set on `ConnectionRequestFrame.mediums`.
 *
 * The order encodes the project's bandwidth-vs-availability heuristic:
 *
 *  1. **Wi-Fi Aware** — direct NAN data path when both chipsets support it.
 *  2. **Wi-Fi Direct** — peer-to-peer Wi-Fi, no router in the middle.
 *  3. **Wi-Fi Hotspot** — same bandwidth ceiling as LAN, but requires
 *     standing up a soft-AP (slow on cold start).
 *  4. **Wi-Fi LAN** — the already-open discovery transport.
 *  5. **WebRTC** — internet-relayed; only relevant when both peers
 *     have connectivity but no shared LAN.
 *  6. **BLE L2CAP** — meaningful throughput on the LE radio (≈ 1 Mbps
 *     on BT 5+); preferred over RFCOMM for the file-transfer phase.
 *  7. **Bluetooth (RFCOMM)** — slowest of the bulk-transfer mediums but
 *     extremely widely supported; the universal fallback.
 *  8. **BLE (GATT)** — discovery-only in practice; left in the ladder
 *     so a future "tiny payload over GATT" path stays expressible.
 *
 * The default is exposed as [Default]; a per-call override can be
 * passed to [MediumRegistry.selectBestUpgrade] when integration tests
 * or specific UX flows want a different ordering (e.g. forcing a
 * Wi-Fi-Direct-first run).
 */
public class MediumLadder(
    /**
     * Mediums in descending preference order.
     *
     * The list is `distinct`-ed in the constructor so accidentally
     * duplicate entries do not affect selection. Empty lists are
     * permitted (and produce [MediumRegistry.selectBestUpgrade] returning
     * `null` for every input).
     */
    rungs: List<Medium>,
) {
    public val rungs: List<Medium> = rungs.distinct()

    /**
     * Pick the highest-priority medium from [candidates]. Returns
     * `null` when the intersection is empty (i.e. no candidate appears
     * on the ladder).
     *
     * Order of [candidates] is irrelevant: the ladder controls the
     * outcome.
     */
    public fun pickBest(candidates: Set<Medium>): Medium? = rungs.firstOrNull { it in candidates }

    public companion object {
        /**
         * Project default. See class KDoc for the ordering rationale.
         */
        @JvmStatic
        public val Default: MediumLadder =
            MediumLadder(
                listOf(
                    Medium.WIFI_AWARE,
                    Medium.WIFI_DIRECT,
                    Medium.WIFI_HOTSPOT,
                    Medium.WIFI_LAN,
                    Medium.WEB_RTC,
                    Medium.BLE_L2CAP,
                    Medium.BLUETOOTH,
                    Medium.BLE,
                ),
            )
    }
}
