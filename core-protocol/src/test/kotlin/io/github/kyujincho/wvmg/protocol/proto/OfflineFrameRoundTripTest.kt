/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.proto

import com.google.common.truth.Truth.assertThat
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.KeepAliveFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.OfflineFrame
import com.google.location.nearby.connections.proto.OfflineWireFormatsProto.V1Frame
import org.junit.jupiter.api.Test

/**
 * Smoke test for the vendored Quick Share `.proto` files (#6).
 *
 * The goal is twofold:
 * 1. Confirm `protoc` codegen actually emitted the expected Java classes
 *    under the `com.google.location.nearby.connections.proto` package
 *    declared by `offline_wire_formats.proto`'s `option java_package`.
 * 2. Round-trip a representative message through `toByteArray()` /
 *    `parseFrom()` so we know the `protobuf-javalite` runtime is on the
 *    classpath and wired correctly. If this test compiles AND passes, the
 *    full proto pipeline is healthy.
 */
class OfflineFrameRoundTripTest {
    @Test
    fun `OfflineFrame with KEEP_ALIVE survives a serialize-parse round trip`() {
        val original =
            OfflineFrame
                .newBuilder()
                .setVersion(OfflineFrame.Version.V1)
                .setV1(
                    V1Frame
                        .newBuilder()
                        .setType(V1Frame.FrameType.KEEP_ALIVE)
                        .setKeepAlive(KeepAliveFrame.newBuilder().build())
                        .build(),
                ).build()

        val bytes = original.toByteArray()
        val parsed = OfflineFrame.parseFrom(bytes)

        assertThat(parsed).isEqualTo(original)
        assertThat(parsed.version).isEqualTo(OfflineFrame.Version.V1)
        assertThat(parsed.v1.type).isEqualTo(V1Frame.FrameType.KEEP_ALIVE)
        assertThat(parsed.v1.hasKeepAlive()).isTrue()
    }

    @Test
    fun `generated OfflineFrame class lives under the documented java_package`() {
        // The vendored `offline_wire_formats.proto` declares
        // `option java_package = "com.google.location.nearby.connections.proto"`
        // and (in the verbatim NearDrop copy) does NOT set
        // `java_multiple_files = true`, so messages are emitted as nested
        // static classes inside the `OfflineWireFormatsProto` outer class.
        // We pin the full FQCN here so any future change to either option in
        // the .proto file is caught at unit-test time rather than at link
        // time in a downstream module that imports the message.
        assertThat(OfflineFrame::class.java.name)
            .isEqualTo(
                "com.google.location.nearby.connections.proto." +
                    "OfflineWireFormatsProto\$OfflineFrame",
            )
        assertThat(OfflineFrame::class.java.`package`.name)
            .isEqualTo("com.google.location.nearby.connections.proto")
    }
}
