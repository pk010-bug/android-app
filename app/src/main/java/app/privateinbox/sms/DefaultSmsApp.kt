package app.privateinbox.sms

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony

/** Helpers for asking the user to make PrivateInbox their default SMS app. */
object DefaultSmsApp {

    fun isDefault(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = context.getSystemService(RoleManager::class.java)
            rm?.isRoleAvailable(RoleManager.ROLE_SMS) == true && rm.isRoleHeld(RoleManager.ROLE_SMS)
        } else {
            Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        }

    /**
     * Intent to launch with an ActivityResultLauncher. Returns null if the role is unavailable
     * (e.g. tablets without telephony).
     */
    fun requestRoleIntent(context: Context): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = context.getSystemService(RoleManager::class.java)
            if (rm?.isRoleAvailable(RoleManager.ROLE_SMS) == true) {
                rm.createRequestRoleIntent(RoleManager.ROLE_SMS)
            } else {
                null
            }
        } else {
            @Suppress("DEPRECATION")
            Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
        }
}
