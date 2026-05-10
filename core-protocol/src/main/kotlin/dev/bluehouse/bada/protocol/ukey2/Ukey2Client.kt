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
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey

/**
 * Client (initiator) side of the UKEY2 P256_SHA512 handshake.
 *
 * The client drives the cipher-commitment-then-reveal flow:
 *
 *  1. Generate an ephemeral P-256 keypair.
 *  2. Pre-compute `ClientFinished{public_key}` bytes (wrapped in a
 *     `Ukey2Message`) and SHA-512 those bytes to form the cipher
 *     commitment — the public key is committed to **before** the server
 *     gets to see it.
 *  3. Send `ClientInit{cipher_commitments=[(P256_SHA512, commitment)],
 *     next_protocol="AES_256_CBC-HMAC_SHA256", random=32B}`.
 *  4. Receive and validate `ServerInit`.
 *  5. Reveal `ClientFinished` (the same bytes we hashed in step 2).
 *  6. Run ECDH with the server's public key, hash the resulting X
 *     magnitude, and return the [Ukey2HandshakeResult].
 *
 * Steps 2 and 5 are tightly coupled: the bytes hashed in step 2 must be
 * the **exact** bytes sent in step 5, otherwise the server's commitment
 * verification fails. This implementation captures the serialized
 * `Ukey2Message` once and reuses it.
 *
 * @see Ukey2Server for the responder side.
 * @see Ukey2HandshakeResult for the data this returns to callers.
 */
public object Ukey2Client {
    /**
     * Performs a complete UKEY2 client handshake over [connection],
     * blocking the calling coroutine until the handshake either succeeds
     * or fails.
     *
     * @param connection The framed transport (post-TCP, pre-cipher) over
     *   which to exchange UKEY2 frames. Owned by the caller; not closed
     *   on failure (the caller decides whether to recycle the socket
     *   or tear it down).
     * @param secureRandom Optional override for keypair generation and
     *   the 32-byte random nonce. Default is a fresh `SecureRandom()`;
     *   tests inject deterministic instances for reproducibility.
     * @return The handshake result: `dhs`, `clientInitMsg`,
     *   `serverInitMsg`. The caller passes these into the next-stage
     *   HKDF derivation (see issue #11).
     * @throws Ukey2HandshakeException If the server emits a `Ukey2Alert`
     *   frame, or if its `ServerInit` is malformed / unexpected.
     * @throws java.io.IOException If the underlying [connection] fails
     *   mid-handshake.
     */
    public suspend fun performHandshake(
        connection: FramedConnection,
        secureRandom: SecureRandom = SecureRandom(),
    ): Ukey2HandshakeResult {
        val keyPair = Ukey2Crypto.generateP256KeyPair(secureRandom)
        val ourPrivateKey = keyPair.private as ECPrivateKey
        val ourPublicKey = keyPair.public as ECPublicKey

        // Step 1: build (but don't yet send) ClientFinished. Its bytes
        // are what gets committed to.
        val clientFinishedMsgBytes = buildClientFinishedMessage(ourPublicKey)
        val commitment = Ukey2Crypto.sha512(clientFinishedMsgBytes)

        // Step 2: build ClientInit with the commitment and a fresh random.
        val clientRandom = ByteArray(RANDOM_SIZE).also(secureRandom::nextBytes)
        val clientInitMsgBytes = buildClientInitMessage(clientRandom, commitment)

        // Step 3: send ClientInit.
        connection.sendFrame(clientInitMsgBytes)

        // Step 4: receive ServerInit and validate every field.
        val serverInitMsgBytes = connection.receiveFrame()
        val serverPublicKey = parseAndValidateServerInit(serverInitMsgBytes)

        // Step 5: reveal ClientFinished (bytes are identical to step 1).
        connection.sendFrame(clientFinishedMsgBytes)

        // Step 6: run ECDH and hash the X magnitude.
        val dhs = Ukey2Crypto.computeDhs(ourPrivateKey, serverPublicKey)

        return Ukey2HandshakeResult(
            dhs = dhs,
            clientInitMsg = clientInitMsgBytes,
            serverInitMsg = serverInitMsgBytes,
        )
    }

