package dev.qcom.bandmenu.network

/** A QMI response frame or TLV that fails validation. */
class QmiFormatException(message: String) : Exception(message)

/**
 * One validated NAS service response: 7-byte header
 * `[flags u8][txid u16][msg u16][body_len u16]` plus TLVs `[tag u8][len u16][value]`.
 * The daemon already matched txid/msg; this re-checks every length so a
 * corrupt frame never reaches a decoder.
 */
class QmiFrame private constructor(
    val messageId: Int,
    val tlvs: Map<Int, ByteArray>,
    /** QMI result (0 = success) and error code from TLV 0x02. */
    val result: Int,
    val error: Int
) {
    val ok: Boolean get() = result == 0

    fun tlv(tag: Int): ByteArray? = tlvs[tag]

    companion object {
        fun parse(bytes: ByteArray): QmiFrame {
            if (bytes.size < 7) throw QmiFormatException("frame shorter than header (${bytes.size})")
            if (bytes[0].toInt() and 0xFF != 0x02) throw QmiFormatException("not a response frame")
            val msg = u16(bytes, 3)
            val bodyLen = u16(bytes, 5)
            if (7 + bodyLen != bytes.size) {
                throw QmiFormatException("body length $bodyLen does not match frame size ${bytes.size}")
            }
            val tlvs = LinkedHashMap<Int, ByteArray>()
            var p = 7
            while (p < bytes.size) {
                if (p + 3 > bytes.size) throw QmiFormatException("truncated TLV header at $p")
                val tag = bytes[p].toInt() and 0xFF
                val len = u16(bytes, p + 1)
                if (p + 3 + len > bytes.size) throw QmiFormatException("TLV 0x%02x overruns frame".format(tag))
                tlvs[tag] = bytes.copyOfRange(p + 3, p + 3 + len)
                p += 3 + len
            }
            val res = tlvs[0x02] ?: throw QmiFormatException("missing result TLV")
            if (res.size != 4) throw QmiFormatException("result TLV has length ${res.size}")
            return QmiFrame(msg, tlvs, u16(res, 0), u16(res, 2))
        }

        fun parseHex(hex: String): QmiFrame = parse(hexToBytes(hex))

        private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    }
}

fun hexToBytes(hex: String): ByteArray {
    if (hex.length % 2 != 0) throw QmiFormatException("odd hex length")
    return ByteArray(hex.length / 2) { i ->
        val hi = Character.digit(hex[2 * i], 16)
        val lo = Character.digit(hex[2 * i + 1], 16)
        if (hi < 0 || lo < 0) throw QmiFormatException("bad hex digit")
        ((hi shl 4) or lo).toByte()
    }
}

/** Bounds-checked little-endian reader over one TLV value. */
class TlvReader(private val b: ByteArray) {
    var offset = 0
        private set
    val remaining: Int get() = b.size - offset

    private fun need(n: Int) {
        if (offset + n > b.size) throw QmiFormatException("TLV value too short (need ${offset + n}, have ${b.size})")
    }

    fun u8(): Int { need(1); return b[offset++].toInt() and 0xFF }
    fun i8(): Int { need(1); return b[offset++].toInt() }
    fun u16(): Int { need(2); val v = (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8); offset += 2; return v }
    fun i16(): Int = u16().toShort().toInt()
    fun u32(): Long { need(4); var v = 0L; for (i in 0 until 4) v = v or ((b[offset + i].toLong() and 0xFF) shl (8 * i)); offset += 4; return v }
    fun i32(): Int = u32().toInt()
    fun u64(): Long { need(8); var v = 0L; for (i in 0 until 8) v = v or ((b[offset + i].toLong() and 0xFF) shl (8 * i)); offset += 8; return v }
    fun f32(): Float = java.lang.Float.intBitsToFloat(i32())
    fun bytes(n: Int): ByteArray { need(n); return b.copyOfRange(offset, offset + n).also { offset += n } }
    fun skip(n: Int) { need(n); offset += n }

    /** Requires the whole value to be consumed. */
    fun done() {
        if (offset != b.size) throw QmiFormatException("TLV has ${b.size - offset} unparsed bytes")
    }
}

/** Common sentinels. */
internal const val INT16_INVALID = -32768
internal const val INT32_INVALID = Int.MIN_VALUE
internal const val UINT32_INVALID = 0xFFFFFFFFL
