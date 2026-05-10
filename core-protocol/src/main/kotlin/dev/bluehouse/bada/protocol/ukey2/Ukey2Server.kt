/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.ukey2

import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import com.google.security.cryptauth.lib.securegcm.UkeyProto.Ukey2ClientFinished
import com.google.security.cryptauth.lib.securegcm.UkeyProto.Ukey2ClientInit
import com.google.security.cryptauth.lib.securegcm.UkeyProto.Ukey2HandshakeCipher
import com.google.security.cryptauth.lib.securegcm.UkeyProto.Ukey2Message
import com.google.security.cryptauth.lib.securegcm.UkeyProto.Ukey2ServerInit
import dev.bluehouse.bada.protocol.transport.FramedConnection
import dev.bluehouse.bada.protocol.ukey2.Ukey2.NEXT_PROTOCOL
import dev.bluehouse.bada.protocol.ukey2.Ukey2.PROTOCOL_VERSION
import dev.bluehouse.bada.protocol.ukey2.Ukey2.RANDOM_SIZE
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey

/**
 * Server (responder) side of the UKEY2 P256_SHA512 handshake.
 *
 * The server waits for `ClientInit`, validates every field, replies with
 * `ServerInit`, then waits for `ClientFinished` and verifies that
 * `SHA-512(ClientFinishedMessageBytes)` matches the cipher commitment
 * the client published in step 1.
 *
 *  1. Receive `ClientInit`. Validate type/version/random length, find
 *     `P256_SHA512` in the cipher commitment list, confirm the
 *     `next_protocol` is exactly `AES_256_CBC-HMAC_SHA256`. On any
 *     failure, emit the matching `Ukey2Alert` and throw.
 *  2. Generate an ephemeral P-256 keypair.
 *  3. Send `ServerInit{P256_SHA512, our_public_key, random=32B}`.
 *  4. Receive `ClientFinished`. Verify the commitment using a
 *     constant-time byte comparison ([java.security.MessageDigest.isEqual]).
 *     On mismatch, emit `BAD_MESSAGE` and throw — this is the security
 *     check that makes the cipher-commitment-then-reveal flow work.
 *  5. Parse the client's public key and run ECDH.
 *
 * The `Ukey2Alert` send is a best-effort courtesy: the peer is going to
 * be torn down regardless, so failures on the alert write are silently
 * swallowed (see [sendUkey2Alert]).
 *
 * @see Ukey2Client for the initiator side.
 */
public object Ukey2Server {
    /**
     * Performs a complete UKEY2 server handshake over [connection].
     *
     * @param connection The framed transport. Owned by the caller.
     * @param secureRandom Optional override for keypair generation and
     *   the 32-byte server random nonce. Default is a fresh
     *   `SecureRandom()`; tests inject deterministic instances.
     * @return The handshake result: `dhs`, `clientInitMsg`,
     *   `serverInitMsg`. Forwarded to the next-stage HKDF derivation.
     * @throws Ukey2HandshakeException If the client violates the
     *   protocol. The exception's [Ukey2HandshakeException.alert] field
     *   describes the alert that was sent (best-effort) to the peer.
     * @throws java.io.IOException If the underlying [connection] fails.
     */
    public suspend fun performHandshake(
        connection: FramedConnection,
        secureRandom: SecureRandom = SecureRandom(),
    ): Ukey2HandshakeResult {
        // Step 1: receive and validate ClientInit. Capture the cipher
        // commitment for the post-ClientFinished verification.
        val clientInitMsgBytes = connection.receiveFrame()
        val commitment = parseAndValidateClientInit(connection, clientInitMsgBytes)

        // Step 2: generate our ephemeral keypair.
        val keyPair = Ukey2Crypto.generateP256KeyPair(secureRandom)
        val ourPrivateKey = keyPair.private as ECPrivateKey
        val ourPublicKey = keyPair.public as ECPublicKey

        // Step 3: build and send ServerInit.
        val serverRandom = ByteArray(RANDOM_SIZE).also(secureRandom::nextBytes)
        val serverInitMsgBytes = buildServerInitMessage(serverRandom, ourPublicKey)
        connection.sendFrame(serverInitMsgBytes)

        // Step 4: receive ClientFinished. Verify commitment in
        // constant time BEFORE parsing the inner proto, so a malformed
        // client cannot leak commitment-byte timing.
        val clientFinishedMsgBytes = connection.receiveFrame()
        verifyCipherCommitment(connection, expected = commitment, actualMsgBytes = clientFinishedMsgBytes)

        // Step 5: parse the inner ClientFinished and run ECDH.
        val clientPublicKey = parseClientFinished(connection, clientFinishedMsgBytes)
        val dhs = Ukey2Crypto.computeDhs(ourPrivateKey, clientPublicKey)

        return Ukey2HandshakeResult(
            dhs = dhs,
            clientInitMsg = clientInitMsgBytes,
            serverInitMsg = serverInitMsgBytes,
        )
    }

