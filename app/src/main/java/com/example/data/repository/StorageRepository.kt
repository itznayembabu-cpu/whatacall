package com.example.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.util.UUID

class StorageRepository(
    private val storage: FirebaseStorage = FirebaseStorage.getInstance()
) {
    suspend fun uploadMedia(
        uid: String,
        folder: String,
        uri: Uri,
        extension: String = "bin"
    ): Result<String> {
        return try {
            val cleanExt = extension.ifBlank { "bin" }.take(10)
            val fileName = "${UUID.randomUUID()}.$cleanExt"
            val ref = storage.reference.child("users/$uid/$folder/$fileName")
            ref.putFile(uri).await()
            val url = ref.downloadUrl.await().toString()
            Result.success(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("StorageRepository", "Media upload failed for $folder", e)
            Result.failure(
                IllegalStateException(
                    "Firebase Storage upload failed (${e.localizedMessage ?: "check Storage rules & bucket setup in Firebase Console"}).",
                    e
                )
            )
        }
    }

    fun inferExtension(context: Context, uri: Uri, fallback: String): String {
        val mime = context.contentResolver.getType(uri) ?: return fallback
        return when {
            mime.contains("png") -> "png"
            mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
            mime.contains("webp") -> "webp"
            mime.contains("mp4") -> "mp4"
            mime.contains("3gpp") -> "3gp"
            mime.contains("m4a") || mime.contains("mp4a") -> "m4a"
            mime.contains("pdf") -> "pdf"
            else -> fallback
        }
    }
}
