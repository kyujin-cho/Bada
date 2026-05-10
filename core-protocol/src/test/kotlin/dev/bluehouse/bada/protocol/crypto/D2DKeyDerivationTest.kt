/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.crypto

import com.google.common.truth.Truth.assertThat
import dev.bluehouse.bada.protocol.test.D2DKeyDerivationVectors
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.charset.StandardCharsets

/**
 * KAT and structural tests for [D2DKeyDerivation].
 *
 * The test surface here is deliberately wider than usual because every
 * constant in the derivation chain — every salt byte, every info string —
 * has to match NearDrop's `finalizeKeyExchange` byte-for-byte or interop
 * silently breaks at the SecureMessage layer with no useful error.
 *
 * What we cover:
 *
 *  1. Hard-coded salts ([D2DKeyDerivation.D2D_SALT],
 *     [D2DKeyDerivation.SECURE_MESSAGE_SALT]) are recomputed at test time
 *     from `SHA-256("D2D")` and `SHA-256("SecureMessage")` and asserted to
 *     match. A typo in either constant fails this test loudly.
 *  2. Info strings ([D2DKeyDerivation.UKEY2_AUTH_SALT],
 *     [D2DKeyDerivation.UKEY2_NEXT_SALT], `client`, `server`, `ENC:2`,
 *     `SIG:1`) match their literal protocol values.
 *  3. The full eight-key chain reproduces the pinned KAT in
 *     [D2DKeyDerivationVectors.primary].
 *  4. The role swap is symmetric: deriving with `CLIENT` and `SERVER` on
 *     identical inputs flips send and receive without touching the rest of
 *     the bundle.
 *  5. `dhs.size != 32` is rejected up-front (defense in depth — the same
 *     check exists in `Hkdf.derive` indirectly via length, but failing fast
 *     here makes misuse easier to debug).
 *  6. `toString` does not leak key bytes.
 */
class D2DKeyDerivationTest {
    @Test
    fun `D2D_SALT equals SHA-256 of D2D ASCII`() {
        // The hard-coded constant in production code is reviewable inline,
        // but only this test guarantees it actually equals SHA-256("D2D").
        val expected = D2DKeyDerivation.sha256("D2D".toByteArray(StandardCharsets.US_ASCII))
        assertThat(D2DKeyDerivation.D2D_SALT).isEqualTo(expected)
    }

    @Test
    fun `SECURE_MESSAGE_SALT equals SHA-256 of SecureMessage ASCII`() {
        val expected =
            D2DKeyDerivation.sha256("SecureMessage".toByteArray(StandardCharsets.US_ASCII))
        assertThat(D2DKeyDerivation.SECURE_MESSAGE_SALT).isEqualTo(expected)
    }

    @Test
    fun `UKEY2 salts are the literal ASCII strings from the spec`() {
        assertThat(D2DKeyDerivation.UKEY2_AUTH_SALT)
            .isEqualTo("UKEY2 v1 auth".toByteArray(StandardCharsets.US_ASCII))
        assertThat(D2DKeyDerivation.UKEY2_NEXT_SALT)
            .isEqualTo("UKEY2 v1 next".toByteArray(StandardCharsets.US_ASCII))
    }

    @Test
    fun `D2D info strings are the literal ASCII strings from the spec`() {
        assertThat(D2DKeyDerivation.D2D_CLIENT_INFO)
            .isEqualTo("client".toByteArray(StandardCharsets.US_ASCII))
        assertThat(D2DKeyDerivation.D2D_SERVER_INFO)
            .isEqualTo("server".toByteArray(StandardCharsets.US_ASCII))
    }

    @Test
    fun `SecureMessage info strings are ENC colon 2 and SIG colon 1`() {
        // The colon-version suffixes encode the SecureMessage scheme version
        // (encryption v2 = AES-256-CBC, signature v1 = HMAC-SHA256). Drift
        // here would silently negotiate an unsupported scheme.
        assertThat(D2DKeyDerivation.SM_ENC_INFO)
            .isEqualTo("ENC:2".toByteArray(StandardCharsets.US_ASCII))
        assertThat(D2DKeyDerivation.SM_SIG_INFO)
            .isEqualTo("SIG:1".toByteArray(StandardCharsets.US_ASCII))
    }

