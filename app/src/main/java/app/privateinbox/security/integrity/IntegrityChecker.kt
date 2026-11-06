package app.privateinbox.security.integrity

/**
 * Everything the integrity checks need from the platform, behind an interface so the decision
 * logic runs in JVM unit tests with fake procfs contents. Every member must be local and cheap:
 * no network, no package-list queries, no third-party SDK.
 */
internal interface IntegrityEnv {
    /** Text of a file (procfs or otherwise), or null if missing/unreadable. Implementations cap the size. */
    fun readText(path: String): String?

    /** Names in a directory, or null if unreadable. */
    fun list(dir: String): List<String>?

    fun exists(path: String): Boolean

    /** Build.TAGS */
    val buildTags: String?

    /** True for debug builds of the app itself (BuildConfig.DEBUG). */
    val isDebugBuild: Boolean

    /** ApplicationInfo.FLAG_DEBUGGABLE of the installed package. */
    val manifestDebuggable: Boolean

    fun debuggerConnected(): Boolean

    fun classPresent(name: String): Boolean

    /** Stack frames worth inspecting ("class.method" strings). */
    fun stackFrames(): List<String>

    /** SHA-256 digests (hex) of the installed package's signing certificates, null if unavailable. */
    fun signing(): SigningInfo?

    /** Expected release digests from BuildConfig (empty = pin not configured, e.g. debug builds). */
    val expectedCertDigests: Set<String>

    /** Installing package name, or null (sideload/adb/unknown). Signal only. */
    fun installer(): String?

    /** Directories from $PATH. */
    val pathDirs: List<String>
}

/** Signing certificates as reported by PackageManager (digests already hex SHA-256). */
internal data class SigningInfo(
    /** Current signer(s): apkContentsSigners on API 28+, signatures below. */
    val current: List<String>,
    /** Rotation lineage (oldest first) for a single signer on API 28+; empty otherwise. */
    val history: List<String> = emptyList(),
    val multipleSigners: Boolean = current.size > 1,
)

enum class IntegrityCategory { TAMPER, HOOK, DEBUGGER, ROOT }

enum class IntegritySignal(val category: IntegrityCategory) {
    CERT_MISMATCH(IntegrityCategory.TAMPER),
    DEBUGGABLE_RELEASE(IntegrityCategory.TAMPER),
    FRIDA_LIBRARY(IntegrityCategory.HOOK),
    FRIDA_THREAD(IntegrityCategory.HOOK),
    FRIDA_PORT(IntegrityCategory.HOOK),
    XPOSED_CLASS(IntegrityCategory.HOOK),
    XPOSED_LIBRARY(IntegrityCategory.HOOK),
    HOOK_STACK_FRAME(IntegrityCategory.HOOK),
    DEBUGGER_ATTACHED(IntegrityCategory.DEBUGGER),
    TRACER_ATTACHED(IntegrityCategory.DEBUGGER),
    SU_BINARY(IntegrityCategory.ROOT),
    ROOT_MANAGER_FILES(IntegrityCategory.ROOT),
    MAGISK_MOUNT(IntegrityCategory.ROOT),
    TEST_KEYS(IntegrityCategory.ROOT),
    SYSTEM_RW(IntegrityCategory.ROOT),
}

enum class CertStatus { MATCH, MISMATCH, NOT_CONFIGURED, UNAVAILABLE }

/** Who installed the app. Logged as a coarse signal only; never affects risk. */
enum class InstallerKind { PLAY, OTHER, UNKNOWN }

enum class IntegrityRisk {
    /** Nothing found. */
    NONE,

    /** Root (or hooking/debugging in a debug build): informational only, dismissible. */
    INFO,

    /** Tampered package, or active hooking/debugger in a release build: re-lock + one-time warning. */
    HIGH,
}

data class IntegrityReport(
    val signals: Set<IntegritySignal>,
    val certStatus: CertStatus,
    val installer: InstallerKind,
    val isDebugBuild: Boolean,
) {
    val categories: Set<IntegrityCategory> get() = signals.mapTo(sortedSetOf()) { it.category }

    /** Signals that make the session high risk (empty in debug builds, where debuggers are expected). */
    val highRiskSignals: Set<IntegritySignal>
        get() = if (isDebugBuild) emptySet() else signals.filterTo(sortedSetOf()) { it.category != IntegrityCategory.ROOT }

    val risk: IntegrityRisk
        get() = when {
            highRiskSignals.isNotEmpty() -> IntegrityRisk.HIGH
            signals.isNotEmpty() -> IntegrityRisk.INFO
            else -> IntegrityRisk.NONE
        }

    /** Stable key for "already warned about exactly this" (signal names only, no device details). */
    fun key(of: Set<IntegritySignal>): String = of.map { it.name }.sorted().joinToString(",")
}

/**
 * Local, offline runtime-integrity checks (finding I-01): tamper (signing-cert pin, debuggable
 * release), hooking (Frida, Xposed family), debugger, and root. Heuristics only: a determined
 * attacker with root can hide from every one of them, so results drive a re-lock and warnings,
 * never a hard block (rooted / de-Googled phones are normal for this app's users).
 */
internal class IntegrityChecker(private val env: IntegrityEnv) {

    fun check(): IntegrityReport {
        val s = sortedSetOf<IntegritySignal>()
        val cert = certStatus()
        if (cert == CertStatus.MISMATCH) s += IntegritySignal.CERT_MISMATCH
        if (!env.isDebugBuild && env.manifestDebuggable) s += IntegritySignal.DEBUGGABLE_RELEASE
        hooking(s)
        debugger(s)
        root(s)
        return IntegrityReport(s, cert, installerKind(env.installer()), env.isDebugBuild)
    }

