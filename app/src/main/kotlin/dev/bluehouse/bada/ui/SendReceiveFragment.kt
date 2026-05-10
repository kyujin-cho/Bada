/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.res.use
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dev.bluehouse.bada.R
import dev.bluehouse.bada.migration.LegacyPackageDetectorAndroid
import dev.bluehouse.bada.send.SendActivity
import dev.bluehouse.bada.service.receiver.MdnsVisibilityOverrideHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Send/Receive tab content for the bottom-navigation shell in
 * [dev.bluehouse.bada.MainActivity].
 *
 * Carries the user-facing controls that affect day-to-day send/receive
 * usage:
 *
 *   * #145 legacy-WhenVivoMeetsGoogle migration banner — non-dismissible
 *     prompt to uninstall the pre-rename install whose duplicate 0xFEF3
 *     GATT service breaks BLE bootstrap. Auto-hides on resume once the
 *     legacy package is gone.
 *   * "Send files" entry point — `ACTION_OPEN_DOCUMENT` with multi-select
 *     enabled, forwards the picked URIs to [SendActivity] as
 *     `ACTION_SEND_MULTIPLE` (or `ACTION_SEND` when exactly one file was
 *     picked).
 *   * "Send folder" entry point (#38) — `ACTION_OPEN_DOCUMENT_TREE`
 *     forwarded to [SendActivity] via `ACTION_SEND_FOLDER`.
 *   * #34 always-visible override toggle — process-wide receiver
 *     visibility switch.
 *
 * The battery-optimization (#47) prompt is no longer surfaced as a
 * banner here — it has moved to a one-shot AlertDialog raised by
 * MainActivity on first launch + a re-triggerable row on the Settings
 * tab. Keeping the legacy-WVMG banner inline because it is not
 * dismissible (the duplicate GATT service must be removed for BLE
 * bootstrap to work at all, so an easy-to-skip dialog is the wrong
 * UX for it).
 */
internal class SendReceiveFragment : Fragment(R.layout.fragment_send_receive) {
    /**
     * Launcher for the multi-file system picker. Returns a `List<Uri>`
     * with read permissions for the calling process; the fragment
     * forwards them to [SendActivity] via `ACTION_SEND` /
     * `ACTION_SEND_MULTIPLE` so the existing share-intent pipeline does
     * the discovery + connection work without duplication here.
     *
     * Registered during [onCreate] to satisfy the activity-result API's
     * lifecycle contract.
     */
    private lateinit var openFilesLauncher: ActivityResultLauncher<Array<String>>

    /**
     * Launcher for SAF's `ACTION_OPEN_DOCUMENT_TREE` (#38). On a
     * successful pick the resolved tree URI is forwarded to
     * [SendActivity] via [SendActivity.ACTION_SEND_FOLDER]; the activity
     * walks the tree, builds one
     * [dev.bluehouse.bada.protocol.connection.FileSource] per
     * descendant file, and runs the existing peer-discovery /
     * outbound-connection flow with `parent_folder` populated.
     */
    private lateinit var openTreeLauncher: ActivityResultLauncher<Uri?>

    /**
     * Most recent result of
     * [LegacyPackageDetectorAndroid.findInstalledLegacy]. Captured by
     * [refreshLegacyWvmgBanner] so the Uninstall click handler does not
     * have to re-probe (which could race with a partially-completed
     * uninstall and target the wrong variant when both `wvmg` and
     * `wvmg.debug` are present).
     */
    private var legacyPackageInBanner: String? = null

    /**
     * One-shot session latch for the `READ_MEDIA_IMAGES` (or pre-33
     * `READ_EXTERNAL_STORAGE`) request that fills the polaroid photo
     * preview. We auto-prompt the first time the user lands on this
     * tab and then never re-prompt for the rest of this fragment
     * lifetime — tapping back into the tab repeatedly should not
     * spam the user with a denied dialog.
     */
    private var galleryPermissionRequested: Boolean = false

    /**
     * Launcher for the gallery-read permission. On grant, we trigger
     * the photo-preview fill; on denial, we leave the polaroid cards
     * as decorative empty white frames (the rest of the screen
     * functions normally without it).
     */
    private val galleryPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                loadRandomGalleryPhotos()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        openFilesLauncher =
            registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
                if (uris.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.main_send_files_no_selection, Toast.LENGTH_SHORT).show()
                    return@registerForActivityResult
                }
                val intent =
                    Intent(requireContext(), SendActivity::class.java).apply {
                        // Single-file picks degrade to ACTION_SEND so SendActivity's
                        // ShareIntentRouter takes the SingleUri branch unchanged.
                        // Multi-file picks ride ACTION_SEND_MULTIPLE / EXTRA_STREAM
                        // as an ArrayList<Uri>, matching the system share-sheet shape.
                        if (uris.size == 1) {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_STREAM, uris.first())
                        } else {
                            action = Intent.ACTION_SEND_MULTIPLE
                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                        }
                        // Forwarded URIs come from a DocumentsProvider; the
                        // OpenDocument contract grants the calling process read
                        // access. Same-UID forwards do not strictly require an
                        // explicit grant flag, but setting it keeps the intent
                        // shape uniform with the share-sheet entry point and
                        // tolerates any picker that returns provider-side URIs
                        // requiring the cross-process grant.
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                startActivity(intent)
            }

        openTreeLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri: Uri? ->
                if (treeUri != null) {
                    val intent =
                        Intent(requireContext(), SendActivity::class.java).apply {
                            action = SendActivity.ACTION_SEND_FOLDER
                            data = treeUri
                            // FLAG_GRANT_READ_URI_PERMISSION propagates the
                            // SAF read grant to SendActivity. Without it,
                            // the receiving activity can read top-level
                            // children but `openInputStream` on individual
                            // file URIs throws SecurityException. The
                            // `OpenDocumentTree` contract already takes a
                            // persistable grant on our behalf, so the read
                            // is safe to pass through.
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    startActivity(intent)
                }
            }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        val visibilityButton = view.findViewById<Button>(R.id.main_always_visible_button)
        val visibilityCaption = view.findViewById<TextView>(R.id.main_always_visible_caption)
        // The pulse view lives at the activity level (so its drifting
        // gradient blobs paint behind the toolbar, fragment, and
        // bottom-nav as one continuous backdrop). Look it up via the
        // host activity rather than the fragment's own view subtree.
        val visibilityPulse = requireActivity().findViewById<VisibilityPulseView>(R.id.main_always_visible_pulse)
        refreshVisibilityButton(visibilityButton, visibilityCaption, visibilityPulse)
        visibilityButton.setOnClickListener {
            val next = !MdnsVisibilityOverrideHolder.isActive
            MdnsVisibilityOverrideHolder.setAlwaysVisible(next)
            refreshVisibilityButton(visibilityButton, visibilityCaption, visibilityPulse)
        }

        view.findViewById<Button>(R.id.main_send_files_button).setOnClickListener {
            launchOpenFilesPicker()
        }

        view.findViewById<Button>(R.id.main_send_folder_button).setOnClickListener {
            // Passing null means "no initial directory hint"; the
            // system picker opens at its default landing screen
            // (typically the most recently used location). The user
            // is free to navigate to any tree they have access to.
            openTreeLauncher.launch(null)
        }

        view.findViewById<Button>(R.id.main_legacy_wvmg_banner_uninstall).setOnClickListener {
            onLegacyWvmgUninstallClicked()
        }

        // Seed both polaroid ImageViews with empty-frame placeholders
        // so the cards have a recognizable silhouette during the
        // race between fragment inflation and the async gallery
        // decode. The placeholder is the same baked polaroid path
        // the real photos use, just with a neutral fill in the
        // photo area, so the rotation and edge AA are identical
        // before and after.
        val colors = polaroidColors()
        val placeholder1 = buildPolaroidBitmap(null, CARD_1_ROTATION_DEG, colors)
        val placeholder2 = buildPolaroidBitmap(null, CARD_2_ROTATION_DEG, colors)
        view.findViewById<ImageView>(R.id.main_send_preview_photo_1).setPolaroidBitmap(placeholder1)
        view.findViewById<ImageView>(R.id.main_send_preview_photo_2).setPolaroidBitmap(placeholder2)
    }

    override fun onStart() {
        super.onStart()
        // Re-sync the visibility pill to the holder on every onStart —
        // the override is process-wide and could have been changed by
        // another activity (or, in the future, a quick-settings tile)
        // while we were paused.
        val v = view
        if (v != null) {
            val visibilityButton = v.findViewById<Button>(R.id.main_always_visible_button)
            val visibilityCaption = v.findViewById<TextView>(R.id.main_always_visible_caption)
            val visibilityPulse = requireActivity().findViewById<VisibilityPulseView>(R.id.main_always_visible_pulse)
            refreshVisibilityButton(visibilityButton, visibilityCaption, visibilityPulse)
        }
        refreshLegacyWvmgBanner()
        ensurePhotoPreviews()
    }

    /**
     * Repaint the always-visible pill to match the current
     * [MdnsVisibilityOverrideHolder] state. ON gets the blue gradient
     * background plus a white label that reads "Always visible"; OFF
     * falls back to the global frosted button background plus the
     * theme's default text color and the inverted "Visible on scan"
     * label. Both states share the 24dp pill silhouette so the only
     * thing that changes between taps is the surface and the label —
     * the button's footprint stays put.
     */
    private fun refreshVisibilityButton(
        button: Button,
        caption: TextView,
        pulse: VisibilityPulseView,
    ) {
        val active = MdnsVisibilityOverrideHolder.isActive
        if (active) {
            button.setBackgroundResource(R.drawable.btn_visibility_on_background)
            button.setTextColor(ContextCompat.getColor(button.context, R.color.brand_on_primary))
            button.setText(R.string.main_always_visible_title)
            caption.setText(R.string.main_always_visible_caption_on)
            // Soft "device is broadcasting" cue: animated, low-alpha
            // concentric pulses around the pill while ON; idle while OFF.
            pulse.startPulse()
        } else {
            button.setBackgroundResource(R.drawable.btn_frosted_background)
            val defaultTextColor =
                button.context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).use {
                    it.getColor(0, 0)
                }
            button.setTextColor(defaultTextColor)
            button.setText(R.string.main_always_visible_off_title)
            caption.setText(R.string.main_always_visible_caption_off)
            pulse.stopPulse()
        }
    }

    /**
     * Drive the polaroid photo-preview fill above the action buttons.
     *
     *   * If the gallery-read permission is already granted, kick off
     *     a background load of two random recent images.
     *   * Otherwise, auto-request the permission once per fragment
     *     instance (the launcher's grant callback chains into the
     *     same load on success).
     *
     * On denial we silently leave the polaroid cards as decorative
     * empty white frames — the photos are non-essential, so a denied
     * grant should not leave the rest of the tab unusable.
     */
    private fun ensurePhotoPreviews() {
        if (hasGalleryPermission()) {
            loadRandomGalleryPhotos()
            return
        }
        if (!galleryPermissionRequested) {
            galleryPermissionRequested = true
            galleryPermissionLauncher.launch(galleryPermissionName())
        }
    }

    private fun launchOpenFilesPicker() {
        try {
            // `arrayOf("*/*")` lets the picker show every MIME type — gallery-only
            // and documents-only paths both surface.
            openFilesLauncher.launch(arrayOf("*/*"))
        } catch (e: ActivityNotFoundException) {
            // Vendor ROMs occasionally strip ACTION_OPEN_DOCUMENT (typically AOSP
            // variants without Documents UI). Fail soft with a Toast instead of
            // crashing the activity.
            Log.w(TAG, "OpenMultipleDocuments not resolvable: ${e.message}", e)
            Toast.makeText(requireContext(), R.string.main_send_files_pick_failed, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * The platform's media-permission model split at API 33: pre-33
     * apps ride the catch-all `READ_EXTERNAL_STORAGE`, while 33+
     * uses the granular `READ_MEDIA_IMAGES`. The manifest declares
     * both with the right `maxSdkVersion`/`minSdkVersion` bounds, so
     * here we just pick the right one for the running platform.
     */
    private fun galleryPermissionName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun hasGalleryPermission(): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), galleryPermissionName()) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Fetch two random recent images from MediaStore and paint them
     * into the polaroid preview cards. Runs entirely on a background
     * dispatcher; the final `setImageBitmap` calls hop back to the
     * main thread. Bound to [viewLifecycleOwner] so a destroyed view
     * cancels in-flight work cleanly.
     */
    private fun loadRandomGalleryPhotos() {
        val owner = viewLifecycleOwner
        val colors = polaroidColors()
        owner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val uris = queryRecentGalleryUris(GALLERY_QUERY_LIMIT)
                if (uris.isEmpty()) return@withContext
                val shuffled = uris.shuffled()
                val uri1 = shuffled[0]
                val uri2 = if (shuffled.size > 1) shuffled[1] else uri1
                val photo1 = decodeSampledBitmap(uri1, PREVIEW_TARGET_PX)
                val photo2 = decodeSampledBitmap(uri2, PREVIEW_TARGET_PX)
                // Bake the polaroids on the IO dispatcher (CPU work
                // but the surrounding withContext block is already
                // off the main thread, and chaining a Default hop
                // would just trade one dispatcher round trip for
                // another). Recycle the source bitmaps as soon as
                // the composition is done — each ARGB_8888 source is
                // ~5 MB, and we only need the rendered polaroid from
                // here on.
                val polaroid1 = photo1?.let { buildPolaroidBitmap(it, CARD_1_ROTATION_DEG, colors) }
                val polaroid2 = photo2?.let { buildPolaroidBitmap(it, CARD_2_ROTATION_DEG, colors) }
                photo1?.recycle()
                photo2?.recycle()
                withContext(Dispatchers.Main) {
                    val rootView = view ?: return@withContext
                    polaroid1?.let {
                        rootView.findViewById<ImageView>(R.id.main_send_preview_photo_1)?.setPolaroidBitmap(it)
                    }
                    polaroid2?.let {
                        rootView.findViewById<ImageView>(R.id.main_send_preview_photo_2)?.setPolaroidBitmap(it)
                    }
                }
            }
        }
    }

    /**
     * Build a single bitmap that contains a fully pre-rendered
     * polaroid card — drop shadow, themed frame, inner photo (or a
     * neutral placeholder fill), and the [rotationDeg] tilt — at the
     * supersampled output resolution [POLAROID_OUTPUT_PX] so the
     * caller can hand it straight to an axis-aligned ImageView with
     * no further rotation. Doing the rotation in software at ~3×
     * the display size lets us downsample on draw with bilinear +
     * mipmap filtering, which kills the stair-step aliasing that
     * view-level rotation showed at every contrast boundary (outer
     * card edge, inner photo edge, and the photo content itself).
     *
     * Layout (proportional to [POLAROID_OUTPUT_PX]):
     *   * Card occupies ~75% of the bitmap so the rotated bounding
     *     box plus a soft drop-shadow margin still fit inside.
     *   * Inner photo area is the card minus a 5% padding ring,
     *     matching the original "thin white border around the photo"
     *     polaroid look.
     *   * Corners on both the card and shadow are ~3.3% of the card
     *     size — visually about a 4dp radius on a 120dp card.
     *
     * If [photo] is null, the photo area is filled with the themed
     * chip color so the empty state still reads as a polaroid frame
     * waiting for content without flashing a white card in dark mode.
     */
    @Suppress("MagicNumber")
    private fun buildPolaroidBitmap(
        photo: Bitmap?,
        rotationDeg: Float,
        colors: PolaroidColors,
    ): Bitmap {
        val outputSize = POLAROID_OUTPUT_PX
        val output = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val center = outputSize / 2f
        val cardSize = outputSize * 0.75f
        val cornerRadius = cardSize * (4f / 120f)
        val photoInset = cardSize * (6f / 120f)
        val photoSize = cardSize - photoInset * 2

        canvas.save()
        canvas.rotate(rotationDeg, center, center)

        val cardLeft = center - cardSize / 2f
        val cardTop = center - cardSize / 2f
        val cardRight = center + cardSize / 2f
        val cardBottom = center + cardSize / 2f

        // Drop shadow — drawn first so the card overpaints it. Slight
        // downward offset + blurred mask-filter gives the same
        // gravity-pulled feel the View elevation API produced for the
        // rotated FrameLayout, but baked into the bitmap so the shadow
        // tilts with the card instead of being clipped to the
        // axis-aligned host view.
        val shadowOffsetY = cardSize * 0.025f
        val shadowBlurRadius = cardSize * 0.05f
        val shadowPaint =
            Paint().apply {
                color = 0x33000000
                isAntiAlias = true
                maskFilter = BlurMaskFilter(shadowBlurRadius, BlurMaskFilter.Blur.NORMAL)
            }
        canvas.drawRoundRect(
            cardLeft,
            cardTop + shadowOffsetY,
            cardRight,
            cardBottom + shadowOffsetY,
            cornerRadius,
            cornerRadius,
            shadowPaint,
        )

        // Card surface follows the resource palette so generated
        // bitmaps match the XML photo cards in day and night mode.
        val cardPaint =
            Paint().apply {
                color = colors.card
                isAntiAlias = true
            }
        canvas.drawRoundRect(
            cardLeft,
            cardTop,
            cardRight,
            cardBottom,
            cornerRadius,
            cornerRadius,
            cardPaint,
        )

        // Inner photo area.
        val photoLeft = center - photoSize / 2f
        val photoTop = center - photoSize / 2f
        val photoRect =
            RectF(
                photoLeft,
                photoTop,
                photoLeft + photoSize,
                photoTop + photoSize,
            )

        if (photo != null) {
            val photoPaint =
                Paint().apply {
                    isAntiAlias = true
                    isFilterBitmap = true
                    isDither = true
                }
            canvas.drawBitmap(photo, computeCenterCropSrcRect(photo, photoRect), photoRect, photoPaint)
        } else {
            val placeholderPaint =
                Paint().apply {
                    color = colors.placeholder
                    isAntiAlias = true
                }
            canvas.drawRect(photoRect, placeholderPaint)
        }

        canvas.restore()
        return output
    }

    private fun polaroidColors(): PolaroidColors =
        PolaroidColors(
            card = ContextCompat.getColor(requireContext(), R.color.surface_card),
            placeholder = ContextCompat.getColor(requireContext(), R.color.surface_chip),
        )

    private data class PolaroidColors(
        val card: Int,
        val placeholder: Int,
    )

    /**
     * Compute the source-bitmap sub-rect that fills [dstRect] under
     * `centerCrop` semantics — the same rule
     * `ImageView.scaleType="centerCrop"` follows but applied to a
     * raw Canvas.drawBitmap call. Whichever axis is the longer one
     * relative to the destination's aspect gets cropped equally on
     * both sides.
     */
    private fun computeCenterCropSrcRect(
        bitmap: Bitmap,
        dstRect: RectF,
    ): Rect {
        val srcAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
        val dstAspect = dstRect.width() / dstRect.height()
        return if (srcAspect > dstAspect) {
            // Source wider than destination — crop horizontally.
            val cropWidth = (bitmap.height * dstAspect).toInt()
            val cropX = (bitmap.width - cropWidth) / 2
            Rect(cropX, 0, cropX + cropWidth, bitmap.height)
        } else {
            // Source taller than destination — crop vertically.
            val cropHeight = (bitmap.width / dstAspect).toInt()
            val cropY = (bitmap.height - cropHeight) / 2
            Rect(0, cropY, bitmap.width, cropY + cropHeight)
        }
    }

    /**
     * Wire [bitmap] into the polaroid ImageView. Called by both the
     * placeholder seeding path and the post-decode update path so
     * the mipmap + paint-flag setup happens in exactly one place.
     */
    private fun ImageView.setPolaroidBitmap(bitmap: Bitmap) {
        bitmap.setHasMipMap(true)
        val drawable = BitmapDrawable(resources, bitmap)
        drawable.paint.apply {
            isAntiAlias = true
            isFilterBitmap = true
            isDither = true
        }
        setImageDrawable(drawable)
    }

    /**
     * Query MediaStore for the most-recently-added image URIs,
     * capped at [limit]. Sorted by `DATE_ADDED DESC` — running on a
     * device with thousands of images, we still only walk the first
     * page since [limit] is small. Returns an empty list on any
     * provider error so callers can fall through to the empty-card
     * decorative state.
     */
    private fun queryRecentGalleryUris(limit: Int): List<Uri> {
        val ctx = context ?: return emptyList()
        val collection =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        val uris = mutableListOf<Uri>()
        try {
            ctx.contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext() && uris.size < limit) {
                    uris.add(ContentUris.withAppendedId(collection, cursor.getLong(idCol)))
                }
            }
        } catch (e: SecurityException) {
            // Permission was revoked between the check above and the
            // query (rare but documented). Treat as empty.
            Log.w(TAG, "MediaStore query denied: ${e.message}")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "MediaStore projection rejected: ${e.message}")
        }
        return uris
    }

    /**
     * Decode an image URI into a memory-friendly bitmap whose larger
     * edge is at most [targetPx]. Uses the platform `ImageDecoder`
     * pipeline on API 28+ (which exposes a clean `setTargetSampleSize`
     * call), and falls back to the classic `BitmapFactory` two-pass
     * sampling loop on older devices. Returns null on any decode
     * failure so the caller can leave the polaroid card empty.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun decodeSampledBitmap(
        uri: Uri,
        targetPx: Int,
    ): Bitmap? {
        val resolver = context?.contentResolver ?: return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(resolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val ratio =
                        maxOf(
                            info.size.width / targetPx,
                            info.size.height / targetPx,
                            1,
                        )
                    decoder.setTargetSampleSize(ratio)
                    // Force a software (ARGB_8888) bitmap rather than
                    // the API 28+ default of HARDWARE. The hardware
                    // config is GPU-only and crashes with
                    // "Software rendering doesn't support hardware
                    // bitmaps" the moment the host card promotes
                    // itself to a software layer (which we do on the
                    // rotated polaroid frames so the antialiased
                    // canvas transform smooths the white card edges).
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                decodeWithLegacySampling(uri, targetPx)
            }
        } catch (e: Exception) {
            Log.w(TAG, "decodeSampledBitmap failed for $uri", e)
            null
        }
    }

    /**
     * Pre-API-28 fallback: peek at the image bounds with
     * `inJustDecodeBounds`, compute a sample size that targets
     * [targetPx] on the larger edge, then decode for real. Keeps
     * the polaroid preview working all the way down to minSdk 24
     * even though the receivers we test on are all 28+.
     */
    private fun decodeWithLegacySampling(
        uri: Uri,
        targetPx: Int,
    ): Bitmap? {
        val resolver = context?.contentResolver ?: return null
        val bounds =
            android.graphics.BitmapFactory
                .Options()
                .apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, bounds)
        }
        val sample =
            maxOf(
                bounds.outWidth / targetPx,
                bounds.outHeight / targetPx,
                1,
            )
        val opts =
            android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        return resolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, opts)
        }
    }

    /**
     * Show or hide the #145 migration banner based on whether a
     * coresident legacy WhenVivoMeetsGoogle install is still present.
     * Stores the detected legacy package id in [legacyPackageInBanner]
     * so the Uninstall button click handler can route directly without
     * re-running detection (avoids a TOCTOU race where the user
     * uninstalls one variant while both were detected).
     */
    private fun refreshLegacyWvmgBanner() {
        val banner = view?.findViewById<View>(R.id.main_legacy_wvmg_banner) ?: return
        val legacyPackage = LegacyPackageDetectorAndroid.findInstalledLegacy(requireContext())
        legacyPackageInBanner = legacyPackage
        banner.visibility = if (legacyPackage != null) View.VISIBLE else View.GONE
    }

    /**
     * Open Android's standard uninstall confirmation dialog for the
     * legacy WhenVivoMeetsGoogle package using `Intent.ACTION_DELETE`.
     * That action does not need any extra permission and does not
     * require us to be a device owner — the platform shows its own
     * confirmation UI before tearing the package down.
     */
    private fun onLegacyWvmgUninstallClicked() {
        val target = legacyPackageInBanner ?: return
        val intent =
            Intent(Intent.ACTION_DELETE).apply {
                data = Uri.fromParts("package", target, null)
            }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "ACTION_DELETE not resolvable for $target: ${e.message}", e)
            val message = getString(R.string.main_legacy_wvmg_banner_unavailable, target)
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val TAG = "BadaMain"

        // Polaroid preview tunables. The query limit is intentionally
        // small — we only show two cards and want a "recent" feel,
        // so capping at 30 keeps the cursor walk cheap on devices
        // with massive galleries while still giving us enough
        // headroom to randomize without picking the same two photos
        // every cold start.
        const val GALLERY_QUERY_LIMIT = 30

        // 1080 px on the larger edge — three times the ~360 px the
        // 120dp polaroid card occupies on a 3x density screen. The
        // extra resolution acts as supersample anti-aliasing for the
        // ±7° rotation: the canvas downsamples nine source pixels
        // into one display pixel, which averages out the diagonal
        // stair-stepping the 1:1 sized bitmap was showing. Cost is
        // ~5 MB per ARGB_8888 bitmap × 2 cards = ~10 MB resident,
        // acceptable for a flagship-target Android app.
        const val PREVIEW_TARGET_PX = 1080

        // Side length of the supersampled output bitmap that holds the
        // pre-rendered polaroid card. 1080 lines up with the source
        // photo decode size so the inner photo area (~75% of the
        // output) ends up being painted from a near-1:1 src→dst pixel
        // ratio. Drawn into a 160dp ImageView, this ratio is roughly
        // 3× the display pixel density — the supersampling headroom
        // that produces clean AA at every contrast boundary on the
        // card after the rotation is baked in.
        const val POLAROID_OUTPUT_PX = 1080

        // Card tilt angles. Both halves of the pair come from the
        // pre-baked-rotation refactor; the previous XML
        // `rotation="-7"` / `rotation="6"` attributes are gone, so
        // the only place these values live now is here.
        const val CARD_1_ROTATION_DEG = -7f
        const val CARD_2_ROTATION_DEG = 6f
    }
}
