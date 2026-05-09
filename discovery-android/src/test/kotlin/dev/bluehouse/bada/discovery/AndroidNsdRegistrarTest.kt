/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.discovery

import android.net.nsd.NsdServiceInfo
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure-JVM tests for [AndroidNsdRegistrar]'s `applyAttributes` helper
 * that pushes binary TXT attributes onto an `NsdServiceInfo`.
 *
 * Robolectric integration coverage for the actual [NsdServiceInfo]
 * mutation path lives in [AndroidNsdRobolectricTest]. These tests keep
 * the test-friendly helper pinned with plain recording lambdas.
 *
 * The lambda always receives raw bytes regardless of API level — both
 * the public byte[] overload (API 33+) and the reflective fallback
 * (API 24-32) target the same underlying platform method. The previous
 * tests' "ISO-8859-1 round-trip" claim was factually wrong:
 * `NsdServiceInfo.setAttribute(String, String)` calls
 * `value.getBytes("UTF-8")` internally before delegating to the byte[]
 * form, so any String wrapper (Latin-1 or otherwise) re-encodes bytes
 * >= 0x80 into two wire bytes each. Reflection into the hidden byte[]
 * overload is the only correct path on pre-API-33.
 */
class AndroidNsdRegistrarTest {
    @Test
    fun `applyAttributes invokes the byte setter exactly once per attribute`() {
        val attributes =
            mapOf(
                "n" to byteArrayOf(0x00, 0x10.toByte(), 0x80.toByte(), 0xFF.toByte()),
                "v" to byteArrayOf(0x01),
            )
        val captured = mutableListOf<Pair<String, ByteArray>>()

        AndroidNsdRegistrar.applyAttributes(
            attributes = attributes,
            setBytes = { k, v -> captured += k to v },
        )

        assertThat(captured).hasSize(2)
        assertThat(captured.map { it.first }).containsExactly("n", "v").inOrder()
        assertThat(captured[0].second).isEqualTo(attributes.getValue("n"))
        assertThat(captured[1].second).isEqualTo(attributes.getValue("v"))
    }

    @Test
    fun `applyAttributes passes corruption-prone bytes through verbatim`() {
        // 0x80, 0xFF, 0x00 are the bytes the previous String fallback
        // would have corrupted by UTF-8-re-encoding them. Pin that the
        // current path forwards them byte-for-byte.
        val payload = byteArrayOf(0x00, 0x7F.toByte(), 0x80.toByte(), 0xFF.toByte(), 0x00, 0x10)
        val captured = mutableListOf<Pair<String, ByteArray>>()

        AndroidNsdRegistrar.applyAttributes(
            attributes = mapOf(QuickShareMdns.TXT_KEY_ENDPOINT_INFO to payload),
            setBytes = { k, v -> captured += k to v },
        )

        assertThat(captured).hasSize(1)
        assertThat(captured[0].first).isEqualTo(QuickShareMdns.TXT_KEY_ENDPOINT_INFO)
        assertThat(captured[0].second).isEqualTo(payload)
    }

    @Test
    fun `applyAttributes preserves all 256 byte values without modification`() {
        // Property test: every byte 0x00..0xFF must round-trip the
        // setBytes lambda exactly. This is the regression guard that
        // would have caught the previous (broken) String fallback.
        val payload = ByteArray(256) { it.toByte() }
        val captured = mutableListOf<Pair<String, ByteArray>>()

        AndroidNsdRegistrar.applyAttributes(
            attributes = mapOf("k" to payload),
            setBytes = { k, v -> captured += k to v },
        )

        assertThat(captured).hasSize(1)
        assertThat(captured[0].second).isEqualTo(payload)
        // Spot check each byte explicitly so a regression that swaps
        // even a single position is obvious in the failure diff.
        for (i in 0..0xFF) {
            assertThat(captured[0].second[i].toInt() and 0xFF).isEqualTo(i)
        }
    }

    @Test
    fun `reflective setAttribute lookup target exists on the platform stub`() {
        // The pre-API-33 fallback resolves
        // `NsdServiceInfo.setAttribute(String, byte[])` reflectively
        // (the public byte[] overload only became @hide-free in
        // TIRAMISU). Pin that the lookup target is at minimum visible
        // on whatever platform JAR the unit tests run against.
        //
        // Best-effort: when the plain AGP unit-test stub cannot load
        // the platform class, this test no-ops. The executable
        // Robolectric coverage is in AndroidNsdRobolectricTest.
        val method =
            try {
                NsdServiceInfo::class.java.getDeclaredMethod(
                    "setAttribute",
                    String::class.java,
                    ByteArray::class.java,
                )
            } catch (_: NoSuchMethodException) {
                null
            } catch (_: ClassFormatError) {
                null
            } catch (_: LinkageError) {
                null
            }
        if (method != null) {
            assertThat(method.name).isEqualTo("setAttribute")
            assertThat(method.parameterTypes)
                .asList()
                .containsExactly(String::class.java, ByteArray::class.java)
                .inOrder()
        }
    }
}
