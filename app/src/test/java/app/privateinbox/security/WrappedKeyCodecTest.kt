package app.privateinbox.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WrappedKeyCodecTest {
    @Test fun roundTrips() {
        val w = WrappedKeyCodec.Wrapped(ByteArray(12) { it.toByte() }, ByteArray(48) { (it * 3).toByte() })
        assertEquals(w, WrappedKeyCodec.decode(WrappedKeyCodec.encode(w)))
    }

    @Test fun rejectsCorruptInput() {
        assertNull(WrappedKeyCodec.decode(ByteArray(0)))
        assertNull(WrappedKeyCodec.decode(byteArrayOf(9, 12, 1, 2, 3)))
        assertNull(WrappedKeyCodec.decode(byteArrayOf(1, 12, 1, 2, 3)))
        assertNull(WrappedKeyCodec.decode(byteArrayOf(1, 0, 1, 2, 3)))
    }
}
