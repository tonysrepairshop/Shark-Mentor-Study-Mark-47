package com.example.sharkmentor.domain.usecase

import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

class RedeemCodeUseCase @Inject constructor() {
    private val db get() = GoogleServicesHelper.getFirebaseFirestore()
    private val auth get() = GoogleServicesHelper.getFirebaseAuth()

    suspend operator fun invoke(code: String): Result<Unit> {
        return try {
            val uid = GoogleServicesHelper.getCurrentUser(auth)?.uid
                ?: return Result.failure(Exception("Oturum bulunamadı."))
            val firestore = db ?: return Result.failure(Exception("Veritabanı erişilemedi."))

            val codeRef = firestore.collection("institution_codes").document(code)
            val userRef = firestore.collection("users").document(uid)

            firestore.runTransaction { transaction ->
                val codeSnapshot = transaction.get(codeRef)

                if (!codeSnapshot.exists()) {
                    throw Exception("Geçersiz kod.")
                }

                val isUsed = codeSnapshot.getBoolean("isUsed") ?: false
                if (isUsed) {
                    throw Exception("Bu kod daha önce kullanılmış.")
                }

                val institutionId = codeSnapshot.getString("institutionId")
                val coachId = codeSnapshot.getString("coachId")

                // Update code
                transaction.update(
                    codeRef,
                    mapOf(
                        "isUsed" to true,
                        "usedByStudentId" to uid
                    )
                )

                // Update user
                transaction.set(
                    userRef,
                    mapOf(
                        "assignedCoachId" to coachId,
                        "institutionId" to institutionId
                    ),
                    SetOptions.merge()
                )
            }.await()
            Result.success(Unit)
        } catch (e: Throwable) {
            Result.failure(e as? Exception ?: Exception(e.message))
        }
    }
}
