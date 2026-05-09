/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.medium

/**
 * Pluggable registry of [MediumProvider]s.
 *
 * The framework
 * ([dev.bluehouse.bada.protocol.connection.OutboundConnection] /
 * [dev.bluehouse.bada.protocol.connection.InboundConnection])
 * holds exactly one of these per process. The registry is the only
 * surface the orchestrator needs to know about; per-medium adapters
 * (Phase 4 sub-issues #49–#53) just register themselves.
 *
 * Two responsibilities:
 *
 *  1. **Capability advertisement** — [supportedMediums] expands every
 *     registered provider's [MediumProvider.isSupported] into the set
 *     used to populate `ConnectionRequestFrame.mediums` on the sender
 *     side and to intersect the peer's mediums on the receiver side.
 *     [supportedMediumsForCurrentTransport] is the current-medium-aware
 *     variant for sessions whose initial control channel is not Wi-Fi
 *     LAN: `WIFI_LAN` is only a meaningful "stay on this socket" marker
 *     when the current socket is already Wi-Fi LAN.
 *  2. **Provider lookup** — [providerFor] retrieves the registered
 *     adapter for a chosen medium, used by [selectBestUpgrade] when
 *     the framework is ready to perform the bandwidth-upgrade
 *     handshake.
 *
 * Stays empty by default so the framework's behaviour is unchanged
 * when no Phase 4 medium is wired up — see [DefaultWifiLan] for the
 * Phase 1 default that mirrors today's "Wi-Fi LAN only" surface.
 *
 * Thread-safety: providers are inserted at construction time only; the
 * registry itself is immutable after construction. Look-up paths are
 * lock-free and safe to call from any thread.
 */
