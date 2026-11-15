package com.example.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.model.UserPrivateInfo
import com.example.core.model.UserProfile
import com.example.data.repository.LocalAppSettings
import com.example.data.repository.SettingsDataStore
import com.example.ui.components.UserAvatar
import com.example.ui.viewmodel.WhatacallMainViewModel
import kotlinx.coroutines.launch

@Composable
fun SettingsTabScreen(
    profile: UserProfile?,
    privateInfo: UserPrivateInfo?,
    localSettings: LocalAppSettings,
    settingsDataStore: SettingsDataStore,
    isAdmin: Boolean,
    mainViewModel: WhatacallMainViewModel,
    onOpenAdminDashboard: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showEditProfileDialog by remember { mutableStateOf(false) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var showBlockedDialog by remember { mutableStateOf(false) }
    var showWallpaperDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    var editName by remember(profile?.displayName) { mutableStateOf(profile?.displayName ?: "") }
    var editAbout by remember(profile?.about) { mutableStateOf(profile?.about ?: "") }
    var pendingPhotoUri by remember { mutableStateOf<Uri?>(null) }

    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            pendingPhotoUri = uri
            mainViewModel.updateProfile(context, editName.ifBlank { profile?.displayName ?: "User" }, editAbout, uri)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 48.dp)
    ) {
        item {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showEditProfileDialog = true }
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UserAvatar(
                        name = profile?.displayName ?: "User",
                        photoUrl = profile?.photoUrl ?: "",
                        size = 64.dp
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = profile?.displayName ?: "Whatacall User",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = profile?.about ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!privateInfo?.phoneNumber.isNullOrBlank()) {
                            Text(
                                text = privateInfo!!.phoneNumber,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Icon(Icons.Rounded.Edit, contentDescription = "Edit profile")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        item {
            SettingsRow(
                icon = Icons.Rounded.Lock,
                title = "Privacy",
                subtitle = "Last seen: ${profile?.lastSeenPrivacy ?: "everyone"}, Photo: ${profile?.photoPrivacy ?: "everyone"}",
                onClick = { showPrivacyDialog = true }
            )
            SettingsRow(
                icon = Icons.Rounded.Block,
                title = "Blocked Contacts",
                subtitle = "${privateInfo?.blockedUserIds?.size ?: 0} blocked user(s)",
                onClick = { showBlockedDialog = true }
            )
            SettingsRow(
                icon = Icons.Rounded.Wallpaper,
                title = "Chat Wallpaper",
                subtitle = "Current theme: ${localSettings.chatWallpaper.replaceFirstChar { it.uppercase() }}",
                onClick = { showWallpaperDialog = true }
            )
        }

        item {
            ListItem(
                leadingContent = { Icon(Icons.Rounded.DarkMode, contentDescription = null) },
                headlineContent = { Text("Appearance Mode") },
                supportingContent = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 6.dp)
                    ) {
                        listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (key, label) ->
                            FilterChip(
                                selected = localSettings.darkModePreference == key,
                                onClick = { scope.launch { settingsDataStore.setDarkMode(key) } },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            )
            ListItem(
                leadingContent = { Icon(Icons.Rounded.Notifications, contentDescription = null) },
                headlineContent = { Text("Push Notifications") },
                supportingContent = { Text("Message and incoming call alerts") },
                trailingContent = {
                    Switch(
                        checked = localSettings.notificationsEnabled,
                        onCheckedChange = { scope.launch { settingsDataStore.setNotificationsEnabled(it) } }
                    )
                }
            )
            ListItem(
                headlineContent = { Text("Use Less Data for Calls") },
                supportingContent = { Text("Caps WebRTC video bitrate for weak cellular connections") },
                trailingContent = {
                    Switch(
                        checked = localSettings.lowDataCalls,
                        onCheckedChange = { scope.launch { settingsDataStore.setLowDataCalls(it) } }
                    )
                }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        if (isAdmin) {
            item {
                ListItem(
                    leadingContent = {
                        Icon(
                            Icons.Rounded.AdminPanelSettings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    headlineContent = {
                        Text(
                            "Admin Moderation Console",
                            color = MaterialTheme.colorScheme.primary
                        )
                    },
                    supportingContent = { Text("Server-verified RBAC: accounts, reports & system health") },
                    modifier = Modifier
                        .clickable { onOpenAdminDashboard() }
                        .testTag("open_admin_dashboard_button")
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
            SettingsRow(
                icon = Icons.AutoMirrored.Rounded.Logout,
                title = "Log Out",
                subtitle = "Sign out safely on this device",
                onClick = onLogout,
                modifier = Modifier.testTag("logout_button")
            )
            SettingsRow(
                icon = Icons.Rounded.DeleteForever,
                title = "Delete Account & Data",
                subtitle = "Permanently remove profile, phone index, and private data",
                isDestructive = true,
                onClick = { showDeleteConfirm = true },
                modifier = Modifier.testTag("delete_account_button")
            )
        }
    }

    if (showEditProfileDialog) {
        AlertDialog(
            onDismissRequest = { showEditProfileDialog = false },
            title = { Text("Edit Profile") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            avatarPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                    ) {
                        Text("Change Profile Photo")
                    }
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { if (it.length <= 60) editName = it },
                        label = { Text("Display Name") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = editAbout,
                        onValueChange = { if (it.length <= 160) editAbout = it },
                        label = { Text("About") },
                        maxLines = 2
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    mainViewModel.updateProfile(context, editName, editAbout, pendingPhotoUri)
                    showEditProfileDialog = false
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditProfileDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showPrivacyDialog) {
        var lastSeen by remember(profile) { mutableStateOf(profile?.lastSeenPrivacy ?: "everyone") }
        var photo by remember(profile) { mutableStateOf(profile?.photoPrivacy ?: "everyone") }
        var status by remember(profile) { mutableStateOf(profile?.statusPrivacy ?: "everyone") }
        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            title = { Text("Privacy Controls") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PrivacySelectorRow("Last Seen Visibility", lastSeen) { lastSeen = it }
                    PrivacySelectorRow("Profile Photo Visibility", photo) { photo = it }
                    PrivacySelectorRow("Status Visibility", status) { status = it }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    mainViewModel.updatePrivacy(lastSeen, photo, status)
                    showPrivacyDialog = false
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPrivacyDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showBlockedDialog) {
        val blocked = privateInfo?.blockedUserIds ?: emptyList()
        AlertDialog(
            onDismissRequest = { showBlockedDialog = false },
            title = { Text("Blocked Users") },
            text = {
                if (blocked.isEmpty()) {
                    Text("You haven't blocked any contacts.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        blocked.forEach { uid ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(uid.take(16), style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = { mainViewModel.toggleBlockUser(uid, false) }) {
                                    Text("Unblock")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBlockedDialog = false }) { Text("Done") }
            }
        )
    }

    if (showWallpaperDialog) {
        AlertDialog(
            onDismissRequest = { showWallpaperDialog = false },
            title = { Text("Select Chat Wallpaper") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "default" to "Classic Warm Parchment",
                        "emerald" to "Whatacall Emerald Mist",
                        "sand" to "Desert Sand",
                        "midnight" to "Midnight Slate"
                    ).forEach { (key, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch { settingsDataStore.setChatWallpaper(key) }
                                    showWallpaperDialog = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (localSettings.chatWallpaper == key) "● $label" else "○ $label",
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showWallpaperDialog = false }) { Text("Close") }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Account?") },
            text = {
                Text("This permanently deletes your Whatacall profile, phone directory entry, and private settings.")
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        showDeleteConfirm = false
                        mainViewModel.deleteAccount { onLogout() }
                    }
                ) {
                    Text("Delete Forever")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isDestructive: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ListItem(
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        headlineContent = {
            Text(
                text = title,
                color = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        },
        supportingContent = { Text(subtitle) },
        modifier = modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun PrivacySelectorRow(label: String, current: String, onSelect: (String) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("everyone", "contacts", "nobody").forEach { opt ->
                FilterChip(
                    selected = current == opt,
                    onClick = { onSelect(opt) },
                    label = { Text(opt.replaceFirstChar { it.uppercase() }) }
                )
            }
        }
    }
}
