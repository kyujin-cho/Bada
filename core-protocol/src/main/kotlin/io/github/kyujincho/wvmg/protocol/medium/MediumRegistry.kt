/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.medium

/**
 * Pluggable registry of [MediumProvider]s.
 *
 * The framework
 * ([io.github.kyujincho.wvmg.protocol.connection.OutboundConnection] /
 * [io.github.kyujincho.wvmg.protocol.connection.InboundConnection])
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

    public companion object {
        /**
         * The Phase 1 default: a registry with a single trivial
         * Wi-Fi-LAN provider. Mirrors today's hard-coded behaviour and
         * is what [io.github.kyujincho.wvmg.protocol.connection.OutboundConnection]
         * and [io.github.kyujincho.wvmg.protocol.connection.InboundConnection]
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
