package com.example.ui.calls

import android.Manifest
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BluetoothAudio
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.UserAvatar
import com.example.ui.components.formatCallDuration
import com.example.ui.theme.CallAcceptGreen
import com.example.ui.theme.CallRejectRed
import com.example.ui.viewmodel.ActiveCallViewModel
import com.example.webrtc.NetworkQuality
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

@Composable
fun ActiveCallScreen(
    currentUserId: String,
    isInPictureInPicture: Boolean,
    callViewModel: ActiveCallViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity

    BackHandler {
        callViewModel.rejectOrEndCall(context)
        onDismiss()
    }
    val session by callViewModel.callSession.collectAsStateWithLifecycle()
    val isMuted by callViewModel.isMuted.collectAsStateWithLifecycle()
    val isSpeakerOn by callViewModel.isSpeakerOn.collectAsStateWithLifecycle()
    val isCameraOn by callViewModel.isCameraOn.collectAsStateWithLifecycle()
    val isBluetoothActive by callViewModel.isBluetoothActive.collectAsStateWithLifecycle()
    val hasBluetooth by callViewModel.hasBluetooth.collectAsStateWithLifecycle()
    val elapsed by callViewModel.elapsedSeconds.collectAsStateWithLifecycle()
    val networkQuality by callViewModel.networkQuality.collectAsStateWithLifecycle()
    val connectionLabel by callViewModel.connectionLabel.collectAsStateWithLifecycle()

    val isVideo = session?.callType == "video"
    val requiredPerms = remember(isVideo) {
        buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (isVideo) add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
    }

    var permissionsGranted by remember(session?.callType) {
        mutableStateOf(
            requiredPerms.all { perm ->
                perm == Manifest.permission.BLUETOOTH_CONNECT ||
                    ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionsGranted = requiredPerms.all { perm ->
            perm == Manifest.permission.BLUETOOTH_CONNECT || result[perm] == true ||
                ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    LaunchedEffect(permissionsGranted, session?.callType) {
        if (session != null && permissionsGranted) {
            callViewModel.initializeCallIfNeeded(context, session!!.callType == "video")
        }
    }

    val isIncomingRinging = session?.calleeId == currentUserId && session?.status == "ringing"
    val isEnded = session?.status in listOf("ended", "rejected", "missed")
    val peerName = session?.let {
        if (it.callerId == currentUserId) it.calleeName else it.callerName
    } ?: "Whatacall"
    val peerPhoto = session?.let {
        if (it.callerId == currentUserId) it.calleePhotoUrl else it.callerPhotoUrl
    } ?: ""

    var localRendererRef by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var remoteRendererRef by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    LaunchedEffect(localRendererRef, remoteRendererRef, permissionsGranted) {
        if (permissionsGranted && (localRendererRef != null || remoteRendererRef != null)) {
            callViewModel.attachVideoRenderers(localRendererRef, remoteRendererRef)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0A2E1A), Color(0xFF0B141A), Color(0xFF070C10))
                )
            )
    ) {
        if (isVideo && permissionsGranted && !isEnded) {
            AndroidView(
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).apply {
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        remoteRendererRef = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (isVideo && permissionsGranted && !isEnded && isCameraOn && !isInPictureInPicture) {
            AndroidView(
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).apply {
                        setZOrderMediaOverlay(true)
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        localRendererRef = this
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .safeDrawingPadding()
                    .padding(top = 64.dp, end = 16.dp)
                    .size(width = 104.dp, height = 152.dp)
                    .clip(RoundedCornerShape(16.dp))
            )
        }

        if (!isInPictureInPicture) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = Color.White.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Rounded.Lock, contentDescription = null, tint = Color(0xFF25D366), modifier = Modifier.size(14.dp))
                            val netLabel = when (networkQuality) {
                                NetworkQuality.EXCELLENT -> "HD • Excellent"
                                NetworkQuality.GOOD -> "Good"
                                NetworkQuality.FAIR -> "Fair"
                                NetworkQuality.POOR -> "Weak signal"
                                NetworkQuality.CONNECTING -> "DTLS-SRTP"
                            }
                            Text(netLabel, color = Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    if (isVideo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null && !isEnded) {
                        IconButton(
                            onClick = {
                                val params = PictureInPictureParams.Builder()
                                    .setAspectRatio(Rational(9, 16))
                                    .build()
                                activity.enterPictureInPictureMode(params)
                            },
                            modifier = Modifier.testTag("enter_pip_button")
                        ) {
                            Icon(Icons.Rounded.PictureInPictureAlt, contentDescription = "Picture-in-Picture", tint = Color.White)
                        }
                    }
                }

                // Center Peer Info (hidden during active video unless audio/ringing/ended)
                if (!isVideo || isIncomingRinging || isEnded || elapsed == 0L) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        UserAvatar(name = peerName, photoUrl = peerPhoto, size = 112.dp)
                        Spacer(Modifier.height(18.dp))
                        Text(
                            text = peerName,
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White
                        )
                        Spacer(Modifier.height(6.dp))
                        val statusSubtitle = when {
                            isEnded -> "Call ${session?.status ?: "ended"} • ${formatCallDuration(session?.durationSeconds ?: elapsed)}"
                            isIncomingRinging -> if (isVideo) "Incoming Whatacall video call..." else "Incoming Whatacall voice call..."
                            elapsed > 0L -> formatCallDuration(elapsed)
                            else -> connectionLabel
                        }
                        Text(
                            text = statusSubtitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                        if (!callViewModel.isTurnConfigured() && !isEnded) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "ICE: Public STUN active (configure TURN in Secrets for symmetric NAT relay)",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.55f)
                            )
                        }
                    }
                } else {
                    Text(
                        text = "$peerName • ${formatCallDuration(elapsed)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                }

                // Bottom Controls
                if (!permissionsGranted && !isEnded) {
                    Surface(
                        color = Color.White.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Microphone${if (isVideo) " and Camera permissions are" else " permission is"} required for this WebRTC call.",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Button(onClick = { permLauncher.launch(requiredPerms.toTypedArray()) }) {
                                Text("Grant Call Permissions")
                            }
                        }
                    }
                } else if (isEnded) {
                    Button(
                        onClick = onDismiss,
                        shape = CircleShape,
                        modifier = Modifier
                            .height(54.dp)
                            .fillMaxWidth(0.6f)
                            .testTag("close_ended_call_button")
                    ) {
                        Text("Close")
                    }
                } else if (isIncomingRinging) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        CallRoundButton(
                            icon = Icons.Rounded.CallEnd,
                            label = "Decline",
                            containerColor = CallRejectRed,
                            onClick = {
                                callViewModel.rejectOrEndCall(context)
                                onDismiss()
                            },
                            modifier = Modifier.testTag("reject_call_button")
                        )
                        CallRoundButton(
                            icon = if (isVideo) Icons.Rounded.Videocam else Icons.Rounded.Call,
                            label = "Accept",
                            containerColor = CallAcceptGreen,
                            onClick = { callViewModel.acceptIncomingCall() },
                            modifier = Modifier.testTag("accept_call_button")
                        )
                    }
                } else {
                    Surface(
                        color = Color.Black.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CallControlChip(
                                icon = if (isMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                                label = if (isMuted) "Unmute" else "Mute",
                                active = isMuted,
                                onClick = { callViewModel.toggleMute() }
                            )
                            CallControlChip(
                                icon = Icons.AutoMirrored.Rounded.VolumeUp,
                                label = "Speaker",
                                active = isSpeakerOn,
                                onClick = { callViewModel.toggleSpeaker() }
                            )
                            if (hasBluetooth) {
                                CallControlChip(
                                    icon = Icons.Rounded.BluetoothAudio,
                                    label = "Bluetooth",
                                    active = isBluetoothActive,
                                    onClick = { callViewModel.toggleBluetooth() }
                                )
                            }
                            if (isVideo) {
                                CallControlChip(
                                    icon = if (isCameraOn) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff,
                                    label = "Video",
                                    active = !isCameraOn,
                                    onClick = { callViewModel.toggleCamera() }
                                )
                                CallControlChip(
                                    icon = Icons.Rounded.Cameraswitch,
                                    label = "Flip",
                                    active = false,
                                    onClick = { callViewModel.flipCamera() }
                                )
                            }
                            CallRoundButton(
                                icon = Icons.Rounded.CallEnd,
                                label = "End",
                                containerColor = CallRejectRed,
                                onClick = {
                                    callViewModel.rejectOrEndCall(context)
                                    onDismiss()
                                },
                                modifier = Modifier.testTag("end_call_button")
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CallRoundButton(
    icon: ImageVector,
    label: String,
    containerColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = CircleShape,
            color = containerColor,
            modifier = modifier.size(62.dp)
        ) {
            IconButton(onClick = onClick, modifier = Modifier.fillMaxSize()) {
                Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun CallControlChip(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = CircleShape,
            color = if (active) Color.White else Color.White.copy(alpha = 0.16f),
            modifier = Modifier.size(48.dp)
        ) {
            IconButton(onClick = onClick) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (active) Color.Black else Color.White
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall)
    }
}
