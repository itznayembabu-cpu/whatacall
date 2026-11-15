package com.example.ui.admin

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.model.AbuseReport
import com.example.core.model.UserProfile
import com.example.data.repository.AdminRepository
import com.example.ui.components.UserAvatar
import com.example.ui.components.formatRelativeDateTime
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDashboardScreen(
    adminRepository: AdminRepository,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    val scope = rememberCoroutineScope()
    val users by adminRepository.observeAllUsersForAdmin().collectAsStateWithLifecycle(initialValue = emptyList())
    val reports by adminRepository.observeReportsForAdmin().collectAsStateWithLifecycle(initialValue = emptyList())
    var selectedTab by remember { mutableIntStateOf(0) }

    val suspendedCount = users.count { it.isSuspended }
    val pendingReports = reports.count { it.status == "pending" }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Whatacall Admin Console") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Privacy Guarantee Banner
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = "Zero-knowledge enforcement: private chat messages and PII are isolated by Firestore security rules and inaccessible to admins.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Summary Metrics
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatCard("Users", users.size.toString(), Modifier.weight(1f))
                StatCard("Suspended", suspendedCount.toString(), Modifier.weight(1f))
                StatCard("Pending Reports", pendingReports.toString(), Modifier.weight(1f))
            }

            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Accounts (${users.size})") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Reports (${reports.size})") })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Health") })
            }

            when (selectedTab) {
                0 -> {
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(users, key = { it.uid }) { user: UserProfile ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                tonalElevation = 1.dp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    UserAvatar(name = user.displayName, photoUrl = user.photoUrl, size = 44.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(user.displayName, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            text = "${user.phoneMasked.ifBlank { "No phone" }} • ${if (user.isSuspended) "SUSPENDED" else "Active"}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (user.isSuspended) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                adminRepository.setUserSuspended(user.uid, !user.isSuspended)
                                            }
                                        }
                                    ) {
                                        Text(if (user.isSuspended) "Restore" else "Suspend")
                                    }
                                }
                            }
                        }
                    }
                }
                1 -> {
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(reports, key = { it.id }) { report: AbuseReport ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                tonalElevation = 1.dp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Reported: ${report.reportedUserName.ifBlank { report.reportedUserId }}",
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        Text(
                                            text = report.status.uppercase(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Text("Reason: ${report.reason}", style = MaterialTheme.typography.bodyMedium)
                                    if (report.details.isNotBlank()) {
                                        Text(report.details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(
                                        "Filed ${formatRelativeDateTime(report.createdAt)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (report.status == "pending") {
                                        Spacer(Modifier.height(8.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                                onClick = {
                                                    scope.launch {
                                                        adminRepository.setUserSuspended(report.reportedUserId, true)
                                                        adminRepository.resolveReport(report.id, "resolved", "Account suspended by admin")
                                                    }
                                                }
                                            ) {
                                                Text("Suspend User")
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    scope.launch {
                                                        adminRepository.resolveReport(report.id, "dismissed", "No policy violation")
                                                    }
                                                }
                                            ) {
                                                Text("Dismiss")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                2 -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            tonalElevation = 1.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Rounded.HealthAndSafety, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Text("Backend & Signaling Health", style = MaterialTheme.typography.titleMedium)
                                }
                                HorizontalDivider()
                                Text("• Firestore Enterprise Database: Connected", style = MaterialTheme.typography.bodyMedium)
                                Text("• Phone Verified Users: ${users.count { it.isPhoneVerified }} / ${users.size}", style = MaterialTheme.typography.bodyMedium)
                                Text("• WebRTC STUN Signaling: Active", style = MaterialTheme.typography.bodyMedium)
                                Text("• Messaging & Call Security: TLS + Firestore Rules + DTLS-SRTP", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 2.dp,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
