package com.example.data.auth

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

internal fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        trySend(firebaseAuth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

object AuthManager {
    private const val TAG = "WhatacallAuth"

    fun attemptAutoSignIn(
        context: Context,
        credentialManager: CredentialManager,
        onAuthSuccess: () -> Unit,
        onUnauthenticated: () -> Unit,
        scope: CoroutineScope
    ) {
        if (Firebase.auth.currentUser != null) {
            onAuthSuccess()
            return
        }
        val clientId = resolveWebClientId(context)
        if (clientId == null) {
            onUnauthenticated()
            return
        }

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(true)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        scope.launch {
            try {
                val result = credentialManager.getCredential(context, request)
                val credential = result.credential
                if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                    val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                    Firebase.auth.signInWithCredential(authCredential).await()
                    onAuthSuccess()
                } else {
                    onUnauthenticated()
                }
            } catch (_: Exception) {
                onUnauthenticated()
            }
        }
    }

    fun onGoogleSignInClicked(
        context: Context,
        credentialManager: CredentialManager,
        onAuthSuccess: () -> Unit,
        onAuthError: (String) -> Unit,
        scope: CoroutineScope,
        onAuthCancelled: () -> Unit = {}
    ) {
        val clientId = resolveWebClientId(context)
        if (clientId == null) {
            onAuthError("Google Sign-In configuration missing: default_web_client_id not found")
            return
        }

        val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInOption)
            .build()

        scope.launch {
            try {
                val activity = context as? Activity
                    ?: throw IllegalStateException("Google Sign-In requires an Activity context.")
                val result = credentialManager.getCredential(activity, request)
                val credential = result.credential
                if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                    val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                    Firebase.auth.signInWithCredential(authCredential).await()
                    onAuthSuccess()
                } else {
                    onAuthError("Unexpected credential type received.")
                }
            } catch (e: GetCredentialCancellationException) {
                Log.w(TAG, "Google Sign-In flow cancelled or dismissed: ${e.message}", e)
                onAuthCancelled()
            } catch (e: Exception) {
                Log.e(TAG, "Google Sign-In failed", e)
                onAuthError(e.localizedMessage ?: "Google Sign-In failed")
            }
        }
    }

    fun sendPhoneOtp(
        activity: Activity,
        e164Phone: String,
        forceResendingToken: PhoneAuthProvider.ForceResendingToken? = null,
        onCodeSent: (verificationId: String, token: PhoneAuthProvider.ForceResendingToken) -> Unit,
        onAutoVerified: (PhoneAuthCredential) -> Unit,
        onError: (String) -> Unit,
        onBillingOrSmsUnavailable: ((String) -> Unit)? = null
    ) {
        try {
            SmsRetriever.getClient(activity).startSmsRetriever()
        } catch (e: Exception) {
            Log.d(TAG, "SMS Retriever unavailable: ${e.message}")
        }

        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                onAutoVerified(credential)
            }

            override fun onVerificationFailed(e: FirebaseException) {
                Log.e(TAG, "Phone OTP verification failed", e)
                val errorCode = (e as? FirebaseAuthException)?.errorCode
                val rawMsg = generateSequence(e as Throwable) { it.cause }
                    .mapNotNull { it.message }
                    .joinToString(" | ")
                val isBillingOrSmsDisabled =
                    rawMsg.contains("BILLING_NOT_ENABLED", ignoreCase = true) ||
                        rawMsg.contains("QUOTA_EXCEEDED", ignoreCase = true) ||
                        errorCode == "ERROR_OPERATION_NOT_ALLOWED" ||
                        rawMsg.contains("not enabled", ignoreCase = true)

                if (isBillingOrSmsDisabled) {
                    val friendlyBillingMsg =
                        "Firebase SMS OTP is blocked on the free Spark plan (BILLING_NOT_ENABLED). " +
                            "Enable the Blaze plan or add test numbers in Firebase Console for SMS OTP, " +
                            "or tap \"Continue with Google-Verified Account\" below."
                    if (onBillingOrSmsUnavailable != null) {
                        onBillingOrSmsUnavailable(friendlyBillingMsg)
                    } else {
                        onError(friendlyBillingMsg)
                    }
                    return
                }

                val friendly = when {
                    rawMsg.contains("TOO_MANY_REQUESTS", ignoreCase = true) ->
                        "Too many OTP attempts. Please wait before requesting another verification code."
                    rawMsg.contains("INVALID_PHONE_NUMBER", ignoreCase = true) ->
                        "Please enter a valid mobile phone number with country code."
                    rawMsg.contains("APP_NOT_AUTHORIZED", ignoreCase = true) ||
                        rawMsg.contains("INVALID_APP_CREDENTIAL", ignoreCase = true) ->
                        "App SHA-1/Play Integrity verification mismatch in Firebase Console."
                    else -> e.localizedMessage ?: "Failed to send OTP via Firebase Phone Auth."
                }
                onError(friendly)
            }

            override fun onCodeSent(
                verificationId: String,
                token: PhoneAuthProvider.ForceResendingToken
            ) {
                onCodeSent(verificationId, token)
            }
        }

        val builder = PhoneAuthOptions.newBuilder(Firebase.auth)
            .setPhoneNumber(e164Phone)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(callbacks)
        if (forceResendingToken != null) {
            builder.setForceResendingToken(forceResendingToken)
        }
        PhoneAuthProvider.verifyPhoneNumber(builder.build())
    }

    suspend fun verifyAndLinkPhoneCredential(credential: PhoneAuthCredential): Result<String> {
        return try {
            val user = Firebase.auth.currentUser
                ?: return Result.failure(IllegalStateException("Sign in with Google first before linking phone number."))
            try {
                user.updatePhoneNumber(credential).await()
                withTimeoutOrNull(2_500L) {
                    user.getIdToken(true).await()
                }
            } catch (e: FirebaseAuthException) {
                if (e.errorCode == "ERROR_CREDENTIAL_ALREADY_IN_USE") {
                    return Result.failure(
                        IllegalStateException("This phone number is already linked to another Whatacall account.")
                    )
                }
                throw e
            }
            Result.success(user.phoneNumber ?: "")
        } catch (e: Exception) {
            Log.e(TAG, "Phone credential linking failed", e)
            Result.failure(e)
        }
    }

    fun signOut(
        credentialManager: CredentialManager,
        scope: CoroutineScope,
        onSignOutComplete: () -> Unit = {}
    ) {
        scope.launch {
            withContext(NonCancellable) {
                try {
                    credentialManager.clearCredentialState(ClearCredentialStateRequest())
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clear credential state: ${e.message}")
                } finally {
                    Firebase.auth.signOut()
                    onSignOutComplete()
                }
            }
        }
    }

    private fun resolveWebClientId(context: Context): String? {
        val resId = context.resources.getIdentifier(
            "default_web_client_id",
            "string",
            context.packageName
        )
        if (resId == 0) return null
        return runCatching { context.getString(resId) }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
