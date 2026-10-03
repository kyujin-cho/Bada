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
import java.io.File

internal sealed interface SendPayloadResolution {
    data class Payloads(
        val files: List<FileSource>,
        val texts: List<TextSource>,
        private val ownedSources: List<AutoCloseable> = emptyList(),
    ) : SendPayloadResolution,
        AutoCloseable {
        override fun close() = ownedSources.forEach { runCatching { it.close() } }
    }

    data class PreparationFailed(
        val reason: SourcePreparationFailure,
    ) : SendPayloadResolution

    data object Unsupported : SendPayloadResolution

    data object FolderEmpty : SendPayloadResolution

    data object FolderWalkFailed : SendPayloadResolution
}

internal class SendPayloadResolver(
    private val fileSourceFactory: UriFileSourceFactory,
    private val documentTreeFactory: DocumentTreeFileSourceFactory,
    stagingDirectory: File,
    private val textPayloadIdGenerator: () -> Long = UriFileSourceFactory::randomPositivePayloadId,
) {
    private val uriPreparer = ContentUriPreparer(fileSourceFactory, stagingDirectory).also { it.scavengeStale() }

    fun resolve(intent: Intent): SendPayloadResolution =
        if (intent.action == SendActivity.ACTION_SEND_FOLDER) {
            intent.data?.let(::materializeFolder) ?: SendPayloadResolution.Unsupported
        } else {
            val parsed = ShareIntentRouter.route(toShareIntent(intent))
            if (parsed == null) {
                SendPayloadResolution.Unsupported
            } else {
                materializePayloads(parsed)
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
        val combinedUris = collectUris(source, streamUri, streamUris)
        val title =
            source.getStringExtra(Intent.EXTRA_TITLE)?.takeIf { it.isNotBlank() }
                ?: source.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() }
        return ShareIntent(
            action = source.action,
            streamUri = null,
            streamUris = combinedUris,
            textExtra = text,
            textTitle = title,
        )
    }

    private fun collectUris(
        source: Intent,
        single: Uri?,
        multiple: List<Uri>?,
    ): List<Uri> {
        val candidates = mutableListOf<Uri>()
        source.data?.let(candidates::add)
        single?.let(candidates::add)
        multiple?.let(candidates::addAll)
        getParcelableExtraCompat(source, "output")?.let(candidates::add)
        source.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(candidates::add)
        }
        return candidates.distinctBy { it.normalizeScheme().toString() }
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

    @Suppress("ReturnCount") // Text, malformed URI collections, and prepared attachments are distinct outcomes.
    private fun materializePayloads(input: ShareIntentInput): SendPayloadResolution {
        if (input is ShareIntentInput.Text) {
            return SendPayloadResolution.Payloads(
                files = emptyList(),
                texts = listOf(textSource(input)),
            )
        }
        val uris =
            when (input) {
                is ShareIntentInput.SingleUri -> listOf(input.uri)
                is ShareIntentInput.MultipleUris -> input.uris
                is ShareIntentInput.Text -> error("handled above")
            }
        val typedUris = uris.mapNotNull { it as? Uri }
        if (typedUris.size != uris.size) return SendPayloadResolution.Unsupported
        val owned = mutableListOf<PreparedUriSource>()
        val ids = mutableSetOf<Long>()
        return try {
            val files =
                typedUris.map { uri ->
                    var id: Long
                    do id = UriFileSourceFactory.randomPositivePayloadId() while (!ids.add(id))
                    uriPreparer.prepare(uri, id).also(owned::add).source
                }
            SendPayloadResolution.Payloads(files, emptyList(), owned)
        } catch (failure: SourcePreparationException) {
            owned.forEach { it.close() }
            SendPayloadResolution.PreparationFailed(failure.failure)
        } catch (_: SecurityException) {
            owned.forEach { it.close() }
            SendPayloadResolution.PreparationFailed(SourcePreparationFailure.SOURCE_UNREADABLE)
        }
    }

    /**
     * Purpose: reuse the shared text classifier for prepared and NFC sends.
     * Invocation: text-only Share Sheet payload preparation on Dispatchers.IO.
     * Contract: preserve an explicit share title, otherwise use the classifier's
     * host/preview; the classified body and kind use the upstream TextSource API.
     * Verification: classifier and protocol JVM tests; Android UI unverified.
     */
    private fun textSource(input: ShareIntentInput.Text): TextSource {
        val classified = SharedTextClassifier.classify(input.text)
        return TextSource(
            text = classified.body,
            title = input.title.takeIf { it.isNotBlank() } ?: classified.title,
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
            SendPayloadResolution.Payloads(walked, emptyList())
        }
    }
}
