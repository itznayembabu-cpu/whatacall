@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.ui.updates

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.core.model.StatusUpdate
import com.example.core.model.UiState
import com.example.core.model.UserProfile
import com.example.ui.components.EmptyStateView
import com.example.ui.components.UserAvatar
import com.example.ui.components.formatRelativeDateTime
import com.example.ui.components.parseHexColor
import com.example.ui.viewmodel.WhatacallMainViewModel

@Composable
fun UpdatesTabScreen(
    currentUserId: String,
    currentUserProfile: UserProfile?,
    statusesState: UiState<List<StatusUpdate>>,
    peerProfiles: Map<String, UserProfile>,
    mainViewModel: WhatacallMainViewModel
) {
    val context = LocalContext.current
    var showCreateTextDialog by remember { mutableStateOf(false) }
    var textStatusDraft by remember { mutableStateOf("") }
    var textBgHex by remember { mutableStateOf("#0AA83F") }
    var statusPrivacy by remember { mutableStateOf(currentUserProfile?.statusPrivacy ?: "everyone") }

    var viewingStatus by remember { mutableStateOf<StatusUpdate?>(null) }
    var showViewerListSheet by remember { mutableStateOf(false) }

    val mediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri) ?: ""
            val isVideo = mime.startsWith("video")
            mainViewModel.postMediaStatus(context, uri, isVideo, caption = "", privacy = statusPrivacy)
        }
    }

    Scaffold(
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FloatingActionButton(
                    onClick = { showCreateTextDialog = true },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("create_text_status_fab")
                ) {
                    Icon(Icons.Rounded.Edit, contentDescription = "Create text status")
                }
                FloatingActionButton(
                    onClick = {
                        mediaPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                    modifier = Modifier.testTag("create_media_status_fab")
                ) {
                    Icon(Icons.Rounded.PhotoCamera, contentDescription = "Create photo or video status")
                }
            }
        }
    ) { innerPadding ->
        when (statusesState) {
            is UiState.Loading -> {
                Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is UiState.Error -> {
                EmptyStateView(
                    icon = Icons.Rounded.PhotoCamera,
                    title = "Unable to load updates",
                    subtitle = statusesState.message,
                    modifier = Modifier.padding(innerPadding)
                )
            }
            is UiState.Success -> {
                val all = statusesState.data
                val myStatuses = all.filter { it.userId == currentUserId }
                val othersStatuses = all.filter { it.userId != currentUserId }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (myStatuses.isNotEmpty()) viewingStatus = myStatuses.first()
                                    else showCreateTextDialog = true
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box {
                                UserAvatar(
                                    name = currentUserProfile?.displayName ?: "My Status",
                                    photoUrl = currentUserProfile?.photoUrl ?: "",
                                    size = 54.dp,
                                    modifier = if (myStatuses.isNotEmpty()) {
                                        Modifier.border(2.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                    } else Modifier
                                )
                                if (myStatuses.isEmpty()) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .size(20.dp)
                                    ) {
                                        Icon(Icons.Rounded.Add, contentDescription = null, tint = Color.White, modifier = Modifier.padding(2.dp))
                                    }
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("My Status", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = if (myStatuses.isEmpty()) {
                                        "Tap to post a 24-hour text, photo, or video update"
                                    } else {
                                        "${myStatuses.size} active update(s) • ${myStatuses.first().viewerIds.size} views"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }

                    item {
                        Text(
                            text = "Recent Updates",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }

                    if (othersStatuses.isEmpty()) {
                        item {
                            Text(
                                text = "No recent status updates from contacts yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp)
                            )
                        }
                    } else {
                        items(othersStatuses, key = { it.id }) { status ->
                            val seen = currentUserId in status.viewerIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewingStatus = status }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                UserAvatar(
                                    name = status.userName,
                                    photoUrl = status.userPhotoUrl,
                                    size = 50.dp,
                                    modifier = Modifier.border(
                                        width = 2.5.dp,
                                        color = if (seen) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                                        shape = CircleShape
                                    )
                                )
                                Spacer(Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(status.userName, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = "${status.type.replaceFirstChar { it.uppercase() }} • ${formatRelativeDateTime(status.createdAt)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateTextDialog) {
        val bgOptions = listOf("#0AA83F", "#00897B", "#1E40AF", "#6D28D9", "#B91C1C", "#1F2937")
        AlertDialog(
            onDismissRequest = { showCreateTextDialog = false },
            title = { Text("New Text Status") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = textStatusDraft,
                        onValueChange = { if (it.length <= 700) textStatusDraft = it },
                        label = { Text("Share an update (expires in 24h)") },
                        maxLines = 4,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("text_status_input")
                    )
                    Text("Background Color", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(bgOptions) { hex ->
                            Surface(
                                shape = CircleShape,
                                color = parseHexColor(hex, MaterialTheme.colorScheme.primary),
                                modifier = Modifier
                                    .size(32.dp)
                                    .border(
                                        width = if (textBgHex == hex) 3.dp else 0.dp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        shape = CircleShape
                                    )
                                    .clickable { textBgHex = hex }
                            ) {}
                        }
                    }
                    Text("Who can view", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("everyone" to "Everyone", "contacts" to "Contacts").forEach { (key, label) ->
                            FilterChip(
                                selected = statusPrivacy == key,
                                onClick = { statusPrivacy = key },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = textStatusDraft.isNotBlank(),
                    onClick = {
                        mainViewModel.postTextStatus(textStatusDraft, textBgHex, statusPrivacy)
                        textStatusDraft = ""
                        showCreateTextDialog = false
                    },
                    modifier = Modifier.testTag("post_status_button")
                ) {
                    Text("Post")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateTextDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (viewingStatus != null) {
        val status = viewingStatus!!
        BackHandler { viewingStatus = null }
        LaunchedEffect(status.id) {
            if (status.userId != currentUserId) {
                mainViewModel.markStatusViewed(status.id)
            }
        }
        val bg = parseHexColor(status.backgroundColorHex, Color(0xFF0B141A))
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (status.type == "text") bg else Color.Black)
                .safeDrawingPadding()
        ) {
            when (status.type) {
                "image" -> {
                    AsyncImage(
                        model = status.mediaUrl,
                        contentDescription = "Status image",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                "video" -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        IconButton(
                            onClick = {
                                if (status.mediaUrl.isNotBlank()) {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(status.mediaUrl)))
                                }
                            },
                            modifier = Modifier.size(72.dp)
                        ) {
                            Icon(Icons.Rounded.PlayCircle, contentDescription = "Play video status", tint = Color.White, modifier = Modifier.size(64.dp))
                        }
                        Text("Tap to play video update", color = Color.White)
                    }
                }
                else -> {
                    Text(
                        text = status.textContent,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                UserAvatar(name = status.userName, photoUrl = status.userPhotoUrl, size = 40.dp)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(status.userName, color = Color.White, style = MaterialTheme.typography.titleSmall)
                    Text(formatRelativeDateTime(status.createdAt), color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall)
                }
                if (status.userId == currentUserId) {
                    IconButton(onClick = { showViewerListSheet = true }) {
                        Icon(Icons.Rounded.Visibility, contentDescription = "Viewers", tint = Color.White)
                    }
                    IconButton(onClick = {
                        mainViewModel.deleteStatus(status.id)
                        viewingStatus = null
                    }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Delete status", tint = Color.White)
                    }
                }
                IconButton(onClick = { viewingStatus = null }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close status", tint = Color.White)
                }
            }

            if (status.userId == currentUserId) {
                Surface(
                    color = Color.Black.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                        .clickable { showViewerListSheet = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Rounded.Visibility, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Text("${status.viewerIds.size} views", color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        if (showViewerListSheet) {
            ModalBottomSheet(onDismissRequest = { showViewerListSheet = false }) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Viewed by ${status.viewerIds.size}", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    if (status.viewerIds.isEmpty()) {
                        Text("No views yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        status.viewerIds.forEach { viewerUid ->
                            val name = peerProfiles[viewerUid]?.displayName ?: viewerUid.take(12)
                            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 6.dp))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}
