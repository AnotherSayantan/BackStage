package dev.legendsayantan.backstage.core.auth

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import dev.legendsayantan.backstage.core.platform.PlatformContext
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

actual open class AuthGate actual constructor(
    private val context: PlatformContext,
) {
    actual open suspend fun authenticate(reason: String): AuthResult = withContext(Dispatchers.Main) {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

        val biometricManager = BiometricManager.from(context.androidContext)
        val canAuth = biometricManager.canAuthenticate(authenticators)
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            return@withContext AuthResult.Unavailable
        }

        val activity = context.activityProvider()
            ?: return@withContext AuthResult.Failure("No foreground FragmentActivity available for BiometricPrompt")

        suspendCancellableCoroutine { cont ->
            val executor = ContextCompat.getMainExecutor(activity)
            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (cont.isActive) cont.resume(AuthResult.Success)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (cont.isActive) cont.resume(AuthResult.Failure(errString.toString()))
                    }

                    override fun onAuthenticationFailed() {
                        // BiometricPrompt stays open and allows retry until error/cancel
                    }
                }
            )

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("BackStage Security Gate")
                .setSubtitle(reason)
                .setAllowedAuthenticators(authenticators)
                .build()

            prompt.authenticate(promptInfo)
            cont.invokeOnCancellation { prompt.cancelAuthentication() }
        }
    }
}
