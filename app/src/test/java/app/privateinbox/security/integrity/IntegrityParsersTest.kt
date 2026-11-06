package app.privateinbox.security.integrity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegrityParsersTest {
    private val cleanMaps = """
        12c00000-32c00000 rw-p 00000000 00:00 0                                  [anon:dalvik-main space]
        70a1c000-70c4e000 r--p 00000000 fd:00 1234                               /system/framework/arm64/boot.art
        7b2a400000-7b2a600000 r-xp 00000000 fd:05 4242                           /data/app/~~x==/app.privateinbox-1/lib/arm64/libsqlcipher.so
        7b2b000000-7b2b001000 ---p 00000000 00:00 0
        7ffd0000-7ffd1000 r--p 00000000 00:00 0                                  [vdso]
    """.trimIndent()

    @Test fun mapsCleanProcessHasNoHits() {
        assertEquals(IntegrityParsers.MapsHits(frida = false, xposed = false), IntegrityParsers.scanMaps(cleanMaps))
        assertEquals(IntegrityParsers.MapsHits(false, false), IntegrityParsers.scanMaps(""))
    }

    @Test fun mapsDetectsFridaAgentGadgetAndGum() {
        listOf(
            "7c00000000-7c00100000 r-xp 00000000 fd:05 99 /data/local/tmp/re.frida.server/frida-agent-64.so",
            "7c00000000-7c00100000 r-xp 00000000 fd:05 99 /data/app/x/lib/arm64/libfrida-gadget.so",
            "7c00000000-7c00100000 rw-p 00000000 00:00 0  /memfd:frida-agent-64.so (deleted)",
            "7c00000000-7c00100000 r-xp 00000000 00:00 0  [anon:gum-js-loop]",
        ).forEach { line ->
            assertTrue(line, IntegrityParsers.scanMaps(cleanMaps + "\n" + line).frida)
        }
    }

    @Test fun mapsDetectsXposedFamily() {
        listOf(
            "7c00000000-7c00100000 r--p 00000000 fd:05 9 /system/framework/XposedBridge.jar",
            "7c00000000-7c00100000 r-xp 00000000 fd:05 9 /data/adb/modules/zygisk_lsposed/lib/arm64/liblspd.so",
            "7c00000000-7c00100000 r-xp 00000000 fd:05 9 /system/lib64/libriru_edxp.so",
        ).forEach { line ->
            val hits = IntegrityParsers.scanMaps(cleanMaps + "\n" + line)
            assertTrue(line, hits.xposed)
            assertFalse(line, hits.frida)
        }
    }

    @Test fun mapsIgnoresKeywordsOutsidePathColumn() {
        // "frida" only in the perms/offset area of a malformed line must not count; path column only.
        assertFalse(IntegrityParsers.scanMaps("7c00-7c01 r-xp 00000000 fd:05 9").frida)
    }

    @Test fun fridaThreadNames() {
        assertFalse(IntegrityParsers.hasFridaThread(listOf("main\n", "RenderThread\n", "Signal Catcher\n", "HeapTaskDaemon\n")))
        assertTrue(IntegrityParsers.hasFridaThread(listOf("main\n", "gum-js-loop\n")))
        assertTrue(IntegrityParsers.hasFridaThread(listOf("gmain\n")))
        assertTrue(IntegrityParsers.hasFridaThread(listOf("pool-frida-12\n")))
        assertTrue(IntegrityParsers.hasFridaThread(listOf("GDBUS")))
    }

    @Test fun tracerPidFromStatus() {
        val status = "Name:\tapp.privateinbox\nState:\tS (sleeping)\nTgid:\t1234\nPid:\t1234\nPPid:\t600\nTracerPid:\t%s\nUid:\t10123\n"
        assertEquals(0, IntegrityParsers.tracerPid(status.format("0")))
        assertEquals(4321, IntegrityParsers.tracerPid(status.format("4321")))
        assertNull(IntegrityParsers.tracerPid("Name:\tx\n"))
        assertNull(IntegrityParsers.tracerPid("TracerPid:\tabc\n"))
    }

    private val tcpHeader = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode"

    @Test fun procNetTcpListeningPort() {
        // 0x69A2 = 27042 (Frida default), state 0A = LISTEN
        val listening = "$tcpHeader\n   0: 0100007F:69A2 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 12345 1"
        val established = "$tcpHeader\n   0: 0100007F:69A2 0100007F:C350 01 00000000:00000000 00:00000000 00000000     0        0 12345 1"
        val other = "$tcpHeader\n   0: 00000000:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 999 1"
        val tcp6 = "$tcpHeader\n   0: 00000000000000000000000001000000:69A2 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000 0 0 1 1"
        val ports = setOf(IntegrityParsers.FRIDA_DEFAULT_PORT)
        assertTrue(IntegrityParsers.listensOn(listening, ports))
        assertFalse(IntegrityParsers.listensOn(established, ports))
        assertFalse(IntegrityParsers.listensOn(other, ports))
        assertTrue(IntegrityParsers.listensOn(tcp6, ports))
        assertFalse(IntegrityParsers.listensOn(tcpHeader, ports))
        assertFalse(IntegrityParsers.listensOn("garbage\nnot a table", ports))
    }

    @Test fun hookStackFrames() {
        assertFalse(IntegrityParsers.hasHookFrames(listOf("com.android.internal.os.ZygoteInit.main", "android.app.ActivityThread.main")))
        assertTrue(IntegrityParsers.hasHookFrames(listOf("de.robv.android.xposed.XposedBridge.main", "com.android.internal.os.ZygoteInit.main")))
        assertTrue(IntegrityParsers.hasHookFrames(listOf("LSPHooker_.onAuthenticationSucceeded")))
    }

    @Test fun mountsStockDevice() {
        val stock = """
            /dev/block/dm-0 / ext4 ro,seclabel,relatime 0 0
            tmpfs /dev tmpfs rw,seclabel,nosuid,relatime,mode=755 0 0
            /dev/block/dm-1 /vendor ext4 ro,seclabel,relatime 0 0
            /dev/block/dm-2 /product erofs ro,seclabel 0 0
            /dev/block/by-name/userdata /data f2fs rw,lazytime,seclabel,nosuid,nodev 0 0
        """.trimIndent()
        assertEquals(IntegrityParsers.MountHits(magisk = false, systemWritable = false), IntegrityParsers.scanMounts(stock))
        // Pre-system-as-root devices: rootfs "/" is rw by design.
        assertFalse(IntegrityParsers.scanMounts("rootfs / rootfs rw,seclabel 0 0").systemWritable)
    }

    @Test fun mountsMagiskAndWritableSystem() {
        assertTrue(IntegrityParsers.scanMounts("magisk /system/bin tmpfs rw,seclabel,relatime 0 0").magisk)
        assertTrue(IntegrityParsers.scanMounts("tmpfs /sbin tmpfs rw 0 0\n/dev/block/loop1 /sbin/.magisk/block ext4 rw 0 0").magisk)
        assertTrue(IntegrityParsers.scanMounts("KSU /system/etc overlay ro 0 0").magisk)
        assertTrue(IntegrityParsers.scanMounts("/dev/block/dm-0 /system ext4 rw,seclabel,relatime 0 0").systemWritable)
        assertTrue(IntegrityParsers.scanMounts("/dev/block/dm-0 / ext4 rw,seclabel 0 0").systemWritable)
        assertFalse(IntegrityParsers.scanMounts("/dev/block/dm-0 /system ext4 ro,seclabel 0 0").systemWritable)
        assertFalse(IntegrityParsers.scanMounts("short line").magisk)
    }

    @Test fun buildTags() {
        assertTrue(IntegrityParsers.isTestKeys("test-keys"))
        assertFalse(IntegrityParsers.isTestKeys("release-keys"))
        assertFalse(IntegrityParsers.isTestKeys(null))
    }

    @Test fun digestNormalisationAndPins() {
        val colon = (0 until 32).joinToString(":") { "ab" }
        assertEquals("AB".repeat(32), IntegrityParsers.normalizeDigest(colon))
        assertEquals("AB".repeat(32), IntegrityParsers.normalizeDigest(" ${"ab".repeat(32)} "))
        assertNull(IntegrityParsers.normalizeDigest("AB:CD"))
        assertNull(IntegrityParsers.normalizeDigest("ZZ".repeat(32)))
        assertEquals(setOf("AB".repeat(32), "01".repeat(32)), IntegrityParsers.parsePins("$colon, ${"01".repeat(32)};"))
        assertEquals(emptySet<String>(), IntegrityParsers.parsePins(""))
    }
}
