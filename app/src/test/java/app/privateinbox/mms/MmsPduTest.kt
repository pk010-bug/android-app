package app.privateinbox.mms

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MmsPduTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun str(s: String) = (s.toByteArray() + 0).map { it.toInt() and 0xFF }.toIntArray()

    @Test fun parsesNotificationInd() {
        val from = str("+15551234567/TYPE=PLMN")
        val loc = str("http://mmsc.example/abc")
        val pdu = bytes(
            0x8C, 0x82,
            0x98, *str("T123"),
            0x8D, 0x92,
            0x89, from.size + 1, 0x80, *from,
            0x8A, 0x80, // message class personal (skipped)
            0x8E, 0x02, 0x10, 0x00, // size 4096
            0x88, 0x05, 0x81, 0x03, 0x01, 0x51, 0x80, // expiry (skipped)
            0x83, *loc,
        )
        val p = MmsPdu.parse(pdu)
        assertEquals(MmsPdu.TYPE_NOTIFICATION_IND, p.messageType)
        assertEquals("T123", p.transactionId)
        assertEquals("+15551234567", p.from)
        assertEquals(4096L, p.messageSize)
        assertEquals("http://mmsc.example/abc", p.contentLocation)
    }

    @Test fun sendReqRoundTrips() {
        val jpeg = ByteArray(300) { (it * 7).toByte() }
        val encoded = MmsPdu.encodeSendReq(
            "tx1", listOf("+1 555 000 1111", "5552222"), "Hi",
            listOf(
                MmsPdu.OutPart("text/plain", "text_0.txt", "héllo".toByteArray()),
                MmsPdu.OutPart("image/jpeg", "image_0.jpg", jpeg),
            ),
        )
        val p = MmsPdu.parse(encoded)
        assertEquals(MmsPdu.TYPE_SEND_REQ, p.messageType)
        assertEquals(listOf("+15550001111", "5552222"), p.to)
        assertEquals("Hi", p.subject)
        assertEquals(2, p.parts.size)
        assertEquals("héllo", p.parts[0].text())
        assertEquals("image/jpeg", p.parts[1].contentType)
        assertEquals("image_0.jpg", p.parts[1].name)
        assertArrayEquals(jpeg, p.parts[1].data)
    }

    @Test fun notifyRespEncodes() {
        val p = MmsPdu.parse(MmsPdu.encodeNotifyResp("abc"))
        assertEquals(MmsPdu.TYPE_NOTIFYRESP_IND, p.messageType)
        assertEquals("abc", p.transactionId)
    }

    @Test fun truncatedInputIsRejected() {
        val good = MmsPdu.encodeSendReq("t", listOf("5551234"), null,
            listOf(MmsPdu.OutPart("image/png", "a.png", ByteArray(100))))
        for (cut in listOf(3, 12, good.size - 5)) {
            assertThrows(MmsPdu.MalformedPdu::class.java) { MmsPdu.parse(good.copyOf(cut)) }
        }
    }

    @Test fun hugePartCountRejected() {
        val pdu = bytes(0x8C, 0x84, 0x84, 0xA3, 0x81, 0x00) // 128 parts
        assertThrows(MmsPdu.MalformedPdu::class.java) { MmsPdu.parse(pdu) }
    }

    @Test fun fuzzNeverCrashesUnexpectedly() {
        val rnd = java.util.Random(42)
        repeat(3000) {
            val b = ByteArray(rnd.nextInt(80)).also(rnd::nextBytes)
            if (b.isNotEmpty()) b[0] = 0x8C.toByte()
            try { MmsPdu.parse(b) } catch (_: MmsPdu.MalformedPdu) { } catch (_: IllegalArgumentException) { }
        }
    }
}
