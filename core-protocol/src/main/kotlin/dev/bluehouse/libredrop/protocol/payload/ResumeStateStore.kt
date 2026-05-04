/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.protocol.payload

/**
 * Persistent store of how far each in-flight FILE payload has gotten,
 * keyed by `(endpointId, payloadId)`.
 *
 * The receiver-side resume protocol (#43) needs to remember, across
 * connection lifetimes, which byte ranges of which payloads have
 * already been written to disk. When the same peer reconnects, the
 * receiver consults the store to:
 *
 *  1. Tell the [PayloadAssembler] to seed pre-existing coverage so
 *     duplicate chunks (the sender retransmits from offset 0) are
 *     deduplicated against on-disk bytes rather than re-written.
 *  2. Build the `AUTO_RESUME` frame announcing where to pick up.
 *
 * #### Identity model
 *
 * `endpointId` is the peer's wire-level endpoint identifier — the
 * 4-character string from the `ConnectionRequest` frame, persisted
 * across reconnects by the discovery layer. It is NOT the user-facing
 * device name (which can change). Pairing on this id keeps the resume
 * window correctly scoped: a different peer that happens to claim a
 * payload id we have seen does not get to inherit our partial file.
 *
 * `payloadId` is the `PayloadHeader.id` from the original session.
 * The sender is required to use the same id on resume; this is the
 * AUTO_RESUME contract. If the sender re-announces the same logical
 * file with a fresh id, we cannot match — the bytes start over.
 *
 * #### Threading
 *
 * Implementations MUST be thread-safe. The [PayloadAssembler]'s
 * receive loop calls [recordCoverage] from a single coroutine, but
 * [loadCoverage] / [purgeOlderThan] / [forget] may be called from a
 * UI coroutine or from the foreground service's GC tick. A
 * [java.util.concurrent.ConcurrentHashMap] or a `synchronized` block
 * is sufficient — all access patterns are short.
 *
 * #### Serialization
 *
 * Each [ResumeRecord] is value-typed and trivially serializable
 * (string + long + ranges of long pairs). The Android-side store can
 * persist as JSON in the app's `cacheDir`; the JVM-only test fake
 * keeps a `ConcurrentHashMap`. No Room schema migration is needed.
 *
 * @see InMemoryResumeStateStore
 */
public interface ResumeStateStore {
    /**
     * Persist that [endpointId] has received bytes `[start, end)` of
     * [payloadId] of total size [totalSize], at the given wall-clock
     * timestamp [updatedAtMillis].
     *
     * Implementations MUST merge the new range into any existing
     * coverage for the same key. The merge semantics are those of
     * [ByteRangeSet.add]: adjacent and overlapping ranges collapse;
     * partial-overlap-with-extension is **not** explicitly handled by
     * the store — the assembler has already raised a protocol error
     * for that case before the bytes hit disk.
     *
     * @param endpointId Peer endpoint identifier.
     * @param payloadId The `PayloadHeader.id`.
     * @param totalSize The payload's `total_size`. Used to detect
     *   completion in [loadCoverage] and to garbage-collect once a
     *   payload is fully covered.
     * @param start Inclusive lower bound of the newly-covered range.
     * @param end Exclusive upper bound. `start <= end`.
     * @param updatedAtMillis Wall-clock timestamp; used by
     *   [purgeOlderThan] to GC stale records. Production wires
     *   `System.currentTimeMillis()`; tests inject a fixed value.
     */
    public fun recordCoverage(
        endpointId: String,
        payloadId: Long,
        totalSize: Long,
        start: Long,
        end: Long,
        updatedAtMillis: Long,
    )

    /**
     * Look up the coverage state for `(endpointId, payloadId)`.
     *
     * @return The recorded [ResumeRecord], or `null` if no record
     *   exists. Records that have been GC'd by [purgeOlderThan] also
     *   return `null`.
     */
    public fun loadCoverage(
        endpointId: String,
        payloadId: Long,
    ): ResumeRecord?

