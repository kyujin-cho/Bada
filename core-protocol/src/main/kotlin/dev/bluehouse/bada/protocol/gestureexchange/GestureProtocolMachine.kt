/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.gestureexchange

import com.google.protobuf.ByteString
import dev.bluehouse.bada.protocol.gestureexchange.proto.ApplicationLabelRequest
import dev.bluehouse.bada.protocol.gestureexchange.proto.ApplicationLabelResponse
import dev.bluehouse.bada.protocol.gestureexchange.proto.ConnectionHandoverRequest
import dev.bluehouse.bada.protocol.gestureexchange.proto.ConnectionHandoverResponse
import dev.bluehouse.bada.protocol.gestureexchange.proto.ConnectivityCapability
import dev.bluehouse.bada.protocol.gestureexchange.proto.ConnectivityInfo
import dev.bluehouse.bada.protocol.gestureexchange.proto.GestureExchangeFeature
import dev.bluehouse.bada.protocol.gestureexchange.proto.HandoverResult
import dev.bluehouse.bada.protocol.gestureexchange.proto.HandoverResultStatus
import dev.bluehouse.bada.protocol.gestureexchange.proto.ManufacturerInfo
import dev.bluehouse.bada.protocol.gestureexchange.proto.ServiceData

/** Exact primary-AID constants for the user-facing “Tap to Share” feature. */
public object GestureAid {
    public val PRIMARY: ByteArray = byteArrayOf(0xA0.toByte(), 0x00, 0x00, 0x04, 0x76, 0x09)
    public val SELECT_PRIMARY_APDU: ByteArray =
        byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x06, 0xA0.toByte(), 0x00, 0x00, 0x04, 0x76, 0x09)
    public val OK: ByteArray = byteArrayOf(0x90.toByte(), 0x00)
    public val COMPLETED: ByteArray = byteArrayOf(0x69, 0x98.toByte())
    public val FAILURE: ByteArray = byteArrayOf(0x69, 0x99.toByte())
    public val FILE_NOT_FOUND: ByteArray = byteArrayOf(0x6A, 0x82.toByte())
    public val WRONG_LENGTH: ByteArray = byteArrayOf(0x67, 0x00)
    public val INS_NOT_SUPPORTED: ByteArray = byteArrayOf(0x6D, 0x00)
    public const val VERSION: Byte = 0xDC.toByte()
    public const val ZERO_PARTY_FILE_SHARE: String = "nearby.sharing"
}

/** Local Nearby identity placed in the encrypted capability request. */
public data class GestureLocalIdentity(
    val endpointId: String,
    val serviceId: String,
    val endpointInfo: ByteArray,
)

/**
 * Pure initiator state machine for Google primary-AID file sharing. Android's
 * reader owns `IsoDep`; this class owns ordering, crypto, protobufs, and strict
 * terminal validation. It never invokes the proprietary Name Card path.
 */
