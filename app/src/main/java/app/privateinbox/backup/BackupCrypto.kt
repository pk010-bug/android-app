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
 * Layout: "PIBK" | version(1) | iterations(int32) | salt(16) | iv(12) | AES-256-GCM(gzip(json))
 * Key = PBKDF2-HMAC-SHA256(password, salt, iterations). The header is authenticated as GCM AAD,
 * so tampering with any byte (including the iteration count) fails decryption.
 */
object BackupCrypto {
    private val MAGIC = byteArrayOf('P'.code.toByte(), 'I'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    private const val VERSION: Byte = 1
    const val DEFAULT_ITERATIONS = 310_000
    const val MIN_PASSWORD = 8
    private const val SALT = 16
    private const val IV = 12
    private const val HEADER = 4 + 1 + 4 + SALT + IV

    class BadPasswordOrCorrupt : Exception("wrong password or damaged backup file")

    fun encrypt(plain: ByteArray, password: CharArray, iterations: Int = DEFAULT_ITERATIONS): ByteArray {
        require(password.size >= MIN_PASSWORD) { "password too short" }
        val rnd = SecureRandom()
        val salt = ByteArray(SALT).also(rnd::nextBytes)
        val iv = ByteArray(IV).also(rnd::nextBytes)
        val header = ByteBuffer.allocate(HEADER).put(MAGIC).put(VERSION).putInt(iterations).put(salt).put(iv).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv))
        cipher.updateAAD(header)
        return header + cipher.doFinal(gzip(plain))
    }

    fun decrypt(blob: ByteArray, password: CharArray): ByteArray {
        if (blob.size <= HEADER || !blob.copyOfRange(0, 4).contentEquals(MAGIC) || blob[4] != VERSION) throw BadPasswordOrCorrupt()
        val buf = ByteBuffer.wrap(blob)
        buf.position(5)
        val iterations = buf.int
        if (iterations !in 10_000..5_000_000) throw BadPasswordOrCorrupt()
        val salt = ByteArray(SALT).also { buf.get(it) }
        val iv = ByteArray(IV).also { buf.get(it) }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv))
            cipher.updateAAD(blob, 0, HEADER)
            gunzip(cipher.doFinal(blob, HEADER, blob.size - HEADER))
        } catch (e: GeneralSecurityException) {
            throw BadPasswordOrCorrupt()
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
