package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.core.firebase.OperationType
import com.example.core.firebase.handleFirestoreError
import com.example.core.model.StatusUpdate
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.Date
import java.util.UUID

class StatusRepository(
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

    fun observeActiveStatuses(uid: String): Flow<List<StatusUpdate>> = flow {
        val ownFlow = db.collection("statuses")
            .whereEqualTo("userId", uid)
            .snapshots()
            .map { it.toObjects(StatusUpdate::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }

        val publicFlow = db.collection("statuses")
            .whereEqualTo("privacy", "everyone")
            .snapshots()
            .map { it.toObjects(StatusUpdate::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }

        val sharedFlow = db.collection("statuses")
            .whereArrayContains("allowedViewerIds", uid)
            .snapshots()
            .map { it.toObjects(StatusUpdate::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }

        emitAll(
            combine(ownFlow, publicFlow, sharedFlow) { own, pub, shared ->
                val now = Timestamp.now()
                (own + pub + shared)
                    .distinctBy { it.id }
                    .filter { status ->
                        val exp = status.expiresAt
                        exp == null || exp.seconds > now.seconds
                    }
                    .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
            }.catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val toLog = firestoreError ?: (error as? Exception)
                if (toLog != null && auth.currentUser?.uid == uid) {
                    handleFirestoreError(toLog, OperationType.LIST, "statuses")
                }
                throw error
            }
        )
    }

    suspend fun postStatus(
        userName: String,
        userPhotoUrl: String,
        type: String,
        textContent: String,
        backgroundColorHex: String = "#0AA83F",
        mediaUrl: String = "",
        privacy: String = "everyone",
        allowedViewerIds: List<String> = emptyList()
    ): Result<String> {
        val uid = requireUserId()
        val statusId = "st_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val docRef = db.collection("statuses").document(statusId)
        val expiresAt = Timestamp(Date(System.currentTimeMillis() + 24L * 60L * 60L * 1000L))
        val viewersScope = (listOf(uid) + allowedViewerIds).distinct().take(100)
        return try {
            docRef.set(
                mapOf(
                    "userId" to uid,
                    "userName" to userName.trim().ifBlank { "Whatacall User" }.take(60),
                    "userPhotoUrl" to userPhotoUrl.take(1024),
                    "type" to type,
                    "textContent" to textContent.trim().take(700),
                    "backgroundColorHex" to backgroundColorHex.take(16),
                    "mediaUrl" to mediaUrl.take(1024),
                    "privacy" to privacy,
                    "allowedViewerIds" to viewersScope,
                    "viewerIds" to emptyList<String>(),
                    "expiresAt" to expiresAt,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(statusId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun markStatusViewed(statusId: String) {
        val uid = auth.currentUser?.uid ?: return
        val docRef = db.collection("statuses").document(statusId)
        try {
            docRef.set(
                mapOf("viewerIds" to FieldValue.arrayUnion(uid)),
                SetOptions.merge()
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
        }
    }

    suspend fun deleteStatus(statusId: String): Result<Unit> {
        requireUserId()
        val docRef = db.collection("statuses").document(statusId)
        return try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }
}
