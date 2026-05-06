/*
 * Copyright 2026 LibreDrop contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.send

import android.content.Intent
import android.net.Uri
import android.os.Build
import dev.bluehouse.bada.protocol.connection.FileSource

internal sealed interface SendPayloadResolution {
    data class Files(
        val files: List<FileSource>,
    ) : SendPayloadResolution

    data object Unsupported : SendPayloadResolution

    data object FolderEmpty : SendPayloadResolution

    data object FolderWalkFailed : SendPayloadResolution
}

internal class SendPayloadResolver(
    private val fileSourceFactory: UriFileSourceFactory,
    private val documentTreeFactory: DocumentTreeFileSourceFactory,
) {
    fun resolve(intent: Intent): SendPayloadResolution =
        if (intent.action == SendActivity.ACTION_SEND_FOLDER) {
            intent.data?.let(::materializeFolder) ?: SendPayloadResolution.Unsupported
        } else {
            val parsed = ShareIntentRouter.route(toShareIntent(intent))
            if (parsed == null) {
                SendPayloadResolution.Unsupported
            } else {
                materializeFiles(parsed)
                    .takeIf { it.isNotEmpty() }
                    ?.let(SendPayloadResolution::Files)
                    ?: SendPayloadResolution.Unsupported
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

    private fun materializeFiles(input: ShareIntentInput): List<FileSource> =
        when (input) {
            is ShareIntentInput.SingleUri ->
                listOf(fileSourceFactory.fromUri(input.uri as Uri))
            is ShareIntentInput.MultipleUris ->
                input.uris.map { fileSourceFactory.fromUri(it as Uri) }
            is ShareIntentInput.Text ->
                emptyList()
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
            SendPayloadResolution.Files(walked)
        }
    }
}