    /**
     * Drop the record for `(endpointId, payloadId)`. Called by the
     * receiver after the payload completes successfully — there is
     * no further need to reserve resume state.
     */
    public fun forget(
        endpointId: String,
        payloadId: Long,
    )

    /**
     * Drop every record older than [cutoffMillis] (exclusive).
     *
     * The acceptance criteria in #43 specify a 24 h GC window. The
     * caller (the foreground service) wires up
     * `cutoffMillis = System.currentTimeMillis() - 24 * 60 * 60 * 1000L`
     * on a periodic timer.
     *
     * @return The number of records removed.
     */
    public fun purgeOlderThan(cutoffMillis: Long): Int
}

/**
 * A single payload's persisted resume state.
 *
 * @property endpointId Peer endpoint identifier.
 * @property payloadId `PayloadHeader.id`.
 * @property totalSize Total payload size; the sender must announce
 *   the same value on resume for the record to apply.
 * @property coverage Sorted, non-overlapping byte ranges already
 *   written to disk. Built by feeding every chunk's `[offset,
 *   offset + body.size)` into [ByteRangeSet.add] across the
 *   payload's lifetime.
 * @property updatedAtMillis Wall-clock timestamp of the most recent
 *   chunk recorded for this payload; drives [ResumeStateStore.purgeOlderThan].
 */
public data class ResumeRecord(
    val endpointId: String,
    val payloadId: Long,
    val totalSize: Long,
    val coverage: List<ByteRangeSet.Range>,
    val updatedAtMillis: Long,
) {
    /**
     * Build a [ByteRangeSet] populated with this record's coverage.
     * Useful for seeding the [PayloadAssembler] when a fresh
     * connection wants to skip already-received bytes.
     */
    public fun toByteRangeSet(): ByteRangeSet {
        val s = ByteRangeSet()
        for (r in coverage) s.add(r.start, r.end)
        return s
    }
}

/**
 * Pure JVM, in-memory [ResumeStateStore] implementation. Used by
 * `:core-protocol`'s tests and by any future JVM-side host harness;
 * Android production wires a JSON-backed store under
 * `:service-android` (`PersistentResumeStateStore`).
 *
 * Thread-safe via a single backing
 * [java.util.concurrent.ConcurrentHashMap]. The hashmap holds
 * [ResumeRecord]s keyed by an opaque `String` that combines
 * `endpointId` and `payloadId` — chosen instead of a typed `Pair` so
 * the JSON-backed Android implementation can use the same key shape
 * without paying for a custom `Map.Entry.equals` per lookup.
 */
public class InMemoryResumeStateStore : ResumeStateStore {
    private val records: java.util.concurrent.ConcurrentHashMap<String, ResumeRecord> =
        java.util.concurrent.ConcurrentHashMap()

    override fun recordCoverage(
        endpointId: String,
        payloadId: Long,
        totalSize: Long,
        start: Long,
        end: Long,
        updatedAtMillis: Long,
    ) {
        val key = key(endpointId, payloadId)
        records.compute(key) { _, existing ->
            val ranges = ByteRangeSet()
            existing?.coverage?.forEach { ranges.add(it.start, it.end) }
            ranges.add(start, end)
            ResumeRecord(
                endpointId = endpointId,
                payloadId = payloadId,
                totalSize = totalSize,
                coverage = ranges.snapshot(),
                updatedAtMillis = updatedAtMillis,
            )
        }
    }

    override fun loadCoverage(
        endpointId: String,
        payloadId: Long,
    ): ResumeRecord? = records[key(endpointId, payloadId)]

    override fun forget(
        endpointId: String,
        payloadId: Long,
    ) {
        records.remove(key(endpointId, payloadId))
    }

    override fun purgeOlderThan(cutoffMillis: Long): Int {
        var removed = 0
        val it = records.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.value.updatedAtMillis < cutoffMillis) {
                it.remove()
                removed += 1
            }
        }
        return removed
    }

    /** Visible for tests that want to assert on the cardinality. */
    public val recordCount: Int get() = records.size

    private fun key(
        endpointId: String,
        payloadId: Long,
    ): String = "$endpointId#$payloadId"
}