    fun certStatus(): CertStatus {
        val expected = env.expectedCertDigests
        if (expected.isEmpty()) return CertStatus.NOT_CONFIGURED
        val info = env.signing() ?: return CertStatus.UNAVAILABLE
        return evaluateCert(info, expected)
    }

    private fun hooking(s: MutableSet<IntegritySignal>) {
        env.readText("/proc/self/maps")?.let { maps ->
            val hits = IntegrityParsers.scanMaps(maps)
            if (hits.frida) s += IntegritySignal.FRIDA_LIBRARY
            if (hits.xposed) s += IntegritySignal.XPOSED_LIBRARY
        }
        env.list("/proc/self/task")?.let { tids ->
            val names = tids.take(MAX_THREADS).mapNotNull { env.readText("/proc/self/task/$it/comm") }
            if (IntegrityParsers.hasFridaThread(names)) s += IntegritySignal.FRIDA_THREAD
        }
        val ports = setOf(IntegrityParsers.FRIDA_DEFAULT_PORT, IntegrityParsers.FRIDA_DEFAULT_PORT + 1)
        if (listOf("/proc/net/tcp", "/proc/net/tcp6").any { p -> env.readText(p)?.let { IntegrityParsers.listensOn(it, ports) } == true }) {
            s += IntegritySignal.FRIDA_PORT
        }
        if (XPOSED_CLASSES.any(env::classPresent)) s += IntegritySignal.XPOSED_CLASS
        if (IntegrityParsers.hasHookFrames(env.stackFrames())) s += IntegritySignal.HOOK_STACK_FRAME
    }

    private fun debugger(s: MutableSet<IntegritySignal>) {
        if (env.debuggerConnected()) s += IntegritySignal.DEBUGGER_ATTACHED
        val tracer = env.readText("/proc/self/status")?.let(IntegrityParsers::tracerPid)
        if (tracer != null && tracer != 0) s += IntegritySignal.TRACER_ATTACHED
    }

    private fun root(s: MutableSet<IntegritySignal>) {
        val suDirs = (SU_DIRS + env.pathDirs).map { it.trimEnd('/') }.filter { it.startsWith("/") }.distinct()
        if (suDirs.any { env.exists("$it/su") }) s += IntegritySignal.SU_BINARY
        if (ROOT_MANAGER_PATHS.any(env::exists)) s += IntegritySignal.ROOT_MANAGER_FILES
        env.readText("/proc/self/mounts")?.let { m ->
            val hits = IntegrityParsers.scanMounts(m)
            if (hits.magisk) s += IntegritySignal.MAGISK_MOUNT
            if (hits.systemWritable) s += IntegritySignal.SYSTEM_RW
        }
        if (IntegrityParsers.isTestKeys(env.buildTags)) s += IntegritySignal.TEST_KEYS
    }

    companion object {
        private const val MAX_THREADS = 512

        private val XPOSED_CLASSES = listOf(
            "de.robv.android.xposed.XposedBridge",
            "de.robv.android.xposed.XposedHelpers",
            "io.github.libxposed.api.XposedInterface",
            "org.lsposed.lspd.core.Main",
            "com.saurik.substrate.MS\$2",
        )

        private val SU_DIRS = listOf(
            "/system/bin", "/system/xbin", "/sbin", "/su/bin", "/system/sbin", "/vendor/bin",
            "/data/local", "/data/local/bin", "/data/local/xbin", "/system/bin/failsafe", "/odm/bin",
            "/product/bin", "/system_ext/bin", "/debug_ramdisk",
        )

        /** Files left by Magisk / KernelSU / APatch / SuperSU that an app can sometimes still see. */
        private val ROOT_MANAGER_PATHS = listOf(
            "/sbin/.magisk", "/data/adb/magisk", "/data/adb/magisk.db", "/data/adb/modules", "/data/adb/ksu",
            "/data/adb/ksud", "/data/adb/ap", "/cache/.disable_magisk", "/dev/.magisk.unblock",
            "/debug_ramdisk/.magisk", "/system/app/Superuser.apk", "/system/etc/init.d/99SuperSUDaemon",
        )

        /**
         * Signing-cert pin. Single signer: the current cert OR any cert in its rotation lineage must
         * be pinned (a lineage entry can only exist if our key authorised the rotation). Multiple
         * signers: every signer must be pinned, so a re-signer can't just add their own key.
         */
        fun evaluateCert(info: SigningInfo, expected: Set<String>): CertStatus {
            val pins = expected.mapNotNull(IntegrityParsers::normalizeDigest).toSet()
            if (pins.isEmpty()) return CertStatus.NOT_CONFIGURED
            val current = info.current.map { IntegrityParsers.normalizeDigest(it) ?: return CertStatus.UNAVAILABLE }
            if (current.isEmpty()) return CertStatus.UNAVAILABLE
            val ok = if (info.multipleSigners) {
                current.all { it in pins }
            } else {
                (current + info.history.mapNotNull(IntegrityParsers::normalizeDigest)).any { it in pins }
            }
            return if (ok) CertStatus.MATCH else CertStatus.MISMATCH
        }

        private val PLAY_INSTALLERS = setOf("com.android.vending", "com.google.android.feedback")
        /** Signal only: Play is never required (F-Droid, Obtainium, adb installs are all normal). */
        fun installerKind(pkg: String?): InstallerKind = when {
            pkg.isNullOrEmpty() -> InstallerKind.UNKNOWN
            pkg in PLAY_INSTALLERS -> InstallerKind.PLAY
            else -> InstallerKind.OTHER
        }
    }
}
