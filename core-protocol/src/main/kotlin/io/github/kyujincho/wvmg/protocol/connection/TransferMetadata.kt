/*
 * Copyright 2026 WhenVivoMeetsGoogle contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package io.github.kyujincho.wvmg.protocol.connection

import com.google.android.gms.nearby.sharing.Protocol
import io.github.kyujincho.wvmg.protocol.sharing.IntroductionFrame

/**
 * Receiver-side description of an in-flight Quick Share transfer.
 *
 * Surfaced by [InboundConnection] when the receiver's negotiation FSM
 * enters [io.github.kyujincho.wvmg.protocol.sharing.InboundSharingState.WaitingForUserConsent].
 * The UI layer renders this object — file/text counts, sizes, MIME types,
 * and the 4-digit confirmation PIN — to drive the consent sheet.
 *
 * `:core-protocol` is platform-independent, so this type holds plain
 * value semantics: no `Uri`, no `Bitmap`, no `MediaStore`. Higher
 * layers (`:service-android`, `:app`) translate the item list into the
 * appropriate platform notification.
 *
 * ### Why a single immutable snapshot
 *
 * The UI needs all metadata at once (the consent sheet is rendered in
 * one shot), so a single `data class` carrying a list of items plus the
 * PIN is the smallest API surface that gets the job done. We do NOT
 * surface the raw [IntroductionFrame] proto here — that would leak
 * platform-irrelevant fields (`required_package`, `app_metadata`,
 * `wifi_credentials_metadata`, ...) and force the UI to know the proto
 * shape. The mapping in [fromIntroductionFrame] keeps the proto
 * dependency in one place.
 *
 * @property items One entry per file or text payload announced in the
 *   peer's introduction. Empty if the introduction announced no
 *   file/text items (Quick Share also supports Wi-Fi credential and app
 *   transfers; we ignore those for Phase 1).
 * @property pin 4-digit ASCII confirmation code derived from the UKEY2
 *   `authString` via [io.github.kyujincho.wvmg.protocol.crypto.pin.PinDerivation.deriveFourDigitPin].
 *   Always exactly 4 ASCII digits.
 * @property sourceDeviceName Optional human-readable name of the sender
 *   device, decoded from the peer's `endpoint_info` carried in the
 *   `ConnectionRequest`. `null` when the sender advertised in
 *   "hidden" visibility mode (no name on the wire) or when the
 *   `endpoint_info` could not be parsed. The receiver UI (#22)
 *   renders this on the consent notification / dialog so the user
 *   knows who is asking for permission to send.
 */
public data class TransferMetadata(
    val items: List<TransferItem>,
    val pin: String,
    val sourceDeviceName: String? = null,
) {
    init {
        require(pin.length == PIN_LENGTH) {
            "pin must be exactly $PIN_LENGTH characters, got ${pin.length}"
        }
    }

    /**
     * Total advertised byte count across all [items]. Useful for the
     * consent sheet's "12 files, 1.4 GB" subtitle.
     */
    public val totalSize: Long
        get() = items.sumOf { it.size }

    public companion object {
        /** Length of the 4-digit confirmation PIN. */
        public const val PIN_LENGTH: Int = 4

        /**
         * Build a [TransferMetadata] from the peer's [IntroductionFrame]
         * and the locally derived [pin].
         *
         * The mapping:
         *  - Each entry of `file_metadata` becomes a [TransferItem.File].
         *  - Each entry of `text_metadata` becomes a [TransferItem.Text].
         *  - All other introduction fields are dropped on purpose.
         *
         * @param introduction The frame the FSM surfaced in
         *   [io.github.kyujincho.wvmg.protocol.sharing.SharingFsmEffect.IntroductionReceived].
         * @param pin Confirmation PIN from
         *   [io.github.kyujincho.wvmg.protocol.crypto.pin.PinDerivation.deriveFourDigitPin].
         * @param sourceDeviceName Optional sender device name decoded
         *   from the peer's `endpoint_info`. Defaults to `null` —
         *   callers that already parsed the peer identity (the
         *   inbound connection driver does) pass it through here so
         *   the consent UI can render "Pixel 8 wants to share…".
         */
        public fun fromIntroductionFrame(
            introduction: IntroductionFrame,
            pin: String,
            sourceDeviceName: String? = null,
        ): TransferMetadata {
            val files =
                introduction.fileMetadataList.map { file ->
                    TransferItem.File(
                        payloadId = file.payloadId,
                        name = file.name,
                        size = file.size,
                        mimeType = file.mimeType,
                    )
                }
            val texts =
                introduction.textMetadataList.map { text ->
                    TransferItem.Text(
                        payloadId = text.payloadId,
                        title = text.textTitle,
                        size = text.size,
                        // The proto `Type` enum maps cleanly onto our
                        // narrower [TransferItem.Text.Kind] surface.
                        kind =
                            when (text.type) {
                                Protocol.TextMetadata.Type.URL -> TransferItem.Text.Kind.URL
                                Protocol.TextMetadata.Type.ADDRESS -> TransferItem.Text.Kind.ADDRESS
                                Protocol.TextMetadata.Type.PHONE_NUMBER -> TransferItem.Text.Kind.PHONE_NUMBER
                                else -> TransferItem.Text.Kind.PLAIN
                            },
                    )
                }
            return TransferMetadata(
                items = files + texts,
                pin = pin,
                sourceDeviceName = sourceDeviceName,
            )
        }
    }
}

/**
 * One item in a [TransferMetadata]'s announced manifest.
 *
 * Sealed because we only handle the two payload kinds that Phase 1
 * supports — files and text — and because consumers benefit from
 * exhaustive `when` dispatch on the kind.
 */
public sealed interface TransferItem {
    /**
     * The Quick Share `payload_id` the FILE or BYTES bytes will travel
     * under. The orchestrator uses this id to route inbound
     * `PayloadTransferFrame`s to the right reassembly state.
     */
    public val payloadId: Long

    /**
     * Advertised total byte count. May be `0` (e.g. a zero-byte file or
     * an empty text). Always `>= 0` (the proto would forbid negatives,
     * but we re-validate at the consent layer for safety).
     */
    public val size: Long

    /**
     * A FILE payload announcement.
     *
     * @property name Human-readable filename as the peer chose it
     *   (`Cookbook.pdf`). The receiver may sanitize this before opening
     *   a destination — `:core-protocol` does not.
     * @property mimeType MIME type guess provided by the peer
     *   (`image/jpeg`). Defaults on the proto side to
     *   `application/octet-stream`.
     */
    public data class File(
        override val payloadId: Long,
        val name: String,
        override val size: Long,
        val mimeType: String,
    ) : TransferItem

    /**
     * A BYTES (text) payload announcement.
     *
     * @property title The peer's chosen title for this text item.
     *   Empty string when the peer omitted it.
     * @property kind Whether the bytes are a URL, address, phone number,
     *   or plain text. The UI may want to render an icon based on this.
     */
    public data class Text(
        override val payloadId: Long,
        val title: String,
        override val size: Long,
        val kind: Kind,
    ) : TransferItem {
        /** Sub-types within a text payload. */
        public enum class Kind {
            /** Plain text content. */
            PLAIN,

            /** A URL the receiver should be able to open in a browser. */
            URL,

            /** An address — Quick Share's phrasing for "open in a maps app". */
            ADDRESS,

            /** A phone number — typically rendered as a "Call" action. */
            PHONE_NUMBER,
        }
    }
}
