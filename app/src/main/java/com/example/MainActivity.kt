package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.core.crypto.E2eeCryptoManager
import com.example.core.model.CallSession
import com.example.core.model.UiState
import com.example.data.auth.AuthManager
import com.example.data.auth.authStateFlow
import com.example.data.repository.AdminRepository
import com.example.data.repository.CallRepository
import com.example.data.repository.ChatRepository
import com.example.data.repository.ContactsRepository
import com.example.data.repository.LocalAppSettings
import com.example.data.repository.SettingsDataStore
import com.example.data.repository.StatusRepository
import com.example.data.repository.StorageRepository
import com.example.data.repository.UserRepository
import com.example.ui.admin.AdminDashboardScreen
import com.example.ui.auth.PhoneVerificationScreen
import com.example.ui.auth.ProfileSetupScreen
import com.example.ui.auth.WelcomeSignInScreen
import com.example.ui.calls.ActiveCallScreen
import com.example.ui.calls.CallsTabScreen
import com.example.ui.chats.ChatRoomScreen
import com.example.ui.chats.ChatsTabScreen
import com.example.ui.components.UserAvatar
import com.example.ui.contacts.ContactsPickerScreen
import com.example.ui.settings.SettingsTabScreen
import com.example.ui.theme.CallAcceptGreen
import com.example.ui.theme.CallRejectRed
import com.example.ui.theme.WhatacallTheme
import com.example.ui.updates.UpdatesTabScreen
import com.example.ui.viewmodel.ActiveCallViewModel
import com.example.ui.viewmodel.ChatRoomViewModel
import com.example.ui.viewmodel.WhatacallMainViewModel
import com.example.webrtc.WhatacallMessagingService
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore

sealed interface AppOverlayRoute {
    data object None : AppOverlayRoute
    data class ChatRoom(val chatId: String) : AppOverlayRoute
    data class ContactsPicker(val isGroupMode: Boolean) : AppOverlayRoute
    data class ActiveCall(val callId: String) : AppOverlayRoute
    data object AdminConsole : AppOverlayRoute
}

class MainActivity : ComponentActivity() {
    private var isInPip by mutableStateOf(false)
    private var deepLinkCallId by mutableStateOf<String?>(null)
    private var deepLinkChatId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        deepLinkCallId = intent?.getStringExtra(WhatacallMessagingService.EXTRA_INCOMING_CALL_ID)
        deepLinkChatId = intent?.getStringExtra(WhatacallMessagingService.EXTRA_CHAT_ID)

        setContent {
            val settingsDataStore = remember { SettingsDataStore(applicationContext) }
            val localSettings by settingsDataStore.settingsFlow.collectAsStateWithLifecycle(
                initialValue = LocalAppSettings()
            )
            val isDark = when (localSettings.darkModePreference) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }

            WhatacallTheme(darkTheme = isDark) {
                WhatacallRoot(
                    isInPip = isInPip,
                    deepLinkCallId = deepLinkCallId,
                    deepLinkChatId = deepLinkChatId,
                    onConsumeDeepLinks = {
                        deepLinkCallId = null
                        deepLinkChatId = null
                    },
                    localSettings = localSettings,
                    settingsDataStore = settingsDataStore
                )
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPip = isInPictureInPictureMode
    }
}

