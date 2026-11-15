package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.core.crypto.E2eeCryptoManager
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class WhatacallRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun registerProfile_and_preventDuplicatePhoneRegistration() = runBlocking {
        val aliceUid = signInTestUser("alice_${UUID.randomUUID()}@whatacall.test")
        val aliceKeys = E2eeCryptoManager.generateEphemeralKeyPair()
        val userRepo = UserRepository(firestore)
        val uniquePhone = "+88017${(10000000..99999999).random()}"

        val res = withTimeout(TIMEOUT_MS) {
            userRepo.registerOrUpdatePhoneAndProfile(
                displayName = "Alice Rahman",
                about = "Available",
                username = "alice_${UUID.randomUUID().toString().take(6)}",
                photoUrl = "",
                verifiedE164Phone = uniquePhone,
                publicKeyBase64 = aliceKeys.publicKeyBase64,
                email = "alice@whatacall.test"
            )
        }
        assertTrue("Alice registration failed: ${res.exceptionOrNull()}", res.isSuccess)

        val fetched = withTimeout(TIMEOUT_MS) { userRepo.getUserProfile(aliceUid) }
        assertNotNull(fetched)
        assertEquals("Alice Rahman", fetched?.displayName)

        // Bob tries to register the exact same phone number -> blocked!
        signInTestUser("bob_${UUID.randomUUID()}@whatacall.test")
        val bobKeys = E2eeCryptoManager.generateEphemeralKeyPair()
        val dupRes = withTimeout(TIMEOUT_MS) {
            userRepo.registerOrUpdatePhoneAndProfile(
                displayName = "Bob Impostor",
                about = "",
                username = "",
                photoUrl = "",
                verifiedE164Phone = uniquePhone,
                publicKeyBase64 = bobKeys.publicKeyBase64,
                email = "bob@whatacall.test"
            )
        }
        assertTrue("Duplicate phone registration should fail", dupRes.isFailure)
    }

    @Test
    fun directChat_and_e2eeMessage_roundtrip_and_nonParticipantDenied() = runBlocking {
        val aliceEmail = "alice_chat_${UUID.randomUUID()}@whatacall.test"
        val charlieEmail = "charlie_chat_${UUID.randomUUID()}@whatacall.test"
        val aliceUid = signInTestUser(aliceEmail)
        val aliceKeys = E2eeCryptoManager.generateEphemeralKeyPair()
        val bobUid = signInTestUser("bob_chat_${UUID.randomUUID()}@whatacall.test")
        val bobKeys = E2eeCryptoManager.generateEphemeralKeyPair()
        val charlieUid = signInTestUser(charlieEmail)

        // Switch back to Alice to create chat and send E2EE message
        auth.signOut()
        val realAliceUid = signInTestUser(aliceEmail)
        assertEquals(aliceUid, realAliceUid)

        val chatRepo = ChatRepository(firestore)
        val chatId = withTimeout(TIMEOUT_MS) { chatRepo.getOrCreateDirectChat(bobUid).getOrThrow() }
        val chat = withTimeout(FLOW_TIMEOUT_MS) { chatRepo.observeChat(chatId).first { it != null } }!!

        val secretMsg = "Assalamu Alaikum! Meet at Dhanmondi at 5 PM."
        val sendRes = withTimeout(TIMEOUT_MS) {
            chatRepo.sendMessage(
                chat = chat,
                senderName = "Alice",
                type = "text",
                plainText = secretMsg,
                myPrivateKeyBase64 = aliceKeys.privateKeyBase64,
                peerPublicKeyBase64 = bobKeys.publicKeyBase64
            )
        }
        assertTrue("Send message failed: ${sendRes.exceptionOrNull()}", sendRes.isSuccess)

        val msgs = withTimeout(FLOW_TIMEOUT_MS) {
            chatRepo.observeMessages(chatId, realAliceUid).first { it.isNotEmpty() }
        }
        val first = msgs.first()
        assertEquals(1L, first.encryptionVersion)
        assertTrue(first.encryptedPayload.isNotBlank())
        assertFalse(first.text.contains("Dhanmondi"))

        // Bob decrypts E2EE message
        val decrypted = E2eeCryptoManager.decryptText(
            encryptedBase64 = first.encryptedPayload,
            myPrivateKeyBase64 = bobKeys.privateKeyBase64,
            peerPublicKeyBase64 = aliceKeys.publicKeyBase64,
            chatId = chatId
        )
        assertEquals(secretMsg, decrypted)

        // Charlie (non-participant) cannot see messages and gets PERMISSION_DENIED on chat or Alice's messages
        val realCharlieUid = signInTestUser(charlieEmail)
        assertEquals(charlieUid, realCharlieUid)
        val charlieFiltered = withTimeout(FLOW_TIMEOUT_MS) {
            chatRepo.observeMessages(chatId, realCharlieUid).first()
        }
        assertTrue("Non-participant must see zero messages", charlieFiltered.isEmpty())

        var deniedQuery = false
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                chatRepo.observeMessages(chatId, realAliceUid).first()
            }
        } catch (e: Exception) {
            val ffe = e.unwrapFirestoreException()
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, ffe?.code)
            deniedQuery = true
        }
        assertTrue("Non-participant querying Alice's messages should receive PERMISSION_DENIED", deniedQuery)

        var deniedChat = false
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                chatRepo.observeChat(chatId).first()
            }
        } catch (e: Exception) {
            val ffe = e.unwrapFirestoreException()
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, ffe?.code)
            deniedChat = true
        }
        assertTrue("Non-participant reading chat doc should receive PERMISSION_DENIED", deniedChat)
    }

    @Test
    fun unauthenticated_observeChats_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val chatRepo = ChatRepository(firestore)
        var denied = false
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                chatRepo.observeChats("any_uid").first()
            }
        } catch (e: Exception) {
            val ffe = e.unwrapFirestoreException()
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, ffe?.code)
            denied = true
        }
        assertTrue("Unauthenticated caller should receive PERMISSION_DENIED", denied)
    }

    @Test
    fun callSession_and_statusLifecycle_workWithSecurityRules() = runBlocking {
        val aliceUid = signInTestUser("alice_call_${UUID.randomUUID()}@whatacall.test")
        val bobUid = "bob_peer_${UUID.randomUUID().toString().take(8)}"

        val callRepo = CallRepository(firestore)
        val callId = withTimeout(TIMEOUT_MS) {
            callRepo.createCallSession(
                callerName = "Alice",
                callerPhotoUrl = "",
                calleeId = bobUid,
                calleeName = "Bob",
                calleePhotoUrl = "",
                callType = "video",
                offerSdp = "v=0\r\no=- 123 2 IN IP4 127.0.0.1"
            ).getOrThrow()
        }
        assertTrue(callId.isNotBlank())

        val endRes = withTimeout(TIMEOUT_MS) {
            callRepo.updateCallStatus(callId, "ended", durationSeconds = 15L)
        }
        assertTrue(endRes.isSuccess)

        val statusRepo = StatusRepository(firestore)
        val statusId = withTimeout(TIMEOUT_MS) {
            statusRepo.postStatus(
                userName = "Alice",
                userPhotoUrl = "",
                type = "text",
                textContent = "Testing Whatacall Status!",
                privacy = "everyone"
            ).getOrThrow()
        }
        val activeStatuses = withTimeout(FLOW_TIMEOUT_MS) {
            statusRepo.observeActiveStatuses(aliceUid).first { list -> list.any { it.id == statusId } }
        }
        assertTrue(activeStatuses.any { it.id == statusId })
        assertTrue(withTimeout(TIMEOUT_MS) { statusRepo.deleteStatus(statusId) }.isSuccess)
    }

    private companion object {
        const val TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 4000L
    }
}
