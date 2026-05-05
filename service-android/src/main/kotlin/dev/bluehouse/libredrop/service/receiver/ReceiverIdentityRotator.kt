/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.libredrop.service.receiver

import dev.bluehouse.libredrop.protocol.connection.InboundResult
import dev.bluehouse.libredrop.protocol.endpoint.EndpointInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Rotates the receiver's advertised BLE/mDNS identity after an inbound terminal
 * result.
 *
 * Samsung ShareLive caches the off-LAN receiver by BLE endpoint id / token after
 * a BLE GATT + Wi-Fi Direct handoff. Reusing that identity for the next tap can
 * make ShareLive skip the fresh GATT path and attempt a stale `mosey0` TCP
 * connection directly. Device testing showed the cache can be poisoned by a
 * peer-cancelled handoff as well as a successful transfer, so every terminal
 * inbound result invalidates the discovery identity while preserving the visible
 * device name.
 */
internal class ReceiverIdentityRotator(
    private val nextIdentityProvider: () -> EndpointInfo,
    private val bleBroadcasterFactory: () -> BleVisibilityBroadcaster,
    private val logger: (String) -> Unit = {},
) {
    private val rotationMutex = Mutex()

    fun start(
        scope: CoroutineScope,
        session: ReceiverSession,
    ): Job =
        scope.launch {
            logger("identity rotate: observing inbound completions")
            session.completions.collect { completion ->
                runCatching {
                    rotationMutex.withLock {
                        rotateAfterResult(completion.result, session)
                    }
                }.onFailure { cause ->
                    logger(
                        "identity rotate: failed to process completion " +
                            "result=${completion.result} " +
                            "error=${cause::class.simpleName}: ${cause.message}",
                    )
                }
            }
        }

    fun rotateAfterResult(
        result: InboundResult,
        activeSession: ReceiverSession,
    ): Boolean = rotateAfterTerminalInbound(activeSession, result.rotationReason())

    private fun rotateAfterTerminalInbound(
        activeSession: ReceiverSession,
        reason: String,
    ): Boolean {
        if (!activeSession.isRunning) {
            logger("identity rotate: skipped reason=$reason sessionRunning=false")
            return false
        }

        val previousIdentity = EndpointIdentityHolder.snapshot.get()
        val previousEndpointId = BleEndpointIdHolder.snapshot()
        val nextIdentity = nextIdentityProvider()
        val nextEndpointId = BleEndpointIdHolder.newCandidate()
        val wasAdvertising = activeSession.isAdvertising
        logger(
            "identity rotate: start reason=$reason " +
                "previousEndpointId=${previousEndpointId?.toAsciiLabel()} " +
                "nextEndpointId=${nextEndpointId.toAsciiLabel()}",
        )
        val applied =
            BleEndpointIdHolder.applyCandidate(nextEndpointId) {
                activeSession.replaceEndpointInfo(
                    endpointInfo = nextIdentity,
                    requireAdvertisement = false,
                )
            }

        val rotated =
            if (applied) {
                completeAppliedRotation(activeSession, nextIdentity, nextEndpointId, wasAdvertising, reason)
                true
            } else {
                restorePreviousRotation(activeSession, previousIdentity, previousEndpointId, wasAdvertising)
                false
            }
        return rotated
    }

    private fun completeAppliedRotation(
        activeSession: ReceiverSession,
        nextIdentity: EndpointInfo,
        nextEndpointId: ByteArray,
        wasAdvertising: Boolean,
        reason: String,
    ) {
        EndpointIdentityHolder.snapshot.set(nextIdentity)
        if (activeSession.isAdvertising) {
            bleBroadcasterFactory().start()
        }
        logger(
            "identity rotate: reason=$reason " +
                "endpointId=${nextEndpointId.toAsciiLabel()} wasAdvertising=$wasAdvertising",
        )
    }

    private fun restorePreviousRotation(
        activeSession: ReceiverSession,
        previousIdentity: EndpointInfo?,
        previousEndpointId: ByteArray?,
        wasAdvertising: Boolean,
    ) {
        BleEndpointIdHolder.restore(previousEndpointId)
        EndpointIdentityHolder.snapshot.set(previousIdentity)
        if (previousIdentity != null && wasAdvertising) {
            runCatching { activeSession.publishAdvertisement() }
            if (activeSession.isAdvertising) {
                bleBroadcasterFactory().start()
            }
        }
        logger("identity rotate: failed, restored previous identity")
    }

    private fun InboundResult.rotationReason(): String =
        when (this) {
            is InboundResult.Completed -> "completed"
            InboundResult.Rejected -> "rejected"
            is InboundResult.Cancelled -> "cancelled-${cause.name.lowercase()}"
            is InboundResult.Failed -> "failed"
        }
}