@Composable
private fun WhatacallRoot(
    isInPip: Boolean,
    deepLinkCallId: String?,
    deepLinkChatId: String?,
    onConsumeDeepLinks: () -> Unit,
    localSettings: LocalAppSettings,
    settingsDataStore: SettingsDataStore
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }

    val auth = Firebase.auth
    val currentUser: FirebaseUser? by auth.authStateFlow().collectAsStateWithLifecycle(
        initialValue = auth.currentUser
    )

    var attemptedAutoSignIn by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!attemptedAutoSignIn && auth.currentUser == null) {
            attemptedAutoSignIn = true
            AuthManager.attemptAutoSignIn(
                context = context,
                credentialManager = credentialManager,
                onAuthSuccess = {},
                onUnauthenticated = {},
                scope = scope
            )
        }
    }

    val user = currentUser
    if (user == null) {
        WelcomeSignInScreen(onAuthSuccess = {})
    } else {
        androidx.compose.runtime.key(user.uid) {
            AuthenticatedWhatacallShell(
                firebaseUser = user,
                isInPip = isInPip,
                deepLinkCallId = deepLinkCallId,
                deepLinkChatId = deepLinkChatId,
                onConsumeDeepLinks = onConsumeDeepLinks,
                localSettings = localSettings,
                settingsDataStore = settingsDataStore,
                onSignOut = {
                    AuthManager.signOut(credentialManager, scope)
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthenticatedWhatacallShell(
    firebaseUser: FirebaseUser,
    isInPip: Boolean,
    deepLinkCallId: String?,
    deepLinkChatId: String?,
    onConsumeDeepLinks: () -> Unit,
    localSettings: LocalAppSettings,
    settingsDataStore: SettingsDataStore,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val uid = firebaseUser.uid

    val mainViewModel: WhatacallMainViewModel = viewModel(
        key = uid,
        factory = viewModelFactory {
            initializer {
                val db = FirebaseFirestore.getInstance(context.getString(R.string.firestore_database_id))
                val userRepo = UserRepository(db)
                WhatacallMainViewModel(
                    currentUserId = uid,
                    userRepository = userRepo,
                    chatRepository = ChatRepository(db),
                    callRepository = CallRepository(db),
                    statusRepository = StatusRepository(db),
                    adminRepository = AdminRepository(db),
                    contactsRepository = ContactsRepository(userRepo),
                    storageRepository = StorageRepository()
                )
            }
        }
    )

    val profileState by mainViewModel.userProfileState.collectAsStateWithLifecycle()
    val privateInfo by mainViewModel.privateInfoState.collectAsStateWithLifecycle()
    val chatsState by mainViewModel.chatsState.collectAsStateWithLifecycle()
    val callsState by mainViewModel.callsState.collectAsStateWithLifecycle()
    val statusesState by mainViewModel.statusesState.collectAsStateWithLifecycle()
    val peerProfiles by mainViewModel.peerProfiles.collectAsStateWithLifecycle()
    val incomingRingingCall by mainViewModel.incomingRingingCall.collectAsStateWithLifecycle()
    val isAdmin by mainViewModel.isAdmin.collectAsStateWithLifecycle()
    val actionMessage by mainViewModel.actionMessage.collectAsStateWithLifecycle()

    var pendingVerifiedPhone by remember { mutableStateOf(firebaseUser.phoneNumber) }
    var overlay by remember { mutableStateOf<AppOverlayRoute>(AppOverlayRoute.None) }
    var selectedTab by remember { mutableIntStateOf(0) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(actionMessage) {
        if (actionMessage != null) {
            snackbarHostState.showSnackbar(actionMessage!!)
            mainViewModel.clearActionMessage()
        }
    }

    // Request POST_NOTIFICATIONS on Android 13+ once authenticated
    val notifPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Online presence management
    DisposableEffect(uid) {
        mainViewModel.setPresence(true)
        onDispose { mainViewModel.setPresence(false) }
    }

    // Deep links from notifications
    LaunchedEffect(deepLinkCallId, deepLinkChatId) {
        if (!deepLinkCallId.isNullOrBlank()) {
            overlay = AppOverlayRoute.ActiveCall(deepLinkCallId)
            onConsumeDeepLinks()
        } else if (!deepLinkChatId.isNullOrBlank()) {
            overlay = AppOverlayRoute.ChatRoom(deepLinkChatId)
            onConsumeDeepLinks()
        }
    }

    when (val pState = profileState) {
        is UiState.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        is UiState.Error -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Could not load Whatacall account: ${pState.message}", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onSignOut) { Text("Switch Account") }
            }
        }
        is UiState.Success -> {
            val profile = pState.data
            if (profile?.isSuspended == true) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Account Suspended", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
                        Text("Your Whatacall account has been suspended by an administrator due to policy violations.")
                        Button(onClick = onSignOut) { Text("Log Out") }
                    }
                }
            } else if (profile == null || !profile.isPhoneVerified) {
                val verifiedPhone = pendingVerifiedPhone?.takeIf { it.isNotBlank() }
                if (verifiedPhone == null) {
                    PhoneVerificationScreen(
                        userRepository = remember { UserRepository(context) },
                        onPhoneVerified = { e164 -> pendingVerifiedPhone = e164 },
                        onSignOutRequested = onSignOut
                    )
                } else {
                    ProfileSetupScreen(
                        verifiedE164Phone = verifiedPhone,
                        defaultDisplayName = firebaseUser.displayName ?: "",
                        email = firebaseUser.email ?: "",
                        mainViewModel = mainViewModel
                    )
                }
            } else {
                // Full Registered Whatacall App
                Box(modifier = Modifier.fillMaxSize()) {
                    when (val currentOverlay = overlay) {
                        is AppOverlayRoute.ActiveCall -> {
                            val callVm: ActiveCallViewModel = viewModel(
                                key = "call_${currentOverlay.callId}",
                                factory = viewModelFactory {
                                    initializer {
                                        val db = FirebaseFirestore.getInstance(context.getString(R.string.firestore_database_id))
                                        ActiveCallViewModel(
                                            callId = currentOverlay.callId,
                                            currentUserId = uid,
                                            lowDataMode = localSettings.lowDataCalls,
                                            callRepository = CallRepository(db)
                                        )
                                    }
                                }
                            )
                            ActiveCallScreen(
                                currentUserId = uid,
                                isInPictureInPicture = isInPip,
                                callViewModel = callVm,
                                onDismiss = { overlay = AppOverlayRoute.None }
                            )
                        }

                        is AppOverlayRoute.ChatRoom -> {
                            val keyPair = remember(uid) { E2eeCryptoManager.getOrCreateIdentityKeyPair(context, uid) }
                            val chatVm: ChatRoomViewModel = viewModel(
                                key = "chat_${currentOverlay.chatId}",
                                factory = viewModelFactory {
                                    initializer {
                                        val db = FirebaseFirestore.getInstance(context.getString(R.string.firestore_database_id))
                                        ChatRoomViewModel(
                                            chatId = currentOverlay.chatId,
                                            currentUserId = uid,
                                            myPrivateKeyBase64 = keyPair.privateKeyBase64,
                                            chatRepository = ChatRepository(db),
                                            userRepository = UserRepository(db),
                                            storageRepository = StorageRepository()
                                        )
                                    }
                                }
                            )
                            ChatRoomScreen(
                                chatId = currentOverlay.chatId,
                                currentUserId = uid,
                                currentUserName = profile.displayName,
                                wallpaperPreset = localSettings.chatWallpaper,
                                allChats = (chatsState as? UiState.Success)?.data ?: emptyList(),
                                peerProfiles = peerProfiles,
                                blockedUserIds = privateInfo?.blockedUserIds ?: emptyList(),
                                chatViewModel = chatVm,
                                mainViewModel = mainViewModel,
                                onBack = { overlay = AppOverlayRoute.None },
                                onStartCall = { peer, callType ->
                                    mainViewModel.startOutgoingCall(peer, callType) { callId ->
                                        overlay = AppOverlayRoute.ActiveCall(callId)
                                    }
                                }
                            )
                        }

                        is AppOverlayRoute.ContactsPicker -> {
                            ContactsPickerScreen(
                                isGroupMode = currentOverlay.isGroupMode,
                                mainViewModel = mainViewModel,
                                onBack = { overlay = AppOverlayRoute.None },
                                onChatCreated = { chatId ->
                                    overlay = AppOverlayRoute.ChatRoom(chatId)
                                }
                            )
                        }

                        is AppOverlayRoute.AdminConsole -> {
                            if (isAdmin) {
                                AdminDashboardScreen(
                                    adminRepository = remember { AdminRepository(context) },
                                    onBack = { overlay = AppOverlayRoute.None }
                                )
                            } else {
                                overlay = AppOverlayRoute.None
                            }
                        }

                        AppOverlayRoute.None -> {
                            val totalUnread = (chatsState as? UiState.Success)?.data
                                ?.sumOf { (it.unreadCounts[uid] ?: 0L).toInt() } ?: 0

                            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                                val useNavRail = maxWidth >= 600.dp

                                Scaffold(
                                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                                    snackbarHost = { SnackbarHost(snackbarHostState) },
                                    topBar = {
                                        TopAppBar(
                                            title = {
                                                Text(
                                                    text = when (selectedTab) {
                                                        0 -> "Whatacall"
                                                        1 -> "Updates"
                                                        2 -> "Calls"
                                                        else -> "Settings"
                                                    },
                                                    style = MaterialTheme.typography.headlineSmall,
                                                    color = if (selectedTab == 0) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        )
                                    },
                                    bottomBar = {
                                        if (!useNavRail) {
                                            NavigationBar {
                                                WhatacallNavItems(
                                                    selectedTab = selectedTab,
                                                    unreadCount = totalUnread,
                                                    onSelect = { selectedTab = it }
                                                ).forEach { item ->
                                                    NavigationBarItem(
                                                        selected = item.selected,
                                                        onClick = item.onClick,
                                                        icon = item.icon,
                                                        label = { Text(item.label) },
                                                        modifier = Modifier.testTag(item.tag)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                ) { innerPadding ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(innerPadding)
                                    ) {
                                        if (useNavRail) {
                                            NavigationRail {
                                                WhatacallNavItems(
                                                    selectedTab = selectedTab,
                                                    unreadCount = totalUnread,
                                                    onSelect = { selectedTab = it }
                                                ).forEach { item ->
                                                    NavigationRailItem(
                                                        selected = item.selected,
                                                        onClick = item.onClick,
                                                        icon = item.icon,
                                                        label = { Text(item.label) },
                                                        modifier = Modifier.testTag(item.tag)
                                                    )
                                                }
                                            }
                                        }

                                        Box(modifier = Modifier.weight(1f)) {
                                            when (selectedTab) {
                                                0 -> ChatsTabScreen(
                                                    currentUserId = uid,
                                                    chatsState = chatsState,
                                                    peerProfiles = peerProfiles,
                                                    mainViewModel = mainViewModel,
                                                    onOpenChat = { chatId -> overlay = AppOverlayRoute.ChatRoom(chatId) },
                                                    onOpenContacts = { overlay = AppOverlayRoute.ContactsPicker(false) },
                                                    onOpenCreateGroup = { overlay = AppOverlayRoute.ContactsPicker(true) }
                                                )
                                                1 -> UpdatesTabScreen(
                                                    currentUserId = uid,
                                                    currentUserProfile = profile,
                                                    statusesState = statusesState,
                                                    peerProfiles = peerProfiles,
                                                    mainViewModel = mainViewModel
                                                )
                                                2 -> CallsTabScreen(
                                                    currentUserId = uid,
                                                    callsState = callsState,
                                                    peerProfiles = peerProfiles,
                                                    onCallPeer = { peer, callType ->
                                                        mainViewModel.startOutgoingCall(peer, callType) { callId ->
                                                            overlay = AppOverlayRoute.ActiveCall(callId)
                                                        }
                                                    }
                                                )
                                                else -> SettingsTabScreen(
                                                    profile = profile,
                                                    privateInfo = privateInfo,
                                                    localSettings = localSettings,
                                                    settingsDataStore = settingsDataStore,
                                                    isAdmin = isAdmin,
                                                    mainViewModel = mainViewModel,
                                                    onOpenAdminDashboard = { overlay = AppOverlayRoute.AdminConsole },
                                                    onLogout = onSignOut
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Live Incoming Call Heads-Up Banner whenever user is not already on that ActiveCallScreen
                    val ringing = incomingRingingCall
                    if (ringing != null && (overlay as? AppOverlayRoute.ActiveCall)?.callId != ringing.id) {
                        IncomingCallBanner(
                            call = ringing,
                            onAccept = { overlay = AppOverlayRoute.ActiveCall(ringing.id) },
                            onReject = { mainViewModel.rejectIncomingCall(ringing.id) },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .safeDrawingPadding()
                                .padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}

private data class NavItemSpec(
    val label: String,
    val tag: String,
    val selected: Boolean,
    val icon: @Composable () -> Unit,
    val onClick: () -> Unit
)

private fun WhatacallNavItems(
    selectedTab: Int,
    unreadCount: Int,
    onSelect: (Int) -> Unit
): List<NavItemSpec> = listOf(
    NavItemSpec(
        label = "Chats",
        tag = "tab_chats",
        selected = selectedTab == 0,
        icon = {
            Icon(
                imageVector = if (selectedTab == 0) Icons.AutoMirrored.Rounded.Chat else Icons.AutoMirrored.Outlined.Chat,
                contentDescription = if (unreadCount > 0) "Chats ($unreadCount unread)" else "Chats"
            )
        },
        onClick = { onSelect(0) }
    ),
    NavItemSpec(
        label = "Updates",
        tag = "tab_updates",
        selected = selectedTab == 1,
        icon = {
            Icon(
                imageVector = if (selectedTab == 1) Icons.Rounded.Update else Icons.Outlined.Update,
                contentDescription = "Updates"
            )
        },
        onClick = { onSelect(1) }
    ),
    NavItemSpec(
        label = "Calls",
        tag = "tab_calls",
        selected = selectedTab == 2,
        icon = {
            Icon(
                imageVector = if (selectedTab == 2) Icons.Rounded.Call else Icons.Outlined.Call,
                contentDescription = "Calls"
            )
        },
        onClick = { onSelect(2) }
    ),
    NavItemSpec(
        label = "Settings",
        tag = "tab_settings",
        selected = selectedTab == 3,
        icon = {
            Icon(
                imageVector = if (selectedTab == 3) Icons.Rounded.Settings else Icons.Outlined.Settings,
                contentDescription = "Settings"
            )
        },
        onClick = { onSelect(3) }
    )
)

@Composable
private fun IncomingCallBanner(
    call: CallSession,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF111B21),
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            UserAvatar(name = call.callerName, photoUrl = call.callerPhotoUrl, size = 46.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = call.callerName.ifBlank { "Incoming Call" },
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                Text(
                    text = if (call.callType == "video") "Incoming Whatacall video call" else "Incoming Whatacall voice call",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.75f)
                )
            }
            Surface(shape = RoundedCornerShape(50), color = CallRejectRed, modifier = Modifier.size(44.dp)) {
                IconButton(onClick = onReject) {
                    Icon(Icons.Rounded.CallEnd, contentDescription = "Decline", tint = Color.White)
                }
            }
            Spacer(Modifier.width(10.dp))
            Surface(shape = RoundedCornerShape(50), color = CallAcceptGreen, modifier = Modifier.size(44.dp)) {
                IconButton(onClick = onAccept) {
                    Icon(Icons.Rounded.Call, contentDescription = "Accept", tint = Color.White)
                }
            }
        }
    }
}
