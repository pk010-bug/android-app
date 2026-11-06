package app.privateinbox

import android.app.Application
import app.privateinbox.data.db.PrivateInboxDatabase
import app.privateinbox.data.prefs.SettingsStore
import app.privateinbox.data.rules.AssetRuleLoader
import app.privateinbox.data.rules.RuleEngine

/**
 * Application entry point and tiny manual DI container.
 * Everything here is local: no SDK initialisation, no telemetry.
 */
class PrivateInboxApp : Application() {

    val database: PrivateInboxDatabase by lazy { PrivateInboxDatabase.get(this) }
    val settings: SettingsStore by lazy { SettingsStore(this) }
    val ruleEngine: RuleEngine by lazy { AssetRuleLoader.loadDefault(this) }
}