public class GestureReaderProtocolMachine(
    private val identity: GestureLocalIdentity,
    private val manufacturer: String,
    private val noise: GestureNoiseSession = GestureNoiseSession(GestureNoiseSession.Role.INITIATOR),
) {
    private var step: Step = Step.SELECT

    public sealed interface Result {
        public data class Continue(
            val bytes: ByteArray,
        ) : Result

        public data class ConnectivityReady(
            val info: ConnectivityInfo,
            val completionRequest: ByteArray,
        ) : Result

        public data object Completed : Result

        public data class Failed(
            val reason: String,
        ) : Result
    }

    public fun start(): ByteArray = GestureAid.SELECT_PRIMARY_APDU.clone()

    @Suppress("TooGenericExceptionCaught") // Convert parse/crypto failures into the typed protocol result.
    public fun handle(response: ByteArray): Result =
        try {
            when (step) {
                Step.SELECT ->
                    requireExact(response, GestureAid.OK, "primary AID rejected") {
                        step = Step.VERSION
                        Result.Continue(byteArrayOf(GestureAid.VERSION))
                    }
                Step.VERSION ->
                    requireExact(response, byteArrayOf(GestureAid.VERSION), "version 220 rejected") {
                        step = Step.KEY
                        Result.Continue(noise.createInitiatorKey())
                    }
                Step.KEY -> {
                    noise.acceptResponderKey(response)
                    step = Step.LABEL
                    val label =
                        ApplicationLabelRequest
                            .newBuilder()
                            .setCurrentFeature(GestureExchangeFeature.SYSTEM_SHARE_SHEET)
                            .setManufacturerInfo(ManufacturerInfo.newBuilder().setManufacturer(manufacturer))
                            .build()
                    Result.Continue(noise.encrypt(label.toByteArray()))
                }
                Step.LABEL -> handleLabel(response)
                Step.HANDOVER -> handleHandover(response)
                Step.COMPLETION ->
                    requireExact(response, GestureAid.COMPLETED, "peer did not complete") {
                        step = Step.DONE
                        noise.clear()
                        Result.Completed
                    }
                Step.DONE -> Result.Failed("session already completed")
            }
        } catch (t: Exception) {
            clear()
            Result.Failed(t.message ?: t::class.java.simpleName)
        }

    public fun clear() {
        step = Step.DONE
        noise.clear()
    }

    private fun handleLabel(response: ByteArray): Result {
        val label = ApplicationLabelResponse.parseFrom(noise.decrypt(response))
        require(label.selectedFeature == GestureExchangeFeature.SYSTEM_SHARE_SHEET) {
            "peer selected unsupported feature ${label.selectedFeature}"
        }
        val binding = noise.channelBindingToken()
        val capability =
            ConnectivityCapability
                .newBuilder()
                .addServiceData(
                    ServiceData
                        .newBuilder()
                        .setServiceId(identity.serviceId)
                        .setEndpointId(identity.endpointId)
                        .setZeroPartyIdentifier(GestureAid.ZERO_PARTY_FILE_SHARE),
                ).setChannelBindingToken(ByteString.copyFrom(binding))
                .build()
        val handover =
            ConnectionHandoverRequest
                .newBuilder()
                .setHandoverResult(
                    HandoverResult
                        .newBuilder()
                        .setStatus(HandoverResultStatus.MESSAGE)
                        .setPayload(ByteString.copyFrom(capability.toByteArray())),
                ).build()
        step = Step.HANDOVER
        return Result.Continue(noise.encrypt(handover.toByteArray()))
    }

    private fun handleHandover(response: ByteArray): Result {
        val envelope = ConnectionHandoverResponse.parseFrom(noise.decrypt(response))
        val result = envelope.handoverResult
        require(result.status == HandoverResultStatus.MESSAGE) { "peer handover status=${result.status}" }
        val info = ConnectivityInfo.parseFrom(result.payload)
        require(info.channelBindingToken.toByteArray().contentEquals(noise.channelBindingToken())) {
            "channel binding mismatch"
        }
        val completion =
            ConnectionHandoverRequest
                .newBuilder()
                .setHandoverResult(HandoverResult.newBuilder().setStatus(HandoverResultStatus.COMPLETED))
                .build()
        step = Step.COMPLETION
        return Result.ConnectivityReady(info, noise.encrypt(completion.toByteArray()))
    }

    private inline fun requireExact(
        actual: ByteArray,
        expected: ByteArray,
        message: String,
        block: () -> Result,
    ): Result {
        require(actual.contentEquals(expected)) { message }
        return block()
    }

    private enum class Step { SELECT, VERSION, KEY, LABEL, HANDOVER, COMPLETION, DONE }
}

/**
 * Pure responder state machine for the `A00000047609` HCE service. The Android
 * service supplies live connectivity only after it has created a real listener;
 * [NeedsConnectivity] is the asynchronous boundary used with `sendResponseApdu`.
 */
