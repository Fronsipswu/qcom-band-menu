package dev.qcom.bandmenu

import java.security.SecureRandom

/**
 * Shared-token authentication helpers (F3, daemon v4.6.0).
 *
 * The app generates a 128-bit random token once, persists it in app-private
 * storage, writes it to a file in filesDir and passes that path to the daemon
 * via `-tokenfile`. Every request then carries `"token"` and the daemon echoes
 * `"auth"`; a peer that cannot produce the token is not the real daemon.
 *
 * The generated token is always 32 lowercase hex characters, which is the
 * canonical form the daemon stores when its tokenfile contains hex.
 */
object DaemonAuth {

    const val TOKEN_BYTES = 16
    const val TOKEN_HEX_LENGTH = TOKEN_BYTES * 2

    private const val HEX = "0123456789abcdef"

    /** Generates a fresh 128-bit token as a 32-character lowercase hex string. */
    fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return toHex(bytes)
    }

    /** Lowercase hex encoding. */
    fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    /** True for exactly 32 hex characters (upper- or lower-case). */
    fun isValidToken(token: String): Boolean {
        if (token.length != TOKEN_HEX_LENGTH) return false
        return token.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }
}
