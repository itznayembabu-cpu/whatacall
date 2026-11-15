package com.example.data.repository

import android.content.Context
import android.provider.ContactsContract
import com.example.core.crypto.E2eeCryptoManager
import com.example.core.model.CountryCatalog
import com.example.core.model.MatchedContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ContactsRepository(
    private val userRepository: UserRepository
) {
    suspend fun loadAndMatchContacts(context: Context): List<MatchedContact> = withContext(Dispatchers.IO) {
        val rawContacts = mutableListOf<Pair<String, String>>()
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )
        cursor?.use { c ->
            val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext()) {
                val name = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else ""
                val rawNum = if (numIdx >= 0) c.getString(numIdx) ?: "" else ""
                val e164 = CountryCatalog.normalizeDeviceContactNumber(rawNum)
                if (name.isNotBlank() && !e164.isNullOrBlank()) {
                    rawContacts.add(name to e164)
                }
            }
        }

        val deduplicated = rawContacts.distinctBy { it.second }
        val hashes = deduplicated.map { E2eeCryptoManager.sha256Hex(it.second) }
        val matchedProfiles = userRepository.lookupPhoneDirectory(hashes)

        deduplicated.map { (name, e164) ->
            val hash = E2eeCryptoManager.sha256Hex(e164)
            MatchedContact(
                displayName = name,
                phoneNumber = e164,
                phoneHash = hash,
                registeredProfile = matchedProfiles[hash]
            )
        }.sortedWith(
            compareByDescending<MatchedContact> { it.registeredProfile != null }
                .thenBy { it.displayName.lowercase() }
        )
    }
}
