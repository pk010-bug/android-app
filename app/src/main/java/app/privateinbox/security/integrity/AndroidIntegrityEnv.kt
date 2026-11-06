package app.privateinbox.security.integrity

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.os.Debug
import android.os.Looper
import app.privateinbox.BuildConfig
import java.io.File
import java.security.MessageDigest

/**
 * Real [IntegrityEnv]: plain file reads of procfs, PackageManager for our OWN package only (no
 * package-visibility queries, no QUERY_ALL_PACKAGES), and reflection on well-known hook classes.
 * No sockets are opened and nothing leaves the device.
 */
internal class AndroidIntegrityEnv(private val context: Context) : IntegrityEnv {

    override fun readText(path: String): String? = try {
        File(path).inputStream().use { ins ->
            // procfs files report length 0, so stream with a hard cap.
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (out.size() < MAX_READ) {
                val n = ins.read(buf)
                if (n < 0) break
                out.write(buf, 0, minOf(n, MAX_READ - out.size()))
            }
            out.toString(Charsets.UTF_8.name())
        }
    } catch (e: Exception) {
        null // missing or denied by SELinux: the signal is simply unavailable
    }

    override fun list(dir: String): List<String>? = runCatching { File(dir).list()?.toList() }.getOrNull()

    override fun exists(path: String): Boolean = runCatching { File(path).exists() }.getOrDefault(false)

    override val buildTags: String? get() = Build.TAGS

    override val isDebugBuild: Boolean get() = BuildConfig.DEBUG

    override val manifestDebuggable: Boolean
        get() = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    override fun debuggerConnected(): Boolean = Debug.isDebuggerConnected() || Debug.waitingForDebugger()

    override fun classPresent(name: String): Boolean =
        listOf(context.classLoader, ClassLoader.getSystemClassLoader()).any { cl ->
            try {
                Class.forName(name, false, cl)
                true
            } catch (t: Throwable) {
                false
            }
        }

    override fun stackFrames(): List<String> =
        (Looper.getMainLooper().thread.stackTrace.asList() + Throwable().stackTrace.asList())
            .map { "${it.className}.${it.methodName}" }

    @Suppress("DEPRECATION")
    override fun signing(): SigningInfo? = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                ?: return@runCatching null
            if (info.hasMultipleSigners()) {
                SigningInfo(info.apkContentsSigners.map(::sha256), multipleSigners = true)
            } else {
                SigningInfo(
                    current = info.apkContentsSigners.map(::sha256),
                    history = info.signingCertificateHistory.orEmpty().map(::sha256),
                    multipleSigners = false,
                )
            }
        } else {
            val sigs = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures.orEmpty()
            SigningInfo(sigs.map(::sha256), multipleSigners = sigs.size > 1)
        }
    }.getOrNull()

    override val expectedCertDigests: Set<String>
        get() = IntegrityParsers.parsePins(BuildConfig.EXPECTED_SIGNING_CERT_SHA256)

    @Suppress("DEPRECATION")
    override fun installer(): String? = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            pm.getInstallerPackageName(context.packageName)
        }
    }.getOrNull()

    override val pathDirs: List<String>
        get() = System.getenv("PATH")?.split(':')?.filter { it.isNotBlank() }.orEmpty()

    private fun sha256(sig: Signature): String =
        MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02X".format(it) }

    private companion object {
        const val MAX_READ = 4 * 1024 * 1024
    }
}
