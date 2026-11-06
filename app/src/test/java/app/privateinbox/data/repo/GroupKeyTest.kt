package app.privateinbox.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupKeyTest {
    @Test fun orderIndependentAndPrefixed() {
        val a = MessageRepository.groupKey(listOf("+1 555 000 1111", "5552222"))
        val b = MessageRepository.groupKey(listOf("5552222", "+15550001111"))
        assertEquals(a, b)
        assertTrue(a.startsWith("group:"))
        assertNotEquals(a, MessageRepository.groupKey(listOf("5552222", "5553333")))
    }
}
