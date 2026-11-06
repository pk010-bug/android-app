package app.privateinbox.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager

/**
 * Dual-SIM awareness. Lists active SIMs (needs READ_PHONE_STATE, requested only when the user
 * opens the SIM picker). Never reads phone numbers or identifiers, only slot + carrier label.
 */
class SimManager(private val context: Context) {

    data class Sim(val subscriptionId: Int, val slotIndex: Int, val label: String)

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    fun activeSims(): List<Sim> {
        if (!hasPermission()) return emptyList()
        val sm = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        return try {
            sm.activeSubscriptionInfoList.orEmpty().map {
                Sim(it.subscriptionId, it.simSlotIndex, "SIM ${it.simSlotIndex + 1} · ${it.displayName ?: it.carrierName ?: ""}".trimEnd(' ', '·'))
            }.sortedBy { it.slotIndex }
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    fun labelFor(subscriptionId: Int?): String? =
        subscriptionId?.let { id -> activeSims().firstOrNull { it.subscriptionId == id }?.let { "SIM ${it.slotIndex + 1}" } }
}