public class MediumRegistry(
    providers: List<MediumProvider> = emptyList(),
    /**
     * Ladder used to break ties when multiple mediums are present in
     * the intersection between the local and peer-supported sets.
     * Defaults to [MediumLadder.Default]. Tests override to lock the
     * pick on a deterministic medium.
     */
    public val ladder: MediumLadder = MediumLadder.Default,
) {
    /**
     * Indexed view of the registered providers. Two providers
     * registering the same [MediumProvider.medium] is a programmer
     * error — `require` rather than silently dropping the duplicate
     * because the alternative would be order-dependent and unsurprising
     * fields like `[providerFor]` would silently return one or the
     * other.
     */
    private val providers: Map<Medium, MediumProvider>

    init {
        val map = HashMap<Medium, MediumProvider>(providers.size)
        for (provider in providers) {
            require(map.put(provider.medium, provider) == null) {
                "Duplicate MediumProvider for ${provider.medium}"
            }
        }
        this.providers = map
    }

    /**
     * Set of mediums whose providers report [MediumProvider.isSupported] true
     * **and** are registered with this instance. Recomputed on every
     * call: [MediumProvider.isSupported] may flip with OS-level state
     * changes (Wi-Fi off, Bluetooth off, …) and the framework expects
     * a fresh snapshot at the start of every connection lifecycle.
     */
    public fun supportedMediums(): Set<Medium> =
        providers.values
            .asSequence()
            .filter { it.isSupported() }
            .map { it.medium }
            .toSet()

    /**
     * Like [supportedMediums], but shaped for a connection already
     * running on [currentMedium].
     *
     * `WIFI_LAN` in our protocol surface does not represent a generic
     * upgrade path provider; it means "keep using the already-open
     * LAN socket". Advertising it while the current control channel is
     * Bluetooth/BLE/etc. would mislead the peer into thinking a LAN
     * stay-put path exists when it does not.
     */
    public fun supportedMediumsForCurrentTransport(currentMedium: Medium): Set<Medium> =
        supportedMediums().filterTo(linkedSetOf()) { medium ->
            medium != Medium.WIFI_LAN || currentMedium == Medium.WIFI_LAN
        }

    /**
     * Return the registered provider for [medium], or `null` when no
     * provider was registered for it. Note this does NOT consult
     * [MediumProvider.isSupported]; the caller is responsible for
     * ordering the check (almost always `provider.isSupported()` after
     * `providerFor`).
     */
    public fun providerFor(medium: Medium): MediumProvider? = providers[medium]

    /**
     * Whether the registry has any provider registered, regardless of
     * support state. Used as a cheap gate for code paths that should
     * stay dormant when no Phase 4 medium has been wired up.
     */
    public fun isEmpty(): Boolean = providers.isEmpty()

    /**
     * Intersect the local supported set with the peer's advertised
     * mediums and return the highest-priority entry per [ladder].
     *
     * Returns `null` when the intersection is empty (no shared medium)
     * or when [ladder] does not contain any of the intersection
     * members.
     *
     * @param peerSupported The peer's advertised set, decoded from
     *   `ConnectionRequestFrame.mediums`.
     * @param ladderOverride Optional per-call ladder override; defaults
     *   to the one supplied at construction.
     */
    public fun selectBestUpgrade(
        peerSupported: Set<Medium>,
        ladderOverride: MediumLadder = ladder,
    ): Medium? {
        val intersection = supportedMediums().intersect(peerSupported)
        if (intersection.isEmpty()) return null
        return ladderOverride.pickBest(intersection)
    }

    /**
     * Like [selectBestUpgrade], but excludes `WIFI_LAN` unless the
     * session is already running on Wi-Fi LAN. This keeps a
     * preconnected Bluetooth/BLE bootstrap from incorrectly resolving
     * to "stay on current medium" via the Wi-Fi-LAN rung.
     */
    public fun selectBestUpgradeForCurrentTransport(
        peerSupported: Set<Medium>,
        currentMedium: Medium,
        ladderOverride: MediumLadder = ladder,
    ): Medium? {
        val intersection =
            supportedMediumsForCurrentTransport(currentMedium)
                .intersect(peerSupported)
        if (intersection.isEmpty()) return null
        return ladderOverride.pickBest(intersection)
    }

    /**
     * Like [selectBestUpgrade], but attempts the server-side bring-up
     * for each shared medium in ladder order until one succeeds.
     *
     * This is the fallback-selection helper for medium negotiation:
     * when a higher-priority medium is advertised and supported but its
     * [MediumProvider.prepareUpgrade] path fails at runtime (for
     * example, Wi-Fi Direct group creation fails because the OEM
     * refuses P2P while STA is active), the registry falls through to
     * the next shared rung instead of returning a failed selection.
     * Connection drivers still need to apply the returned selection to
     * the `BANDWIDTH_UPGRADE_NEGOTIATION` transport-swap handshake.
     *
     * [Medium.WIFI_LAN] is the "stay on the current socket" case. The
     * returned [PreparedUpgradeSelection.StayOnCurrentMedium] signals
     * that the caller should keep using the existing transport rather
     * than emitting `UPGRADE_PATH_AVAILABLE`.
     */
    @Suppress(
        "ReturnCount",
        "LoopWithTooManyJumpStatements",
    ) // The ladder walk is intentionally linear: one guard per rung, one exit on success.
    public suspend fun prepareBestUpgrade(
        peerSupported: Set<Medium>,
        ladderOverride: MediumLadder = ladder,
    ): PreparedUpgradeSelection? {
        val intersection = supportedMediums().intersect(peerSupported)
        if (intersection.isEmpty()) return null
        for (medium in ladderOverride.rungs) {
            if (medium !in intersection) continue
            val provider = providerFor(medium) ?: continue
            if (!provider.isSupported()) continue
            if (medium == Medium.WIFI_LAN) {
                return PreparedUpgradeSelection.StayOnCurrentMedium
            }
            val credentials =
                runCatching { provider.prepareUpgrade() }
                    .getOrNull() ?: continue
            return PreparedUpgradeSelection.Upgrade(credentials)
        }
        return null
    }

    /**
     * Like [prepareBestUpgrade], but treats `WIFI_LAN` as a valid
     * "stay on current transport" result only when [currentMedium] is
     * actually [Medium.WIFI_LAN].
     */
    @Suppress(
        "ReturnCount",
        "LoopWithTooManyJumpStatements",
    )
    public suspend fun prepareBestUpgradeForCurrentTransport(
        peerSupported: Set<Medium>,
        currentMedium: Medium,
        ladderOverride: MediumLadder = ladder,
    ): PreparedUpgradeSelection? {
        val intersection =
            supportedMediumsForCurrentTransport(currentMedium)
                .intersect(peerSupported)
        if (intersection.isEmpty()) return null
        for (medium in ladderOverride.rungs) {
            if (medium !in intersection) continue
            val provider = providerFor(medium) ?: continue
            if (!provider.isSupported()) continue
            if (medium == Medium.WIFI_LAN) {
                return PreparedUpgradeSelection.StayOnCurrentMedium
            }
            val credentials =
                runCatching { provider.prepareUpgrade() }
                    .getOrNull() ?: continue
            return PreparedUpgradeSelection.Upgrade(credentials)
        }
        return null
    }

    public companion object {
        /**
         * The Phase 1 default: a registry with a single trivial
         * Wi-Fi-LAN provider. Mirrors today's hard-coded behaviour and
         * is what [dev.bluehouse.bada.protocol.connection.OutboundConnection]
         * and [dev.bluehouse.bada.protocol.connection.InboundConnection]
         * use when the caller does not supply their own registry.
         *
         * The provider here returns no upgrade credentials and adopts
         * none — Wi-Fi LAN is the **discovery** medium today, so there
         * is nothing to "upgrade to". Keeping it in the registry just
         * makes `ConnectionRequestFrame.mediums` advertise WIFI_LAN as
         * a medium the device supports, which matches what NearDrop
         * has hard-coded since day one.
         */
        @JvmStatic
        public val DefaultWifiLan: MediumRegistry =
            MediumRegistry(
                providers = listOf(WifiLanDefaultProvider),
            )

        /**
         * Trivial Wi-Fi-LAN provider used by [DefaultWifiLan]. Reports
         * supported = true unconditionally (the orchestrator already
         * has a TCP socket open by the time we ask, so Wi-Fi is by
         * definition reachable) and offers no upgrade path.
         */
        private object WifiLanDefaultProvider : MediumProvider {
            override val medium: Medium = Medium.WIFI_LAN

            override fun isSupported(): Boolean = true
        }
    }
}

/**
 * Result of [MediumRegistry.prepareBestUpgrade].
 */
public sealed interface PreparedUpgradeSelection {
    /**
     * Medium selected for the resulting transport decision.
     */
    public val medium: Medium

    /**
     * Higher-priority medium succeeded in `prepareUpgrade`; the caller
     * should advertise [credentials] to the peer and continue the
     * bandwidth-upgrade handshake.
     */
    public data class Upgrade(
        val credentials: UpgradePathCredentials,
    ) : PreparedUpgradeSelection {
        override val medium: Medium = credentials.medium
    }

    /**
     * No higher-priority upgrade path came up, so the connection
     * should stay on the existing Wi-Fi LAN transport.
     */
    public data object StayOnCurrentMedium : PreparedUpgradeSelection {
        override val medium: Medium = Medium.WIFI_LAN
    }
}