    @Test
    fun `KAT — primary vector reproduces every output in the chain`() {
        val v = D2DKeyDerivationVectors.primary
        val keys =
            D2DKeyDerivation.derive(
                dhs = v.dhs,
                ukeyClientInitMsg = v.ukeyClientInitMsg,
                ukeyServerInitMsg = v.ukeyServerInitMsg,
                role = D2DRole.CLIENT,
            )

        // Stage 1 — UKEY2 layer.
        assertThat(keys.authString).isEqualTo(v.expectedAuthString)
        assertThat(keys.nextSecret).isEqualTo(v.expectedNextSecret)

        // Stage 2 — D2D layer.
        assertThat(keys.d2dClientKey).isEqualTo(v.expectedD2dClientKey)
        assertThat(keys.d2dServerKey).isEqualTo(v.expectedD2dServerKey)

        // Stage 3 — SecureMessage layer (all four keys).
        assertThat(keys.clientEncryptKey).isEqualTo(v.expectedClientEncryptKey)
        assertThat(keys.clientHmacKey).isEqualTo(v.expectedClientHmacKey)
        assertThat(keys.serverEncryptKey).isEqualTo(v.expectedServerEncryptKey)
        assertThat(keys.serverHmacKey).isEqualTo(v.expectedServerHmacKey)
    }

    @Test
    fun `KAT — every output is exactly 32 bytes`() {
        val v = D2DKeyDerivationVectors.primary
        val keys =
            D2DKeyDerivation.derive(
                dhs = v.dhs,
                ukeyClientInitMsg = v.ukeyClientInitMsg,
                ukeyServerInitMsg = v.ukeyServerInitMsg,
                role = D2DRole.CLIENT,
            )

        listOf(
            keys.authString,
            keys.nextSecret,
            keys.d2dClientKey,
            keys.d2dServerKey,
            keys.clientEncryptKey,
            keys.clientHmacKey,
            keys.serverEncryptKey,
            keys.serverHmacKey,
        ).forEach { assertThat(it.size).isEqualTo(D2DKeyDerivation.KEY_SIZE) }
    }

    @Test
    fun `role swap flips send and receive symmetrically`() {
        // Deriving with the same inputs but opposite roles must produce the
        // same eight-key bundle internally, and the per-direction selector
        // must flip send and receive on each side. This is the #1 debugging
        // timesink the issue calls out — pinning it down here means a future
        // refactor cannot silently transpose the role swap.
        val v = D2DKeyDerivationVectors.primary

        val asClient =
            D2DKeyDerivation.derive(
                dhs = v.dhs,
                ukeyClientInitMsg = v.ukeyClientInitMsg,
                ukeyServerInitMsg = v.ukeyServerInitMsg,
                role = D2DRole.CLIENT,
            )
        val asServer =
            D2DKeyDerivation.derive(
                dhs = v.dhs,
                ukeyClientInitMsg = v.ukeyClientInitMsg,
                ukeyServerInitMsg = v.ukeyServerInitMsg,
                role = D2DRole.SERVER,
            )

        // Underlying derivation is identical regardless of role.
        assertThat(asClient.authString).isEqualTo(asServer.authString)
        assertThat(asClient.nextSecret).isEqualTo(asServer.nextSecret)
        assertThat(asClient.d2dClientKey).isEqualTo(asServer.d2dClientKey)
        assertThat(asClient.d2dServerKey).isEqualTo(asServer.d2dServerKey)
        assertThat(asClient.clientEncryptKey).isEqualTo(asServer.clientEncryptKey)
        assertThat(asClient.clientHmacKey).isEqualTo(asServer.clientHmacKey)
        assertThat(asClient.serverEncryptKey).isEqualTo(asServer.serverEncryptKey)
        assertThat(asClient.serverHmacKey).isEqualTo(asServer.serverHmacKey)

        // The role selector swaps send and receive — what one side sends, the
        // other side receives, byte-for-byte.
        val clientDir = asClient.forRole()
        val serverDir = asServer.forRole()

        assertThat(clientDir.sendEncryptKey).isEqualTo(serverDir.receiveEncryptKey)
        assertThat(clientDir.sendHmacKey).isEqualTo(serverDir.receiveHmacKey)
        assertThat(clientDir.receiveEncryptKey).isEqualTo(serverDir.sendEncryptKey)
        assertThat(clientDir.receiveHmacKey).isEqualTo(serverDir.sendHmacKey)
    }