    /**
     * Parses a `Ukey2Message{CLIENT_INIT}` frame, validates every field,
     * and returns the cipher commitment for `P256_SHA512`. Sends an
     * appropriate `Ukey2Alert` and throws on any malformed input.
     */
    @Suppress("ThrowsCount", "ReturnCount", "LongMethod")
    private suspend fun parseAndValidateClientInit(
        connection: FramedConnection,
        clientInitMsgBytes: ByteArray,
    ): ByteArray {
        val outer =
            try {
                Ukey2Message.parseFrom(clientInitMsgBytes)
            } catch (ex: InvalidProtocolBufferException) {
                sendUkey2Alert(connection, Ukey2AlertType.BAD_MESSAGE)
                throw Ukey2HandshakeException(
                    alert = Ukey2AlertType.BAD_MESSAGE,
                    message = "Could not deserialize Ukey2Message from ClientInit frame",
                    cause = ex,
                )
            }

        if (outer.messageType != Ukey2Message.Type.CLIENT_INIT) {
            sendUkey2Alert(connection, Ukey2AlertType.BAD_MESSAGE_TYPE)
            throw Ukey2HandshakeException(
                alert = Ukey2AlertType.BAD_MESSAGE_TYPE,
                message = "Expected CLIENT_INIT, got ${outer.messageType}",
            )
        }

        val clientInit =
            try {
                Ukey2ClientInit.parseFrom(outer.messageData)
            } catch (ex: InvalidProtocolBufferException) {
                sendUkey2Alert(connection, Ukey2AlertType.BAD_MESSAGE)
                throw Ukey2HandshakeException(
                    alert = Ukey2AlertType.BAD_MESSAGE,
                    message = "Could not deserialize Ukey2ClientInit",
                    cause = ex,
                )
            }

        if (clientInit.version != PROTOCOL_VERSION) {
            sendUkey2Alert(connection, Ukey2AlertType.BAD_VERSION)
            throw Ukey2HandshakeException(
                alert = Ukey2AlertType.BAD_VERSION,
                message = "Client version is ${clientInit.version}, expected $PROTOCOL_VERSION",
            )
        }

        if (!clientInit.hasRandom() || clientInit.random.size() != RANDOM_SIZE) {
            sendUkey2Alert(connection, Ukey2AlertType.BAD_RANDOM)
            throw Ukey2HandshakeException(
                alert = Ukey2AlertType.BAD_RANDOM,
                message =
                    "Client random must be exactly $RANDOM_SIZE bytes, got " +
                        "${if (clientInit.hasRandom()) clientInit.random.size() else "absent"}",
            )
        }

        val matched =
            clientInit.cipherCommitmentsList.firstOrNull {
                it.handshakeCipher == Ukey2HandshakeCipher.P256_SHA512
            }
        if (matched == null) {
            sendUkey2Alert(connection, Ukey2AlertType.BAD_HANDSHAKE_CIPHER)
            throw Ukey2HandshakeException(
                alert = Ukey2AlertType.BAD_HANDSHAKE_CIPHER,
                message = "Client offered no P256_SHA512 cipher commitment",
            )
        }

        if (clientInit.nextProtocol != NEXT_PROTOCOL) {
            sendUkey2Alert(connection, Ukey2AlertType.BAD_NEXT_PROTOCOL)
            throw Ukey2HandshakeException(
                alert = Ukey2AlertType.BAD_NEXT_PROTOCOL,
                message =
                    "Client requested next_protocol \"${clientInit.nextProtocol}\", " +
                        "expected \"$NEXT_PROTOCOL\"",
            )
        }

        return matched.commitment.toByteArray()
    }

