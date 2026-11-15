package com.example.core.crypto

import android.content.Context
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Real End-to-End Encryption (E2EE) engine using ECDH (secp256r1 / P-256) key agreement,
 * HKDF-SHA256 key derivation, and AES-256-GCM authenticated encryption.
 *
 * Also provides SHA-256 phone number hashing and phone number masking for privacy-preserving
 * contact matching without storing or listing raw phone numbers in public Firestore collections.
 */
object E2eeCryptoManager {
    private const val PREFS_NAME = "whatacall_e2ee_keys"
    private const val KEY_PRIVATE = "ecdh_private_pkcs8"
    private const val KEY_PUBLIC = "ecdh_public_x509"
    private const val GCM_IV_LEN = 12
    private const val GCM_TAG_BITS = 128

    private val secureRandom = SecureRandom()

    data class KeyPairEncoded(
        val publicKeyBase64: String,
        val privateKeyBase64: String
    )

    @Synchronized
    fun getOrCreateIdentityKeyPair(context: Context, uid: String): KeyPairEncoded {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val privKeyName = "${KEY_PRIVATE}_$uid"
        val pubKeyName = "${KEY_PUBLIC}_$uid"
        val existingPriv = prefs.getString(privKeyName, null)
        val existingPub = prefs.getString(pubKeyName, null)
        if (!existingPriv.isNullOrBlank() && !existingPub.isNullOrBlank()) {
            val validStored = runCatching {
                val privBytes = Base64.decode(existingPriv, Base64.NO_WRAP)
                val pubBytes = Base64.decode(existingPub, Base64.NO_WRAP)
                privBytes.isNotEmpty() && pubBytes.isNotEmpty()
            }.getOrDefault(false)
            if (validStored) {
                return KeyPairEncoded(existingPub, existingPriv)
            }
        }
        val encoded = generateEcP256KeyPair()
        prefs.edit()
            .putString(pubKeyName, encoded.publicKeyBase64)
            .putString(privKeyName, encoded.privateKeyBase64)
            .apply()
        return encoded
    }

    fun generateEphemeralKeyPair(): KeyPairEncoded = generateEcP256KeyPair()

    private fun generateEcP256KeyPair(): KeyPairEncoded {
        val kpg = KeyPairGenerator.getInstance("EC")
        val initAttempts = listOf<() -> Unit>(
            { kpg.initialize(ECGenParameterSpec("secp256r1"), secureRandom) },
            { kpg.initialize(ECGenParameterSpec("prime256v1"), secureRandom) },
            { kpg.initialize(256, secureRandom) }
        )
        var lastError: Throwable? = null
        for (attempt in initAttempts) {
            try {
                attempt()
                val kp = kpg.generateKeyPair()
                return KeyPairEncoded(
                    publicKeyBase64 = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP),
                    privateKeyBase64 = Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP)
                )
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw IllegalStateException("Device EC P-256 key generation failed: ${lastError?.localizedMessage ?: "unknown error"}", lastError)
    }

    fun deriveSharedAesKey(
        myPrivateKeyBase64: String,
        peerPublicKeyBase64: String,
        contextInfo: String
    ): SecretKey {
        val kf = KeyFactory.getInstance("EC")
        val privBytes = Base64.decode(myPrivateKeyBase64, Base64.NO_WRAP)
        val pubBytes = Base64.decode(peerPublicKeyBase64, Base64.NO_WRAP)
        val privateKey: PrivateKey = kf.generatePrivate(PKCS8EncodedKeySpec(privBytes))
        val publicKey: PublicKey = kf.generatePublic(X509EncodedKeySpec(pubBytes))

        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(publicKey, true)
        val sharedSecret = ka.generateSecret()

        // HKDF-SHA256 Extract + Expand (32-byte AES-256 key)
        val salt = ByteArray(32)
        val prkMac = Mac.getInstance("HmacSHA256")
        prkMac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = prkMac.doFinal(sharedSecret)

        val expandMac = Mac.getInstance("HmacSHA256")
        expandMac.init(SecretKeySpec(prk, "HmacSHA256"))
        val infoBytes = contextInfo.toByteArray(Charsets.UTF_8)
        expandMac.update(infoBytes)
        expandMac.update(0x01.toByte())
        val okm = expandMac.doFinal().copyOf(32)
        return SecretKeySpec(okm, "AES")
    }

    fun encryptText(
        plaintext: String,
        myPrivateKeyBase64: String,
        peerPublicKeyBase64: String,
        chatId: String
    ): String {
        val aesKey = deriveSharedAesKey(myPrivateKeyBase64, peerPublicKeyBase64, "whatacall-e2ee-$chatId")
        val iv = ByteArray(GCM_IV_LEN)
        secureRandom.nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(chatId.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteBuffer.allocate(iv.size + ciphertext.size)
            .put(iv)
            .put(ciphertext)
            .array()
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decryptText(
        encryptedBase64: String,
        myPrivateKeyBase64: String,
        peerPublicKeyBase64: String,
        chatId: String
    ): String? {
        return try {
            val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
            if (combined.size <= GCM_IV_LEN) return null
            val iv = combined.copyOfRange(0, GCM_IV_LEN)
            val ciphertext = combined.copyOfRange(GCM_IV_LEN, combined.size)
            val aesKey = deriveSharedAesKey(myPrivateKeyBase64, peerPublicKeyBase64, "whatacall-e2ee-$chatId")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(chatId.toByteArray(Charsets.UTF_8))
            val plainBytes = cipher.doFinal(ciphertext)
            String(plainBytes, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun maskPhone(e164: String): String {
        val clean = e164.trim()
        if (clean.length <= 6) return clean
        val prefix = clean.take(6)
        val suffix = clean.takeLast(2)
        val stars = "•".repeat((clean.length - 8).coerceIn(3, 6))
        return "$prefix$stars$suffix"
    }
}
