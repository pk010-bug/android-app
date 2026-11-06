package app.privateinbox.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-encrypted backup container (pure JVM, unit-tested).
 *
 * Current layout (version 2):
 *   "PIBK" | version=2 (1) | kdf (1) | iterations (int32) | salt (16) | iv (12) | AES-256-GCM(gzip(json))
 *   kdf 1 = PBKDF2-HMAC-SHA256, [DEFAULT_ITERATIONS] for new backups.
 * Legacy layout (version 1, PrivateInbox <= 1.0.1, 310k iterations), still restorable:
 *   "PIBK" | version=1 (1) | iterations (int32) | salt (16) | iv (12) | AES-256-GCM(gzip(json))
 *
 * The KDF parameters live in the header, which is authenticated as GCM AAD, so tampering with any
 * byte (including the KDF id or iteration count) fails decryption, and the cost can be raised again
 * later without breaking old files. New backups also require a strong password
 * ([BackupPasswordPolicy]); restores accept any password so older backups keep working.
 */
object BackupCrypto {
    private val MAGIC = byteArrayOf('P'.code.toByte(), 'I'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    private const val VERSION_LEGACY: Byte = 1
    private const val VERSION: Byte = 2
    private const val KDF_PBKDF2_SHA256: Byte = 1

    /** OWASP Password Storage Cheat Sheet (2023+) minimum for PBKDF2-HMAC-SHA256. */
    const val DEFAULT_ITERATIONS = 600_000

    /** What version-1 backups were written with (informational; the real value is read from the header). */
    const val LEGACY_ITERATIONS = 310_000
    const val MIN_PASSWORD = BackupPasswordPolicy.MIN_LENGTH
    private const val MIN_ITERATIONS = 10_000
    private const val MAX_ITERATIONS = 5_000_000
    private const val SALT = 16
    private const val IV = 12
    private const val HEADER_V1 = 4 + 1 + 4 + SALT + IV
    private const val HEADER = 4 + 1 + 1 + 4 + SALT + IV

    class BadPasswordOrCorrupt : Exception("wrong password or damaged backup file")

    /** Encrypts a new backup. Throws IllegalArgumentException if [password] fails [BackupPasswordPolicy]. */
    fun encrypt(plain: ByteArray, password: CharArray): ByteArray = encrypt(plain, password, DEFAULT_ITERATIONS)

    /** [iterations] is overridable only for fast unit tests; production always uses [DEFAULT_ITERATIONS]. */
    internal fun encrypt(plain: ByteArray, password: CharArray, iterations: Int): ByteArray {
        BackupPasswordPolicy.check(password)?.let { throw IllegalArgumentException(it.message) }
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "iterations out of range" }
        val rnd = SecureRandom()
        val salt = ByteArray(SALT).also(rnd::nextBytes)
        val iv = ByteArray(IV).also(rnd::nextBytes)
        val header = ByteBuffer.allocate(HEADER)
            .put(MAGIC).put(VERSION).put(KDF_PBKDF2_SHA256).putInt(iterations).put(salt).put(iv).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(header)
        return header + cipher.doFinal(gzip(plain))
    }

    fun decrypt(blob: ByteArray, password: CharArray): ByteArray {
        if (blob.size < 5 || !blob.copyOfRange(0, 4).contentEquals(MAGIC)) throw BadPasswordOrCorrupt()
        val headerSize = when (blob[4]) {
            VERSION_LEGACY -> HEADER_V1
            VERSION -> HEADER
            else -> throw BadPasswordOrCorrupt()
        }
        if (blob.size <= headerSize) throw BadPasswordOrCorrupt()
        val buf = ByteBuffer.wrap(blob)
        buf.position(5)
        if (blob[4] == VERSION && buf.get() != KDF_PBKDF2_SHA256) throw BadPasswordOrCorrupt()
        val iterations = buf.int
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw BadPasswordOrCorrupt()
        val salt = ByteArray(SALT).also { buf.get(it) }
        val iv = ByteArray(IV).also { buf.get(it) }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv))
            cipher.updateAAD(blob, 0, headerSize)
            gunzip(cipher.doFinal(blob, headerSize, blob.size - headerSize))
        } catch (e: GeneralSecurityException) {
            throw BadPasswordOrCorrupt()
        }
    }

    /** KDF parameters of an existing backup file, or null if it isn't one (for UI/diagnostics). */
    fun kdfIterations(blob: ByteArray): Int? {
        if (blob.size < HEADER || !blob.copyOfRange(0, 4).contentEquals(MAGIC)) return null
        val buf = ByteBuffer.wrap(blob)
        return when (blob[4]) {
            VERSION_LEGACY -> buf.getInt(5)
            VERSION -> buf.getInt(6)
            else -> null
        }
    }

    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    private const val MAX_PLAIN = 256L * 1024 * 1024

    private fun gunzip(b: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(b)).use { ins ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_PLAIN) throw BadPasswordOrCorrupt() // zip-bomb guard
            out.write(buf, 0, n)
        }
        out.toByteArray()
    }
}
