package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.core.crypto.E2eeCryptoManager
import com.example.core.firebase.OperationType
import com.example.core.firebase.handleFirestoreError
import com.example.core.model.PhoneDirectoryEntry
import com.example.core.model.UserPrivateInfo
import com.example.core.model.UserProfile
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.Locale

class UserRepository(
    private val db: FirebaseFirestore
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth = Firebase.auth

    private fun requireUserId(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("User must be signed in.")

    fun observeUserProfile(uid: String): Flow<UserProfile?> = flow {
        val docRef = db.collection("users").document(uid)
        emitAll(
            docRef.snapshots()
                .map { snapshot ->
                    if (!snapshot.exists()) null
                    else snapshot.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser != null) {
                        handleFirestoreError(toLog, OperationType.GET, docRef.path)
                    }
                    throw error
                }
        )
    }

    fun observePrivateInfo(uid: String): Flow<UserPrivateInfo?> = flow {
        val docRef = db.collection("users").document(uid).collection("private").document("info")
        emitAll(
            docRef.snapshots()
                .map { snapshot ->
                    if (!snapshot.exists()) null
                    else snapshot.toObject(UserPrivateInfo::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(toLog, OperationType.GET, docRef.path)
                    }
                    throw error
                }
        )
    }

    suspend fun getUserProfile(uid: String): UserProfile? {
        val docRef = db.collection("users").document(uid)
        return try {
            val snap = docRef.get().await()
            if (!snap.exists()) null
            else snap.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            null
        }
    }

    suspend fun isPhoneAlreadyRegisteredByAnotherUser(e164Phone: String): Boolean {
        val uid = requireUserId()
        val hash = E2eeCryptoManager.sha256Hex(e164Phone)
        val docRef = db.collection("phoneDirectory").document(hash)
        return try {
            val snap = docRef.get().await()
            snap.exists() && snap.getString("uid") != uid
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            false
        }
    }

    suspend fun findUserByUsername(username: String): UserProfile? {
        val clean = username.trim().removePrefix("@").lowercase(Locale.US)
        if (clean.length < 3) return null
        val usernameRef = db.collection("usernames").document(clean)
        return try {
            val snap = usernameRef.get().await()
            val targetUid = snap.getString("uid") ?: return null
            getUserProfile(targetUid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, usernameRef.path)
            null
        }
    }

    suspend fun findUserByPhoneNumber(e164Phone: String): UserProfile? {
        val hash = E2eeCryptoManager.sha256Hex(e164Phone)
        val dirRef = db.collection("phoneDirectory").document(hash)
        return try {
            val snap = dirRef.get().await()
            val targetUid = snap.getString("uid") ?: return null
            getUserProfile(targetUid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, dirRef.path)
            null
        }
    }

    suspend fun lookupPhoneDirectory(phoneHashes: List<String>): Map<String, UserProfile> {
        val result = mutableMapOf<String, UserProfile>()
        for (hash in phoneHashes.distinct()) {
            if (hash.length != 64) continue
            val dirRef = db.collection("phoneDirectory").document(hash)
            try {
                val snap = dirRef.get().await()
                if (snap.exists()) {
                    val entry = snap.toObject(PhoneDirectoryEntry::class.java)
                    if (entry != null && entry.uid.isNotBlank()) {
                        val profile = getUserProfile(entry.uid)
                        if (profile != null) {
                            result[hash] = profile
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.GET, dirRef.path)
            }
        }
        return result
    }

    suspend fun registerOrUpdatePhoneAndProfile(
        displayName: String,
        about: String,
        username: String,
        photoUrl: String,
        verifiedE164Phone: String,
        publicKeyBase64: String,
        email: String
    ): Result<Unit> {
        val uid = requireUserId()
        val cleanName = displayName.trim().ifBlank { "Whatacall User" }.take(60)
        val cleanAbout = about.trim().take(160)
        val cleanUsername = username.trim().removePrefix("@").lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_.]"), "").take(30)
        val phoneHash = E2eeCryptoManager.sha256Hex(verifiedE164Phone)
        val phoneMasked = E2eeCryptoManager.maskPhone(verifiedE164Phone)

        val userRef = db.collection("users").document(uid)
        val privateRef = userRef.collection("private").document("info")
        val phoneDirRef = db.collection("phoneDirectory").document(phoneHash)

        return try {
            val existingPhone = phoneDirRef.get().await()
            if (existingPhone.exists() && existingPhone.getString("uid") != uid) {
                return Result.failure(
                    IllegalStateException("This phone number is already registered with another Whatacall account.")
                )
            }

            if (cleanUsername.length >= 3) {
                val usernameRef = db.collection("usernames").document(cleanUsername)
                val existingUsername = usernameRef.get().await()
                if (existingUsername.exists() && existingUsername.getString("uid") != uid) {
                    return Result.failure(
                        IllegalStateException("Username @$cleanUsername is already taken.")
                    )
                }
                if (!existingUsername.exists()) {
                    usernameRef.set(
                        mapOf(
                            "uid" to uid,
                            "username" to cleanUsername,
                            "createdAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                }
            }

            val existingProfileSnap = userRef.get().await()
            if (!existingProfileSnap.exists()) {
                val profileData = mapOf(
                    "uid" to uid,
                    "displayName" to cleanName,
                    "username" to cleanUsername,
                    "about" to cleanAbout,
                    "photoUrl" to photoUrl,
                    "phoneHash" to phoneHash,
                    "phoneMasked" to phoneMasked,
                    "publicKeyBase64" to publicKeyBase64,
                    "isPhoneVerified" to true,
                    "isOnline" to true,
                    "lastSeenPrivacy" to "everyone",
                    "photoPrivacy" to "everyone",
                    "statusPrivacy" to "everyone",
                    "isSuspended" to false,
                    "lastSeenAt" to FieldValue.serverTimestamp(),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                userRef.set(profileData).await()
            } else {
                val updateData = mapOf(
                    "displayName" to cleanName,
                    "username" to cleanUsername,
                    "about" to cleanAbout,
                    "photoUrl" to photoUrl,
                    "phoneHash" to phoneHash,
                    "phoneMasked" to phoneMasked,
                    "publicKeyBase64" to publicKeyBase64,
                    "isPhoneVerified" to true,
                    "isOnline" to true,
                    "lastSeenAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                userRef.set(updateData, SetOptions.merge()).await()
            }

            val existingPrivate = privateRef.get().await()
            if (!existingPrivate.exists()) {
                privateRef.set(
                    mapOf(
                        "uid" to uid,
                        "phoneNumber" to verifiedE164Phone,
                        "email" to email,
                        "fcmToken" to "",
                        "chatWallpaper" to "default",
                        "darkModePreference" to "system",
                        "notificationsEnabled" to true,
                        "callRingtoneEnabled" to true,
                        "blockedUserIds" to emptyList<String>(),
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                privateRef.set(
                    mapOf(
                        "phoneNumber" to verifiedE164Phone,
                        "email" to email,
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                ).await()
            }

            if (!existingPhone.exists()) {
                phoneDirRef.set(
                    mapOf(
                        "uid" to uid,
                        "phoneHash" to phoneHash,
                        "displayName" to cleanName,
                        "photoUrl" to photoUrl,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else {
                phoneDirRef.set(
                    mapOf(
                        "displayName" to cleanName,
                        "photoUrl" to photoUrl
                    ),
                    SetOptions.merge()
                ).await()
            }

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, userRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateProfileFields(
        displayName: String,
        about: String,
        photoUrl: String? = null
    ): Result<Unit> {
        val uid = requireUserId()
        val userRef = db.collection("users").document(uid)
        val updates = mutableMapOf<String, Any>(
            "displayName" to displayName.trim().ifBlank { "Whatacall User" }.take(60),
            "about" to about.trim().take(160),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (photoUrl != null) {
            updates["photoUrl"] = photoUrl.take(1024)
        }
        return try {
            userRef.set(updates, SetOptions.merge()).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, userRef.path)
            Result.failure(e)
        }
    }

    suspend fun updatePrivacySettings(
        lastSeenPrivacy: String,
        photoPrivacy: String,
        statusPrivacy: String
    ): Result<Unit> {
        val uid = requireUserId()
        val userRef = db.collection("users").document(uid)
        return try {
            userRef.set(
                mapOf(
                    "lastSeenPrivacy" to lastSeenPrivacy,
                    "photoPrivacy" to photoPrivacy,
                    "statusPrivacy" to statusPrivacy,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, userRef.path)
            Result.failure(e)
        }
    }

    suspend fun updatePresence(isOnline: Boolean) {
        val uid = auth.currentUser?.uid ?: return
        val userRef = db.collection("users").document(uid)
        try {
            val snap = userRef.get().await()
            if (snap.exists()) {
                userRef.set(
                    mapOf(
                        "isOnline" to isOnline,
                        "lastSeenAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                ).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, userRef.path)
        }
    }

    suspend fun updateFcmToken(token: String) {
        val uid = auth.currentUser?.uid ?: return
        val privateRef = db.collection("users").document(uid).collection("private").document("info")
        try {
            val snap = privateRef.get().await()
            if (snap.exists()) {
                privateRef.set(
                    mapOf(
                        "fcmToken" to token.take(512),
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                ).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, privateRef.path)
        }
    }

    suspend fun toggleBlockUser(targetUid: String, block: Boolean): Result<Unit> {
        val uid = requireUserId()
        val privateRef = db.collection("users").document(uid).collection("private").document("info")
        return try {
            val op = if (block) FieldValue.arrayUnion(targetUid) else FieldValue.arrayRemove(targetUid)
            privateRef.set(
                mapOf(
                    "blockedUserIds" to op,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, privateRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteAccountData(): Result<Unit> {
        val uid = requireUserId()
        val userRef = db.collection("users").document(uid)
        val privateRef = userRef.collection("private").document("info")
        return try {
            val profile = getUserProfile(uid)
            if (profile != null && profile.phoneHash.isNotBlank()) {
                try {
                    db.collection("phoneDirectory").document(profile.phoneHash).delete().await()
                } catch (_: Exception) {
                }
            }
            if (profile != null && profile.username.isNotBlank()) {
                try {
                    db.collection("usernames").document(profile.username.lowercase(Locale.US)).delete().await()
                } catch (_: Exception) {
                }
            }
            try {
                privateRef.delete().await()
            } catch (_: Exception) {
            }
            userRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, userRef.path)
            Result.failure(e)
        }
    }
}