    /**
     * Builds the serialized `Ukey2Message{CLIENT_FINISH, ...}` carrying
     * our public key. The exact bytes returned here are both:
     *
     *  - SHA-512-hashed to form the cipher commitment in `ClientInit`.
     *  - Sent verbatim on the wire after we receive `ServerInit`.
     *
     * Any mismatch between the hashed bytes and the sent bytes would
     * cause the server's commitment check to fail with a `BAD_MESSAGE`
     * alert, so we serialize once and reuse the same byte array
     * unconditionally.
     */
    private fun buildClientFinishedMessage(ourPublicKey: ECPublicKey): ByteArray {
        val genericPublicKeyBytes = Ukey2KeyEncoding.serialize(ourPublicKey)
        val clientFinished =
            Ukey2ClientFinished
                .newBuilder()
                .setPublicKey(ByteString.copyFrom(genericPublicKeyBytes))
                .build()
        return Ukey2Message
            .newBuilder()
            .setMessageType(Ukey2Message.Type.CLIENT_FINISH)
            .setMessageData(ByteString.copyFrom(clientFinished.toByteArray()))
            .build()
            .toByteArray()
    }

    /**
     * Builds the serialized `Ukey2Message{CLIENT_INIT, ...}` carrying the
     * version, random nonce, P256_SHA512 cipher commitment, and the
     * fixed `AES_256_CBC-HMAC_SHA256` next-protocol string.
     */
    private fun buildClientInitMessage(
        random: ByteArray,
        commitment: ByteArray,
    ): ByteArray {
        val cipherCommitment =
            Ukey2ClientInit.CipherCommitment
                .newBuilder()
                .setHandshakeCipher(Ukey2HandshakeCipher.P256_SHA512)
                .setCommitment(ByteString.copyFrom(commitment))
                .build()
        val clientInit =
            Ukey2ClientInit
                .newBuilder()
                .setVersion(PROTOCOL_VERSION)
                .setRandom(ByteString.copyFrom(random))
                .addCipherCommitments(cipherCommitment)
                .setNextProtocol(NEXT_PROTOCOL)
                .build()
        return Ukey2Message
            .newBuilder()
            .setMessageType(Ukey2Message.Type.CLIENT_INIT)
            .setMessageData(ByteString.copyFrom(clientInit.toByteArray()))
            .build()
            .toByteArray()
    }

    /**
     * Parses [serverInitMsgBytes] as a `Ukey2Message` of type
     * `SERVER_INIT`, validates every field per the UKEY2 spec, and
     * returns the server's parsed public key.
     *
     * Validation order is deliberate: we first check that the outer
     * message is even a `SERVER_INIT` before peeking at the inner
     * fields, so a misbehaving server that sends an alert (e.g.,
     * because it disliked our `ClientInit`) surfaces as a clean
     * [Ukey2HandshakeException] rather than a parse-error chain.
     */
    @Suppress("ThrowsCount", "ReturnCount")
    private fun parseAndValidateServerInit(serverInitMsgBytes: ByteArray): ECPublicKey {
        val outer =
            try {
                Ukey2Message.parseFrom(serverInitMsgBytes)
            } catch (ex: InvalidProtocolBufferException) {
                throw Ukey2HandshakeException(
                    alert = null,
                    message = "Could not deserialize Ukey2Message from ServerInit frame",
                    cause = ex,
                )
            }

        if (outer.messageType == Ukey2Message.Type.ALERT) {
            throw Ukey2HandshakeException(
                alert = null,
                message = "Server replied with Ukey2Alert (raw payload bytes intentionally redacted)",
            )
        }
        if (outer.messageType != Ukey2Message.Type.SERVER_INIT) {
            throw Ukey2HandshakeException(
                alert = null,
                message = "Expected SERVER_INIT, got ${outer.messageType}",
            )
        }

        val serverInit =
            try {
                Ukey2ServerInit.parseFrom(outer.messageData)
            } catch (ex: InvalidProtocolBufferException) {
                throw Ukey2HandshakeException(
                    alert = null,
                    message = "Could not deserialize Ukey2ServerInit",
                    cause = ex,
                )
            }

        if (serverInit.version != PROTOCOL_VERSION) {
            throw Ukey2HandshakeException(
                alert = null,
                message = "Server picked version ${serverInit.version}, expected $PROTOCOL_VERSION",
            )
        }
        if (!serverInit.hasRandom() || serverInit.random.size() != RANDOM_SIZE) {
            throw Ukey2HandshakeException(
                alert = null,
                message =
                    "Server random must be exactly $RANDOM_SIZE bytes, got " +
                        "${if (serverInit.hasRandom()) serverInit.random.size() else "absent"}",
            )
        }
        if (serverInit.handshakeCipher != Ukey2HandshakeCipher.P256_SHA512) {
            throw Ukey2HandshakeException(
                alert = null,
                message = "Server picked cipher ${serverInit.handshakeCipher}, expected P256_SHA512",
            )
        }
        if (!serverInit.hasPublicKey()) {
            throw Ukey2HandshakeException(
                alert = null,
                message = "Server did not include a public_key in ServerInit",
            )
        }

        return Ukey2KeyEncoding.parse(serverInit.publicKey.toByteArray())
    }
}
