/*
 * Copyright 2026 Bada contributors.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package dev.bluehouse.bada.namecard

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import dev.bluehouse.bada.discovery.diagnostics.DiagnosticLog
import dev.bluehouse.bada.protocol.namecard.NameCard
import dev.bluehouse.bada.protocol.namecard.NameCardEntry
import dev.bluehouse.bada.protocol.namecard.NameCardEntryKind
import android.provider.ContactsContract.CommonDataKinds as ck
import android.provider.ContactsContract.Intents.Insert as ins

/**
 * **Name Card → Android contact saver.** Saves a received [NameCard] as a REAL
 * Android contact for the tap-to-share feature.
 *
 * Deliberately does NOT go through a vCard `.vcf` file (vCard file import is
 * unreliable across OEM Contacts apps, and the card already has structured
 * fields). Instead:
 *  - [saveDirect] — a `ContactsContract` raw-contact insert (needs WRITE_CONTACTS).
 *    Seamless, stays in Bada.
 *  - [systemInsertIntent] — the system **Add contact** screen prefilled
 *    (`Intent.ACTION_INSERT`), used as the no-permission fallback when
 *    WRITE_CONTACTS is denied; the user confirms in the OS Contacts UI.
 *
 * Callers ([NameCardTransferActivity]) try [saveDirect] when [hasWritePermission]
 * and otherwise `startActivity([systemInsertIntent])`.
 */
internal object NameCardSaver {
    private const val TAG = "NameCardSaver"

