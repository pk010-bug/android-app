package app.privateinbox.security

/**
 * File format for the Keystore-wrapped database passphrase:
 *   [1 byte version][1 byte iv length][iv][AES-GCM ciphertext+tag]
 * Pure so it can be unit-tested without a device.
 */
object WrappedKeyCodec {
    const val VERSION: Byte = 1

    data class Wrapped(val iv: ByteArray, val ciphertext: ByteArray) {
        override fun equals(other: Any?) =
            other is Wrapped && iv.contentEquals(other.iv) && ciphertext.contentEquals(other.ciphertext)
        override fun hashCode() = 31 * iv.contentHashCode() + ciphertext.contentHashCode()
    }

    fun encode(w: Wrapped): ByteArray {
        require(w.iv.size in 1..255)
        return byteArrayOf(VERSION, w.iv.size.toByte()) + w.iv + w.ciphertext
    }

    fun decode(bytes: ByteArray): Wrapped? {
        if (bytes.size < 3 || bytes[0] != VERSION) return null
        val ivLen = bytes[1].toInt() and 0xFF
        if (ivLen == 0 || bytes.size <= 2 + ivLen) return null
        return Wrapped(bytes.copyOfRange(2, 2 + ivLen), bytes.copyOfRange(2 + ivLen, bytes.size))
    }
}