public class GestureTagProtocolMachine(
    private val manufacturer: String,
    private val noise: GestureNoiseSession = GestureNoiseSession(GestureNoiseSession.Role.RESPONDER),
) {
    private var step: Step = Step.SELECT

    public sealed interface Result {
        public data class Respond(
            val bytes: ByteArray,
        ) : Result

        public data class NeedsConnectivity(
            val capability: ConnectivityCapability,
        ) : Result

        public data object Completed : Result

        public data class Failed(
            val statusWord: ByteArray,
            val reason: String,
        ) : Result
    }

    @Suppress("TooGenericExceptionCaught") // Convert parse/crypto failures into an ISO-7816 status result.
    public fun handle(input: ByteArray): Result =
        try {
            when (step) {
                Step.SELECT -> handleSelect(input)
                Step.VERSION ->
                    require(input.contentEquals(byteArrayOf(GestureAid.VERSION))) { "unsupported version" }
                        .let {
                            step = Step.KEY
                            Result.Respond(byteArrayOf(GestureAid.VERSION))
                        }
                Step.KEY -> Result.Respond(noise.answerInitiatorKey(input)).also { step = Step.LABEL }
                Step.LABEL -> handleLabel(input)
                Step.HANDOVER -> handleHandover(input)
                Step.WAITING_CONNECTIVITY -> Result.Failed(GestureAid.FAILURE.clone(), "connectivity response pending")
                Step.COMPLETION -> handleCompletion(input)
                Step.DONE -> Result.Failed(GestureAid.FAILURE.clone(), "session already terminal")
            }
        } catch (t: Exception) {
            clear()
            Result.Failed(GestureAid.FAILURE.clone(), t.message ?: t::class.java.simpleName)
        }

    /** Completes a prior [Result.NeedsConnectivity] with live responder data. */
    public fun provideConnectivity(info: ConnectivityInfo): Result {
        if (step != Step.WAITING_CONNECTIVITY) {
            return Result.Failed(GestureAid.FAILURE.clone(), "no connectivity request pending")
        }
        val bound = info.toBuilder().setChannelBindingToken(ByteString.copyFrom(noise.channelBindingToken())).build()
        val envelope =
            ConnectionHandoverResponse
                .newBuilder()
                .setHandoverResult(
                    HandoverResult
                        .newBuilder()
                        .setStatus(HandoverResultStatus.MESSAGE)
                        .setPayload(ByteString.copyFrom(bound.toByteArray())),
                ).build()
        step = Step.COMPLETION
        return Result.Respond(noise.encrypt(envelope.toByteArray()))
    }

    public fun clear() {
        step = Step.DONE
        noise.clear()
    }

    @Suppress("ReturnCount", "MagicNumber") // ISO-7816 offsets and guard responses are exact wire constants.
    private fun handleSelect(input: ByteArray): Result {
        if (input.size < 5) return Result.Failed(GestureAid.WRONG_LENGTH.clone(), "SELECT too short")
        val length = input[4].toInt() and 0xFF
        if (input.size < 5 + length) return Result.Failed(GestureAid.WRONG_LENGTH.clone(), "SELECT length mismatch")
        val aid = input.copyOfRange(5, 5 + length)
        if (!aid.contentEquals(GestureAid.PRIMARY)) {
            return Result.Failed(GestureAid.FILE_NOT_FOUND.clone(), "different AID")
        }
        step = Step.VERSION
        return Result.Respond(GestureAid.OK.clone())
    }

    private fun handleLabel(input: ByteArray): Result {
        val request = ApplicationLabelRequest.parseFrom(noise.decrypt(input))
        require(request.currentFeature == GestureExchangeFeature.SYSTEM_SHARE_SHEET) {
            "only file Tap to Share is enabled"
        }
        val response =
            ApplicationLabelResponse
                .newBuilder()
                .setSelectedFeature(GestureExchangeFeature.SYSTEM_SHARE_SHEET)
                .setManufacturerInfo(ManufacturerInfo.newBuilder().setManufacturer(manufacturer))
                .build()
        step = Step.HANDOVER
        return Result.Respond(noise.encrypt(response.toByteArray()))
    }

    private fun handleHandover(input: ByteArray): Result {
        val request = ConnectionHandoverRequest.parseFrom(noise.decrypt(input))
        val result = request.handoverResult
        require(result.status == HandoverResultStatus.MESSAGE) { "expected handover message" }
        val capability = ConnectivityCapability.parseFrom(result.payload)
        require(capability.channelBindingToken.toByteArray().contentEquals(noise.channelBindingToken())) {
            "channel binding mismatch"
        }
        require(capability.serviceDataList.any { it.zeroPartyIdentifier == GestureAid.ZERO_PARTY_FILE_SHARE }) {
            "peer does not offer nearby.sharing"
        }
        step = Step.WAITING_CONNECTIVITY
        return Result.NeedsConnectivity(capability)
    }

    private fun handleCompletion(input: ByteArray): Result {
        val request = ConnectionHandoverRequest.parseFrom(noise.decrypt(input))
        require(request.handoverResult.status == HandoverResultStatus.COMPLETED) { "missing completion status" }
        clear()
        return Result.Respond(GestureAid.COMPLETED.clone())
    }

    private enum class Step { SELECT, VERSION, KEY, LABEL, HANDOVER, WAITING_CONNECTIVITY, COMPLETION, DONE }
}
