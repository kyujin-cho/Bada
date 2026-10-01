/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.send

import android.content.Intent
import android.net.Uri
import android.os.Build
import dev.bluehouse.bada.protocol.connection.FileSource
import dev.bluehouse.bada.protocol.connection.TextSource

internal sealed interface SendPayloadResolution {
    /**
     * Something to send. A share-sheet intent yields either files or a
     * single text item (#301), never both: when `ACTION_SEND` carries a
     * stream and a text, the stream wins (see [ShareIntentRouter]).
     */
    data class Payload(
        val files: List<FileSource> = emptyList(),
        val texts: List<TextSource> = emptyList(),
    ) : SendPayloadResolution

    data object Unsupported : SendPayloadResolution

    data object FolderEmpty : SendPayloadResolution

    data object FolderWalkFailed : SendPayloadResolution
}

internal class SendPayloadResolver(
    private val fileSourceFactory: UriFileSourceFactory,
    private val documentTreeFactory: DocumentTreeFileSourceFactory,
    private val textPayloadIdGenerator: () -> Long = UriFileSourceFactory::randomPositivePayloadId,
) {
    fun resolve(intent: Intent): SendPayloadResolution =
        if (intent.action == SendActivity.ACTION_SEND_FOLDER) {
            intent.data?.let(::materializeFolder) ?: SendPayloadResolution.Unsupported
        } else {
            val parsed = ShareIntentRouter.route(toShareIntent(intent))
            val payload = parsed?.let(::materialize)
            if (payload == null || (payload.files.isEmpty() && payload.texts.isEmpty())) {
                SendPayloadResolution.Unsupported
            } else {
                payload
            }
        }

    private fun toShareIntent(source: Intent): ShareIntent {
        val streamUri: Uri? =
            when (source.action) {
                Intent.ACTION_SEND -> getParcelableExtraCompat(source, Intent.EXTRA_STREAM)
                else -> null
            }
        val streamUris: List<Uri>? =
            when (source.action) {
                Intent.ACTION_SEND_MULTIPLE -> getParcelableArrayListExtraCompat(source, Intent.EXTRA_STREAM)
                else -> null
            }
        val text: CharSequence? = source.getCharSequenceExtra(Intent.EXTRA_TEXT)
        return ShareIntent(
            action = source.action,
            streamUri = streamUri,
            streamUris = streamUris,
            textExtra = text,
        )
    }

    @Suppress("DEPRECATION")
    private fun getParcelableExtraCompat(
        source: Intent,
        key: String,
    ): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            source.getParcelableExtra(key, Uri::class.java)
        } else {
            source.getParcelableExtra(key) as? Uri
        }

    @Suppress("DEPRECATION")
    private fun getParcelableArrayListExtraCompat(
        source: Intent,
        key: String,
    ): List<Uri>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            source.getParcelableArrayListExtra(key, Uri::class.java)
        } else {
            source.getParcelableArrayListExtra(key)
        }

    private fun materialize(input: ShareIntentInput): SendPayloadResolution.Payload =
        when (input) {
            is ShareIntentInput.SingleUri ->
                SendPayloadResolution.Payload(files = listOf(fileSourceFactory.fromUri(input.uri as Uri)))
            is ShareIntentInput.MultipleUris ->
                SendPayloadResolution.Payload(files = input.uris.map { fileSourceFactory.fromUri(it as Uri) })
            is ShareIntentInput.Text ->
                SendPayloadResolution.Payload(texts = listOf(textSource(input.text)))
        }

    private fun textSource(text: String): TextSource {
        val classified = SharedTextClassifier.classify(text)
        return TextSource(
            text = classified.body,
            title = classified.title,
            kind = classified.kind,
            payloadId = textPayloadIdGenerator(),
        )
    }

    @Suppress("ReturnCount")
    private fun materializeFolder(treeUri: Uri): SendPayloadResolution {
        val walked =
            try {
                documentTreeFactory.walk(treeUri)
            } catch (_: SecurityException) {
                return SendPayloadResolution.FolderWalkFailed
            } catch (_: IllegalArgumentException) {
                return SendPayloadResolution.FolderWalkFailed
            }

        return if (walked.isEmpty()) {
            SendPayloadResolution.FolderEmpty
        } else {
            SendPayloadResolution.Payload(files = walked)
        }
    }
}
