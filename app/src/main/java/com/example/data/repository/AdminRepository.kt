package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.core.firebase.OperationType
import com.example.core.firebase.handleFirestoreError
import com.example.core.model.AbuseReport
import com.example.core.model.UserProfile
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.util.UUID

class AdminRepository(
    private val db: FirebaseFirestore
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth = Firebase.auth

    suspend fun isCurrentUserAdmin(): Boolean {
        val user = auth.currentUser ?: return false
        if (user.isEmailVerified && user.email == BOOTSTRAPPED_ADMIN_EMAIL) return true
        val adminDoc = db.collection("admins").document(user.uid)
        return try {
            adminDoc.get().await().exists()
        } catch (_: Exception) {
            false
        }
    }

    fun observeAllUsersForAdmin(): Flow<List<UserProfile>> = flow {
        val uid = auth.currentUser?.uid ?: throw IllegalStateException("Not signed in")
        emitAll(
            db.collection("users")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(toLog, OperationType.LIST, "users")
                    }
                    throw error
                }
        )
    }

    fun observeReportsForAdmin(): Flow<List<AbuseReport>> = flow {
        val uid = auth.currentUser?.uid ?: throw IllegalStateException("Not signed in")
        emitAll(
            db.collection("reports")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(AbuseReport::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(toLog, OperationType.LIST, "reports")
                    }
                    throw error
                }
        )
    }

    suspend fun submitReport(
        reportedUserId: String,
        reportedUserName: String,
        reason: String,
        details: String
    ): Result<String> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        val reportId = "rep_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val docRef = db.collection("reports").document(reportId)
        return try {
            withTimeout(10_000L) {
                docRef.set(
                    mapOf(
                        "reporterId" to uid,
                        "reportedUserId" to reportedUserId,
                        "reportedUserName" to reportedUserName.take(60),
                        "reason" to reason.trim().ifBlank { "Abuse" }.take(120),
                        "details" to details.trim().take(1000),
                        "status" to "pending",
                        "resolutionNote" to "",
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(reportId)
        } catch (e: TimeoutCancellationException) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(IllegalStateException("Timed out submitting report.", e))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun setUserSuspended(targetUid: String, suspended: Boolean): Result<Unit> {
        val docRef = db.collection("users").document(targetUid)
        return try {
            docRef.set(
                mapOf(
                    "isSuspended" to suspended,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun resolveReport(reportId: String, status: String, resolutionNote: String): Result<Unit> {
        val docRef = db.collection("reports").document(reportId)
        return try {
            docRef.set(
                mapOf(
                    "status" to status,
                    "resolutionNote" to resolutionNote.trim().take(500),
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    private companion object {
        const val BOOTSTRAPPED_ADMIN_EMAIL = "nayemtisha4072@gmail.com"
    }
}
