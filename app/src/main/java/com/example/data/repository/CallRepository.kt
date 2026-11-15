package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.core.firebase.OperationType
import com.example.core.firebase.handleFirestoreError
import com.example.core.model.CallSession
import com.example.core.model.IceCandidateDoc
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

class CallRepository(
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

    fun observeCallHistory(uid: String): Flow<List<CallSession>> = flow {
        val query = db.collection("calls").whereArrayContains("participantIds", uid)
        emitAll(
            query.snapshots()
                .map { snapshot ->
                    snapshot.toObjects(CallSession::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(toLog, OperationType.LIST, "calls")
                    }
                    throw error
                }
        )
    }

    fun observeCall(callId: String): Flow<CallSession?> = flow {
        val docRef = db.collection("calls").document(callId)
        emitAll(
            docRef.snapshots()
                .map { snap ->
                    if (!snap.exists()) null
                    else snap.toObject(CallSession::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
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

    fun observeIceCandidates(callId: String, uid: String): Flow<List<IceCandidateDoc>> = flow {
        val path = "calls/$callId/iceCandidates"
        val query = db.collection("calls").document(callId)
            .collection("iceCandidates")
            .whereArrayContains("participantIds", uid)
        emitAll(
            query.snapshots()
                .map { snapshot ->
                    snapshot.toObjects(IceCandidateDoc::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .filter { it.senderId != uid }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(toLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun createCallSession(
        callerName: String,
        callerPhotoUrl: String,
        calleeId: String,
        calleeName: String,
        calleePhotoUrl: String,
        callType: String,
        offerSdp: String = ""
    ): Result<String> {
        val uid = requireUserId()
        val callId = "call_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val callRef = db.collection("calls").document(callId)
        return try {
            val payload = mapOf(
                "callerId" to uid,
                "callerName" to callerName.take(60),
                "callerPhotoUrl" to callerPhotoUrl.take(1024),
                "calleeId" to calleeId,
                "calleeName" to calleeName.take(60),
                "calleePhotoUrl" to calleePhotoUrl.take(1024),
                "participantIds" to listOf(uid, calleeId),
                "callType" to callType,
                "status" to "ringing",
                "offerSdp" to offerSdp.take(32768),
                "answerSdp" to "",
                "durationSeconds" to 0L,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            withTimeout(10_000L) {
                callRef.set(payload).await()
            }
            Result.success(callId)
        } catch (e: TimeoutCancellationException) {
            handleFirestoreError(e, OperationType.CREATE, callRef.path)
            Result.failure(IllegalStateException("Timed out starting call. Check your connection.", e))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, callRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateOfferSdp(callId: String, offerSdp: String): Result<Unit> {
        requireUserId()
        val callRef = db.collection("calls").document(callId)
        return try {
            callRef.set(
                mapOf(
                    "offerSdp" to offerSdp.take(32768),
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, callRef.path)
            Result.failure(e)
        }
    }

    suspend fun answerCall(callId: String, answerSdp: String): Result<Unit> {
        requireUserId()
        val callRef = db.collection("calls").document(callId)
        return try {
            callRef.set(
                mapOf(
                    "answerSdp" to answerSdp.take(32768),
                    "status" to "connected",
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, callRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateCallStatus(callId: String, status: String, durationSeconds: Long = 0L): Result<Unit> {
        requireUserId()
        val callRef = db.collection("calls").document(callId)
        return try {
            callRef.set(
                mapOf(
                    "status" to status,
                    "durationSeconds" to durationSeconds.coerceIn(0L, 86400L),
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, callRef.path)
            Result.failure(e)
        }
    }

    suspend fun addIceCandidate(
        callId: String,
        participantIds: List<String>,
        sdpMid: String,
        sdpMLineIndex: Int,
        candidate: String
    ): Result<Unit> {
        val uid = requireUserId()
        val candId = "ice_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val candRef = db.collection("calls").document(callId)
            .collection("iceCandidates").document(candId)
        return try {
            candRef.set(
                mapOf(
                    "senderId" to uid,
                    "sdpMid" to sdpMid.take(64),
                    "sdpMLineIndex" to sdpMLineIndex.toLong(),
                    "candidate" to candidate.take(4096),
                    "participantIds" to participantIds,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, candRef.path)
            Result.failure(e)
        }
    }
}
