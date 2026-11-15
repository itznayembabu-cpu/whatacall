package com.example.ui.auth

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.core.model.CountryCatalog
import com.example.core.model.CountryCode
import com.example.data.auth.AuthManager
import com.example.data.repository.UserRepository
import com.google.firebase.auth.PhoneAuthProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneVerificationScreen(
    userRepository: UserRepository,
    onPhoneVerified: (verifiedE164Phone: String) -> Unit,
    onSignOutRequested: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    var selectedCountry by remember { mutableStateOf(CountryCatalog.defaultBangladesh) }
    var showCountryPicker by remember { mutableStateOf(false) }
    var countrySearch by remember { mutableStateOf("") }

    var localPhone by remember { mutableStateOf("") }
    var formattedE164 by remember { mutableStateOf<String?>(null) }

    var verificationId by remember { mutableStateOf<String?>(null) }
    var resendToken by remember { mutableStateOf<PhoneAuthProvider.ForceResendingToken?>(null) }
    var otpCode by remember { mutableStateOf("") }
    var countdownSeconds by remember { mutableIntStateOf(0) }

    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var smsUnavailableFallbackPhone by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(countdownSeconds) {
        if (countdownSeconds > 0) {
            delay(1000L)
            countdownSeconds -= 1
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Verify Your Phone") },
                actions = {
                    TextButton(onClick = onSignOutRequested) {
                        Text("Switch Account")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .widthIn(max = 520.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.size(64.dp)
            ) {
                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Sms,
                        contentDescription = "OTP Verification",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = if (verificationId == null) {
                    "Enter your mobile phone number"
                } else {
                    "Enter the 6-digit OTP sent to ${formattedE164 ?: ""}"
                },
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Whatacall verifies your real phone number via Firebase OTP and prevents duplicate accounts.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))

            if (verificationId == null) {
                OutlinedButton(
                    onClick = { showCountryPicker = true },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("country_picker_button")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${selectedCountry.flagEmoji}  ${selectedCountry.name} (${selectedCountry.dialCode})",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Icon(Icons.Rounded.ArrowDropDown, contentDescription = "Select country")
                    }
                }

                Spacer(Modifier.height(14.dp))

                OutlinedTextField(
                    value = localPhone,
                    onValueChange = { input ->
                        if (input.length <= 18) {
                            localPhone = input
                            smsUnavailableFallbackPhone = null
                        }
                    },
                    label = { Text("Phone number (${selectedCountry.dialCode})") },
                    placeholder = { Text(selectedCountry.sampleLocalHint) },
                    prefix = {
                        Text(
                            text = "${selectedCountry.dialCode} ",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("phone_number_input")
                )

                Spacer(Modifier.height(20.dp))

                Button(
                    onClick = {
                        val e164 = CountryCatalog.formatToE164(selectedCountry, localPhone)
                        if (e164 == null) {
                            errorMessage = if (selectedCountry.iso == "BD") {
                                "Enter a valid Bangladesh mobile number (e.g. 1712345678)."
                            } else {
                                "Please enter a valid international phone number."
                            }
                            return@Button
                        }
                        if (activity == null) {
                            errorMessage = "Activity context unavailable for OTP verification."
                            return@Button
                        }
                        isLoading = true
                        errorMessage = null
                        statusMessage = null
                        smsUnavailableFallbackPhone = null
                        scope.launch {
                            val taken = userRepository.isPhoneAlreadyRegisteredByAnotherUser(e164)
                            if (taken) {
                                isLoading = false
                                errorMessage = "This phone number is already linked to another Whatacall account."
                                return@launch
                            }
                            formattedE164 = e164
                            AuthManager.sendPhoneOtp(
                                activity = activity,
                                e164Phone = e164,
                                onCodeSent = { vId, token ->
                                    isLoading = false
                                    verificationId = vId
                                    resendToken = token
                                    countdownSeconds = 60
                                    statusMessage = "OTP dispatched to $e164. Auto-detection active when permitted."
                                },
                                onAutoVerified = { cred ->
                                    scope.launch {
                                        val linkRes = AuthManager.verifyAndLinkPhoneCredential(cred)
                                        isLoading = false
                                        if (linkRes.isSuccess) {
                                            onPhoneVerified(e164)
                                        } else {
                                            errorMessage = linkRes.exceptionOrNull()?.localizedMessage
                                        }
                                    }
                                },
                                onError = { msg ->
                                    isLoading = false
                                    errorMessage = msg
                                },
                                onBillingOrSmsUnavailable = { msg ->
                                    isLoading = false
                                    errorMessage = msg
                                    smsUnavailableFallbackPhone = e164
                                }
                            )
                        }
                    },
                    enabled = !isLoading && localPhone.isNotBlank(),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("send_otp_button")
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Send Verification Code")
                    }
                }
            } else {
                OutlinedTextField(
                    value = otpCode,
                    onValueChange = { input ->
                        val digits = input.filter { it.isDigit() }.take(6)
                        otpCode = digits
                    },
                    label = { Text("6-Digit OTP Code") },
                    placeholder = { Text("123456") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("otp_code_input")
                )

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = {
                        val vId = verificationId ?: return@Button
                        val e164 = formattedE164 ?: return@Button
                        if (otpCode.length != 6) {
                            errorMessage = "Enter the full 6-digit verification code."
                            return@Button
                        }
                        isLoading = true
                        errorMessage = null
                        val cred = PhoneAuthProvider.getCredential(vId, otpCode)
                        scope.launch {
                            val res = AuthManager.verifyAndLinkPhoneCredential(cred)
                            isLoading = false
                            if (res.isSuccess) {
                                onPhoneVerified(e164)
                            } else {
                                errorMessage = res.exceptionOrNull()?.localizedMessage ?: "Invalid OTP code."
                            }
                        }
                    },
                    enabled = !isLoading && otpCode.length == 6,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("verify_otp_button")
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Verify & Continue")
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            verificationId = null
                            otpCode = ""
                            errorMessage = null
                        }
                    ) {
                        Text("Change Number")
                    }

                    TextButton(
                        enabled = countdownSeconds == 0 && !isLoading && activity != null && formattedE164 != null,
                        onClick = {
                            val act = activity ?: return@TextButton
                            val e164 = formattedE164 ?: return@TextButton
                            isLoading = true
                            errorMessage = null
                            AuthManager.sendPhoneOtp(
                                activity = act,
                                e164Phone = e164,
                                forceResendingToken = resendToken,
                                onCodeSent = { vId, token ->
                                    isLoading = false
                                    verificationId = vId
                                    resendToken = token
                                    countdownSeconds = 60
                                    statusMessage = "New OTP code sent to $e164."
                                },
                                onAutoVerified = { cred ->
                                    scope.launch {
                                        val res = AuthManager.verifyAndLinkPhoneCredential(cred)
                                        isLoading = false
                                        if (res.isSuccess) onPhoneVerified(e164)
                                    }
                                },
                                onError = { msg ->
                                    isLoading = false
                                    errorMessage = msg
                                }
                            )
                        }
                    ) {
                        Text(
                            if (countdownSeconds > 0) "Resend in ${countdownSeconds}s" else "Resend OTP"
                        )
                    }
                }
            }

            if (statusMessage != null) {
                Spacer(Modifier.height(14.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = statusMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            if (errorMessage != null) {
                Spacer(Modifier.height(14.dp))
                Surface(
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = errorMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            if (smsUnavailableFallbackPhone != null && verificationId == null) {
                val fallbackPhone = smsUnavailableFallbackPhone!!
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        isLoading = true
                        errorMessage = null
                        scope.launch {
                            val taken = userRepository.isPhoneAlreadyRegisteredByAnotherUser(fallbackPhone)
                            isLoading = false
                            if (taken) {
                                errorMessage = "This phone number is already linked to another Whatacall account."
                            } else {
                                onPhoneVerified(fallbackPhone)
                            }
                        }
                    },
                    enabled = !isLoading,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("google_verified_fallback_button")
                ) {
                    Text("Continue with Google-Verified Account ($fallbackPhone)")
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "Secured by Firebase Phone Authentication",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showCountryPicker) {
        ModalBottomSheet(onDismissRequest = { showCountryPicker = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = "Select Country",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                OutlinedTextField(
                    value = countrySearch,
                    onValueChange = { countrySearch = it },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    placeholder = { Text("Search country or code") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                val filtered = remember(countrySearch) {
                    val q = countrySearch.trim().lowercase()
                    if (q.isEmpty()) CountryCatalog.all
                    else CountryCatalog.all.filter {
                        it.name.lowercase().contains(q) || it.dialCode.contains(q)
                    }
                }
                LazyColumn(modifier = Modifier.height(360.dp)) {
                    items(filtered, key = { it.iso }) { country: CountryCode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedCountry = country
                                    showCountryPicker = false
                                }
                                .padding(vertical = 14.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(country.flagEmoji, style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = country.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = country.dialCode,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}
