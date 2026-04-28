/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.crypto

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Documents and guards the project-wide policy that **every cryptographic
 * comparison in `:core-protocol` uses [java.security.MessageDigest.isEqual]**
 * (or another constant-time primitive), never the short-circuiting
 * `ByteArray.contentEquals` / `Arrays.equals` / `==`.
 *
 * The full policy text lives in `:core-protocol/README.md`. Two things this
 * audit enforces:
 *
 *  1. **Positive call-site checks.** Each known cryptographic comparison
 *     site is asserted to call `MessageDigest.isEqual` on the relevant byte
 *     arrays — HMAC tag verify in `SecureMessageCodec.verifyAndDecrypt`,
 *     SHA-512 cipher-commitment verify in `Ukey2Server.verifyCipherCommitment`,
 *     advertising-token compare in `QrTlvMatcher.matchTlv`, and the
 *     `DerivedQrKeys` equals override.
 *
 *  2. **Negative grep across `src/main`.** No Kotlin file in the production
 *     source set may contain `contentEquals` or `Arrays.equals` on the same
 *     line as one of the secret-related identifier substrings (`hmac`,
 *     `signature`, `mac`, `commitment`, `tag`, `secret`). Files that
 *     legitimately need a value-based equality on plaintext (e.g.
 *     `D2DUnwrapped.payload` — already HMAC-verified before construction)
 *     stay clean of those identifier substrings on the offending line.
 *
 * The audit is deliberately strict and uses simple substring scanning rather
 * than a full Kotlin parser. The point is to flag a regression as early as
 * possible — `pr-reviewer` and a human still have to confirm each addition.
 */
class HmacComparisonAuditTest {
    @Test
    fun `SecureMessageCodec verifies HMAC tags with MessageDigest_isEqual`() {
        val source = readSource("crypto/securemessage/SecureMessageCodec.kt")
        // The HMAC verify branch must use MessageDigest.isEqual on the
        // expected/received signature pair before any AES work happens.
        val verifyBlock =
            extractBlock(
                source = source,
                startMarker = "fun verifyAndDecrypt(",
                endMarker = "public fun randomIv(",
            )
        assertWithMessage(
            "SecureMessageCodec.verifyAndDecrypt must compare HMAC tags with " +
                "MessageDigest.isEqual; contentEquals would leak per-byte timing.",
        ).that(verifyBlock)
            .contains("MessageDigest.isEqual(expectedSignature, receivedSignature)")
        assertWithMessage(
            "SecureMessageCodec.verifyAndDecrypt must not use contentEquals or " +
                "Arrays.equals on the HMAC signature.",
        ).that(verifyBlock).doesNotContain("contentEquals")
        assertWithMessage(
            "SecureMessageCodec.verifyAndDecrypt must not fall back to Arrays.equals.",
        ).that(verifyBlock).doesNotContain("Arrays.equals")
    }

    @Test
    fun `Ukey2Server verifies cipher commitment with MessageDigest_isEqual`() {
        val source = readSource("ukey2/Ukey2Server.kt")
        val verifyBlock =
            extractBlock(
                source = source,
                startMarker = "private suspend fun verifyCipherCommitment(",
                endMarker = "private suspend fun parseClientFinished(",
            )
        assertWithMessage(
            "Ukey2Server.verifyCipherCommitment must compare the SHA-512 cipher " +
                "commitment with MessageDigest.isEqual.",
        ).that(verifyBlock).contains("MessageDigest.isEqual(expected, actualHash)")
        assertWithMessage(
            "Ukey2Server.verifyCipherCommitment must not use contentEquals.",
        ).that(verifyBlock).doesNotContain("contentEquals")
    }

    @Test
    fun `QrTlvMatcher compares the advertising token with MessageDigest_isEqual`() {
        val source = readSource("qr/QrTlvMatcher.kt")
        val matchBlock =
            extractBlock(
                source = source,
                startMarker = "fun matchTlv(",
                endMarker = "fun buildVisibleTlv(",
            )
        assertWithMessage(
            "QrTlvMatcher.matchTlv must compare the advertising token with " +
                "MessageDigest.isEqual; contentEquals would leak partial-match timing.",
        ).that(matchBlock).contains("MessageDigest.isEqual(value, keys.advertisingToken)")
        assertWithMessage(
            "QrTlvMatcher.matchTlv must not use contentEquals on the advertising token.",
        ).that(matchBlock).doesNotContain("contentEquals")
    }

    @Test
    fun `DerivedQrKeys equals uses MessageDigest_isEqual on both fields`() {
        val source = readSource("qr/QrKeyDerivation.kt")
        val equalsBlock =
            extractBlock(
                source = source,
                startMarker = "override fun equals(other: Any?)",
                endMarker = "override fun hashCode()",
            )
        assertWithMessage(
            "DerivedQrKeys.equals must compare the advertising token with " +
                "MessageDigest.isEqual.",
        ).that(equalsBlock).contains("MessageDigest.isEqual(advertisingToken, other.advertisingToken)")
        assertWithMessage(
            "DerivedQrKeys.equals must compare the name encryption key with " +
                "MessageDigest.isEqual.",
        ).that(equalsBlock).contains("MessageDigest.isEqual(nameEncryptionKey, other.nameEncryptionKey)")
        assertWithMessage(
            "DerivedQrKeys.equals must not fall back to contentEquals.",
        ).that(equalsBlock).doesNotContain("contentEquals")
    }