    fun hasWritePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Insert [card] directly via ContactsContract. Builds a new raw contact
     * (no account = device/local) with a structured name + phone + email as
     * present. Returns the saved contact's viewable [Uri] (for opening it in the
     * Contacts app), or `null` on any failure (caller falls back to
     * [systemInsertIntent]).
     */
    fun saveDirect(
        context: Context,
        card: NameCard,
    ): Uri? {
        val ops = ArrayList<ContentProviderOperation>()
        // Raw contact anchor (index 0); subsequent rows back-reference it.
        ops.add(
            ContentProviderOperation
                .newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build(),
        )
        card.displayName?.let { name ->
            ops.add(
                dataInsert()
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                    ).withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                    .build(),
            )
        }
        card.phoneNumber?.let { phone ->
            ops.add(
                dataInsert()
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                    ).withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                    .withValue(
                        ContactsContract.CommonDataKinds.Phone.TYPE,
                        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
                    ).build(),
            )
        }
        card.email?.let { email ->
            ops.add(
                dataInsert()
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                    ).withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                    .withValue(
                        ContactsContract.CommonDataKinds.Email.TYPE,
                        ContactsContract.CommonDataKinds.Email.TYPE_HOME,
                    ).build(),
            )
        }
        // Richer typed fields (company, title, address, website, birthday, note, nickname,
        // additional phones/emails) → one ContactsContract data row each.
        for (entry in card.entries) {
            ops.add(entryOp(entry.kind, entry.value))
        }
        return try {
            val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            DiagnosticLog.w(TAG, "saved contact directly (${ops.size - 1} fields)")
            results.firstOrNull()?.uri?.let { rawContactUri -> viewUriFor(context, rawContactUri) }
        } catch (
            @Suppress("TooGenericExceptionCaught") t: Throwable,
        ) {
            DiagnosticLog.w(TAG, "saveDirect failed: ${t.message}")
            null
        }
    }

    /**
     * Resolve the aggregated-contact view [Uri] (content://…/contacts/<id>) for a
     * freshly inserted raw-contact [Uri], so the caller can open it in Contacts.
     * Returns `null` if the contact id can't be read.
     */
    private fun viewUriFor(
        context: Context,
        rawContactUri: Uri,
    ): Uri? =
        try {
            context.contentResolver
                .query(rawContactUri, arrayOf(ContactsContract.RawContacts.CONTACT_ID), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, cursor.getLong(0))
                    } else {
                        null
                    }
                }
        } catch (
            @Suppress("TooGenericExceptionCaught") t: Throwable,
        ) {
            DiagnosticLog.w(TAG, "viewUriFor failed: ${t.message}")
            null
        }

    /**
     * The system "Add contact" screen prefilled from [card]. No permission
     * required; the user taps Save in the OS Contacts UI.
     */
    fun systemInsertIntent(card: NameCard): Intent =
        Intent(Intent.ACTION_INSERT).apply {
            type = ContactsContract.Contacts.CONTENT_TYPE
            card.displayName?.let { putExtra(ContactsContract.Intents.Insert.NAME, it) }
            card.phoneNumber?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
            card.email?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
            // The richer fields the ACTION_INSERT screen accepts as extras (website/birthday/nickname
            // aren't Insert extras, so they only land via saveDirect's ContactsContract path).
            firstEntry(card, NameCardEntryKind.COMPANY)?.let { putExtra(ins.COMPANY, it) }
            firstEntry(card, NameCardEntryKind.TITLE)?.let { putExtra(ins.JOB_TITLE, it) }
            firstEntry(card, NameCardEntryKind.ADDRESS)?.let { putExtra(ins.POSTAL, it) }
            firstEntry(card, NameCardEntryKind.NOTE)?.let { putExtra(ins.NOTES, it) }
            firstEntry(card, NameCardEntryKind.PHONE)?.let { putExtra(ins.SECONDARY_PHONE, it) }
            firstEntry(card, NameCardEntryKind.EMAIL)?.let { putExtra(ins.SECONDARY_EMAIL, it) }
        }

    private fun firstEntry(
        card: NameCard,
        kind: NameCardEntryKind,
    ): String? = card.entries.firstOrNull { it.kind == kind }?.value

    private fun dataInsert(): ContentProviderOperation.Builder =
        ContentProviderOperation
            .newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)

    /** Build the ContactsContract data-row insert for one richer [NameCardEntry]. */
    private fun entryOp(
        kind: NameCardEntryKind,
        value: String,
    ): ContentProviderOperation {
        val b = dataInsert()
        return when (kind) {
            NameCardEntryKind.COMPANY ->
                b
                    .withValue(ContactsContract.Data.MIMETYPE, ck.Organization.CONTENT_ITEM_TYPE)
                    .withValue(ck.Organization.COMPANY, value)
            NameCardEntryKind.TITLE ->
                b
                    .withValue(ContactsContract.Data.MIMETYPE, ck.Organization.CONTENT_ITEM_TYPE)
                    .withValue(ck.Organization.TITLE, value)
            NameCardEntryKind.ADDRESS ->
                b
                    .withValue(ContactsContract.Data.MIMETYPE, ck.StructuredPostal.CONTENT_ITEM_TYPE)
                    .withValue(ck.StructuredPostal.FORMATTED_ADDRESS, value)
            NameCardEntryKind.WEBSITE ->
                b
                    .withValue(ContactsContract.Data.MIMETYPE, ck.Website.CONTENT_ITEM_TYPE)
                    .withValue(ck.Website.URL, value)
            NameCardEntryKind.BIRTHDAY ->
                b
                    .withValue(ContactsContract.Data.MIMETYPE, ck.Event.CONTENT_ITEM_TYPE)
                    .withValue(ck.Event.START_DATE, value)
                    .withValue(ck.Event.TYPE, ck.Event.TYPE_BIRTHDAY)
            NameCardEntryKind.NOTE ->
                b
                    .withValue(ContactsContract.Data.MIMETYPE, ck.Note.CONTENT_ITEM_TYPE)
                    .withValue(ck.Note.NOTE, value)
            NameCardEntryKind.NICKNAME ->
                b
                    .withValue(ContactsContract.Data.MIMETYPE, ck.Nickname.CONTENT_ITEM_TYPE)
                    .withValue(ck.Nickname.NAME, value)
            NameCardEntryKind.PHONE ->
                b
                    .withValue(ContactsContract.Data.MIMETYPE, ck.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ck.Phone.NUMBER, value)
                    .withValue(ck.Phone.TYPE, ck.Phone.TYPE_OTHER)
            NameCardEntryKind.EMAIL ->
                b
                    .withValue(ContactsContract.Data.MIMETYPE, ck.Email.CONTENT_ITEM_TYPE)
                    .withValue(ck.Email.ADDRESS, value)
                    .withValue(ck.Email.TYPE, ck.Email.TYPE_OTHER)
        }.build()
    }
}
