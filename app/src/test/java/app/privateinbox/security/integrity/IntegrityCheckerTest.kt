package app.privateinbox.security.integrity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegrityCheckerTest {
    private val ours = "A1".repeat(32)
    private val oldKey = "B2".repeat(32)
    private val attacker = "C3".repeat(32)

    private class FakeEnv(
        val files: Map<String, String> = emptyMap(),
        val dirs: Map<String, List<String>> = emptyMap(),
        val present: Set<String> = emptySet(),
        override val buildTags: String? = "release-keys",
        override val isDebugBuild: Boolean = false,
        override val manifestDebuggable: Boolean = false,
        val debugger: Boolean = false,
        val classes: Set<String> = emptySet(),
        val frames: List<String> = listOf("android.app.ActivityThread.main"),
        val signingInfo: SigningInfo? = null,
        override val expectedCertDigests: Set<String> = emptySet(),
        val installerPkg: String? = "org.fdroid.fdroid",
        override val pathDirs: List<String> = listOf("/system/bin", "/system/xbin"),
    ) : IntegrityEnv {
        override fun readText(path: String) = files[path]
        override fun list(dir: String) = dirs[dir]
        override fun exists(path: String) = path in present || path in files
        override fun debuggerConnected() = debugger
        override fun classPresent(name: String) = name in classes
        override fun stackFrames() = frames
        override fun signing() = signingInfo
        override fun installer() = installerPkg
    }

    private fun check(env: FakeEnv) = IntegrityChecker(env).check()

    @Test fun cleanDeviceHasNoSignals() {
        val r = check(
            FakeEnv(
                files = mapOf(
                    "/proc/self/maps" to "70a1c000-70c4e000 r--p 00000000 fd:00 1 /system/framework/arm64/boot.art",
                    "/proc/self/status" to "TracerPid:\t0\n",
                    "/proc/self/mounts" to "/dev/block/dm-0 / ext4 ro 0 0",
                    "/proc/self/task/1/comm" to "main\n",
                ),
                dirs = mapOf("/proc/self/task" to listOf("1")),
                signingInfo = SigningInfo(listOf(ours)),
                expectedCertDigests = setOf(ours),
            ),
        )
        assertEquals(emptySet<IntegritySignal>(), r.signals)
        assertEquals(CertStatus.MATCH, r.certStatus)
        assertEquals(IntegrityRisk.NONE, r.risk)
        assertEquals(InstallerKind.OTHER, r.installer)
    }

    @Test fun certPinMismatchIsHighRiskTamper() {
        val r = check(FakeEnv(signingInfo = SigningInfo(listOf(attacker)), expectedCertDigests = setOf(ours)))
        assertEquals(CertStatus.MISMATCH, r.certStatus)
        assertTrue(IntegritySignal.CERT_MISMATCH in r.signals)
        assertEquals(IntegrityRisk.HIGH, r.risk)
    }

    @Test fun certPinNotConfiguredOrUnavailableIsNotASignal() {
        assertEquals(CertStatus.NOT_CONFIGURED, check(FakeEnv(signingInfo = SigningInfo(listOf(attacker)))).certStatus)
        val r = check(FakeEnv(signingInfo = null, expectedCertDigests = setOf(ours)))
        assertEquals(CertStatus.UNAVAILABLE, r.certStatus)
        assertEquals(IntegrityRisk.NONE, r.risk)
    }

    @Test fun certRotationAndMultipleSigners() {
        val pins = setOf(oldKey)
        // Rotated from our pinned old key to a new key: lineage proves it.
        assertEquals(CertStatus.MATCH, IntegrityChecker.evaluateCert(SigningInfo(listOf(ours), history = listOf(oldKey, ours)), pins))
        // Lineage without our key: mismatch.
        assertEquals(CertStatus.MISMATCH, IntegrityChecker.evaluateCert(SigningInfo(listOf(attacker), history = listOf(attacker)), pins))
        // Multiple signers: every signer must be pinned (re-signer can't just add theirs).
        assertEquals(CertStatus.MISMATCH, IntegrityChecker.evaluateCert(SigningInfo(listOf(ours, attacker), multipleSigners = true), setOf(ours)))
        assertEquals(CertStatus.MATCH, IntegrityChecker.evaluateCert(SigningInfo(listOf(ours, oldKey), multipleSigners = true), setOf(ours, oldKey)))
        // Colon-separated, lowercase pins are normalised.
        val colonLower = ours.lowercase().chunked(2).joinToString(":")
        assertEquals(CertStatus.MATCH, IntegrityChecker.evaluateCert(SigningInfo(listOf(ours)), setOf(colonLower)))
        assertEquals(CertStatus.UNAVAILABLE, IntegrityChecker.evaluateCert(SigningInfo(emptyList()), setOf(ours)))
    }

    @Test fun debuggableReleaseIsTamper() {
        assertTrue(IntegritySignal.DEBUGGABLE_RELEASE in check(FakeEnv(manifestDebuggable = true)).signals)
        assertFalse(IntegritySignal.DEBUGGABLE_RELEASE in check(FakeEnv(manifestDebuggable = true, isDebugBuild = true)).signals)
    }

    @Test fun fridaSignals() {
        val r = check(
            FakeEnv(
                files = mapOf(
                    "/proc/self/maps" to "7c00-7c01 r-xp 00000000 fd:05 9 /data/local/tmp/frida-agent-64.so",
                    "/proc/self/task/7/comm" to "gum-js-loop\n",
                    "/proc/net/tcp" to "  sl  local_address rem_address st\n   0: 0100007F:69A2 00000000:0000 0A 0 0 0 0 0 1 1",
                ),
                dirs = mapOf("/proc/self/task" to listOf("1", "7")),
            ),
        )
        assertTrue(r.signals.containsAll(setOf(IntegritySignal.FRIDA_LIBRARY, IntegritySignal.FRIDA_THREAD, IntegritySignal.FRIDA_PORT)))
        assertEquals(IntegrityRisk.HIGH, r.risk)
    }

    @Test fun xposedSignals() {
        val r = check(
            FakeEnv(
                classes = setOf("de.robv.android.xposed.XposedBridge"),
                frames = listOf("de.robv.android.xposed.XposedBridge.main", "com.android.internal.os.ZygoteInit.main"),
            ),
        )
        assertEquals(setOf(IntegritySignal.XPOSED_CLASS, IntegritySignal.HOOK_STACK_FRAME), r.signals)
        assertEquals(IntegrityRisk.HIGH, r.risk)
    }

    @Test fun debuggerSignalsAreHighInReleaseInfoInDebug() {
        val env = { debug: Boolean ->
            FakeEnv(debugger = true, files = mapOf("/proc/self/status" to "TracerPid:\t812\n"), isDebugBuild = debug)
        }
        val release = check(env(false))
        assertEquals(setOf(IntegritySignal.DEBUGGER_ATTACHED, IntegritySignal.TRACER_ATTACHED), release.signals)
        assertEquals(IntegrityRisk.HIGH, release.risk)
        val debug = check(env(true))
        assertEquals(IntegrityRisk.INFO, debug.risk)
        assertTrue(debug.highRiskSignals.isEmpty())
    }

    @Test fun rootAloneIsInformational() {
        val r = check(
            FakeEnv(
                present = setOf("/system/xbin/su", "/data/adb/magisk"),
                files = mapOf("/proc/self/mounts" to "magisk /system/bin tmpfs rw 0 0\n/dev/block/dm-0 /system ext4 rw 0 0"),
                buildTags = "test-keys",
            ),
        )
        assertEquals(
            setOf(
                IntegritySignal.SU_BINARY, IntegritySignal.ROOT_MANAGER_FILES, IntegritySignal.MAGISK_MOUNT,
                IntegritySignal.TEST_KEYS, IntegritySignal.SYSTEM_RW,
            ),
            r.signals,
        )
        assertEquals(setOf(IntegrityCategory.ROOT), r.categories)
        assertEquals(IntegrityRisk.INFO, r.risk)
        assertTrue(r.highRiskSignals.isEmpty())
    }

    @Test fun suFoundViaPathAndRelativePathEntriesIgnored() {
        assertTrue(IntegritySignal.SU_BINARY in check(FakeEnv(pathDirs = listOf("/apex/bin/"), present = setOf("/apex/bin/su"))).signals)
        assertFalse(IntegritySignal.SU_BINARY in check(FakeEnv(pathDirs = listOf("relative"), present = setOf("relative/su"))).signals)
    }

    @Test fun unreadableProcfsMeansNoSignalNotCrash() {
        val r = check(FakeEnv(files = emptyMap(), dirs = emptyMap()))
        assertEquals(emptySet<IntegritySignal>(), r.signals)
    }

    @Test fun installerIsSignalOnly() {
        assertEquals(InstallerKind.PLAY, IntegrityChecker.installerKind("com.android.vending"))
        assertEquals(InstallerKind.OTHER, IntegrityChecker.installerKind("org.fdroid.fdroid"))
        assertEquals(InstallerKind.UNKNOWN, IntegrityChecker.installerKind(null))
        assertEquals(IntegrityRisk.NONE, check(FakeEnv(installerPkg = null)).risk)
    }

    @Test fun warningKeyIsStableAndDetailFree() {
        val r = IntegrityReport(setOf(IntegritySignal.TRACER_ATTACHED, IntegritySignal.CERT_MISMATCH), CertStatus.MISMATCH, InstallerKind.UNKNOWN, false)
        assertEquals("CERT_MISMATCH,TRACER_ATTACHED", r.key(r.highRiskSignals))
    }

    private val tcpListen = "  sl  local_address rem_address st\n   0: 0100007F:69A2 00000000:0000 0A 0 0 0 0 0 1 1"

    @Test fun listeningPortAloneIsNotFrida() {
        // HB-2 false positive: something else listens on 27042.
        val r = check(FakeEnv(files = mapOf("/proc/net/tcp" to tcpListen)))
        assertEquals(setOf(IntegritySignal.FRIDA_PORT), r.signals)
        assertEquals(IntegrityRisk.INFO, r.risk)
        assertTrue(r.highRiskSignals.isEmpty())
        assertEquals(setOf(IntegritySignal.FRIDA_PORT), r.informationalSignals)
    }

    @Test fun glibThreadsAloneAreNotFrida() {
        val r = check(
            FakeEnv(
                files = mapOf("/proc/self/task/1/comm" to "main\n", "/proc/self/task/2/comm" to "gmain\n", "/proc/self/task/3/comm" to "gdbus\n"),
                dirs = mapOf("/proc/self/task" to listOf("1", "2", "3")),
            ),
        )
        assertEquals(setOf(IntegritySignal.GLIB_THREAD), r.signals)
        assertEquals(IntegrityRisk.INFO, r.risk)
    }

    @Test fun genericRiruIsRootInformationNotHooking() {
        val r = check(FakeEnv(files = mapOf("/proc/self/maps" to "7c00-7c01 r-xp 00000000 fd:05 9 /system/lib64/libriru.so")))
        assertEquals(setOf(IntegritySignal.ZYGOTE_MODULE), r.signals)
        assertEquals(setOf(IntegrityCategory.ROOT), r.categories)
        assertEquals(IntegrityRisk.INFO, r.risk)
    }

    @Test fun weakSignalsCountWhenCorroboratedByStrongHooking() {
        val r = check(
            FakeEnv(
                files = mapOf(
                    "/proc/self/maps" to "7c00-7c01 r-xp 00000000 fd:05 9 /data/local/tmp/frida-agent-64.so",
                    "/proc/self/task/2/comm" to "gmain\n",
                    "/proc/net/tcp" to tcpListen,
                ),
                dirs = mapOf("/proc/self/task" to listOf("2")),
            ),
        )
        assertEquals(setOf(IntegritySignal.FRIDA_LIBRARY, IntegritySignal.GLIB_THREAD, IntegritySignal.FRIDA_PORT), r.highRiskSignals)
        assertEquals(IntegrityRisk.HIGH, r.risk)
    }

    @Test fun weakHintsAreNotCorroboratedByRootOrDebuggerAlone() {
        val r = IntegrityReport(
            setOf(IntegritySignal.FRIDA_PORT, IntegritySignal.SU_BINARY, IntegritySignal.TRACER_ATTACHED),
            CertStatus.MATCH, InstallerKind.UNKNOWN, isDebugBuild = false,
        )
        assertEquals(setOf(IntegritySignal.TRACER_ATTACHED), r.highRiskSignals)
        assertEquals(setOf(IntegritySignal.FRIDA_PORT, IntegritySignal.SU_BINARY), r.informationalSignals)
    }

    @Test fun confirmedCertMismatchAloneIsHighRisk() {
        val r = check(FakeEnv(signingInfo = SigningInfo(listOf(attacker)), expectedCertDigests = setOf(ours)))
        assertEquals(setOf(IntegritySignal.CERT_MISMATCH), r.highRiskSignals)
        assertEquals(IntegrityRisk.HIGH, r.risk)
        // Unavailable signing info is not a mismatch.
        assertEquals(IntegrityRisk.NONE, check(FakeEnv(signingInfo = null, expectedCertDigests = setOf(ours))).risk)
    }

    @Test fun rootPlusZygoteModuleStaysInformational() {
        val r = check(
            FakeEnv(
                present = setOf("/data/adb/magisk"),
                files = mapOf("/proc/self/maps" to "7c00-7c01 r-xp 00000000 00:00 0 /memfd:libzygisk.so (deleted)"),
            ),
        )
        assertEquals(setOf(IntegritySignal.ROOT_MANAGER_FILES, IntegritySignal.ZYGOTE_MODULE), r.signals)
        assertTrue(r.highRiskSignals.isEmpty())
    }
}
