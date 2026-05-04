/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.discovery

/**
 * Best-effort identifier for "this is a Samsung One UI device on the
 * other end" — used to gate the BLE GATT bootstrap UX caveat surfaced
 * by the send picker.
 *
 * **Why this exists.** Samsung One UI's Quick Share receiver enforces a
 * Google-account-bound `SenderCertificate` lookup before it registers a
 * per-peer Weave handler on its `0xFEF3` GATT server. WVMG cannot
 * satisfy that check without GMS internals, so writes to the Weave
 * write characteristic always come back rejected with
 * `BluetoothGattException: No handler registered for characteristic …`
 * and the bootstrap stalls at the 15-second handshake timeout. See
 * `docs/research/samsung-ble-gatt-cert-gate.md` for the GMS
 * decompilation details.
 *
 * The block is **only** in the BLE GATT path. Wi-Fi LAN works fine
 * against Samsung. So when a peer is Samsung-class **and** the only
 * available bootstrap is BLE GATT, the picker should warn the user and
 * point them to Wi-Fi rather than letting them sit through a doomed
 * 15 s handshake.
 *
 * **Detection strategy.** We can't read the BLE pulse to identify the
 * vendor reliably (the FastInitiation payload format is the same for
 * every Quick Share peer). The next-best signal is the receiver-side
 * device name we resolve from `EndpointInfo.deviceName` or the BLE
 * fast-advertisement slot 0. Samsung's defaults follow predictable
 * model-name patterns, and even after user customization the model
 * suffix usually survives (`"Kyujin's S26 Ultra"`, `"Family Z Fold5"`,
 * etc.).
 *
 * Patterns we match (case-insensitive, word-boundary aware):
 *
 * | Family            | Pattern marker                             |
 * |-------------------|--------------------------------------------|
 * | Galaxy umbrella   | the literal substring `galaxy`             |
 * | Foldables         | `z fold`, `z flip` (any digit)             |
 * | S series          | `s20`–`s29` ± `+`/`fe`/`ultra`/`plus`      |
 * | Note series       | `note 10`, `note 20`                       |
 * | A series          | `a10`–`a89` (Galaxy A line)                |
 * | M series          | `m10`–`m59` (Galaxy M line)                |
 * | F series          | `f20`–`f69` (Galaxy F line)                |
 * | Tab S series      | `tab s` followed by a digit                |
 *
 * Patterns chosen so non-Samsung devices that happen to share a
 * letter+number shape (`Pixel 8`, `OnePlus 10 Pro`, `Xperia 1 V`,
 * `vivo X300 Ultra`) do not trigger. False positives bias toward
 * "warn unnecessarily, user clicks Try Anyway" rather than "let them
 * burn 15 seconds on a guaranteed timeout"; false negatives bias
 * toward "Samsung peer with an unusual name doesn't get the warning,
 * user burns 15 seconds." Both failure modes are recoverable.
 *
 * This is intentionally a conservative heuristic, not a vendor
 * fingerprint. If Samsung ever loosens the cert gate (or another
 * vendor adopts the same restriction), update the pattern set rather
 * than the surrounding picker logic.
 */
public object SamsungQuickShareHeuristic {
    /**
     * @return `true` iff [peer]'s resolved display name suggests a
     *   Samsung Galaxy device. `false` for unknown/unnamed peers.
     */
    public fun isLikelySamsungReceiver(peer: NearbyPeer): Boolean {
        // Prefer the EndpointInfo-derived name (post-mDNS resolve, full
        // device name) over the BLE fast-advertisement name (which can
        // be truncated to fit the advertising PDU). Both are available
        // by the time the picker calls into us, so use either.
        val endpointName = peer.endpointInfo?.deviceName
        val bleName = peer.bleAdvertisement?.displayName
        return matches(endpointName) || matches(bleName)
    }

    /** Same matcher as [isLikelySamsungReceiver] but on a raw name string. Useful in tests. */
    public fun matchesDeviceName(name: String?): Boolean = matches(name)

    private fun matches(rawName: String?): Boolean {
        val name = rawName?.lowercase()?.trim().orEmpty()
        if (name.isEmpty()) return false
        return SAMSUNG_PATTERNS.any { pattern -> pattern.containsMatchIn(name) }
    }

    /**
     * Compiled patterns. Each pattern is anchored to a word boundary
     * (or the equivalent lookaround) so that, e.g., `"a25"` is matched
     * inside `"Galaxy A25"` but not inside `"OnePlus 10a25-something"`.
     *
     * Kept as a single-source-of-truth list so adding/tweaking a model
     * family is a one-line change.
     */
    private val SAMSUNG_PATTERNS: List<Regex> =
        listOf(
            Regex("""\bgalaxy\b"""),
            Regex("""\bz\s?fold\s?\d*\b"""),
            Regex("""\bz\s?flip\s?\d*\b"""),
            // Galaxy S20..S29 (with optional +/FE/Ultra/Plus suffix).
            Regex("""\bs2\d(\+|\s?fe|\s?ultra|\s?plus)?\b"""),
            // Galaxy Note 10 / Note 20 (active models in the field).
            Regex("""\bnote\s?(10|20)\b"""),
            // Galaxy A10..A89 — A-series budget/mid-tier line.
            Regex("""\ba[1-8]\d\b"""),
            // Galaxy M10..M59 — emerging-market M-series.
            Regex("""\bm[1-5]\d\b"""),
            // Galaxy F20..F69 — India-only F-series.
            Regex("""\bf[2-6]\d\b"""),
            // Galaxy Tab S* (tablets — same Quick Share gate).
            Regex("""\btab\s?s\d+\b"""),
        )
}