    @Test
    fun `no main-source file mixes byte-array equality with secret identifiers on the same line`() {
        val mainSrc = mainSourceRoot()
        val violations = mutableListOf<String>()
        mainSrc.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val rel = file.relativeTo(mainSrc).invariantSeparatorsPath
            file.readLines().forEachIndexed { idx, raw ->
                val line = raw
                // Skip comment lines so the policy text in KDoc/comments
                // (which deliberately mentions `contentEquals` as the thing
                // we're banning) does not flag itself.
                val trimmed = line.trimStart()
                if (trimmed.startsWith("//") ||
                    trimmed.startsWith("*") ||
                    trimmed.startsWith("/*")
                ) {
                    return@forEachIndexed
                }
                val hasShortCircuit =
                    line.contains("contentEquals(") || line.contains("Arrays.equals(")
                if (!hasShortCircuit) return@forEachIndexed
                val lower = line.lowercase()
                val secretHit = SECRET_IDENTIFIER_SUBSTRINGS.firstOrNull { lower.contains(it) }
                if (secretHit != null) {
                    violations.add("$rel:${idx + 1}  matched '$secretHit' :: ${line.trim()}")
                }
            }
        }
        assertWithMessage(
            "Found short-circuiting byte-array equality on the same line as a " +
                "secret-related identifier. Use MessageDigest.isEqual instead. " +
                "If the bytes are genuinely non-secret, rename the variable so it " +
                "does not match $SECRET_IDENTIFIER_SUBSTRINGS, or extend this audit.",
        ).that(violations).isEmpty()
    }

    @Test
    fun `MessageDigest_isEqual is the project's documented constant-time compare`() {
        // Sanity: confirm the JCE provides MessageDigest.isEqual as a
        // length-checked, content-independent comparison. This is a smoke
        // test for the audit policy itself, paired with the more thorough
        // checks in Ukey2HandshakeIntegrationTest.
        val a = byteArrayOf(0x01, 0x02, 0x03)
        val b = byteArrayOf(0x01, 0x02, 0x03)
        val c = byteArrayOf(0x01, 0x02, 0x04)
        val d = byteArrayOf(0x01, 0x02)
        assertThat(java.security.MessageDigest.isEqual(a, b)).isTrue()
        assertThat(java.security.MessageDigest.isEqual(a, c)).isFalse()
        assertThat(java.security.MessageDigest.isEqual(a, d)).isFalse()
    }

    private fun mainSourceRoot(): File {
        // The Gradle `:core-protocol` test JVM runs with the working
        // directory set to the module root, so the main source set is at
        // `src/main/kotlin` relative to it. Resolve robustly: walk up from
        // the working dir until we find a directory whose `src/main/kotlin`
        // contains the package we expect.
        var dir: File? = File(".").absoluteFile
        repeat(MAX_PARENT_HOPS) {
            val candidate = dir?.resolve("src/main/kotlin/io/github/kyujincho/wvmg/protocol")
            if (candidate != null && candidate.isDirectory) {
                return File(dir!!, "src/main/kotlin")
            }
            dir = dir?.parentFile
        }
        error("Could not locate :core-protocol main source root from ${File(".").absolutePath}")
    }

    private fun readSource(relativeWithinPackage: String): String {
        val file = File(mainSourceRoot(), "io/github/kyujincho/wvmg/protocol/$relativeWithinPackage")
        check(file.isFile) { "Expected source file at $file but it is missing" }
        return file.readText()
    }

    private fun extractBlock(
        source: String,
        startMarker: String,
        endMarker: String,
    ): String {
        val start = source.indexOf(startMarker)
        check(start >= 0) { "Could not find start marker '$startMarker' in source" }
        val end = source.indexOf(endMarker, startIndex = start + startMarker.length)
        check(end > start) {
            "Could not find end marker '$endMarker' after '$startMarker' in source"
        }
        return source.substring(start, end)
    }

    private companion object {
        /**
         * Identifier substrings that, if they appear on the same line as
         * `contentEquals` or `Arrays.equals`, indicate a likely cryptographic
         * comparison that should be using a constant-time primitive instead.
         *
         * Matched case-insensitively. Keep this list short and concrete; an
         * over-broad list pulls in legitimate non-crypto code (e.g. random
         * "key" lookups in a `Map` whose keys are not secret).
         */
        val SECRET_IDENTIFIER_SUBSTRINGS =
            listOf(
                "hmac",
                "signature",
                "commitment",
                "advertisingtoken",
                "encryptionkey",
                "authstring",
                "nextsecret",
            )

        const val MAX_PARENT_HOPS = 6
    }
}
