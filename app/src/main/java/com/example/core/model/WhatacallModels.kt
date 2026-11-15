package com.example.core.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val uid: String = "",
    val displayName: String = "",
    val username: String = "",
    val about: String = "Hey there! I am using Whatacall.",
    val photoUrl: String = "",
    val phoneHash: String = "",
    val phoneMasked: String = "",
    val publicKeyBase64: String = "",
    val isPhoneVerified: Boolean = false,
    val isOnline: Boolean = false,
    val lastSeenPrivacy: String = "everyone",
    val photoPrivacy: String = "everyone",
    val statusPrivacy: String = "everyone",
    val isSuspended: Boolean = false,
    @ServerTimestamp val lastSeenAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class UserPrivateInfo(
    @DocumentId val id: String = "",
    val uid: String = "",
    val phoneNumber: String = "",
    val email: String = "",
    val fcmToken: String = "",
    val chatWallpaper: String = "default",
    val darkModePreference: String = "system",
    val notificationsEnabled: Boolean = true,
    val callRingtoneEnabled: Boolean = true,
    val blockedUserIds: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class PhoneDirectoryEntry(
    @DocumentId val id: String = "",
    val uid: String = "",
    val phoneHash: String = "",
    val displayName: String = "",
    val photoUrl: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class Chat(
    @DocumentId val id: String = "",
    val type: String = "direct",
    val title: String = "",
    val groupPhotoUrl: String = "",
    val participantIds: List<String> = emptyList(),
    val adminIds: List<String> = emptyList(),
    val lastMessageText: String = "",
    val lastMessageType: String = "text",
    val lastMessageSenderId: String = "",
    @ServerTimestamp val lastMessageAt: Timestamp? = null,
    val pinnedBy: List<String> = emptyList(),
    val archivedBy: List<String> = emptyList(),
    val mutedBy: List<String> = emptyList(),
    val typingUserIds: List<String> = emptyList(),
    val unreadCounts: Map<String, Long> = emptyMap(),
    val createdBy: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class ChatMessage(
    @DocumentId val id: String = "",
    val chatId: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val type: String = "text",
    val text: String = "",
    val encryptedPayload: String = "",
    val encryptionVersion: Long = 0L,
    val mediaUrl: String = "",
    val mediaName: String = "",
    val mediaSizeBytes: Long = 0L,
    val mediaDurationSec: Long = 0L,
    val replyToMessageId: String = "",
    val replyToPreview: String = "",
    val replyToSenderName: String = "",
    val isForwarded: Boolean = false,
    val isDeleted: Boolean = false,
    val deletedForUserIds: List<String> = emptyList(),
    val deliveredTo: List<String> = emptyList(),
    val readBy: List<String> = emptyList(),
    val reactions: Map<String, String> = emptyMap(),
    val participantIds: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class CallSession(
    @DocumentId val id: String = "",
    val callerId: String = "",
    val callerName: String = "",
    val callerPhotoUrl: String = "",
    val calleeId: String = "",
    val calleeName: String = "",
    val calleePhotoUrl: String = "",
    val participantIds: List<String> = emptyList(),
    val callType: String = "audio",
    val status: String = "ringing",
    val offerSdp: String = "",
    val answerSdp: String = "",
    val durationSeconds: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class IceCandidateDoc(
    @DocumentId val id: String = "",
    val senderId: String = "",
    val sdpMid: String = "",
    val sdpMLineIndex: Long = 0L,
    val candidate: String = "",
    val participantIds: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class StatusUpdate(
    @DocumentId val id: String = "",
    val userId: String = "",
    val userName: String = "",
    val userPhotoUrl: String = "",
    val type: String = "text",
    val textContent: String = "",
    val backgroundColorHex: String = "#0AA83F",
    val mediaUrl: String = "",
    val privacy: String = "everyone",
    val allowedViewerIds: List<String> = emptyList(),
    val viewerIds: List<String> = emptyList(),
    val expiresAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class AbuseReport(
    @DocumentId val id: String = "",
    val reporterId: String = "",
    val reportedUserId: String = "",
    val reportedUserName: String = "",
    val reason: String = "",
    val details: String = "",
    val status: String = "pending",
    val resolutionNote: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

data class MatchedContact(
    val displayName: String,
    val phoneNumber: String,
    val phoneHash: String,
    val registeredProfile: UserProfile?
)

data class AdminDashboardStats(
    val totalUsers: Int = 0,
    val verifiedPhoneUsers: Int = 0,
    val suspendedUsers: Int = 0,
    val pendingReports: Int = 0,
    val totalReports: Int = 0
)
