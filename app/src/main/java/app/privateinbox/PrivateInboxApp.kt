package app.privateinbox

import android.app.Application
import app.privateinbox.work.Workers

/**
 * Application entry point. Everything here is local: no SDK initialisation, no telemetry.
 */
class PrivateInboxApp : Application() {

    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // F-12: home country for phone-number grouping (no permission needed; null if unknown).
        app.privateinbox.domain.AddressNormalizer.homeCallingCode = runCatching {
            val tm = getSystemService(android.telephony.TelephonyManager::class.java)
            listOf(tm?.simCountryIso, tm?.networkCountryIso, java.util.Locale.getDefault().country)
                .firstNotNullOfOrNull { app.privateinbox.domain.AddressNormalizer.callingCodeFor(it) }
        }.getOrNull()
        Workers.schedulePeriodic(this)
        Workers.reconcileScheduled(this)
    }
}
