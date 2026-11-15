package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.firebase.unwrapFirestoreMessage
import com.example.core.model.CallSession
import com.example.core.model.Chat
import com.example.core.model.MatchedContact
import com.example.core.model.StatusUpdate
import com.example.core.model.UiState
import com.example.core.model.UserPrivateInfo
import com.example.core.model.UserProfile
import com.example.data.repository.AdminRepository
import com.example.data.repository.CallRepository
import com.example.data.repository.ChatRepository
import com.example.data.repository.ContactsRepository
import com.example.data.repository.StatusRepository
import com.example.data.repository.StorageRepository
import com.example.data.repository.UserRepository
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class WhatacallMainViewModel(
    private val currentUserId: String,
    private val userRepository: UserRepository,
    private val chatRepository: ChatRepository,
    private val callRepository: CallRepository,
    private val statusRepository: StatusRepository,
    private val adminRepository: AdminRepository,
    private val contactsRepository: ContactsRepository,
    private val storageRepository: StorageRepository
) : ViewModel() {

    val userProfileState: StateFlow<UiState<UserProfile?>> = userRepository.observeUserProfile(currentUserId)
        .map<UserProfile?, UiState<UserProfile?>> { UiState.Success(it) }
        .catch { e -> emit(UiState.Error(unwrapFirestoreMessage(e, "Failed to load profile"))) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    val privateInfoState: StateFlow<UserPrivateInfo?> = userRepository.observePrivateInfo(currentUserId)
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val chatsState: StateFlow<UiState<List<Chat>>> = chatRepository.observeChats(currentUserId)
        .map<List<Chat>, UiState<List<Chat>>> { chats ->
            prefetchPeers(chats.flatMap { it.participantIds })
            UiState.Success(chats)
        }
        .catch { e -> emit(UiState.Error(unwrapFirestoreMessage(e, "Failed to load chats"))) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    val callsState: StateFlow<UiState<List<CallSession>>> = callRepository.observeCallHistory(currentUserId)
        .map<List<CallSession>, UiState<List<CallSession>>> { calls ->
            prefetchPeers(calls.flatMap { it.participantIds })
            UiState.Success(calls)
        }
        .catch { e -> emit(UiState.Error(unwrapFirestoreMessage(e, "Failed to load call history"))) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    val incomingRingingCall: StateFlow<CallSession?> = callsState
        .map { state ->
            (state as? UiState.Success)?.data?.firstOrNull {
                it.calleeId == currentUserId && it.status == "ringing"
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val statusesState: StateFlow<UiState<List<StatusUpdate>>> = statusRepository.observeActiveStatuses(currentUserId)
        .map<List<StatusUpdate>, UiState<List<StatusUpdate>>> { UiState.Success(it) }
        .catch { e -> emit(UiState.Error(unwrapFirestoreMessage(e, "Failed to load updates"))) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    private val _peerProfiles = MutableStateFlow<Map<String, UserProfile>>(emptyMap())
    val peerProfiles: StateFlow<Map<String, UserProfile>> = _peerProfiles.asStateFlow()

    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()

    private val _matchedContacts = MutableStateFlow<List<MatchedContact>>(emptyList())
    val matchedContacts: StateFlow<List<MatchedContact>> = _matchedContacts.asStateFlow()

    private val _contactsLoading = MutableStateFlow(false)
    val contactsLoading: StateFlow<Boolean> = _contactsLoading.asStateFlow()

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    private val _profileSetupStep = MutableStateFlow<String?>(null)
    val profileSetupStep: StateFlow<String?> = _profileSetupStep.asStateFlow()

    init {
        viewModelScope.launch {
            _isAdmin.value = withTimeoutOrNull(6_000L) { adminRepository.isCurrentUserAdmin() } ?: false
            try {
                val token = withTimeoutOrNull(6_000L) {
                    FirebaseMessaging.getInstance().token.await()
                }
                if (!token.isNullOrBlank()) {
                    userRepository.updateFcmToken(token)
                }
            } catch (e: Exception) {
                Log.d("WhatacallMainVM", "FCM token unavailable: ${e.message}")
            }
        }
    }

    fun clearActionMessage() {
        _actionMessage.value = null
    }

    fun setPresence(isOnline: Boolean) {
        viewModelScope.launch {
            userRepository.updatePresence(isOnline)
        }
    }

    fun prefetchPeers(uids: List<String>) {
        val missing = uids.distinct().filter { it != currentUserId && !_peerProfiles.value.containsKey(it) }
        if (missing.isEmpty()) return
        viewModelScope.launch {
            val loaded = mutableMapOf<String, UserProfile>()
            for (uid in missing) {
                userRepository.getUserProfile(uid)?.let { loaded[uid] = it }
            }
            if (loaded.isNotEmpty()) {
                _peerProfiles.value = _peerProfiles.value + loaded
            }
        }
    }

    fun completeProfileSetup(
        context: Context,
        displayName: String,
        about: String,
        username: String,
        photoUri: Uri?,
        verifiedPhone: String,
        email: String,
        onComplete: (Boolean, String?) -> Unit
    ) {
        val appContext = context.applicationContext
        viewModelScope.launch {
            var success = false
            var errorMessage: String? = null
            try {
                _profileSetupStep.value = "1/2 Checking phone & @username availability..."

                val res = withContext(Dispatchers.IO) {
                    userRepository.registerOrUpdatePhoneAndProfile(
                        displayName = displayName,
                        about = about,
                        username = username,
                        photoUrl = "",
                        verifiedE164Phone = verifiedPhone,
                        publicKeyBase64 = "",
                        email = email,
                        onStage = { step -> _profileSetupStep.value = step }
                    )
                }
                success = res.isSuccess
                errorMessage = res.exceptionOrNull()?.localizedMessage ?: res.exceptionOrNull()?.message

                if (success && photoUri != null) {
                    viewModelScope.launch(Dispatchers.IO) {
                        val ext = storageRepository.inferExtension(appContext, photoUri, "jpg")
                        val upload = storageRepository.uploadMedia(
                            uid = currentUserId,
                            folder = "avatars",
                            uri = photoUri,
                            extension = ext,
                            timeoutMs = 5_000L
                        )
                        if (upload.isSuccess) {
                            userRepository.updateProfileFields(
                                displayName = displayName,
                                about = about,
                                photoUrl = upload.getOrThrow()
                            )
                        } else {
                            Log.w("WhatacallMainVM", "Optional avatar upload skipped: ${upload.exceptionOrNull()?.message}")
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                errorMessage = "Request timed out. Please check your internet connection and try again."
            } catch (e: CancellationException) {
                errorMessage = "Profile setup was interrupted. Please tap Start again."
                throw e
            } catch (e: Throwable) {
                errorMessage = e.localizedMessage ?: e.message ?: "Could not save profile."
            } finally {
                _profileSetupStep.value = null
                withContext(NonCancellable) {
                    onComplete(success, errorMessage)
                }
            }
        }
    }

    fun updateProfile(context: Context, displayName: String, about: String, newPhotoUri: Uri?) {
        viewModelScope.launch {
            var photoUrl: String? = null
            if (newPhotoUri != null) {
                val ext = storageRepository.inferExtension(context, newPhotoUri, "jpg")
                val upload = storageRepository.uploadMedia(currentUserId, "avatars", newPhotoUri, ext)
                if (upload.isSuccess) {
                    photoUrl = upload.getOrThrow()
                } else {
                    _actionMessage.value = upload.exceptionOrNull()?.message
                    return@launch
                }
            }
            val res = userRepository.updateProfileFields(displayName, about, photoUrl)
            _actionMessage.value = if (res.isSuccess) "Profile updated" else res.exceptionOrNull()?.localizedMessage
        }
    }

    fun updatePrivacy(lastSeen: String, photo: String, status: String) {
        viewModelScope.launch {
            val res = userRepository.updatePrivacySettings(lastSeen, photo, status)
            if (res.isFailure) {
                _actionMessage.value = res.exceptionOrNull()?.localizedMessage
            }
        }
    }

    fun toggleBlockUser(targetUid: String, block: Boolean) {
        viewModelScope.launch {
            val res = userRepository.toggleBlockUser(targetUid, block)
            _actionMessage.value = if (res.isSuccess) {
                if (block) "Contact blocked" else "Contact unblocked"
            } else {
                res.exceptionOrNull()?.localizedMessage
            }
        }
    }

    fun reportUser(reportedUid: String, reportedName: String, reason: String, details: String) {
        viewModelScope.launch {
            val res = adminRepository.submitReport(reportedUid, reportedName, reason, details)
            _actionMessage.value = if (res.isSuccess) {
                "Report submitted to Whatacall safety team."
            } else {
                res.exceptionOrNull()?.localizedMessage
            }
        }
    }

    fun loadContacts(context: Context) {
        viewModelScope.launch {
            _contactsLoading.value = true
            try {
                val matched = contactsRepository.loadAndMatchContacts(context)
                _matchedContacts.value = matched
                matched.mapNotNull { it.registeredProfile }.forEach { profile ->
                    _peerProfiles.value = _peerProfiles.value + (profile.uid to profile)
                }
            } catch (e: Exception) {
                _actionMessage.value = e.localizedMessage ?: "Failed to read contacts"
            } finally {
                _contactsLoading.value = false
            }
        }
    }

    fun lookupUserAndStartChat(query: String, onChatReady: (String) -> Unit) {
        viewModelScope.launch {
            val trimmed = query.trim()
            val found = if (trimmed.startsWith("+") || trimmed.all { it.isDigit() || it == ' ' || it == '-' }) {
                val digits = trimmed.replace(Regex("[^0-9+]"), "")
                val normalized = if (digits.startsWith("+")) digits else "+880${digits.trimStart('0')}"
                userRepository.findUserByPhoneNumber(normalized)
            } else {
                userRepository.findUserByUsername(trimmed)
            }
            if (found == null) {
                _actionMessage.value = "No registered Whatacall user found for \"$trimmed\"."
                return@launch
            }
            if (found.uid == currentUserId) {
                _actionMessage.value = "You cannot start a chat with yourself."
                return@launch
            }
            _peerProfiles.value = _peerProfiles.value + (found.uid to found)
            val res = chatRepository.getOrCreateDirectChat(found.uid)
            if (res.isSuccess) {
                onChatReady(res.getOrThrow())
            } else {
                _actionMessage.value = res.exceptionOrNull()?.localizedMessage
            }
        }
    }

    fun openDirectChat(peerProfile: UserProfile, onChatReady: (String) -> Unit) {
        viewModelScope.launch {
            _peerProfiles.value = _peerProfiles.value + (peerProfile.uid to peerProfile)
            val res = chatRepository.getOrCreateDirectChat(peerProfile.uid)
            if (res.isSuccess) {
                onChatReady(res.getOrThrow())
            } else {
                _actionMessage.value = res.exceptionOrNull()?.localizedMessage
            }
        }
    }

    fun createGroupChat(title: String, memberUids: List<String>, onChatReady: (String) -> Unit) {
        viewModelScope.launch {
            val res = chatRepository.createGroupChat(title, memberUids)
            if (res.isSuccess) {
                onChatReady(res.getOrThrow())
            } else {
                _actionMessage.value = res.exceptionOrNull()?.localizedMessage
            }
        }
    }

    fun toggleChatPin(chat: Chat) {
        val active = currentUserId !in chat.pinnedBy
        viewModelScope.launch { chatRepository.toggleChatFlag(chat.id, "pinnedBy", active) }
    }

    fun toggleChatArchive(chat: Chat) {
        val active = currentUserId !in chat.archivedBy
        viewModelScope.launch { chatRepository.toggleChatFlag(chat.id, "archivedBy", active) }
    }

    fun toggleChatMute(chat: Chat) {
        val active = currentUserId !in chat.mutedBy
        viewModelScope.launch { chatRepository.toggleChatFlag(chat.id, "mutedBy", active) }
    }

    fun postTextStatus(text: String, bgHex: String, privacy: String) {
        val profile = (userProfileState.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            val res = statusRepository.postStatus(
                userName = profile.displayName,
                userPhotoUrl = profile.photoUrl,
                type = "text",
                textContent = text,
                backgroundColorHex = bgHex,
                privacy = privacy
            )
            if (res.isFailure) _actionMessage.value = res.exceptionOrNull()?.localizedMessage
        }
    }

    fun postMediaStatus(context: Context, uri: Uri, isVideo: Boolean, caption: String, privacy: String) {
        val profile = (userProfileState.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            val ext = storageRepository.inferExtension(context, uri, if (isVideo) "mp4" else "jpg")
            val upload = storageRepository.uploadMedia(currentUserId, "statuses", uri, ext)
            if (upload.isFailure) {
                _actionMessage.value = upload.exceptionOrNull()?.message
                return@launch
            }
            val res = statusRepository.postStatus(
                userName = profile.displayName,
                userPhotoUrl = profile.photoUrl,
                type = if (isVideo) "video" else "image",
                textContent = caption,
                mediaUrl = upload.getOrThrow(),
                privacy = privacy
            )
            if (res.isFailure) _actionMessage.value = res.exceptionOrNull()?.localizedMessage
        }
    }

    fun markStatusViewed(statusId: String) {
        viewModelScope.launch { statusRepository.markStatusViewed(statusId) }
    }

    fun deleteStatus(statusId: String) {
        viewModelScope.launch { statusRepository.deleteStatus(statusId) }
    }

    fun startOutgoingCall(peer: UserProfile, callType: String, onCallCreated: (String) -> Unit) {
        val me = (userProfileState.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            val res = callRepository.createCallSession(
                callerName = me.displayName,
                callerPhotoUrl = me.photoUrl,
                calleeId = peer.uid,
                calleeName = peer.displayName,
                calleePhotoUrl = peer.photoUrl,
                callType = callType
            )
            if (res.isSuccess) {
                onCallCreated(res.getOrThrow())
            } else {
                _actionMessage.value = res.exceptionOrNull()?.localizedMessage
            }
        }
    }

    fun rejectIncomingCall(callId: String) {
        viewModelScope.launch {
            callRepository.updateCallStatus(callId, "rejected")
        }
    }

    fun deleteAccount(onDeleted: () -> Unit) {
        viewModelScope.launch {
            val res = userRepository.deleteAccountData()
            if (res.isSuccess) {
                onDeleted()
            } else {
                _actionMessage.value = res.exceptionOrNull()?.localizedMessage
            }
        }
    }
}
