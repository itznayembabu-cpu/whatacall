package com.example.ui.chats

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Forward
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.core.crypto.E2eeCryptoManager
import com.example.core.model.Chat
import com.example.core.model.ChatMessage
import com.example.core.model.UiState
import com.example.core.model.UserProfile
import com.example.ui.components.EncryptionNoticeChip
import com.example.ui.components.UserAvatar
import com.example.ui.components.formatCallDuration
import com.example.ui.components.formatMessageTime
import com.example.ui.components.formatRelativeDateTime
import com.example.ui.theme.IncomingBubbleDark
import com.example.ui.theme.IncomingBubbleLight
import com.example.ui.theme.OutgoingBubbleDark
import com.example.ui.theme.OutgoingBubbleLight
import com.example.ui.theme.ReadReceiptBlue
import com.example.ui.viewmodel.ChatRoomViewModel
import com.example.ui.viewmodel.WhatacallMainViewModel
import kotlinx.coroutines.delay
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatRoomScreen(
    chatId: String,
    currentUserId: String,
    currentUserName: String,
    wallpaperPreset: String,
    allChats: List<Chat>,
    peerProfiles: Map<String, UserProfile>,
    blockedUserIds: List<String>,
    chatViewModel: ChatRoomViewModel,
    mainViewModel: WhatacallMainViewModel,
    onBack: () -> Unit,
    onStartCall: (peer: UserProfile, callType: String) -> Unit
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val chat by chatViewModel.chatState.collectAsStateWithLifecycle()
    val messagesState by chatViewModel.messagesState.collectAsStateWithLifecycle()
    val decryptedTexts by chatViewModel.decryptedTexts.collectAsStateWithLifecycle()
    val peerProfile by chatViewModel.peerProfile.collectAsStateWithLifecycle()
    val isUploading by chatViewModel.isUploading.collectAsStateWithLifecycle()
    val errorBanner by chatViewModel.errorBanner.collectAsStateWithLifecycle()

    var composerText by remember { mutableStateOf("") }
    var replyingTo by remember { mutableStateOf<ChatMessage?>(null) }
    var selectedMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var forwardingMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var showAttachSheet by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var showE2eeDialog by remember { mutableStateOf(false) }
    var reportReason by remember { mutableStateOf("Spam or Harassment") }
    var reportDetails by remember { mutableStateOf("") }

    // Voice note recording
    var isRecordingAudio by remember { mutableStateOf(false) }
    var recordingSec by remember { mutableLongStateOf(0L) }
    var mediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var audioFile by remember { mutableStateOf<File?>(null) }

    val listState = rememberLazyListState()

    val mediaPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri) ?: ""
            val type = if (mime.startsWith("video")) "video" else "image"
            chatViewModel.sendMedia(context, currentUserName, uri, type, replyTo = replyingTo)
            replyingTo = null
        }
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "Document"
            chatViewModel.sendMedia(context, currentUserName, uri, "document", fileName = name, replyTo = replyingTo)
            replyingTo = null
        }
    }

    fun startRecording() {
        try {
            val out = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setOutputFile(out.absolutePath)
            recorder.prepare()
            recorder.start()
            mediaRecorder = recorder
            audioFile = out
            recordingSec = 0L
            isRecordingAudio = true
        } catch (e: Exception) {
            Toast.makeText(context, "Mic recording error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    fun stopRecordingAndSend(send: Boolean) {
        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {
        }
        mediaRecorder = null
        val duration = recordingSec.coerceAtLeast(1L)
        val file = audioFile
        isRecordingAudio = false
        recordingSec = 0L
        if (send && file != null && file.exists()) {
            chatViewModel.sendMedia(
                context = context,
                senderName = currentUserName,
                uri = Uri.fromFile(file),
                type = "audio",
                fileName = file.name,
                durationSec = duration,
                replyTo = replyingTo
            )
            replyingTo = null
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording()
        else Toast.makeText(context, "Microphone permission required for voice messages", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(isRecordingAudio) {
        while (isRecordingAudio) {
            delay(1000L)
            recordingSec += 1L
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try { mediaRecorder?.release() } catch (_: Exception) {}
        }
    }

    LaunchedEffect(messagesState) {
        val data = (messagesState as? UiState.Success)?.data
        if (!data.isNullOrEmpty()) {
            chatViewModel.markMessagesRead()
            listState.animateScrollToItem(data.lastIndex)
        }
    }

    val title = chat?.let { resolveChatTitle(it, currentUserId, peerProfiles) }
        ?: peerProfile?.displayName ?: "Chat"
    val isPeerBlocked = peerProfile != null && peerProfile!!.uid in blockedUserIds
    val peerTyping = chat?.typingUserIds?.any { it != currentUserId } == true

    val subtitleText = when {
        peerTyping -> "typing..."
        chat?.type == "group" -> "${chat?.participantIds?.size ?: 0} members"
        peerProfile?.isOnline == true -> "online"
        peerProfile?.lastSeenPrivacy == "nobody" -> "Whatacall E2EE"
        peerProfile?.lastSeenAt != null -> "last seen ${formatRelativeDateTime(peerProfile?.lastSeenAt)}"
        else -> "tap for info"
    }

    val isDark = isSystemInDarkTheme()
    val wallpaperColors = when (wallpaperPreset) {
        "emerald" -> if (isDark) listOf(Color(0xFF0A1F17), Color(0xFF0B141A)) else listOf(Color(0xFFE6F7EC), Color(0xFFF4FBF6))
        "sand" -> if (isDark) listOf(Color(0xFF1F1A14), Color(0xFF121212)) else listOf(Color(0xFFF6EFE2), Color(0xFFFAF6EE))
        "midnight" -> listOf(Color(0xFF0B141A), Color(0xFF0E1B24))
        else -> if (isDark) listOf(Color(0xFF0B141A), Color(0xFF0E181F)) else listOf(Color(0xFFEFEAE2), Color(0xFFF4F1EA))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { showE2eeDialog = true }
                    ) {
                        UserAvatar(
                            name = title,
                            photoUrl = peerProfile?.photoUrl ?: chat?.groupPhotoUrl ?: "",
                            size = 38.dp,
                            isOnline = peerProfile?.isOnline == true,
                            showOnlineBadge = chat?.type == "direct"
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = subtitleText,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (peerTyping) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    if (peerProfile != null) {
                        IconButton(
                            onClick = { onStartCall(peerProfile!!, "video") },
                            modifier = Modifier.testTag("start_video_call_button")
                        ) {
                            Icon(Icons.Rounded.Videocam, contentDescription = "Video Call")
                        }
                        IconButton(
                            onClick = { onStartCall(peerProfile!!, "audio") },
                            modifier = Modifier.testTag("start_audio_call_button")
                        ) {
                            Icon(Icons.Rounded.Call, contentDescription = "Voice Call")
                        }
                    }
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Encryption Info") },
                                onClick = {
                                    showOverflowMenu = false
                                    showE2eeDialog = true
                                }
                            )
                            if (peerProfile != null) {
                                DropdownMenuItem(
                                    text = { Text(if (isPeerBlocked) "Unblock Contact" else "Block Contact") },
                                    onClick = {
                                        showOverflowMenu = false
                                        mainViewModel.toggleBlockUser(peerProfile!!.uid, !isPeerBlocked)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Report Account") },
                                    onClick = {
                                        showOverflowMenu = false
                                        showReportDialog = true
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .navigationBarsPadding()
                .background(Brush.verticalGradient(wallpaperColors))
        ) {
            if (isUploading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (errorBanner != null) {
                Surface(
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = errorBanner!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { chatViewModel.clearError() }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                when (val state = messagesState) {
                    is UiState.Loading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    is UiState.Error -> {
                        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text(state.message, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    is UiState.Success -> {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    EncryptionNoticeChip(
                                        if (chat?.type == "direct" && !peerProfile?.publicKeyBase64.isNullOrBlank()) {
                                            "Messages are E2EE with ECDH P-256 + AES-256-GCM"
                                        } else {
                                            "Protected by Whatacall TLS & Firestore Security Rules"
                                        }
                                    )
                                }
                            }
                            items(state.data, key = { it.id }) { msg ->
                                val isMine = msg.senderId == currentUserId
                                val resolvedText = decryptedTexts[msg.id] ?: msg.text
                                MessageBubble(
                                    message = msg,
                                    displayText = resolvedText,
                                    isMine = isMine,
                                    isGroup = chat?.type == "group",
                                    participantCount = chat?.participantIds?.size ?: 2,
                                    onLongClick = { selectedMessage = msg },
                                    onOpenMedia = { url ->
                                        try {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                        } catch (_: Exception) {
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (replyingTo != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .height(36.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = replyingTo!!.senderName.ifBlank { "Message" },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = decryptedTexts[replyingTo!!.id] ?: replyingTo!!.text,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { replyingTo = null }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Cancel reply")
                        }
                    }
                }
            }

            if (isPeerBlocked) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "You blocked this contact.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { mainViewModel.toggleBlockUser(peerProfile!!.uid, false) }) {
                            Text("Unblock")
                        }
                    }
                }
            } else if (isRecordingAudio) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Mic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Recording voice note • ${formatCallDuration(recordingSec)}",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { stopRecordingAndSend(false) }) {
                            Text("Cancel")
                        }
                        IconButton(
                            onClick = { stopRecordingAndSend(true) },
                            modifier = Modifier
                                .size(48.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send voice note", tint = Color.White)
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Surface(
                        shape = RoundedCornerShape(26.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 2.dp,
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp)
                        ) {
                            IconButton(
                                onClick = { showAttachSheet = true },
                                modifier = Modifier.testTag("chat_attach_button")
                            ) {
                                Icon(Icons.Rounded.AttachFile, contentDescription = "Attach media")
                            }
                            OutlinedTextField(
                                value = composerText,
                                onValueChange = {
                                    composerText = it
                                    chatViewModel.onTypingChanged(it)
                                },
                                placeholder = { Text("Message") },
                                maxLines = 4,
                                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("chat_message_input")
                            )
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    ) {
                        if (composerText.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    val msg = composerText
                                    composerText = ""
                                    chatViewModel.sendText(currentUserName, msg, replyingTo)
                                    replyingTo = null
                                },
                                modifier = Modifier.testTag("send_message_button")
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.Send,
                                    contentDescription = "Send message",
                                    tint = Color.White
                                )
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                        startRecording()
                                    } else {
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                modifier = Modifier.testTag("record_voice_button")
                            ) {
                                Icon(
                                    Icons.Rounded.Mic,
                                    contentDescription = "Record voice message",
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Attachment Picker Sheet
    if (showAttachSheet) {
        ModalBottomSheet(onDismissRequest = { showAttachSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Share Media & Files", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showAttachSheet = false
                            mediaPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                            )
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Text("Photo or Video (Android Photo Picker)", style = MaterialTheme.typography.bodyLarge)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showAttachSheet = false
                            documentPickerLauncher.launch(arrayOf("*/*"))
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.AutoMirrored.Rounded.InsertDriveFile, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(14.dp))
                    Text("Document (PDF, Docs, Files)", style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // Long-press Message Action Sheet
    if (selectedMessage != null) {
        val msg = selectedMessage!!
        val textVal = decryptedTexts[msg.id] ?: msg.text
        ModalBottomSheet(onDismissRequest = { selectedMessage = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Quick Emoji Reactions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    listOf("👍", "❤️", "😂", "😮", "😢", "🙏").forEach { emoji ->
                        TextButton(onClick = {
                            chatViewModel.react(msg, emoji)
                            selectedMessage = null
                        }) {
                            Text(emoji, style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            replyingTo = msg
                            selectedMessage = null
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Reply, contentDescription = null)
                    Spacer(Modifier.width(14.dp))
                    Text("Reply")
                }
                if (textVal.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Whatacall Message", textVal))
                                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                                selectedMessage = null
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                        Spacer(Modifier.width(14.dp))
                        Text("Copy")
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            forwardingMessage = msg
                            selectedMessage = null
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Forward, contentDescription = null)
                    Spacer(Modifier.width(14.dp))
                    Text("Forward")
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            chatViewModel.deleteForMe(msg.id)
                            selectedMessage = null
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Delete, contentDescription = null)
                    Spacer(Modifier.width(14.dp))
                    Text("Delete for me")
                }
                if (msg.senderId == currentUserId && !msg.isDeleted) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                chatViewModel.deleteForEveryone(msg.id)
                                selectedMessage = null
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(14.dp))
                        Text("Delete for everyone", color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
        }
    }

    // Forward Message Dialog
    if (forwardingMessage != null) {
        val msg = forwardingMessage!!
        AlertDialog(
            onDismissRequest = { forwardingMessage = null },
            title = { Text("Forward to...") },
            text = {
                LazyColumn(modifier = Modifier.height(260.dp)) {
                    items(allChats.filter { it.id != chatId }, key = { it.id }) { target ->
                        val targetName = resolveChatTitle(target, currentUserId, peerProfiles)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    chatViewModel.forwardMessageToChat(
                                        targetChat = target,
                                        senderName = currentUserName,
                                        plainText = decryptedTexts[msg.id] ?: msg.text,
                                        original = msg
                                    )
                                    Toast.makeText(context, "Forwarded to $targetName", Toast.LENGTH_SHORT).show()
                                    forwardingMessage = null
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            UserAvatar(name = targetName, photoUrl = "", size = 36.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(targetName, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { forwardingMessage = null }) { Text("Cancel") }
            }
        )
    }

    // Report User Dialog
    if (showReportDialog && peerProfile != null) {
        AlertDialog(
            onDismissRequest = { showReportDialog = false },
            title = { Text("Report ${peerProfile!!.displayName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = reportReason,
                        onValueChange = { reportReason = it },
                        label = { Text("Reason") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = reportDetails,
                        onValueChange = { reportDetails = it },
                        label = { Text("Details (optional)") },
                        maxLines = 3
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    mainViewModel.reportUser(peerProfile!!.uid, peerProfile!!.displayName, reportReason, reportDetails)
                    showReportDialog = false
                }) {
                    Text("Submit Report")
                }
            },
            dismissButton = {
                TextButton(onClick = { showReportDialog = false }) { Text("Cancel") }
            }
        )
    }

    // E2EE Security Fingerprint Dialog
    if (showE2eeDialog) {
        val peerKey = peerProfile?.publicKeyBase64 ?: ""
        val fingerprint = if (peerKey.isNotBlank()) {
            E2eeCryptoManager.sha256Hex(peerKey).take(32).chunked(4).joinToString(" ")
        } else {
            "Peer has not published an ECDH identity key yet."
        }
        AlertDialog(
            onDismissRequest = { showE2eeDialog = false },
            title = { Text("Encryption Security") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Direct 1-on-1 text messages use ECDH (P-256) key agreement + HKDF-SHA256 + AES-256-GCM authenticated encryption. Voice and video calls use WebRTC DTLS-SRTP.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Peer Key Fingerprint:",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        text = fingerprint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showE2eeDialog = false }) { Text("OK") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ChatMessage,
    displayText: String,
    isMine: Boolean,
    isGroup: Boolean,
    participantCount: Int,
    onLongClick: () -> Unit,
    onOpenMedia: (String) -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val bubbleColor = when {
        isMine && isDark -> OutgoingBubbleDark
        isMine -> OutgoingBubbleLight
        isDark -> IncomingBubbleDark
        else -> IncomingBubbleLight
    }
    val isRead = message.readBy.size >= participantCount.coerceAtLeast(2)
    val isDelivered = message.deliveredTo.size >= 2 || isRead

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isMine) 16.dp else 4.dp,
                bottomEnd = if (isMine) 4.dp else 16.dp
            ),
            tonalElevation = 1.dp,
            modifier = Modifier
                .widthIn(max = 310.dp)
                .combinedClickable(onClick = {}, onLongClick = onLongClick)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (isGroup && !isMine && message.senderName.isNotBlank()) {
                    Text(
                        text = message.senderName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (message.isForwarded) {
                    Text(
                        text = "↪ Forwarded",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (message.replyToPreview.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = message.replyToSenderName.ifBlank { "Reply" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = message.replyToPreview,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                when (message.type) {
                    "image" -> {
                        if (message.mediaUrl.isNotBlank()) {
                            AsyncImage(
                                model = message.mediaUrl,
                                contentDescription = "Shared photo",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(190.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onOpenMedia(message.mediaUrl) }
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                    "video" -> {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { if (message.mediaUrl.isNotBlank()) onOpenMedia(message.mediaUrl) }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = "Play video", tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text("Video attachment • Tap to view", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    "audio" -> {
                        AudioMessageRow(
                            audioUrl = message.mediaUrl,
                            durationSec = message.mediaDurationSec
                        )
                    }
                    "document" -> {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { if (message.mediaUrl.isNotBlank()) onOpenMedia(message.mediaUrl) }
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.InsertDriveFile, contentDescription = "Document", tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = message.mediaName.ifBlank { "Document" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                if (displayText.isNotBlank() && message.type != "document") {
                    Text(
                        text = displayText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (message.isDeleted) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(Modifier.height(3.dp))
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (message.encryptionVersion == 1L && !message.isDeleted) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = "Encrypted",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(11.dp)
                        )
                    }
                    Text(
                        text = formatMessageTime(message.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isMine) {
                        Icon(
                            imageVector = if (isDelivered || isRead) Icons.Rounded.DoneAll else Icons.Rounded.Done,
                            contentDescription = if (isRead) "Read" else "Sent",
                            tint = if (isRead) ReadReceiptBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }

        if (message.reactions.isNotEmpty()) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Text(
                    text = message.reactions.values.distinct().joinToString(" "),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun AudioMessageRow(audioUrl: String, durationSec: Long) {
    var isPlaying by remember { mutableStateOf(false) }
    val player = remember { MediaPlayer() }
    DisposableEffect(Unit) {
        onDispose {
            try { player.release() } catch (_: Exception) {}
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        IconButton(
            onClick = {
                if (audioUrl.isBlank()) return@IconButton
                if (isPlaying) {
                    try { player.pause() } catch (_: Exception) {}
                    isPlaying = false
                } else {
                    try {
                        player.reset()
                        player.setDataSource(audioUrl)
                        player.prepareAsync()
                        player.setOnPreparedListener {
                            it.start()
                            isPlaying = true
                        }
                        player.setOnCompletionListener { isPlaying = false }
                    } catch (_: Exception) {
                        isPlaying = false
                    }
                }
            }
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) "Pause voice note" else "Play voice note",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            text = "Voice note • ${formatCallDuration(durationSec)}",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
