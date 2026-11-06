package app.privateinbox.policy

import app.privateinbox.TestFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app has no network permission; these source guards keep it from growing network code paths
 * (which would fail at runtime anyway) and pin the user-tap-only MMS download (F-04).
 */
class NoNetworkPolicyTest {
    private val sources by lazy { TestFiles.mainSources() }
    private fun src(name: String) = sources.single { it.name == name }.readText()

    @Test fun noAppSideNetworkingApis() {
        val banned = Regex(
            """\b(HttpURLConnection|HttpsURLConnection|java\.net\.(URL|Socket|ServerSocket|DatagramSocket|InetAddress|HttpURLConnection)\b|""" +
                """URL\([^)]*\)\.open|openConnection\(|javax\.net\.|okhttp3|retrofit2|io\.ktor|ConnectivityManager|requestNetwork\(|""" +
                """bindProcessToNetwork|WifiManager|WebView)""",
        )
        val offenders = sources.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, l -> if (!l.trimStart().startsWith("*") && !l.trimStart().startsWith("//") && banned.containsMatchIn(l)) "${f.name}:${i + 1}: $l" else null }
        }
        assertTrue("App-side networking found:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test fun noWorkerNeedsNetwork() {
        val offenders = sources.filter { Regex("""setRequiredNetworkType|NetworkType\.|setRequiresNetwork""").containsMatchIn(it.readText()) }.map { it.name }
        assertEquals("ACCESS_NETWORK_STATE is removed, so no network constraints", emptyList<String>(), offenders)
    }

    @Test fun mmsIsDownloadedOnlyThroughThePolicyCheckedPath() {
        val users = sources.filter { "downloadMultimediaMessage(" in it.readText() }.map { it.name }.toSet()
        assertEquals(setOf("MmsTransport.kt"), users)
        val callers = sources.filter { Regex("""MmsTransport\([^)]*\)\s*\.download\(|\.download\(p\.contentLocation""").containsMatchIn(it.readText()) }.map { it.name }.toSet()
        assertEquals("only AppContainer.startMmsDownload may start a download", setOf("AppContainer.kt"), callers)
        val c = src("AppContainer.kt")
        assertEquals("exactly one download call site", 1, Regex("""\.download\(p\.contentLocation""").findAll(c).count())
        val start = c.substringAfter("suspend fun startMmsDownload").substringBefore("suspend fun resetStuckMmsDownloads")
        assertTrue(start.contains(".download(p.contentLocation"))
        val receiver = src("MmsReceiver.kt")
        assertFalse("WAP push receiver must not download", ".download(" in receiver)
        val container = src("AppContainer.kt")
        assertTrue(container.contains("MmsPolicy.decide") || container.contains("mmsDownloadDecision(messageId)"))
        assertTrue("startMmsDownload re-checks the policy", Regex("""fun startMmsDownload[\s\S]{0,200}mmsDownloadDecision\(messageId\)""").containsMatchIn(container))
    }
}
