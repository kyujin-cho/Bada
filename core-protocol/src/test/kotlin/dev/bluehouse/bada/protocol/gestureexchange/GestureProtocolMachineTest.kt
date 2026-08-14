/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.gestureexchange

import com.google.common.truth.Truth.assertThat
import dev.bluehouse.bada.protocol.gestureexchange.proto.ApplicationLabelRequest
import dev.bluehouse.bada.protocol.gestureexchange.proto.ConnectivityInfo
import dev.bluehouse.bada.protocol.gestureexchange.proto.EndpointConnectivityEntry
import dev.bluehouse.bada.protocol.gestureexchange.proto.GestureExchangeFeature
import dev.bluehouse.bada.protocol.gestureexchange.proto.WifiDirectCredentials
import org.junit.jupiter.api.Test

class GestureProtocolMachineTest {
    @Test
    fun `primary file-share sequence completes with matching channel binding`() {
        val initiator = GestureNoiseSession(GestureNoiseSession.Role.INITIATOR)
        val responder = GestureNoiseSession(GestureNoiseSession.Role.RESPONDER)
        val first = initiator.createInitiatorKey()
        val second = responder.answerInitiatorKey(first)
        initiator.acceptResponderKey(second)

        assertThat(initiator.channelBindingToken()).isEqualTo(responder.channelBindingToken())
        val ciphertext = initiator.encrypt("hello".toByteArray())
        assertThat(responder.decrypt(ciphertext)).isEqualTo("hello".toByteArray())
        val reply = responder.encrypt("world".toByteArray())
        assertThat(initiator.decrypt(reply)).isEqualTo("world".toByteArray())
    }

    @Test
    fun `complete reader and tag machines preserve connectivity info`() {
        val reader =
            GestureReaderProtocolMachine(
                GestureLocalIdentity("ABCD", "NearbySharing", byteArrayOf(1, 2, 3)),
                manufacturer = "SuperDrop",
            )
        val tag = GestureTagProtocolMachine(manufacturer = "SuperDrop")

        var readerOutput: GestureReaderProtocolMachine.Result =
            GestureReaderProtocolMachine.Result.Continue(reader.start())
        var expectedInfo: ConnectivityInfo? = null
        repeat(5) {
            val request = (readerOutput as GestureReaderProtocolMachine.Result.Continue).bytes
            val tagOutput = tag.handle(request)
            val response =
                when (tagOutput) {
                    is GestureTagProtocolMachine.Result.Respond -> tagOutput.bytes
                    is GestureTagProtocolMachine.Result.NeedsConnectivity -> {
                        expectedInfo =
                            ConnectivityInfo
                                .newBuilder()
                                .addEntries(
                                    EndpointConnectivityEntry
                                        .newBuilder()
                                        .setServiceId("NearbySharing")
                                        .setEndpointId("WXYZ")
                                        .setEndpointInfo(
                                            com.google.protobuf.ByteString
                                                .copyFrom(byteArrayOf(9, 8)),
                                        ).setZeroPartyIdentifier(GestureAid.ZERO_PARTY_FILE_SHARE)
                                        .setWifiDirect(
                                            WifiDirectCredentials
                                                .newBuilder()
                                                .setSsid("DIRECT-SD-test")
                                                .setPassword("password")
                                                .setPort(12345)
                                                .setFrequencyMhz(2412),
                                        ),
                                ).build()
                        (
                            tag.provideConnectivity(
                                checkNotNull(expectedInfo),
                            ) as GestureTagProtocolMachine.Result.Respond
                        ).bytes
                    }
                    else -> error("unexpected tag output $tagOutput")
                }
            readerOutput = reader.handle(response)
        }

        val ready = readerOutput as GestureReaderProtocolMachine.Result.ConnectivityReady
        assertThat(ready.info.entriesList).isEqualTo(checkNotNull(expectedInfo).entriesList)
        val finalTag = tag.handle(ready.completionRequest) as GestureTagProtocolMachine.Result.Respond
        assertThat(reader.handle(finalTag.bytes)).isEqualTo(GestureReaderProtocolMachine.Result.Completed)
    }

    @Test
    fun `tag rejects Name Card feature substitution`() {
        val initiatorNoise = GestureNoiseSession(GestureNoiseSession.Role.INITIATOR)
        val tag = GestureTagProtocolMachine(manufacturer = "SuperDrop")

        assertThat((tag.handle(GestureAid.SELECT_PRIMARY_APDU) as GestureTagProtocolMachine.Result.Respond).bytes)
            .isEqualTo(GestureAid.OK)
        assertThat((tag.handle(byteArrayOf(GestureAid.VERSION)) as GestureTagProtocolMachine.Result.Respond).bytes)
            .isEqualTo(byteArrayOf(GestureAid.VERSION))
        val responderKey =
            (tag.handle(initiatorNoise.createInitiatorKey()) as GestureTagProtocolMachine.Result.Respond).bytes
        initiatorNoise.acceptResponderKey(responderKey)

        val contactRequest =
            ApplicationLabelRequest
                .newBuilder()
                .setCurrentFeature(GestureExchangeFeature.CONTACT_EXCHANGE)
                .build()
        val result = tag.handle(initiatorNoise.encrypt(contactRequest.toByteArray()))

        assertThat(result).isInstanceOf(GestureTagProtocolMachine.Result.Failed::class.java)
        assertThat((result as GestureTagProtocolMachine.Result.Failed).statusWord).isEqualTo(GestureAid.FAILURE)
        assertThat(result.reason).contains("only file Tap to Share is enabled")
    }
}
