package com.contactsync.app.data

import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.ContactsContract
import com.contactsync.app.domain.AccountRef
import com.contactsync.app.domain.AccountTargetingException
import com.contactsync.app.domain.ContactRecord
import com.contactsync.app.domain.ContactStore
import com.contactsync.app.domain.LabeledValue
import com.contactsync.app.domain.ReadResult
import java.security.MessageDigest

class AndroidContactStore(private val context: Context) : ContactStore {
    private val resolver get() = context.contentResolver

    fun googleAccounts(): List<AccountRef> = AccountManager.get(context)
        .getAccountsByType(GOOGLE_ACCOUNT_TYPE)
        .map { AccountRef(it.name, it.type) }
        .distinctBy { it.stableKey }
        .sortedBy { it.name.lowercase() }

    override fun read(account: AccountRef): ReadResult {
        val builders = linkedMapOf<Long, ContactBuilder>()
        var rowErrors = 0
        val projection = arrayOf(
            ContactsContract.Data.RAW_CONTACT_ID,
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.DATA1,
            ContactsContract.Data.DATA2,
            ContactsContract.Data.DATA3,
        )
        val selection = "${ContactsContract.RawContacts.ACCOUNT_NAME}=? AND " +
            "${ContactsContract.RawContacts.ACCOUNT_TYPE}=? AND " +
            "${ContactsContract.RawContacts.DELETED}=0"
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            projection,
            selection,
            arrayOf(account.name, account.type),
            ContactsContract.Data.RAW_CONTACT_ID,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(ContactsContract.Data.RAW_CONTACT_ID)
            val mimeColumn = cursor.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
            val data1 = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA1)
            val data2 = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA2)
            val data3 = cursor.getColumnIndexOrThrow(ContactsContract.Data.DATA3)
            while (cursor.moveToNext()) {
                try {
                    val id = cursor.getLong(idColumn)
                    val builder = builders.getOrPut(id) { ContactBuilder(id) }
                    val value = cursor.stringOrNull(data1)
                    val type = if (cursor.isNull(data2)) 0 else cursor.getInt(data2)
                    val label = cursor.stringOrNull(data3)
                    when (cursor.getString(mimeColumn)) {
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> {
                            builder.givenName = cursor.stringOrNull(data2)
                            builder.familyName = cursor.stringOrNull(data3)
                        }
                        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE ->
                            value?.let { builder.emails += LabeledValue(it, type, label) }
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE ->
                            value?.let { builder.phones += LabeledValue(it, type, label) }
                        ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE ->
                            value?.let { builder.websites += LabeledValue(it, type, label) }
                    }
                } catch (_: RuntimeException) {
                    rowErrors++
                }
            }
        } ?: throw IllegalStateException("Contacts Provider returned no cursor for ${account.name}")
        return ReadResult(builders.values.map(ContactBuilder::build), rowErrors)
    }

    override fun insert(account: AccountRef, contact: ContactRecord): Long {
        val operations = arrayListOf<ContentProviderOperation>()
        operations += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, account.name)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, account.type)
            .build()

        if (!contact.givenName.isNullOrBlank() || !contact.familyName.isNullOrBlank()) {
            operations += dataInsert(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, contact.givenName)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, contact.familyName)
                .build()
        }
        contact.emails.forEach { field ->
            operations += dataInsert(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, field.value)
                .withValue(ContactsContract.CommonDataKinds.Email.TYPE, field.type)
                .withValue(ContactsContract.CommonDataKinds.Email.LABEL, field.label)
                .build()
        }
        contact.phones.forEach { field ->
            operations += dataInsert(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, field.value)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, field.type)
                .withValue(ContactsContract.CommonDataKinds.Phone.LABEL, field.label)
                .build()
        }
        contact.websites.forEach { field ->
            operations += dataInsert(ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Website.URL, field.value)
                .withValue(ContactsContract.CommonDataKinds.Website.TYPE, field.type)
                .withValue(ContactsContract.CommonDataKinds.Website.LABEL, field.label)
                .build()
        }

        val results = resolver.applyBatch(ContactsContract.AUTHORITY, operations)
        val rawId = results.firstOrNull()?.uri?.let(ContentUris::parseId)
            ?: throw AccountTargetingException("The Contacts Provider did not return a raw-contact ID.")
        verifyAccount(rawId, account)
        return rawId
    }

    override fun recordMapping(
        from: AccountRef,
        sourceRawId: Long?,
        to: AccountRef,
        destinationRawId: Long,
    ) {
        if (sourceRawId == null) return
        val key = sha256("${from.stableKey}\u0000$sourceRawId\u0000${to.stableKey}")
        context.getSharedPreferences("raw_contact_mappings", Context.MODE_PRIVATE)
            .edit()
            .putLong(key, destinationRawId)
            .apply()
    }

    private fun dataInsert(mimeType: String): ContentProviderOperation.Builder =
        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, mimeType)

    private fun verifyAccount(rawId: Long, expected: AccountRef) {
        val projection = arrayOf(
            ContactsContract.RawContacts.ACCOUNT_NAME,
            ContactsContract.RawContacts.ACCOUNT_TYPE,
        )
        val actual = resolver.query(
            ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawId),
            projection,
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null else AccountRef(
                cursor.getString(0).orEmpty(),
                cursor.getString(1).orEmpty(),
            )
        }
        if (actual != expected) {
            throw AccountTargetingException(
                "Created raw contact $rawId was not verified under the selected TO account. Copying stopped.",
            )
        }
    }

    private fun android.database.Cursor.stringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private class ContactBuilder(private val id: Long) {
        var givenName: String? = null
        var familyName: String? = null
        val emails = mutableListOf<LabeledValue>()
        val phones = mutableListOf<LabeledValue>()
        val websites = mutableListOf<LabeledValue>()
        fun build() = ContactRecord(id, givenName, familyName, emails, phones, websites)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val GOOGLE_ACCOUNT_TYPE = "com.google"
    }
}