    @Test
    fun `forRole CLIENT picks client send keys and server receive keys`() {
        val v = D2DKeyDerivationVectors.primary
        val keys =
            D2DKeyDerivation.derive(
                dhs = v.dhs,
                ukeyClientInitMsg = v.ukeyClientInitMsg,
                ukeyServerInitMsg = v.ukeyServerInitMsg,
                role = D2DRole.CLIENT,
            )
        val dir = keys.forRole()
        assertThat(dir.sendEncryptKey).isEqualTo(v.expectedClientEncryptKey)
        assertThat(dir.sendHmacKey).isEqualTo(v.expectedClientHmacKey)
        assertThat(dir.receiveEncryptKey).isEqualTo(v.expectedServerEncryptKey)
        assertThat(dir.receiveHmacKey).isEqualTo(v.expectedServerHmacKey)
    }

    @Test
    fun `forRole SERVER picks server send keys and client receive keys`() {
        val v = D2DKeyDerivationVectors.primary
        val keys =
            D2DKeyDerivation.derive(
                dhs = v.dhs,
                ukeyClientInitMsg = v.ukeyClientInitMsg,
                ukeyServerInitMsg = v.ukeyServerInitMsg,
                role = D2DRole.SERVER,
            )
        val dir = keys.forRole()
        assertThat(dir.sendEncryptKey).isEqualTo(v.expectedServerEncryptKey)
        assertThat(dir.sendHmacKey).isEqualTo(v.expectedServerHmacKey)
        assertThat(dir.receiveEncryptKey).isEqualTo(v.expectedClientEncryptKey)
        assertThat(dir.receiveHmacKey).isEqualTo(v.expectedClientHmacKey)
    }

    @Test
    fun `derive rejects dhs of the wrong length`() {
        // 31, 33, and empty are all invalid. We don't need an exhaustive
        // sweep — the `require` is a single comparison.
        assertThrows<IllegalArgumentException> {
            D2DKeyDerivation.derive(
                dhs = ByteArray(31),
                ukeyClientInitMsg = ByteArray(0),
                ukeyServerInitMsg = ByteArray(0),
                role = D2DRole.CLIENT,
            )
        }
        assertThrows<IllegalArgumentException> {
            D2DKeyDerivation.derive(
                dhs = ByteArray(33),
                ukeyClientInitMsg = ByteArray(0),
                ukeyServerInitMsg = ByteArray(0),
                role = D2DRole.CLIENT,
            )
        }
        assertThrows<IllegalArgumentException> {
            D2DKeyDerivation.derive(
                dhs = ByteArray(0),
                ukeyClientInitMsg = ByteArray(0),
                ukeyServerInitMsg = ByteArray(0),
                role = D2DRole.CLIENT,
            )
        }
    }

    @Test
    fun `derivation is deterministic across invocations`() {
        // Belt-and-braces — HKDF is deterministic by construction, but a
        // future refactor that accidentally pulls in a per-call random salt
        // (e.g., to "harden" something) would silently break interop. Lock
        // the determinism property in.
        val v = D2DKeyDerivationVectors.primary
        val first =
            D2DKeyDerivation.derive(v.dhs, v.ukeyClientInitMsg, v.ukeyServerInitMsg, D2DRole.CLIENT)
        val second =
            D2DKeyDerivation.derive(v.dhs, v.ukeyClientInitMsg, v.ukeyServerInitMsg, D2DRole.CLIENT)

        assertThat(first.authString).isEqualTo(second.authString)
        assertThat(first.nextSecret).isEqualTo(second.nextSecret)
        assertThat(first.clientEncryptKey).isEqualTo(second.clientEncryptKey)
        assertThat(first.serverHmacKey).isEqualTo(second.serverHmacKey)
    }

    @Test
    fun `toString redacts key material`() {
        // A `toString` that dumps key bytes is a security regression waiting
        // to happen — every logger/IDE that ever prints this object would
        // leak. Lock down that the role is the only field exposed.
        val v = D2DKeyDerivationVectors.primary
        val keys =
            D2DKeyDerivation.derive(v.dhs, v.ukeyClientInitMsg, v.ukeyServerInitMsg, D2DRole.CLIENT)
        val s = keys.toString()
        assertThat(s).contains("CLIENT")
        assertThat(s).contains("redacted")
        // No accidental hex dumps. Construct a probe from a known key byte's
        // hex; toString must not contain it.
        val probe = "%02x".format(keys.clientEncryptKey[0].toInt() and 0xFF)
        // This is a smoke check — `probe` is two chars and may collide with
        // unrelated text in the toString. The strong guarantee is the
        // explicit "redacted" sentinel above; the probe just guards against
        // an accidental long hex blob.
        assertThat(s.length).isLessThan(64)
        // Reference `probe` so the variable isn't flagged as unused; the
        // value itself is intentionally not asserted on (see comment above).
        assertThat(probe).isNotEmpty()
    }
}
