package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.core.crypto.E2eeCryptoManager
import com.example.core.firebase.OperationType
import com.example.core.firebase.handleFirestoreError
import com.example.core.model.Chat
import com.example.core.model.ChatMessage
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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

class ChatRepository(
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

    fun observeChats(uid: String): Flow<List<Chat>> = flow {
        val query = db.collection("chats")
            .whereArrayContains("participantIds", uid)
        emitAll(
            query.snapshots()
                .map { snapshot ->
                    snapshot.toObjects(Chat::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedWith(
                            compareByDescending<Chat> { uid in it.pinnedBy }
                                .thenByDescending { it.lastMessageAt ?: it.updatedAt ?: it.createdAt ?: Timestamp(0, 0) }
                        )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(toLog, OperationType.LIST, "chats")
                    }
                    throw error
                }
        )
    }

    fun observeChat(chatId: String): Flow<Chat?> = flow {
        val docRef = db.collection("chats").document(chatId)
        emitAll(
            docRef.snapshots()
                .map { snap ->
                    if (!snap.exists()) null
                    else snap.toObject(Chat::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
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

    fun observeMessages(chatId: String, uid: String, limit: Long = 100L): Flow<List<ChatMessage>> = flow {
        val colPath = "chats/$chatId/messages"
        val query = db.collection("chats").document(chatId).collection("messages")
            .whereArrayContains("participantIds", uid)
            .limit(limit)
        emitAll(
            query.snapshots()
                .map { snapshot ->
                    snapshot.toObjects(ChatMessage::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .filter { uid !in it.deletedForUserIds }
                        .sortedBy { it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val toLog = firestoreError ?: (error as? Exception)
                    if (toLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(toLog, OperationType.LIST, colPath)
                    }
                    throw error
                }
        )
    }

    suspend fun getOrCreateDirectChat(peerUid: String): Result<String> {
        val uid = requireUserId()
        val sorted = listOf(uid, peerUid).sorted()
        val chatId = "direct_${sorted[0]}_${sorted[1]}"
        val chatRef = db.collection("chats").document(chatId)
        return try {
            val snap = chatRef.get().await()
            if (!snap.exists()) {
                val payload = mapOf(
                    "type" to "direct",
                    "title" to "",
                    "groupPhotoUrl" to "",
                    "participantIds" to sorted,
                    "adminIds" to sorted,
                    "lastMessageText" to "",
                    "lastMessageType" to "text",
                    "lastMessageSenderId" to "",
                    "pinnedBy" to emptyList<String>(),
                    "archivedBy" to emptyList<String>(),
                    "mutedBy" to emptyList<String>(),
                    "typingUserIds" to emptyList<String>(),
                    "unreadCounts" to mapOf(uid to 0L, peerUid to 0L),
                    "createdBy" to uid,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                chatRef.set(payload).await()
            }
            Result.success(chatId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, chatRef.path)
            Result.failure(e)
        }
    }

    suspend fun createGroupChat(title: String, memberUids: List<String>): Result<String> {
        val uid = requireUserId()
        val chatId = "grp_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val allMembers = (listOf(uid) + memberUids).distinct().take(100)
        val unreadMap = allMembers.associateWith { 0L }
        val chatRef = db.collection("chats").document(chatId)
        return try {
            val payload = mapOf(
                "type" to "group",
                "title" to title.trim().ifBlank { "Whatacall Group" }.take(80),
                "groupPhotoUrl" to "",
                "participantIds" to allMembers,
                "adminIds" to listOf(uid),
                "lastMessageText" to "Group created",
                "lastMessageType" to "text",
                "lastMessageSenderId" to uid,
                "lastMessageAt" to FieldValue.serverTimestamp(),
                "pinnedBy" to emptyList<String>(),
                "archivedBy" to emptyList<String>(),
                "mutedBy" to emptyList<String>(),
                "typingUserIds" to emptyList<String>(),
                "unreadCounts" to unreadMap,
                "createdBy" to uid,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            chatRef.set(payload).await()
            Result.success(chatId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, chatRef.path)
            Result.failure(e)
        }
    }

    suspend fun sendMessage(
        chat: Chat,
        senderName: String,
        type: String,
        plainText: String,
        mediaUrl: String = "",
        mediaName: String = "",
        mediaSizeBytes: Long = 0L,
        mediaDurationSec: Long = 0L,
        replyTo: ChatMessage? = null,
        isForwarded: Boolean = false,
        myPrivateKeyBase64: String? = null,
        peerPublicKeyBase64: String? = null
    ): Result<String> {
        val uid = requireUserId()
        val messageId = "msg_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val chatRef = db.collection("chats").document(chat.id)
        val msgRef = chatRef.collection("messages").document(messageId)

        var storedText = plainText.trim().take(4000)
        var encryptedPayload = ""
        var encryptionVersion = 0L

        if (chat.type == "direct" &&
            type == "text" &&
            storedText.isNotEmpty() &&
            !myPrivateKeyBase64.isNullOrBlank() &&
            !peerPublicKeyBase64.isNullOrBlank()
        ) {
            encryptedPayload = E2eeCryptoManager.encryptText(
                plaintext = storedText,
                myPrivateKeyBase64 = myPrivateKeyBase64,
                peerPublicKeyBase64 = peerPublicKeyBase64,
                chatId = chat.id
            )
            encryptionVersion = 1L
            storedText = "[Encrypted message]"
        }

        val previewText = when (type) {
            "image" -> "📷 Photo"
            "video" -> "🎥 Video"
            "audio" -> "🎤 Voice message (${mediaDurationSec}s)"
            "document" -> "📄 ${mediaName.ifBlank { "Document" }}"
            "call" -> plainText.ifBlank { "📞 Call" }
            else -> if (encryptionVersion == 1L) "🔒 Encrypted message" else storedText
        }.take(500)

        val updatedUnread = chat.participantIds.associateWith { participantUid ->
            if (participantUid == uid) 0L
            else (chat.unreadCounts[participantUid] ?: 0L) + 1L
        }

        return try {
            val msgPayload = mapOf(
                "chatId" to chat.id,
                "senderId" to uid,
                "senderName" to senderName.take(60),
                "type" to type,
                "text" to storedText,
                "encryptedPayload" to encryptedPayload,
                "encryptionVersion" to encryptionVersion,
                "mediaUrl" to mediaUrl.take(1024),
                "mediaName" to mediaName.take(255),
                "mediaSizeBytes" to mediaSizeBytes,
                "mediaDurationSec" to mediaDurationSec,
                "replyToMessageId" to (replyTo?.id ?: ""),
                "replyToPreview" to (replyTo?.text?.take(120) ?: ""),
                "replyToSenderName" to (replyTo?.senderName?.take(60) ?: ""),
                "isForwarded" to isForwarded,
                "isDeleted" to false,
                "deletedForUserIds" to emptyList<String>(),
                "deliveredTo" to listOf(uid),
                "readBy" to listOf(uid),
                "reactions" to emptyMap<String, String>(),
                "participantIds" to chat.participantIds,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            msgRef.set(msgPayload).await()

            chatRef.set(
                mapOf(
                    "lastMessageText" to previewText,
                    "lastMessageType" to type,
                    "lastMessageSenderId" to uid,
                    "lastMessageAt" to FieldValue.serverTimestamp(),
                    "unreadCounts" to updatedUnread,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()

            Result.success(messageId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, msgRef.path)
            Result.failure(e)
        }
    }

    suspend fun setTyping(chatId: String, isTyping: Boolean) {
        val uid = auth.currentUser?.uid ?: return
        val chatRef = db.collection("chats").document(chatId)
        try {
            val op = if (isTyping) FieldValue.arrayUnion(uid) else FieldValue.arrayRemove(uid)
            chatRef.set(
                mapOf(
                    "typingUserIds" to op,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, chatRef.path)
        }
    }

    suspend fun markChatRead(chat: Chat, messages: List<ChatMessage>) {
        val uid = auth.currentUser?.uid ?: return
        val chatRef = db.collection("chats").document(chat.id)
        try {
            if ((chat.unreadCounts[uid] ?: 0L) > 0L) {
                val updatedMap = chat.unreadCounts.toMutableMap()
                updatedMap[uid] = 0L
                chatRef.set(
                    mapOf(
                        "unreadCounts" to updatedMap,
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                ).await()
            }
            for (msg in messages) {
                if (msg.senderId != uid && uid !in msg.readBy) {
                    val msgRef = chatRef.collection("messages").document(msg.id)
                    msgRef.set(
                        mapOf(
                            "deliveredTo" to FieldValue.arrayUnion(uid),
                            "readBy" to FieldValue.arrayUnion(uid),
                            "updatedAt" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    ).await()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, chatRef.path)
        }
    }

    suspend fun reactToMessage(chatId: String, message: ChatMessage, emoji: String): Result<Unit> {
        val uid = requireUserId()
        val msgRef = db.collection("chats").document(chatId).collection("messages").document(message.id)
        val nextReactions = message.reactions.toMutableMap()
        if (nextReactions[uid] == emoji) nextReactions.remove(uid) else nextReactions[uid] = emoji
        return try {
            msgRef.set(
                mapOf(
                    "reactions" to nextReactions,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, msgRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteMessageForEveryone(chatId: String, messageId: String): Result<Unit> {
        requireUserId()
        val msgRef = db.collection("chats").document(chatId).collection("messages").document(messageId)
        return try {
            msgRef.set(
                mapOf(
                    "text" to "This message was deleted",
                    "encryptedPayload" to "",
                    "isDeleted" to true,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, msgRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteMessageForMe(chatId: String, messageId: String): Result<Unit> {
        val uid = requireUserId()
        val msgRef = db.collection("chats").document(chatId).collection("messages").document(messageId)
        return try {
            msgRef.set(
                mapOf(
                    "deletedForUserIds" to FieldValue.arrayUnion(uid),
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, msgRef.path)
            Result.failure(e)
        }
    }

    suspend fun toggleChatFlag(chatId: String, field: String, active: Boolean): Result<Unit> {
        val uid = requireUserId()
        val chatRef = db.collection("chats").document(chatId)
        return try {
            val op = if (active) FieldValue.arrayUnion(uid) else FieldValue.arrayRemove(uid)
            chatRef.set(
                mapOf(
                    field to op,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, chatRef.path)
            Result.failure(e)
        }
    }
}
