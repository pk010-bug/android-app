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
        Workers.schedulePeriodic(this)
        Workers.reconcileScheduled(this)
    }
}
