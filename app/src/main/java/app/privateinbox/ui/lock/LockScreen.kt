package app.privateinbox.ui.lock

import android.app.KeyguardManager
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.privateinbox.security.AppLockKey

/**
 * App lock (F-02 hardening):
 *  - Class 3 (strong) biometrics or the device PIN/pattern/password only. Class 2 ("weak", e.g.
 *    2D face unlock on some phones) is not accepted on Android 11+.
 *  - On Android 11+ unlocking also needs an auth-bound Keystore key ([AppLockKey]) to work.
 *  - [requireFreshAuth] re-verifies the user before security-sensitive changes even inside an
 *    unlocked session (disabling the lock, lock settings, backup export/restore).
 */
object AppLock {
    /** Android 11+: strong biometrics or device credential. */
    const val AUTHENTICATORS = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

    /**
     * Android 8-10: androidx.biometric does not support STRONG | DEVICE_CREDENTIAL (or credential
     * alone) before API 30, so the only combination that still offers the PIN fallback is
     * WEAK | DEVICE_CREDENTIAL. Those releases predate the Class 2/3 split; documented residual risk.
     */
    private const val LEGACY_AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    val authenticators: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) AUTHENTICATORS else LEGACY_AUTHENTICATORS

    /** True if the device has a screen lock / biometric we can use. */
    fun available(activity: FragmentActivity): Boolean =
        BiometricManager.from(activity).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
        title: String = "Unlock PrivateInbox",
        subtitle: String = "Your messages are protected",
    ) {
        val cipher = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) AppLockKey.cipherOrNull() else null
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    // With the Keystore gate, success counts only if the auth-bound key really works.
                    if (cipher == null || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && AppLockKey.verify(result.cryptoObject?.cipher))) {
                        onSuccess()
                    } else {
                        onError("Couldn't verify it's you. Try again.")
                    }
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onError(errString.toString())
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(authenticators)
            .build()
        if (cipher != null) prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher)) else prompt.authenticate(info)
    }

    /**
     * Runs [onOk] only after a fresh authentication, for security-sensitive actions inside an
     * unlocked session. If the phone has no secure screen lock at all there is nothing to verify
     * against (and app lock can't be on), so the action proceeds.
     */
    fun requireFreshAuth(activity: FragmentActivity?, reason: String, onError: (String) -> Unit = {}, onOk: () -> Unit) {
        if (activity == null) return onError("Couldn't verify it's you.")
        val secure = activity.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
        if (!secure) return onOk()
        prompt(activity, onOk, onError, title = "Confirm it's you", subtitle = reason)
    }
}

@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val activity = LocalContext.current as FragmentActivity
    var error by remember { mutableStateOf<String?>(null) }
    val unlock = { AppLock.prompt(activity, onSuccess = onUnlocked, onError = { error = it }) }
    LaunchedEffect(Unit) { unlock() }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Text("PrivateInbox is locked", style = MaterialTheme.typography.headlineSmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = unlock) { Text("Unlock") }
    }
}
