/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.protocol.connection

/**
 * Immutable outbound Quick Share text payload.
 *
 * Called by the Android Share Sheet path for plain text and links. The
 * introduction advertises [bytes] as `TextMetadata`; the same bytes then travel
 * under [payloadId] as a Quick Share BYTES payload after receiver consent.
 */
public class TextSource(
    public val bytes: ByteArray,
    public val title: String,
    public val kind: Kind,
    public val payloadId: Long,
) {
    public enum class Kind {
        PLAIN,
        URL,
    }
}
