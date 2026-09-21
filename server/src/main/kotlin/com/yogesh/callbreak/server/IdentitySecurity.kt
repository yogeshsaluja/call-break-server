package com.yogesh.callbreak.server

import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth

data class AuthenticatedUser(
    val uid: String,
    val name: String?,
    val email: String?,
)

fun interface IdentityTokenVerifier {
    fun verify(token: String): AuthenticatedUser?
}

data class IdentitySecurity(
    val verifier: IdentityTokenVerifier?,
    val required: Boolean,
) {
    companion object {
        fun fromEnvironment(): IdentitySecurity {
            val required = System.getenv("REQUIRE_AUTH")?.equals("true", ignoreCase = true) == true
            val enabled = required || System.getenv("FIREBASE_AUTH_ENABLED")?.equals("true", ignoreCase = true) == true
            if (!enabled) return IdentitySecurity(verifier = null, required = false)

            check(FirebaseApp.getApps().isNotEmpty() || FirebaseApp.initializeApp() != null) {
                "Firebase Admin could not initialize. Configure GOOGLE_APPLICATION_CREDENTIALS."
            }
            val verifier = IdentityTokenVerifier { token ->
                runCatching { FirebaseAuth.getInstance().verifyIdToken(token, true) }
                    .getOrNull()
                    ?.let { decoded ->
                        AuthenticatedUser(
                            uid = decoded.uid,
                            name = decoded.name?.take(SecurityPolicy.MAX_NAME_LENGTH),
                            email = decoded.email,
                        )
                    }
            }
            return IdentitySecurity(verifier, required)
        }
    }
}