    /**
     * Builds the serialized `Ukey2Message{SERVER_INIT, ...}`.
     *
     * The inner `Ukey2ServerInit.public_key` field carries the bytes of
     * a `securemessage.GenericPublicKey` — NOT a JCE-encoded SPKI. The
     * server is expected to canonicalize coordinates to exactly 32 bytes
     * (see [Ukey2KeyEncoding] for the rationale and the alternative
     * forms peers may emit).
     */
    private fun buildServerInitMessage(
        random: ByteArray,
        ourPublicKey: ECPublicKey,
    ): ByteArray {
        val genericPublicKeyBytes = Ukey2KeyEncoding.serialize(ourPublicKey)
        val serverInit =
            Ukey2ServerInit
                .newBuilder()
                .setVersion(PROTOCOL_VERSION)
                .setRandom(ByteString.copyFrom(random))
                .setHandshakeCipher(Ukey2HandshakeCipher.P256_SHA512)
                .setPublicKey(ByteString.copyFrom(genericPublicKeyBytes))
                .build()
        return Ukey2Message
            .newBuilder()
            .setMessageType(Ukey2Message.Type.SERVER_INIT)
            .setMessageData(ByteString.copyFrom(serverInit.toByteArray()))
            .build()
            .toByteArray()
    }

    /**
     * Verifies that `SHA-512(actualMsgBytes) == expected` in constant
     * time, using [java.security.MessageDigest.isEqual]. On mismatch,
     * sends a `BAD_MESSAGE` alert and throws.
     *
     * `MessageDigest.isEqual` was hardened against timing attacks in JDK
     * 7u40+; on every JDK we ship against (17+) it performs a
     * length-check followed by a constant-time XOR-then-OR loop, exactly
     * what we want for commitment comparison.
     */
    private suspend fun verifyCipherCommitment(
        connection: FramedConnection,
        expected: ByteArray,
        actualMsgBytes: ByteArray,
    ) {
        val actualHash = Ukey2Crypto.sha512(actualMsgBytes)
        if (!MessageDigest.isEqual(expected, actualHash)) {
            sendUkey2Alert(connection, Ukey2AlertType.BAD_MESSAGE)
            throw Ukey2HandshakeException(
                alert = Ukey2AlertType.BAD_MESSAGE,
                message = "ClientFinished SHA-512 does not match the announced cipher commitment",
            )
        }
    }

    /**
     * Parses the `Ukey2Message{CLIENT_FINISH, ...}` frame after its
     * SHA-512 has been verified against the commitment. The verification
     * order matters: we never deserialize an unverified inner frame, so
     * a malicious peer cannot exploit a parser bug by pre-image-attacking
     * the commitment.
     */
    @Suppress("ThrowsCount")
    private suspend fun parseClientFinished(
        connection: FramedConnection,
        clientFinishedMsgBytes: ByteArray,
    ): ECPublicKey {
        val outer =
            try {
                Ukey2Message.parseFrom(clientFinishedMsgBytes)
            } catch (ex: InvalidProtocolBufferException) {
                sendUkey2Alert(connection, Ukey2AlertType.BAD_MESSAGE)
                throw Ukey2HandshakeException(
                    alert = Ukey2AlertType.BAD_MESSAGE,
                    message = "Could not deserialize Ukey2Message from ClientFinished frame",
                    cause = ex,
                )
            }

        if (outer.messageType != Ukey2Message.Type.CLIENT_FINISH) {
            sendUkey2Alert(connection, Ukey2AlertType.BAD_MESSAGE_TYPE)
            throw Ukey2HandshakeException(
                alert = Ukey2AlertType.BAD_MESSAGE_TYPE,
                message = "Expected CLIENT_FINISH, got ${outer.messageType}",
            )
        }

        val clientFinished =
            try {
                Ukey2ClientFinished.parseFrom(outer.messageData)
            } catch (ex: InvalidProtocolBufferException) {
                sendUkey2Alert(connection, Ukey2AlertType.BAD_MESSAGE)
                throw Ukey2HandshakeException(
                    alert = Ukey2AlertType.BAD_MESSAGE,
                    message = "Could not deserialize Ukey2ClientFinished",
                    cause = ex,
                )
            }

        if (!clientFinished.hasPublicKey()) {
            sendUkey2Alert(connection, Ukey2AlertType.BAD_PUBLIC_KEY)
            throw Ukey2HandshakeException(
                alert = Ukey2AlertType.BAD_PUBLIC_KEY,
                message = "ClientFinished did not include a public_key",
            )
        }

        return try {
            Ukey2KeyEncoding.parse(clientFinished.publicKey.toByteArray())
        } catch (ex: Ukey2HandshakeException) {
            // Re-fire the alert (parse() does not have access to the
            // FramedConnection) and rethrow the same exception.
            ex.alert?.let { sendUkey2Alert(connection, it) }
            throw ex
        }
    }
}
