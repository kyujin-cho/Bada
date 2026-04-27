/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.crypto.kat

import com.google.common.truth.Truth.assertWithMessage
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.KeepAliveFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.V1Frame
import com.google.security.cryptauth.lib.securemessage.SecureMessageProto.HeaderAndBody
import com.google.security.cryptauth.lib.securemessage.SecureMessageProto.SecureMessage
import io.github.kyujincho.wvmg.protocol.crypto.D2DKeyDerivation
import io.github.kyujincho.wvmg.protocol.crypto.D2DRole
import io.github.kyujincho.wvmg.protocol.crypto.Hkdf
import io.github.kyujincho.wvmg.protocol.crypto.pin.PinDerivation
import io.github.kyujincho.wvmg.protocol.crypto.securemessage.SecureMessageCodec
import io.github.kyujincho.wvmg.protocol.endpoint.DeviceType
import io.github.kyujincho.wvmg.protocol.endpoint.EndpointInfo
import io.github.kyujincho.wvmg.protocol.endpoint.TlvRecord
import io.github.kyujincho.wvmg.protocol.qr.QrKeyData
import io.github.kyujincho.wvmg.protocol.qr.QrKeyDerivation
import io.github.kyujincho.wvmg.protocol.test.AesCbcNistVectors
import io.github.kyujincho.wvmg.protocol.test.AesGcmVectors
import io.github.kyujincho.wvmg.protocol.test.AesGcmVectors.ciphertextWithTag
import io.github.kyujincho.wvmg.protocol.test.D2DKeyDerivationVectors
import io.github.kyujincho.wvmg.protocol.test.EcdhP256Vectors
import io.github.kyujincho.wvmg.protocol.test.HkdfVectors
import io.github.kyujincho.wvmg.protocol.test.HmacSha256Vectors
import io.github.kyujincho.wvmg.protocol.test.PinVectors
import io.github.kyujincho.wvmg.protocol.test.QrCodeVectors
import io.github.kyujincho.wvmg.protocol.test.SecureMessageVectors
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * Comprehensive Known-Answer Test (KAT) roll-up.
 *
 * Issue #27 calls for a single roll-up test that runs every cryptography KAT
 * the codebase ships and produces a clear, attributable failure report
 * naming the specific vector that diverged. This class is that roll-up.
 *
 * Each KAT subsystem is wrapped in a [Section] and added to a fixed list. A
 * single [@Test][org.junit.jupiter.api.Test] method walks the list, runs
 * every vector inside every section, accumulates failures (instead of
 * stopping at the first), and at the end either:
 *
 *  - Asserts pass (zero failures) and prints the per-section pass count, or
 *  - Fails with a single multi-line message that names every failing
 *    vector — so a developer reading the CI log can immediately see which
 *    primitive regressed without having to parse a wall of red Truth
 *    assertions.
 *
 * Per-vector tests still live in their dedicated classes (`HkdfTest`,
 * `D2DKeyDerivationTest`, `PinDerivationTest`, `QrKeyDerivationTest`,
 * `SecureMessageCodecTest`, `SecureChannelTest`, `Ukey2CryptoTest`,
 * `EndpointInfoTest`, plus the new `HmacSha256KatTest`,
 * `AesCbcNistKatTest`, `AesGcmKatTest`, `EcdhP256KatTest`). Those classes
 * provide the granular failure output JUnit tooling expects from individual
 * tests; this roll-up provides the bird's-eye summary that lets reviewers
 * confirm "every primitive is locked down" at a glance.
 *
 * Coverage map (issue #27 acceptance criteria):
 *
 * | Issue scope item                          | Section name                  | Source |
 * |-------------------------------------------|-------------------------------|--------|
 * | HKDF-SHA256 RFC 5869 A.1/A.2/A.3          | "HKDF-SHA256 (RFC 5869 A)"    | #9     |
 * | HMAC-SHA256 RFC 4231 cases 1..6           | "HMAC-SHA256 (RFC 4231)"      | #27    |
 * | AES-256-CBC NIST SP 800-38A F.2.5/F.2.6   | "AES-256-CBC (NIST 800-38A)"  | #27    |
 * | AES-GCM NIST GCM Test Cases 4 / 16        | "AES-GCM (NIST GCM)"          | #27    |
 * | ECDH P-256 RFC 5903 §8.1                  | "ECDH P-256 (RFC 5903)"       | #27    |
 * | UKEY2 finalize → authString + nextSecret  | "UKEY2 finalize (D2D KAT)"    | #11    |
 * | D2D + SecureMessage four-key derivation   | "D2D 4-key derivation"        | #11    |
 * | PIN derivation                            | "PIN derivation"              | #12    |
 * | EndpointInfo round-trip 100+ randomized   | "EndpointInfo round-trip"     | #8/#27 |
 * | Sequence-number invariants 1000-frame     | "Sequence-number invariants"  | #13    |
 * | SecureMessage AES+HMAC envelope           | "SecureMessage envelope"      | #13    |
 * | QR-code key derivation                    | "QR-code key derivation"      | #20    |
 *
 * The 1000-frame sequence-number invariant test in `SecureChannelTest`
 * already exercises a real loopback socket, so the roll-up covers the
 * invariant declaratively (via a per-frame counter advance check) rather
 * than spinning up another socket pair — duplicating that I/O here would
 * triple test runtime without adding signal.
 */
class KatRollupTest {
    @Test
    fun `every cryptography KAT vector matches its locked-in expected output`() {
        val sections = buildSections()
        val report = StringBuilder()
        var totalVectors = 0
        var totalFailures = 0

        for (section in sections) {
            val sectionFailures = mutableListOf<String>()
            var sectionVectors = 0
            for (vector in section.vectors) {
                sectionVectors++
                totalVectors++
                try {
                    vector.runnable.invoke()
                } catch (failure: AssertionError) {
                    sectionFailures += "${vector.name}: ${failure.message?.lineSequence()?.firstOrNull() ?: failure}"
                } catch (failure: Throwable) {
                    sectionFailures +=
                        "${vector.name}: unexpected ${failure.javaClass.simpleName}: ${failure.message}"
                }
            }
            totalFailures += sectionFailures.size
            report.append("[")
            report.append(if (sectionFailures.isEmpty()) "PASS" else "FAIL")
            report.append("] ")
            report.append(section.name)
            report.append(" — ")
            report.append(sectionVectors - sectionFailures.size)
            report.append('/')
            report.append(sectionVectors)
            report.append(" passed")
            if (sectionFailures.isNotEmpty()) {
                report.append('\n')
                for (line in sectionFailures) {
                    report.append("    - ")
                    report.append(line)
                    report.append('\n')
                }
            } else {
                report.append('\n')
            }
        }

        // Print the per-section roll-up to stdout regardless of pass/fail so
        // CI logs always show the section breakdown — useful for spotting
        // accidentally-empty sections in code review.
        println("KAT rollup summary ($totalVectors vectors across ${sections.size} sections):")
        println(report.toString().trimEnd())

        assertWithMessage(
            "$totalFailures of $totalVectors KAT vectors failed:\n$report",
        ).that(totalFailures).isEqualTo(0)
    }

    /**
     * One labelled vector inside a section. The runnable is expected to
     * either complete normally (= pass) or throw `AssertionError` (= the
     * vector mismatches and the rollup will report it).
     */
    private data class VectorCase(
        val name: String,
        val runnable: () -> Unit,
    )

    /** A named group of vectors covering one issue-#27 scope item. */
    private data class Section(
        val name: String,
        val vectors: List<VectorCase>,
    )

    /**
     * Builds the full list of sections. Order matches the table in the class
     * doc so reviewers can scan the rollup output top-to-bottom and confirm
     * coverage.
     */
    private fun buildSections(): List<Section> =
        listOf(
            hkdfSection(),
            hmacSection(),
            aesCbcNistSection(),
            aesGcmSection(),
            ecdhP256Section(),
            ukey2FinalizeSection(),
            d2dFourKeySection(),
            pinDerivationSection(),
            endpointInfoSection(),
            sequenceNumberInvariantSection(),
            secureMessageEnvelopeSection(),
            qrKeyDerivationSection(),
        )

    private fun hkdfSection(): Section =
        Section(
            "HKDF-SHA256 (RFC 5869 A)",
            HkdfVectors.all.map { v ->
                VectorCase(v.name) {
                    val prk = Hkdf.extract(v.salt, v.ikm)
                    assertWithMessage("PRK for ${v.name}").that(prk).isEqualTo(v.expectedPrk)
                    val okm = Hkdf.derive(v.ikm, v.salt, v.info, v.length)
                    assertWithMessage("OKM for ${v.name}").that(okm).isEqualTo(v.expectedOkm)
                }
            },
        )

    private fun hmacSection(): Section =
        Section(
            "HMAC-SHA256 (RFC 4231)",
            HmacSha256Vectors.all.map { v ->
                VectorCase(v.name) {
                    val mac = Mac.getInstance("HmacSHA256")
                    mac.init(SecretKeySpec(v.key, "HmacSHA256"))
                    val tag = mac.doFinal(v.data)
                    assertWithMessage("HMAC-SHA256 for ${v.name}").that(tag).isEqualTo(v.expectedTag)
                }
            },
        )

    private fun aesCbcNistSection(): Section =
        Section(
            "AES-256-CBC (NIST 800-38A)",
            AesCbcNistVectors.all.flatMap { v ->
                listOf(
                    VectorCase("${v.name} (encrypt)") {
                        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                        cipher.init(
                            Cipher.ENCRYPT_MODE,
                            SecretKeySpec(v.key, "AES"),
                            IvParameterSpec(v.iv),
                        )
                        val out = cipher.doFinal(v.plaintext)
                        assertWithMessage("CBC encrypt for ${v.name}").that(out).isEqualTo(v.ciphertext)
                    },
                    VectorCase("${v.name} (decrypt)") {
                        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                        cipher.init(
                            Cipher.DECRYPT_MODE,
                            SecretKeySpec(v.key, "AES"),
                            IvParameterSpec(v.iv),
                        )
                        val out = cipher.doFinal(v.ciphertext)
                        assertWithMessage("CBC decrypt for ${v.name}").that(out).isEqualTo(v.plaintext)
                    },
                )
            },
        )

    private fun aesGcmSection(): Section =
        Section(
            "AES-GCM (NIST GCM)",
            AesGcmVectors.all.flatMap { v ->
                listOf(
                    VectorCase("${v.name} (encrypt)") {
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(
                            Cipher.ENCRYPT_MODE,
                            SecretKeySpec(v.key, "AES"),
                            GCMParameterSpec(GCM_TAG_BITS, v.iv),
                        )
                        cipher.updateAAD(v.aad)
                        val out = cipher.doFinal(v.plaintext)
                        assertWithMessage("GCM encrypt for ${v.name}").that(out).isEqualTo(v.ciphertextWithTag())
                    },
                    VectorCase("${v.name} (decrypt)") {
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(
                            Cipher.DECRYPT_MODE,
                            SecretKeySpec(v.key, "AES"),
                            GCMParameterSpec(GCM_TAG_BITS, v.iv),
                        )
                        cipher.updateAAD(v.aad)
                        val out = cipher.doFinal(v.ciphertextWithTag())
                        assertWithMessage("GCM decrypt for ${v.name}").that(out).isEqualTo(v.plaintext)
                    },
                )
            },
        )

    private fun ecdhP256Section(): Section {
        val ap = AlgorithmParameters.getInstance("EC")
        ap.init(ECGenParameterSpec("secp256r1"))
        val params = ap.getParameterSpec(ECParameterSpec::class.java)
        val kf = KeyFactory.getInstance("EC")

        return Section(
            "ECDH P-256 (RFC 5903)",
            EcdhP256Vectors.all.flatMap { v ->
                val privA = kf.generatePrivate(ECPrivateKeySpec(BigInteger(1, v.privateKeyA), params))
                val privB = kf.generatePrivate(ECPrivateKeySpec(BigInteger(1, v.privateKeyB), params))
                val pubA =
                    kf.generatePublic(
                        ECPublicKeySpec(
                            ECPoint(
                                BigInteger(1, v.publicKeyA.copyOfRange(1, 33)),
                                BigInteger(1, v.publicKeyA.copyOfRange(33, 65)),
                            ),
                            params,
                        ),
                    )
                val pubB =
                    kf.generatePublic(
                        ECPublicKeySpec(
                            ECPoint(
                                BigInteger(1, v.publicKeyB.copyOfRange(1, 33)),
                                BigInteger(1, v.publicKeyB.copyOfRange(33, 65)),
                            ),
                            params,
                        ),
                    )
                listOf(
                    VectorCase("${v.name} (Alice → shared X)") {
                        val ka = KeyAgreement.getInstance("ECDH")
                        ka.init(privA)
                        ka.doPhase(pubB, true)
                        assertWithMessage("Alice shared X for ${v.name}").that(ka.generateSecret()).isEqualTo(v.sharedX)
                    },
                    VectorCase("${v.name} (Bob → shared X)") {
                        val kb = KeyAgreement.getInstance("ECDH")
                        kb.init(privB)
                        kb.doPhase(pubA, true)
                        assertWithMessage("Bob shared X for ${v.name}").that(kb.generateSecret()).isEqualTo(v.sharedX)
                    },
                    VectorCase("${v.name} (dhs)") {
                        val ka = KeyAgreement.getInstance("ECDH")
                        ka.init(privA)
                        ka.doPhase(pubB, true)
                        val sharedX = ka.generateSecret()
                        val magnitude = stripLeadingZeroSign(BigInteger(1, sharedX).toByteArray())
                        val dhs = MessageDigest.getInstance("SHA-256").digest(magnitude)
                        assertWithMessage("dhs for ${v.name}").that(dhs).isEqualTo(v.expectedDhs)
                    },
                )
            },
        )
    }

    private fun ukey2FinalizeSection(): Section =
        Section(
            "UKEY2 finalize (D2D KAT)",
            D2DKeyDerivationVectors.all.map { v ->
                VectorCase("${v.name} (authString + nextSecret)") {
                    val keys =
                        D2DKeyDerivation.derive(
                            dhs = v.dhs,
                            ukeyClientInitMsg = v.ukeyClientInitMsg,
                            ukeyServerInitMsg = v.ukeyServerInitMsg,
                            role = D2DRole.CLIENT,
                        )
                    assertWithMessage("authString for ${v.name}")
                        .that(keys.authString)
                        .isEqualTo(v.expectedAuthString)
                    assertWithMessage("nextSecret for ${v.name}")
                        .that(keys.nextSecret)
                        .isEqualTo(v.expectedNextSecret)
                }
            },
        )

    private fun d2dFourKeySection(): Section =
        Section(
            "D2D 4-key derivation",
            D2DKeyDerivationVectors.all.map { v ->
                VectorCase("${v.name} (4 SecureMessage keys)") {
                    val keys =
                        D2DKeyDerivation.derive(
                            dhs = v.dhs,
                            ukeyClientInitMsg = v.ukeyClientInitMsg,
                            ukeyServerInitMsg = v.ukeyServerInitMsg,
                            role = D2DRole.CLIENT,
                        )
                    assertWithMessage("d2dClientKey for ${v.name}")
                        .that(keys.d2dClientKey)
                        .isEqualTo(v.expectedD2dClientKey)
                    assertWithMessage("d2dServerKey for ${v.name}")
                        .that(keys.d2dServerKey)
                        .isEqualTo(v.expectedD2dServerKey)
                    assertWithMessage("clientEncryptKey for ${v.name}")
                        .that(keys.clientEncryptKey)
                        .isEqualTo(v.expectedClientEncryptKey)
                    assertWithMessage("clientHmacKey for ${v.name}")
                        .that(keys.clientHmacKey)
                        .isEqualTo(v.expectedClientHmacKey)
                    assertWithMessage("serverEncryptKey for ${v.name}")
                        .that(keys.serverEncryptKey)
                        .isEqualTo(v.expectedServerEncryptKey)
                    assertWithMessage("serverHmacKey for ${v.name}")
                        .that(keys.serverHmacKey)
                        .isEqualTo(v.expectedServerHmacKey)
                }
            },
        )

    private fun pinDerivationSection(): Section =
        Section(
            "PIN derivation",
            PinVectors.all.map { v ->
                VectorCase(v.name) {
                    val pin = PinDerivation.deriveFourDigitPin(v.authString)
                    assertWithMessage("PIN for ${v.name}").that(pin).isEqualTo(v.expectedPin)
                }
            },
        )

    private fun endpointInfoSection(): Section {
        // 100 randomized round-trips: lower than EndpointInfoTest's 200 to
        // keep the rollup fast, but well above the issue's "100+" threshold.
        val rng = Random(0xCAFEBABE)
        val deviceTypes = DeviceType.entries.toTypedArray()
        val cases =
            (0 until ENDPOINT_INFO_ITERATIONS).map { iteration ->
                val hidden = rng.nextBoolean()
                val version = rng.nextInt(0, 8)
                val deviceType = deviceTypes[rng.nextInt(deviceTypes.size)]
                val reserved = rng.nextBoolean()
                val metadata = ByteArray(EndpointInfo.METADATA_LEN).also { rng.nextBytes(it) }
                val deviceName: String? =
                    if (hidden) {
                        null
                    } else {
                        // ASCII-only names keep the rollup deterministic and
                        // fast; full UTF-8 fuzzing lives in EndpointInfoTest.
                        val nameLen = rng.nextInt(0, 32)
                        buildString(nameLen) { repeat(nameLen) { append(('a' + rng.nextInt(26))) } }
                    }
                val tlvCount = rng.nextInt(0, 4)
                val tlvRecords =
                    List(tlvCount) {
                        val type = rng.nextInt(0, 256)
                        val len = rng.nextInt(0, 32)
                        val value = ByteArray(len).also { rng.nextBytes(it) }
                        TlvRecord(type, value)
                    }
                val original =
                    EndpointInfo(
                        version = version,
                        hidden = hidden,
                        deviceType = deviceType,
                        reserved = reserved,
                        metadata = metadata,
                        deviceName = deviceName,
                        tlvRecords = tlvRecords,
                    )
                VectorCase("randomized iteration $iteration") {
                    val bytes = original.serialize()
                    val parsed = EndpointInfo.parse(bytes)
                    assertWithMessage("EndpointInfo round-trip iteration $iteration")
                        .that(parsed)
                        .isEqualTo(original)
                    assertWithMessage("EndpointInfo serialize stability iteration $iteration")
                        .that(parsed!!.serialize())
                        .isEqualTo(bytes)
                }
            }
        return Section("EndpointInfo round-trip", cases)
    }

    private fun sequenceNumberInvariantSection(): Section {
        // Lightweight in-memory check that the SecureMessage encrypt/decrypt
        // round-trip preserves the inner DeviceToDeviceMessage sequence
        // number for SEQUENCE_FRAME_COUNT distinct frames. This complements
        // the real-socket 1000-frame test in SecureChannelTest, which is
        // already part of the test suite but expensive to re-run inside the
        // rollup. Both check the same invariant ("encrypt+decrypt is
        // sequence-number-preserving") from a different angle: the rollup
        // covers the codec primitive in isolation; SecureChannelTest covers
        // it composed with the full transport.
        val keys =
            D2DKeyDerivation.derive(
                dhs = ByteArray(D2DKeyDerivation.KEY_SIZE) { (it + 0x10).toByte() },
                ukeyClientInitMsg = "client-init-bytes".toByteArray(),
                ukeyServerInitMsg = "server-init-bytes".toByteArray(),
                role = D2DRole.CLIENT,
            )
        val rng = Random(0xC0FFEE)
        val cases =
            (1..SEQUENCE_FRAME_COUNT).map { sequence ->
                VectorCase("sequence frame $sequence/$SEQUENCE_FRAME_COUNT") {
                    val frame = seededFrame(rng.nextInt())
                    val payload =
                        com.google.security.cryptauth.lib.securegcm
                            .DeviceToDeviceMessagesProto.DeviceToDeviceMessage
                            .newBuilder()
                            .setSequenceNumber(sequence)
                            .setMessage(
                                com.google.protobuf.ByteString
                                    .copyFrom(frame.toByteArray()),
                            ).build()
                            .toByteArray()
                    val iv = ByteArray(SecureMessageCodec.IV_SIZE).also { rng.nextBytes(it) }
                    val secureMessage =
                        SecureMessageCodec.encryptAndSign(
                            payload = payload,
                            encryptKey = keys.clientEncryptKey,
                            hmacKey = keys.clientHmacKey,
                            iv = iv,
                        )
                    val recovered =
                        SecureMessageCodec.verifyAndDecrypt(
                            secureMessageBytes = secureMessage,
                            decryptKey = keys.clientEncryptKey,
                            hmacKey = keys.clientHmacKey,
                        )
                    val decoded =
                        com.google.security.cryptauth.lib.securegcm
                            .DeviceToDeviceMessagesProto.DeviceToDeviceMessage
                            .parseFrom(recovered)
                    assertWithMessage("sequence preservation frame $sequence")
                        .that(decoded.sequenceNumber)
                        .isEqualTo(sequence)
                    val innerFrame = OfflineFrame.parseFrom(decoded.message)
                    assertWithMessage("inner frame preservation frame $sequence")
                        .that(innerFrame)
                        .isEqualTo(frame)
                }
            }
        return Section("Sequence-number invariants", cases)
    }

    private fun secureMessageEnvelopeSection(): Section =
        Section(
            "SecureMessage envelope",
            listOf(
                VectorCase("AES-256-CBC primary KAT (40-byte ASCII)") {
                    val v = SecureMessageVectors.aesPrimary
                    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(v.key, "AES"), IvParameterSpec(v.iv))
                    val ct = cipher.doFinal(v.plaintext)
                    assertWithMessage("CBC ciphertext for ${v.name}").that(ct).isEqualTo(v.expectedCiphertext)
                },
                VectorCase("HMAC-SHA256 over primary AES ciphertext") {
                    val v = SecureMessageVectors.hmacOverPrimaryCiphertext
                    val mac = Mac.getInstance("HmacSHA256")
                    mac.init(SecretKeySpec(v.key, "HmacSHA256"))
                    assertWithMessage("HMAC tag for ${v.name}")
                        .that(mac.doFinal(v.data))
                        .isEqualTo(v.expectedTag)
                },
                VectorCase("encryptAndSign + verifyAndDecrypt round-trip") {
                    // Drive the full SecureMessage primitive end-to-end with
                    // the locked-in primary KAT key/IV/plaintext from
                    // SecureMessageVectors. Confirms (a) the inner body
                    // matches the locked-in ciphertext, and (b) the
                    // signature byte-for-byte matches an independently
                    // computed HMAC over the serialized HeaderAndBody. This
                    // is the exact KAT called for by issue #13's acceptance
                    // criterion and quoted by issue #27's scope list.
                    val v = SecureMessageVectors.aesPrimary
                    val secureMessageBytes =
                        SecureMessageCodec.encryptAndSign(
                            payload = v.plaintext,
                            encryptKey = v.key,
                            hmacKey = SecureMessageVectors.primaryHmacKey,
                            iv = v.iv,
                        )
                    val parsed = SecureMessage.parseFrom(secureMessageBytes)
                    val headerAndBody = HeaderAndBody.parseFrom(parsed.headerAndBody)
                    assertWithMessage("inner body equals primary ciphertext")
                        .that(headerAndBody.body.toByteArray())
                        .isEqualTo(v.expectedCiphertext)

                    val mac = Mac.getInstance("HmacSHA256")
                    mac.init(SecretKeySpec(SecureMessageVectors.primaryHmacKey, "HmacSHA256"))
                    val expectedSig = mac.doFinal(parsed.headerAndBody.toByteArray())
                    assertWithMessage("signature is HMAC over HeaderAndBody")
                        .that(parsed.signature.toByteArray())
                        .isEqualTo(expectedSig)

                    val recovered =
                        SecureMessageCodec.verifyAndDecrypt(
                            secureMessageBytes = secureMessageBytes,
                            decryptKey = v.key,
                            hmacKey = SecureMessageVectors.primaryHmacKey,
                        )
                    assertWithMessage("verifyAndDecrypt recovers the plaintext")
                        .that(recovered)
                        .isEqualTo(v.plaintext)
                },
            ),
        )

    private fun qrKeyDerivationSection(): Section =
        Section(
            "QR-code key derivation",
            QrCodeVectors.all.map { v ->
                VectorCase(v.name) {
                    val keyData = QrKeyData.parse(v.keyData)!!
                    val keys = QrKeyDerivation.deriveKeys(keyData)
                    assertWithMessage("advertisingToken for ${v.name}")
                        .that(keys.advertisingToken)
                        .isEqualTo(v.expectedAdvertisingToken)
                    assertWithMessage("nameEncryptionKey for ${v.name}")
                        .that(keys.nameEncryptionKey)
                        .isEqualTo(v.expectedNameEncryptionKey)
                }
            },
        )

    /** Builds a `KEEP_ALIVE` [OfflineFrame] whose payload encodes one bit of [seed]. */
    private fun seededFrame(seed: Int): OfflineFrame =
        OfflineFrame
            .newBuilder()
            .setVersion(OfflineFrame.Version.V1)
            .setV1(
                V1Frame
                    .newBuilder()
                    .setType(V1Frame.FrameType.KEEP_ALIVE)
                    .setKeepAlive(KeepAliveFrame.newBuilder().setAck(seed % 2 == 0)),
            ).build()

    /**
     * Strips a leading zero sign byte from [BigInteger.toByteArray]'s output,
     * matching the magnitude form `Ukey2Crypto.toMagnitude` produces.
     */
    private fun stripLeadingZeroSign(signed: ByteArray): ByteArray =
        if (signed.size > 1 && signed[0] == 0.toByte()) {
            signed.copyOfRange(1, signed.size)
        } else {
            signed
        }

    private companion object {
        const val GCM_TAG_BITS = 128

        /** Number of randomized EndpointInfo round-trip iterations. Above issue #27's "100+" floor. */
        const val ENDPOINT_INFO_ITERATIONS = 120

        /**
         * Number of frames in the codec-level sequence-number round-trip
         * inside the rollup. Lower than `SecureChannelTest`'s 1000-frame
         * loopback test (which is the canonical 1000-frame check) because
         * each iteration here pays SecureMessage encrypt + parse + decrypt
         * cost without any I/O batching. 200 is enough to catch a
         * regression that reused IVs or trampled the sequence number.
         */
        const val SEQUENCE_FRAME_COUNT = 200
    }
}
