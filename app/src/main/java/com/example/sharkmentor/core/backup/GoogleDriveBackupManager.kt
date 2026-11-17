package com.example.sharkmentor.core.backup

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Collections

data class TrainingBackupPayload(
    val timestamp: Long = System.currentTimeMillis(),
    val appVersion: String = "2.0.0",
    val foldersJson: String,
    val questionsJson: String = "[]" // Folders with their question metadata
)

class DriveConsentRequiredException(val consentIntent: Intent) : Exception("Google Drive izni gerekiyor.")

class GoogleDriveBackupManager(
    private val context: Context,
    private val gson: Gson = Gson()
) {
    val driveScope = Scope(DriveScopes.DRIVE_FILE)

    fun getSignInOptions(): GoogleSignInOptions {
        return GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(driveScope)
            .build()
    }

    private fun getDriveService(account: GoogleSignInAccount): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(
            context, Collections.singleton(DriveScopes.DRIVE_FILE)
        ).apply { selectedAccount = account.account }

        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("SharkMentor Study").build()
    }

    suspend fun uploadBackupToDrive(account: GoogleSignInAccount, payload: TrainingBackupPayload): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            try {
                val service = getDriveService(account)
                val jsonString = gson.toJson(payload)
                val contentStream = ByteArrayContent("application/json", jsonString.toByteArray(Charsets.UTF_8))

                // Check if backup already exists
                val fileList = service.files().list()
                    .setQ("name = 'sharkmentor_cloud_backup.json' and trashed = false")
                    .execute()

                val existingFile = fileList.files?.firstOrNull()
                if (existingFile != null) {
                    service.files().update(existingFile.id, null, contentStream).execute()
                    Log.d("DriveBackup", "Existing backup successfully updated in Drive.")
                } else {
                    val metadata = File().apply {
                        name = "sharkmentor_cloud_backup.json"
                    }
                    service.files().create(metadata, contentStream).execute()
                    Log.d("DriveBackup", "New backup successfully created in Drive.")
                }
                Unit
            } catch (e: UserRecoverableAuthException) {
                Log.w("DriveBackup", "User consent required for Drive scope: ${e.message}")
                throw DriveConsentRequiredException(e.intent ?: Intent())
            } catch (e: Exception) {
                Log.e("DriveBackup", "Failed to upload to Drive", e)
                throw e
            }
        }
    }

    suspend fun downloadBackupFromDrive(account: GoogleSignInAccount): Result<TrainingBackupPayload> = withContext(Dispatchers.IO) {
        runCatching {
            try {
                val service = getDriveService(account)
                val fileList = service.files().list()
                    .setQ("name = 'sharkmentor_cloud_backup.json' and trashed = false")
                    .execute()

                val file = fileList.files?.firstOrNull() ?: throw NoSuchElementException("Drive üzerinde kayıtlı yedek bulunamadı.")
                val outputStream = ByteArrayOutputStream()
                service.files().get(file.id).executeMediaAndDownloadTo(outputStream)
                val jsonString = outputStream.toString("UTF-8")
                gson.fromJson(jsonString, TrainingBackupPayload::class.java)
            } catch (e: UserRecoverableAuthException) {
                Log.w("DriveBackup", "User consent required for Drive download: ${e.message}")
                throw DriveConsentRequiredException(e.intent ?: Intent())
            } catch (e: Exception) {
                Log.e("DriveBackup", "Failed to download from Drive", e)
                throw e
            }
        }
    }
}
