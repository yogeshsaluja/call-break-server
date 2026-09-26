package com.yogesh.callbreak.server

import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import org.slf4j.LoggerFactory

data class AuthenticatedUser(
    val uid: String,
    val name: String?,
    val email: String?,
    val photoUrl: String? = null,
    val provider: String? = null,
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
            val required = System.getenv("REQUIRE_AUTH")?.equals("false", ignoreCase = true) != true
            val enabled = required || System.getenv("FIREBASE_AUTH_ENABLED")?.equals("true", ignoreCase = true) == true
            if (!enabled) return IdentitySecurity(verifier = null, required = false)

            check(FirebaseApp.getApps().isNotEmpty() || FirebaseApp.initializeApp() != null) {
                "Firebase Admin could not initialize. Configure GOOGLE_APPLICATION_CREDENTIALS."
            }
            val log = LoggerFactory.getLogger("IdentitySecurity")
            val verifier = IdentityTokenVerifier { token ->
                runCatching { FirebaseAuth.getInstance().verifyIdToken(token, false) }
                    .onFailure { error ->
                        log.warn(
                            "Firebase ID-token verification failed type={} message={} causeType={} causeMessage={}",
                            error.javaClass.simpleName,
                            error.message,
                            error.cause?.javaClass?.simpleName ?: "none",
                            error.cause?.message ?: "none",
                        )
                    }
                    .getOrNull()
                    ?.let { decoded ->
                        @Suppress("UNCHECKED_CAST")
                        val firebaseClaims = decoded.claims["firebase"] as? Map<String, Any?>
                        AuthenticatedUser(
                            uid = decoded.uid,
                            name = decoded.name?.take(SecurityPolicy.MAX_NAME_LENGTH),
                            email = decoded.email,
                            photoUrl = decoded.picture,
                            provider = firebaseClaims?.get("sign_in_provider") as? String,
                        )
                    }
            }
            return IdentitySecurity(verifier, required)
        }
    }
}
