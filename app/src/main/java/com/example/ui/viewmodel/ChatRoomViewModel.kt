package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.crypto.E2eeCryptoManager
import com.example.core.firebase.unwrapFirestoreMessage
import com.example.core.model.Chat
import com.example.core.model.ChatMessage
import com.example.core.model.UiState
import com.example.core.model.UserProfile
import com.example.data.repository.ChatRepository
import com.example.data.repository.StorageRepository
import com.example.data.repository.UserRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatRoomViewModel(
    private val chatId: String,
    private val currentUserId: String,
    private val myPrivateKeyBase64: String,
    private val chatRepository: ChatRepository,
    private val userRepository: UserRepository,
    private val storageRepository: StorageRepository
) : ViewModel() {

    val chatState: StateFlow<Chat?> = chatRepository.observeChat(chatId)
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val messagesState: StateFlow<UiState<List<ChatMessage>>> = chatRepository.observeMessages(chatId, currentUserId)
        .map<List<ChatMessage>, UiState<List<ChatMessage>>> { UiState.Success(it) }
        .catch { e -> emit(UiState.Error(unwrapFirestoreMessage(e, "Failed to load messages"))) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    private val _peerProfile = MutableStateFlow<UserProfile?>(null)
    val peerProfile: StateFlow<UserProfile?> = _peerProfile.asStateFlow()

    private val _errorBanner = MutableStateFlow<String?>(null)
    val errorBanner: StateFlow<String?> = _errorBanner.asStateFlow()

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading.asStateFlow()

    val decryptedTexts: StateFlow<Map<String, String>> = combine(
        messagesState,
        peerProfile
    ) { msgState, peer ->
        val msgs = (msgState as? UiState.Success)?.data ?: return@combine emptyMap()
        val peerKey = peer?.publicKeyBase64
        msgs.associate { msg ->
            val resolved = if (msg.isDeleted) {
                "This message was deleted"
            } else if (msg.encryptionVersion == 1L && msg.encryptedPayload.isNotBlank() && !peerKey.isNullOrBlank()) {
                E2eeCryptoManager.decryptText(
                    encryptedBase64 = msg.encryptedPayload,
                    myPrivateKeyBase64 = myPrivateKeyBase64,
                    peerPublicKeyBase64 = peerKey,
                    chatId = chatId
                ) ?: msg.text
            } else {
                msg.text
            }
            msg.id to resolved
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyMap())

    private var typingJob: Job? = null

    init {
        viewModelScope.launch {
            chatState.collect { chat ->
                if (chat != null && chat.type == "direct") {
                    val peerUid = chat.participantIds.firstOrNull { it != currentUserId }
                    if (peerUid != null && _peerProfile.value?.uid != peerUid) {
                        _peerProfile.value = userRepository.getUserProfile(peerUid)
                    }
                }
            }
        }
    }

    fun clearError() {
        _errorBanner.value = null
    }

    fun markMessagesRead() {
        val chat = chatState.value ?: return
        val msgs = (messagesState.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            chatRepository.markChatRead(chat, msgs)
        }
    }

    fun onTypingChanged(text: String) {
        typingJob?.cancel()
        if (text.isBlank()) {
            viewModelScope.launch { chatRepository.setTyping(chatId, false) }
            return
        }
        typingJob = viewModelScope.launch {
            chatRepository.setTyping(chatId, true)
            delay(2500L)
            chatRepository.setTyping(chatId, false)
        }
    }

    fun sendText(senderName: String, text: String, replyTo: ChatMessage?) {
        val chat = chatState.value ?: return
        if (text.isBlank()) return
        viewModelScope.launch {
            chatRepository.setTyping(chatId, false)
            val res = chatRepository.sendMessage(
                chat = chat,
                senderName = senderName,
                type = "text",
                plainText = text,
                replyTo = replyTo,
                myPrivateKeyBase64 = myPrivateKeyBase64,
                peerPublicKeyBase64 = _peerProfile.value?.publicKeyBase64
            )
            if (res.isFailure) {
                _errorBanner.value = res.exceptionOrNull()?.localizedMessage
            }
        }
    }

    fun sendMedia(
        context: Context,
        senderName: String,
        uri: Uri,
        type: String,
        fileName: String = "",
        durationSec: Long = 0L,
        replyTo: ChatMessage? = null
    ) {
        val chat = chatState.value ?: return
        viewModelScope.launch {
            _isUploading.value = true
            try {
                val fallbackExt = when (type) {
                    "video" -> "mp4"
                    "audio" -> "m4a"
                    "document" -> "pdf"
                    else -> "jpg"
                }
                val ext = storageRepository.inferExtension(context, uri, fallbackExt)
                val uploadRes = storageRepository.uploadMedia(currentUserId, "chats/$chatId", uri, ext)
                if (uploadRes.isFailure) {
                    _errorBanner.value = uploadRes.exceptionOrNull()?.message
                    return@launch
                }
                val sendRes = chatRepository.sendMessage(
                    chat = chat,
                    senderName = senderName,
                    type = type,
                    plainText = if (type == "document") fileName else "",
                    mediaUrl = uploadRes.getOrThrow(),
                    mediaName = fileName,
                    mediaDurationSec = durationSec,
                    replyTo = replyTo
                )
                if (sendRes.isFailure) {
                    _errorBanner.value = sendRes.exceptionOrNull()?.localizedMessage
                }
            } finally {
                _isUploading.value = false
            }
        }
    }

    fun forwardMessageToChat(targetChat: Chat, senderName: String, plainText: String, original: ChatMessage) {
        viewModelScope.launch {
            val res = chatRepository.sendMessage(
                chat = targetChat,
                senderName = senderName,
                type = original.type,
                plainText = plainText,
                mediaUrl = original.mediaUrl,
                mediaName = original.mediaName,
                mediaDurationSec = original.mediaDurationSec,
                isForwarded = true
            )
            if (res.isFailure) {
                _errorBanner.value = res.exceptionOrNull()?.localizedMessage
            }
        }
    }

    fun react(message: ChatMessage, emoji: String) {
        viewModelScope.launch {
            chatRepository.reactToMessage(chatId, message, emoji)
        }
    }

    fun deleteForEveryone(messageId: String) {
        viewModelScope.launch {
            chatRepository.deleteMessageForEveryone(chatId, messageId)
        }
    }

    fun deleteForMe(messageId: String) {
        viewModelScope.launch {
            chatRepository.deleteMessageForMe(chatId, messageId)
        }
    }
}
