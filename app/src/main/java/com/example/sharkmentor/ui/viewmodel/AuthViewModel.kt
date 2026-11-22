package com.example.sharkmentor.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.userProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

sealed interface AuthResultState {
    object Idle : AuthResultState
    object Loading : AuthResultState
    object Success : AuthResultState
    data class Error(val message: String) : AuthResultState
}

class AuthViewModel : ViewModel() {
    private val auth: FirebaseAuth? get() = GoogleServicesHelper.getFirebaseAuth()
    private val db: FirebaseFirestore? get() = GoogleServicesHelper.getFirebaseFirestore()

    var authState by mutableStateOf<AuthResultState>(AuthResultState.Idle)
        private set

    fun resetAuthState() {
        authState = AuthResultState.Idle
    }

    fun registerStudentProfile(
        fullName: String,
        email: String,
        grade: String,
        targetExam: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val user = GoogleServicesHelper.getCurrentUser(auth) ?: run {
            onError("Oturum açılamadı.")
            return
        }

        val database = db ?: run {
            onError("Veritabanı erişilemedi.")
            return
        }

        val cleanName = fullName.trim()
        val cleanEmail = email.trim()
        val cleanGrade = grade.trim()
        val cleanExam = targetExam.trim()
        val studentData = hashMapOf<String, Any>(
            "name" to cleanName,
            "displayName" to cleanName,
            "email" to cleanEmail,
            "grade" to cleanGrade,
            "targetExam" to cleanExam,
            "isStudying" to false,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (cleanExam.isNotBlank()) {
            studentData["targetExamTitle"] = cleanExam
        }

        val profileUpdates = userProfileChangeRequest { displayName = cleanName }
        user.updateProfile(profileUpdates)

        database.collection("users").document(user.uid)
            .set(
                hashMapOf(
                    "uid" to user.uid,
                    "name" to cleanName,
                    "email" to cleanEmail,
                    "grade" to cleanGrade,
                    "targetExam" to cleanExam,
                    "role" to "STUDENT"
                ),
                SetOptions.merge()
            )

        database.collection("students").document(user.uid)
            .set(studentData, SetOptions.merge())
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { e -> onError(e.localizedMessage ?: "Profil kaydedilemedi.") }
    }

    fun signInWithEmail(email: String, pass: String, onSuccess: () -> Unit) {
        val cleanEmail = email.trim()
        val cleanPass = pass.trim()

        if (cleanEmail.isBlank() || cleanPass.isBlank()) {
            authState = AuthResultState.Error("E-posta ve şifre boş bırakılamaz.")
            return
        }

        val authObj = auth ?: run {
            authState = AuthResultState.Error("Google Play Servisleri / Auth kullanılamıyor.")
            return
        }
        val dbObj = db ?: run {
            authState = AuthResultState.Error("Google Play Servisleri / Firestore kullanılamıyor.")
            return
        }

        authState = AuthResultState.Loading

        if (GoogleServicesHelper.getCurrentUser(authObj)?.isAnonymous == true) {
            authObj.signOut()
        }

        authObj.signInWithEmailAndPassword(cleanEmail, cleanPass)
            .addOnSuccessListener { result ->
                val uid = result.user?.uid ?: return@addOnSuccessListener

                val userRef = dbObj.collection("users").document(uid)
                userRef.get().addOnSuccessListener { snapshot ->
                    if (!snapshot.exists()) {
                        val newUser = hashMapOf(
                            "uid" to uid,
                            "email" to cleanEmail,
                            "role" to "STUDENT",
                            "createdAt" to System.currentTimeMillis()
                        )
                        userRef.set(newUser)

                        // Keep students collection for backward compatibility
                        val studentRef = dbObj.collection("students").document(uid)
                        val newStudent = hashMapOf(
                            "email" to cleanEmail,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "isStudying" to false
                        )
                        studentRef.set(newStudent, SetOptions.merge())

                        authState = AuthResultState.Success
                        onSuccess()
                    } else {
                        val role = snapshot.getString("role")
                        if (role != null && role != "STUDENT") {
                            authObj.signOut()
                            authState = AuthResultState.Error("Bu hesap bir Koç/Yönetici hesabıdır. Lütfen SharkMentor COACH uygulamasını kullanın.")
                        } else {
                            // If role is missing but document exists, or if role == STUDENT
                            if (role == null) {
                                userRef.set(hashMapOf("role" to "STUDENT"), SetOptions.merge())
                            }
                            authState = AuthResultState.Success
                            onSuccess()
                        }
                    }
                }.addOnFailureListener {
                    authState = AuthResultState.Error("Oturum kontrolü sırasında hata oluştu.")
                }
            }
            .addOnFailureListener { e ->
                val errorMsg = when (e) {
                    is FirebaseAuthInvalidUserException -> "Bu e-posta ile kayıtlı kullanıcı bulunamadı."
                    is FirebaseAuthInvalidCredentialsException -> "Hatalı şifre veya e-posta biçimi."
                    else -> e.localizedMessage ?: "Giriş yapılırken bir hata oluştu."
                }
                authState = AuthResultState.Error(errorMsg)
            }
    }

    fun signUpWithEmail(email: String, pass: String, onSuccess: () -> Unit) {
        val cleanEmail = email.trim()
        val cleanPass = pass.trim()

        if (cleanEmail.isBlank() || cleanPass.length < 6) {
            authState = AuthResultState.Error("Şifre en az 6 karakter olmalıdır.")
            return
        }

        val authObj = auth ?: run {
            authState = AuthResultState.Error("Google Play Servisleri / Auth kullanılamıyor.")
            return
        }
        val dbObj = db ?: run {
            authState = AuthResultState.Error("Google Play Servisleri / Firestore kullanılamıyor.")
            return
        }

        authState = AuthResultState.Loading

        if (GoogleServicesHelper.getCurrentUser(authObj)?.isAnonymous == true) {
            authObj.signOut()
        }

        authObj.createUserWithEmailAndPassword(cleanEmail, cleanPass)
            .addOnSuccessListener { result ->
                val uid = result.user?.uid ?: return@addOnSuccessListener
                
                val newUser = hashMapOf(
                    "uid" to uid,
                    "email" to cleanEmail,
                    "role" to "STUDENT",
                    "createdAt" to System.currentTimeMillis()
                )
                dbObj.collection("users").document(uid).set(newUser)

                val studentData = hashMapOf(
                    "email" to cleanEmail,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "isStudying" to false
                )
                dbObj.collection("students").document(uid)
                    .set(studentData, SetOptions.merge())
                    .addOnSuccessListener {
                        com.example.sharkmentor.ui.components.FirstTimeTutorialPrefs.markEligibleForNewUserOrGuest(getApplication())
                        authState = AuthResultState.Success
                        onSuccess()
                    }
                    .addOnFailureListener {
                        com.example.sharkmentor.ui.components.FirstTimeTutorialPrefs.markEligibleForNewUserOrGuest(getApplication())
                        authState = AuthResultState.Success
                        onSuccess()
                    }
            }
            .addOnFailureListener { e ->
                authState = AuthResultState.Error(e.localizedMessage ?: "Kayıt oluşturulamadı.")
            }
    }

    fun continueAsGuest(onSuccess: () -> Unit) {
        val authObj = auth ?: run {
            authState = AuthResultState.Error("Google Play Servisleri / Auth kullanılamıyor.")
            return
        }

        if (GoogleServicesHelper.getCurrentUser(authObj) != null) {
            authState = AuthResultState.Success
            onSuccess()
            return
        }

        authState = AuthResultState.Loading
        authObj.signInAnonymously()
            .addOnSuccessListener {
                authState = AuthResultState.Success
                onSuccess()
            }
            .addOnFailureListener { e ->
                authState = AuthResultState.Error(e.localizedMessage ?: "Misafir oturumu açılamadı.")
            }
    }
}
