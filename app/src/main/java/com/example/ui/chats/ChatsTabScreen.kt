package com.example.ui.chats

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.model.Chat
import com.example.core.model.UiState
import com.example.core.model.UserProfile
import com.example.ui.components.EmptyStateView
import com.example.ui.components.UserAvatar
import com.example.ui.components.formatRelativeDateTime
import com.example.ui.viewmodel.WhatacallMainViewModel

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatsTabScreen(
    currentUserId: String,
    chatsState: UiState<List<Chat>>,
    peerProfiles: Map<String, UserProfile>,
    mainViewModel: WhatacallMainViewModel,
    onOpenChat: (String) -> Unit,
    onOpenContacts: () -> Unit,
    onOpenCreateGroup: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var filterMode by remember { mutableStateOf("All") }

    Scaffold(
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FloatingActionButton(
                    onClick = onOpenCreateGroup,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("new_group_fab")
                ) {
                    Icon(Icons.Rounded.GroupAdd, contentDescription = "New Group Chat")
                }
                FloatingActionButton(
                    onClick = onOpenContacts,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                    modifier = Modifier.testTag("new_chat_fab")
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Chat, contentDescription = "New Chat")
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search chats or messages...") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("chat_search_input")
            )

            val filters = listOf("All", "Unread", "Pinned", "Archived")
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 6.dp)
            ) {
                items(filters) { f ->
                    FilterChip(
                        selected = filterMode == f,
                        onClick = { filterMode = f },
                        label = { Text(f) }
                    )
                }
            }

            when (chatsState) {
                is UiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is UiState.Error -> {
                    EmptyStateView(
                        icon = Icons.AutoMirrored.Rounded.Chat,
                        title = "Unable to sync chats",
                        subtitle = chatsState.message
                    )
                }
                is UiState.Success -> {
                    val q = searchQuery.trim().lowercase()
                    val filtered = chatsState.data.filter { chat ->
                        val isArchived = currentUserId in chat.archivedBy
                        val isPinned = currentUserId in chat.pinnedBy
                        val unread = (chat.unreadCounts[currentUserId] ?: 0L) > 0L

                        val matchesFilter = when (filterMode) {
                            "Archived" -> isArchived
                            "Unread" -> !isArchived && unread
                            "Pinned" -> !isArchived && isPinned
                            else -> !isArchived
                        }
                        if (!matchesFilter) return@filter false
                        if (q.isEmpty()) return@filter true

                        val displayTitle = resolveChatTitle(chat, currentUserId, peerProfiles)
                        displayTitle.lowercase().contains(q) || chat.lastMessageText.lowercase().contains(q)
                    }

                    if (filtered.isEmpty()) {
                        EmptyStateView(
                            icon = Icons.AutoMirrored.Rounded.Chat,
                            title = if (filterMode == "Archived") "No archived chats" else "Start a conversation",
                            subtitle = "Tap the message button to find registered contacts by phone number or @username."
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 96.dp)
                        ) {
                            items(filtered, key = { it.id }) { chat ->
                                var menuOpen by remember { mutableStateOf(false) }
                                val peer = if (chat.type == "direct") {
                                    val peerId = chat.participantIds.firstOrNull { it != currentUserId }
                                    peerProfiles[peerId]
                                } else null
                                val title = resolveChatTitle(chat, currentUserId, peerProfiles)
                                val unreadCount = chat.unreadCounts[currentUserId] ?: 0L
                                val isPinned = currentUserId in chat.pinnedBy
                                val isMuted = currentUserId in chat.mutedBy
                                val isArchived = currentUserId in chat.archivedBy
                                val peerTyping = chat.typingUserIds.any { it != currentUserId }

                                Box {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .combinedClickable(
                                                onClick = { onOpenChat(chat.id) },
                                                onLongClick = { menuOpen = true }
                                            )
                                            .padding(horizontal = 16.dp, vertical = 12.dp)
                                            .testTag("chat_item_${chat.id}"),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        UserAvatar(
                                            name = title,
                                            photoUrl = peer?.photoUrl ?: chat.groupPhotoUrl,
                                            size = 52.dp,
                                            isOnline = peer?.isOnline == true,
                                            showOnlineBadge = chat.type == "direct"
                                        )
                                        Spacer(Modifier.width(14.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = title,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Text(
                                                    text = formatRelativeDateTime(chat.lastMessageAt ?: chat.updatedAt),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (unreadCount > 0) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Spacer(Modifier.height(3.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = if (peerTyping) "typing..." else chat.lastMessageText.ifBlank { "Tap to chat" },
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = if (peerTyping) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    fontWeight = if (peerTyping) FontWeight.Medium else FontWeight.Normal,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                if (isMuted) {
                                                    Icon(
                                                        imageVector = Icons.AutoMirrored.Rounded.VolumeOff,
                                                        contentDescription = "Muted",
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier
                                                            .padding(start = 6.dp)
                                                            .size(16.dp)
                                                    )
                                                }
                                                if (isPinned) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.PushPin,
                                                        contentDescription = "Pinned",
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier
                                                            .padding(start = 6.dp)
                                                            .size(16.dp)
                                                    )
                                                }
                                                if (unreadCount > 0) {
                                                    Spacer(Modifier.width(8.dp))
                                                    Surface(
                                                        shape = CircleShape,
                                                        color = MaterialTheme.colorScheme.primary
                                                    ) {
                                                        Text(
                                                            text = unreadCount.toString(),
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = Color.White,
                                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    DropdownMenu(
                                        expanded = menuOpen,
                                        onDismissRequest = { menuOpen = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(if (isPinned) "Unpin chat" else "Pin chat") },
                                            leadingIcon = { Icon(Icons.Rounded.PushPin, null) },
                                            onClick = {
                                                menuOpen = false
                                                mainViewModel.toggleChatPin(chat)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(if (isMuted) "Unmute notifications" else "Mute notifications") },
                                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.VolumeOff, null) },
                                            onClick = {
                                                menuOpen = false
                                                mainViewModel.toggleChatMute(chat)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(if (isArchived) "Unarchive chat" else "Archive chat") },
                                            leadingIcon = { Icon(Icons.Rounded.Archive, null) },
                                            onClick = {
                                                menuOpen = false
                                                mainViewModel.toggleChatArchive(chat)
                                            }
                                        )
                                    }
                                }
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 82.dp, end = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

fun resolveChatTitle(
    chat: Chat,
    currentUserId: String,
    peerProfiles: Map<String, UserProfile>
): String {
    if (chat.type == "group") {
        return chat.title.ifBlank { "Group (${chat.participantIds.size})" }
    }
    val peerId = chat.participantIds.firstOrNull { it != currentUserId }
    return peerProfiles[peerId]?.displayName?.ifBlank { "Whatacall User" } ?: "Whatacall Contact"
}
