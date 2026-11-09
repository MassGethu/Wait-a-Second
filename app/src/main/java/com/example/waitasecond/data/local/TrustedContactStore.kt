package com.example.waitasecond.data.local

import android.content.ContentResolver
import android.content.SharedPreferences
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.Contacts
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

data class TrustedContact(val uri: Uri, val label: String) {
    // Android's lookup URI includes the stable lookup key followed by the current row ID.
    val lookupKey get() = uri.pathSegments[2]
}

/** Stores only picker-selected contact references. The Contacts app owns favorite status. */
class TrustedContactStore(private val options: SharedPreferences) {
    fun contacts(): List<TrustedContact> {
        val json = runCatching { JSONArray(options.getString(CONTACTS, "[]")) }.getOrElse { return emptyList() }
        return (0 until json.length()).mapNotNull { index ->
            val row = json.optJSONObject(index) ?: return@mapNotNull null
            val uri = Uri.parse(row.optString("uri"))
            if (!isContactUri(uri) || uri.pathSegments.getOrNull(1) != "lookup" || uri.pathSegments.size < 3) null
            else TrustedContact(uri, row.optString("label"))
        }
    }

    fun addFromPicker(resolver: ContentResolver, uri: Uri): Boolean {
        if (!isContactUri(uri)) return false
        val contact = try {
            resolver.query(uri, arrayOf(Contacts._ID, Contacts.LOOKUP_KEY, Contacts.DISPLAY_NAME_PRIMARY),
                null, null, null)?.use { row ->
                if (!row.moveToFirst()) return false
                val key = row.getString(1)?.takeIf { it.isNotBlank() } ?: return false
                val lookup = Contacts.getLookupUri(row.getLong(0), key) ?: return false
                TrustedContact(lookup, row.getString(2).orEmpty())
            }
        } catch (_: SecurityException) { null }
        catch (_: IllegalArgumentException) { null }
        if (contact == null) return false
        save(contacts().filterNot { it.lookupKey == contact.lookupKey } + contact)
        return true
    }

    fun remove(contact: TrustedContact) = save(contacts().filterNot { it.lookupKey == contact.lookupKey })

    private fun save(contacts: List<TrustedContact>) {
        val rows = JSONArray()
        contacts.forEach { contact ->
            rows.put(JSONObject().put("uri", contact.uri.toString()).put("label", contact.label))
        }
        options.edit { putString(CONTACTS, rows.toString()) }
    }

    private fun isContactUri(uri: Uri) = uri.scheme == ContentResolver.SCHEME_CONTENT &&
        uri.authority == ContactsContract.AUTHORITY && uri.pathSegments.firstOrNull() == "contacts"

    private companion object { const val CONTACTS = "trusted_contacts" }
}
