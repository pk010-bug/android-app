package app.privateinbox.policy

import app.privateinbox.TestFiles
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Never send without a tap": only the composer UI may send; only SmsSender may touch SmsManager. */
class SendPathTest {
    private fun filesMentioning(token: Regex) =
        TestFiles.mainSources().filter { f -> f.readLines().any { l -> !l.trimStart().startsWith("*") && !l.trimStart().startsWith("//") && token.containsMatchIn(l) } }

    @Test fun smsManagerOnlyInSmsSender() {
        val users = filesMentioning(Regex("""\bSmsManager\b""")).map { it.name }.toSet()
        assertTrue("SmsManager used outside SmsSender: $users", users == setOf("SmsSender.kt"))
    }

    @Test fun sendConfirmationOnlyMintedByComposer() {
        val minters = filesMentioning(Regex("""UserSendConfirmation\.fromSendButton\(""")).map { it.invariantSeparatorsPath }
        assertTrue("fromSendButton() outside ui/compose: $minters", minters.isNotEmpty() && minters.all { "/ui/compose/" in it })
    }

    @Test fun smsSenderOnlyUsedByComposerAndScheduledWorker() {
        val users = filesMentioning(Regex("""\bSmsSender\b"""))
            .map { it.invariantSeparatorsPath }
            .filterNot { it.endsWith("/sms/SmsSender.kt") }
        assertTrue(
            "SmsSender referenced outside ui/compose + ScheduledSendWorker: $users",
            users.all { "/ui/compose/" in it || it.endsWith("/sms/ScheduledSendWorker.kt") },
        )
    }

    @Test fun scheduledSendOnlyFromWorker() {
        val users = filesMentioning(Regex("""\.sendScheduled\(""")).map { it.name }.toSet()
        assertTrue("sendScheduled() called outside ScheduledSendWorker: $users", users == setOf("ScheduledSendWorker.kt"))
    }

    @Test fun scheduledRowsAreSignedAndVerified() {
        val sender = TestFiles.mainSources().single { it.name == "SmsSender.kt" }.readText()
        assertTrue("schedule() must consume a confirmation", Regex("""fun schedule\([\s\S]*?confirmation\.consume\(\)""").containsMatchIn(sender))
        assertTrue("schedule() must sign", "authorizer.sign(" in sender)
        assertTrue("sendScheduled() must verify", "authorizer.verify(" in sender)
    }

    @Test fun scheduledDaoInsertOnlyInSmsSender() {
        val users = filesMentioning(Regex("""scheduledDao\(\)\.insert\(|dao\.insert\(draft""")).map { it.name }.toSet()
        assertTrue("scheduled rows created outside SmsSender: $users", users.all { it == "SmsSender.kt" })
    }

    @Test fun noDirectCalls() {
        val offenders = filesMentioning(Regex("""ACTION_CALL\b|CALL_PHONE|"android\.intent\.action\.CALL"""")).map { it.name }
        // IntentTriage only *mentions* nothing of the sort; any hit is a direct-call path.
        assertTrue("Direct-call code found in: $offenders", offenders.isEmpty())
    }
}
