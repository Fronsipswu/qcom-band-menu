package dev.qcom.bandmenu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DaemonAuthTest {

    @Test
    fun newToken_is32LowercaseHex() {
        val token = DaemonAuth.newToken()
        assertEquals(32, token.length)
        assertEquals(token, token.lowercase())
        assertTrue(token.all { it in "0123456789abcdef" })
        assertTrue(DaemonAuth.isValidToken(token))
    }

    @Test
    fun newToken_isNotReused() {
        assertNotEquals(DaemonAuth.newToken(), DaemonAuth.newToken())
    }

    @Test
    fun toHex_knownVector() {
        val bytes = byteArrayOf(0x00, 0x0f, 0x10, 0xa5.toByte(), 0xff.toByte())
        assertEquals("000f10a5ff", DaemonAuth.toHex(bytes))
    }

    @Test
    fun toHex_empty() {
        assertEquals("", DaemonAuth.toHex(ByteArray(0)))
    }

    @Test
    fun isValidToken_acceptsUppercaseHex() {
        assertTrue(DaemonAuth.isValidToken("ABCDEF0123456789ABCDEF0123456789"))
    }

    @Test
    fun isValidToken_rejectsWrongLength() {
        assertFalse(DaemonAuth.isValidToken(""))
        assertFalse(DaemonAuth.isValidToken("abc"))
        assertFalse(DaemonAuth.isValidToken("0".repeat(31)))
        assertFalse(DaemonAuth.isValidToken("0".repeat(33)))
    }

    @Test
    fun isValidToken_rejectsNonHexCharacters() {
        assertFalse(DaemonAuth.isValidToken("g".repeat(32)))
        assertFalse(DaemonAuth.isValidToken("0123456789abcdef0123456789abcde-"))
        assertFalse(DaemonAuth.isValidToken("0123456789abcdef0123456789abcde "))
    }
}
