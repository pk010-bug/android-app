package app.privateinbox.security.integrity

/**
 * Pure parsers for the local, offline integrity checks (I-01). Everything here works on text that
 * [IntegrityEnv] read from procfs or the platform, so it is unit-tested with fake file contents.
 * No network, no third-party SDK: these are heuristics, not proof (see IntegrityChecker).
 */
internal object IntegrityParsers {

    // ---------- hooking: /proc/self/maps ----------

    private val FRIDA_MAPS = listOf("frida-agent", "frida-gadget", "frida-gum", "gum-js", "libfrida", "/frida-", "linjector")
    private val XPOSED_MAPS = listOf(
        "xposedbridge", "libxposed", "xposed_art", "edxposed", "libedxp", "lspd", "liblspd", "lsposed",
        "libriru", "riru_", "libsandhook", "libwhale", "libpine", "substrate",
    )

    data class MapsHits(val frida: Boolean, val xposed: Boolean)

    /** Scans /proc/self/maps for injected Frida / Xposed-family libraries (path column only). */
    fun scanMaps(maps: String): MapsHits {
        var frida = false
        var xposed = false
        maps.lineSequence().forEach { line ->
            // "addr perms offset dev inode   path": the path is the 6th column, may be absent.
            val path = line.trim().split(Regex("\\s+"), limit = 6).getOrNull(5)?.lowercase() ?: return@forEach
            if (!frida && FRIDA_MAPS.any { it in path }) frida = true
            if (!xposed && XPOSED_MAPS.any { it in path }) xposed = true
            if (frida && xposed) return MapsHits(true, true)
        }
        return MapsHits(frida, xposed)
    }

    // ---------- hooking: thread names (/proc/self/task/*/comm) ----------

    /** GLib / Frida agent threads; none of these exist in a stock ART app process. */
    private val FRIDA_THREADS = setOf("gum-js-loop", "gmain", "gdbus", "pool-frida", "frida-main-loop")

    fun hasFridaThread(comms: Iterable<String>): Boolean =
        comms.any { c -> val n = c.trim().lowercase(); n in FRIDA_THREADS || n.startsWith("pool-frida") || n.startsWith("frida") }

    // ---------- debugger: /proc/self/status ----------

    /** TracerPid from /proc/self/status; null if absent/unparseable. Non-zero = a ptrace tracer is attached. */
    fun tracerPid(status: String): Int? =
        status.lineSequence().firstOrNull { it.startsWith("TracerPid:") }
            ?.substringAfter(':')?.trim()?.toIntOrNull()

    // ---------- hooking: /proc/net/tcp(6) listening ports ----------

    const val FRIDA_DEFAULT_PORT = 27042
    private const val TCP_LISTEN = "0A"

    /**
     * True if any socket in a /proc/net/tcp or tcp6 table is LISTENING on one of [ports].
     * Parsing the table avoids opening sockets (no network activity at all). Android 10+ usually
     * denies apps this file; the caller then gets null and the signal is simply unavailable.
     */
    fun listensOn(procNetTcp: String, ports: Set<Int>): Boolean =
        procNetTcp.lineSequence().drop(1).any { line ->
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size < 4 || cols[3] != TCP_LISTEN) return@any false
            val port = cols[1].substringAfterLast(':', "").toIntOrNull(16) ?: return@any false
            port in ports
        }

    // ---------- hooking: stack frames ----------

    private val HOOK_FRAMES = listOf(
        "de.robv.android.xposed", "com.saurik.substrate", "org.lsposed", "io.github.lsposed",
        "lsphooker", "edhooker", "com.elderdrivers.riru", "top.canyie.pine", "me.weishu.epic",
    )

    fun hasHookFrames(frames: Iterable<String>): Boolean =
        frames.any { f -> val l = f.lowercase(); HOOK_FRAMES.any { it in l } }

    // ---------- root: /proc/mounts ----------

    private val SYSTEM_MOUNT_POINTS = setOf("/system", "/vendor", "/product", "/system_ext", "/odm")

    data class MountHits(val magisk: Boolean, val systemWritable: Boolean)

    fun scanMounts(mounts: String): MountHits {
        var magisk = false
        var rw = false
        mounts.lineSequence().forEach { line ->
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size < 4) return@forEach
            val (source, target, type, options) = cols
            val l = line.lowercase()
            if ("magisk" in l || "/debug_ramdisk" in target || source.lowercase() == "ksu" || type == "overlay" && "/data/adb" in l) magisk = true
            val optRw = options.split(',').firstOrNull() == "rw"
            // "/" is only a real system partition with system-as-root; old rootfs/tmpfs "/" is rw by design.
            val systemTarget = target in SYSTEM_MOUNT_POINTS || (target == "/" && type !in setOf("rootfs", "tmpfs"))
            if (optRw && systemTarget && type != "tmpfs") rw = true
        }
        return MountHits(magisk, rw)
    }

    // ---------- root: build tags ----------

    fun isTestKeys(tags: String?): Boolean = tags?.contains("test-keys") == true

    // ---------- tamper: signing certificate digests ----------

    /** "AB:cd:01 ..." -> "ABCD01"; null if it isn't a 32-byte hex digest. */
    fun normalizeDigest(d: String): String? =
        d.filter { it != ':' && !it.isWhitespace() }.uppercase().takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'A'..'F' } }

    /** Parses the BuildConfig pin list: comma/semicolon separated digests (several = rotation/multiple keys). */
    fun parsePins(config: String): Set<String> =
        config.split(',', ';').mapNotNull { it.trim().takeIf(String::isNotEmpty)?.let(::normalizeDigest) }.toSet()
}
