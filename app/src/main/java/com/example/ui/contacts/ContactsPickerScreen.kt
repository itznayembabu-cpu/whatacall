package com.example.ui.contacts

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.UserAvatar
import com.example.ui.viewmodel.WhatacallMainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsPickerScreen(
    isGroupMode: Boolean,
    mainViewModel: WhatacallMainViewModel,
    onBack: () -> Unit,
    onChatCreated: (String) -> Unit
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val contacts by mainViewModel.matchedContacts.collectAsStateWithLifecycle()
    val isLoading by mainViewModel.contactsLoading.collectAsStateWithLifecycle()

    var hasContactsPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    var lookupQuery by remember { mutableStateOf("") }
    var groupTitle by remember { mutableStateOf("") }
    var selectedGroupUids by remember { mutableStateOf<Set<String>>(emptySet()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasContactsPermission = granted
        if (granted) {
            mainViewModel.loadContacts(context)
        }
    }

    LaunchedEffect(hasContactsPermission) {
        if (hasContactsPermission && contacts.isEmpty()) {
            mainViewModel.loadContacts(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(if (isGroupMode) "New Group" else "Select Contact") },
                actions = {
                    if (isGroupMode && selectedGroupUids.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                mainViewModel.createGroupChat(
                                    title = groupTitle,
                                    memberUids = selectedGroupUids.toList(),
                                    onChatReady = onChatCreated
                                )
                            }
                        ) {
                            Icon(Icons.Rounded.Check, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Create")
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
                .padding(horizontal = 16.dp)
        ) {
            if (isGroupMode) {
                OutlinedTextField(
                    value = groupTitle,
                    onValueChange = { groupTitle = it },
                    label = { Text("Group Subject") },
                    placeholder = { Text("e.g. Dhaka Friends") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = lookupQuery,
                        onValueChange = { lookupQuery = it },
                        label = { Text("Find by +880... phone or @username") },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("contact_lookup_input")
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (lookupQuery.isNotBlank()) {
                                mainViewModel.lookupUserAndStartChat(lookupQuery, onChatCreated)
                            }
                        },
                        modifier = Modifier
                            .height(52.dp)
                            .testTag("contact_lookup_button")
                    ) {
                        Text("Chat")
                    }
                }
            }

            if (!hasContactsPermission) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Contacts,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = "Match Phone Contacts Securely",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Whatacall hashes phone numbers on-device (SHA-256) to find friends on Whatacall without uploading raw address books.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.READ_CONTACTS) },
                            modifier = Modifier.testTag("grant_contacts_button")
                        ) {
                            Text("Allow Contacts Access")
                        }
                    }
                }
            } else if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val registered = contacts.filter { it.registeredProfile != null }
                val unregistered = contacts.filter { it.registeredProfile == null }

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (registered.isNotEmpty()) {
                        item {
                            Text(
                                text = "Contacts on Whatacall (${registered.size})",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        items(registered, key = { "reg_${it.phoneHash}" }) { contact ->
                            val profile = contact.registeredProfile!!
                            val isSelected = profile.uid in selectedGroupUids
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isGroupMode) {
                                            selectedGroupUids = if (isSelected) {
                                                selectedGroupUids - profile.uid
                                            } else {
                                                selectedGroupUids + profile.uid
                                            }
                                        } else {
                                            mainViewModel.openDirectChat(profile, onChatCreated)
                                        }
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                UserAvatar(
                                    name = contact.displayName,
                                    photoUrl = profile.photoUrl,
                                    size = 46.dp,
                                    isOnline = profile.isOnline,
                                    showOnlineBadge = true
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(contact.displayName, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = profile.about.ifBlank { contact.phoneNumber },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (isGroupMode) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { checked ->
                                            selectedGroupUids = if (checked) {
                                                selectedGroupUids + profile.uid
                                            } else {
                                                selectedGroupUids - profile.uid
                                            }
                                        }
                                    )
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }

                    if (!isGroupMode && unregistered.isNotEmpty()) {
                        item {
                            Text(
                                text = "Invite to Whatacall",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                            )
                        }
                        items(unregistered, key = { "unreg_${it.phoneHash}" }) { contact ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                UserAvatar(name = contact.displayName, photoUrl = "", size = 44.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(contact.displayName, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = contact.phoneNumber,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                OutlinedButton(
                                    onClick = {
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(
                                                Intent.EXTRA_TEXT,
                                                "Hey ${contact.displayName}, join me on Whatacall for encrypted messaging and HD voice/video calls!"
                                            )
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, "Invite via"))
                                    }
                                ) {
                                    Icon(Icons.Rounded.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Invite")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
