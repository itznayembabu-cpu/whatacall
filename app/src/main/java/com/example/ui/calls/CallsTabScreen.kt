package com.example.ui.calls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallMissed
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.core.model.CallSession
import com.example.core.model.UiState
import com.example.core.model.UserProfile
import com.example.ui.components.EmptyStateView
import com.example.ui.components.UserAvatar
import com.example.ui.components.formatCallDuration
import com.example.ui.components.formatRelativeDateTime
import com.example.ui.theme.CallAcceptGreen
import com.example.ui.theme.CallRejectRed

@Composable
fun CallsTabScreen(
    currentUserId: String,
    callsState: UiState<List<CallSession>>,
    peerProfiles: Map<String, UserProfile>,
    onCallPeer: (peer: UserProfile, callType: String) -> Unit
) {
    when (callsState) {
        is UiState.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        is UiState.Error -> {
            EmptyStateView(
                icon = Icons.Rounded.Call,
                title = "Unable to load call history",
                subtitle = callsState.message
            )
        }
        is UiState.Success -> {
            if (callsState.data.isEmpty()) {
                EmptyStateView(
                    icon = Icons.Rounded.Call,
                    title = "No calls yet",
                    subtitle = "Start a crystal-clear WebRTC voice or video call from any chat."
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    items(callsState.data, key = { it.id }) { call ->
                        val isOutgoing = call.callerId == currentUserId
                        val peerId = if (isOutgoing) call.calleeId else call.callerId
                        val peerName = if (isOutgoing) {
                            peerProfiles[peerId]?.displayName ?: call.calleeName.ifBlank { "Whatacall User" }
                        } else {
                            peerProfiles[peerId]?.displayName ?: call.callerName.ifBlank { "Whatacall User" }
                        }
                        val peerPhoto = peerProfiles[peerId]?.photoUrl
                            ?: if (isOutgoing) call.calleePhotoUrl else call.callerPhotoUrl
                        val isMissed = !isOutgoing && call.status in listOf("missed", "rejected")

                        val arrowIcon = when {
                            isMissed -> Icons.AutoMirrored.Rounded.CallMissed
                            isOutgoing -> Icons.AutoMirrored.Rounded.CallMade
                            else -> Icons.AutoMirrored.Rounded.CallReceived
                        }
                        val arrowTint = if (isMissed || call.status == "rejected") CallRejectRed else CallAcceptGreen

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            UserAvatar(name = peerName, photoUrl = peerPhoto, size = 48.dp)
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = peerName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (isMissed) CallRejectRed else MaterialTheme.colorScheme.onSurface
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = arrowIcon,
                                        contentDescription = null,
                                        tint = arrowTint,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    val durationLabel = if (call.durationSeconds > 0) {
                                        " • ${formatCallDuration(call.durationSeconds)}"
                                    } else {
                                        " • ${call.status}"
                                    }
                                    Text(
                                        text = "${formatRelativeDateTime(call.createdAt)}$durationLabel",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            val peerObj = peerProfiles[peerId] ?: UserProfile(
                                uid = peerId,
                                displayName = peerName,
                                photoUrl = peerPhoto
                            )
                            IconButton(onClick = { onCallPeer(peerObj, call.callType) }) {
                                Icon(
                                    imageVector = if (call.callType == "video") Icons.Rounded.Videocam else Icons.Rounded.Call,
                                    contentDescription = "Call back",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 78.dp, end = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }
            }
        }
    }
}
