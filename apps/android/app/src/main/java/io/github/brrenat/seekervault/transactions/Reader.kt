package io.github.brrenat.seekervault.transactions

/**
 * Reading a Solana wire format, one field at a time, with every length checked against what is
 * actually there. Every method returns null rather than throwing or guessing, so a malformed
 * transaction ends as "this can't be read" and never as a partly-read one.
 */
internal class Reader(private val bytes: ByteArray) {
    var offset = 0
        private set

    val remaining: Int
        get() = bytes.size - offset

    val exhausted: Boolean
        get() = remaining == 0

    /** One byte as 0..255, or null at the end of the input. */
    fun byte(): Int? {
        if (remaining < 1) return null
        return bytes[offset++].toInt() and 0xff
    }

    /** The next [count] bytes, or null when there aren't that many. */
    fun bytes(count: Int): ByteArray? {
        if (count < 0 || remaining < count) return null
        val slice = bytes.copyOfRange(offset, offset + count)
        offset += count
        return slice
    }

    /** A little-endian unsigned 64-bit integer, kept as a [ULong] because it can exceed Long. */
    fun u64(): ULong? {
        val raw = bytes(8) ?: return null
        var value = 0UL
        for (index in 7 downTo 0) {
            value = (value shl 8) or (raw[index].toULong() and 0xffUL)
        }
        return value
    }

    /** A little-endian unsigned 32-bit integer. */
    fun u32(): UInt? {
        val raw = bytes(4) ?: return null
        var value = 0U
        for (index in 3 downTo 0) {
            value = (value shl 8) or (raw[index].toUInt() and 0xffU)
        }
        return value
    }

    /**
     * A compact-u16 (shortvec): 1 to 3 bytes, seven bits at a time, with the high bit marking that
     * another byte follows. Solana writes every array length this way.
     *
     * The encoding must be the shortest one for its value and must not exceed 16 bits; anything
     * else is a malformed length, not a large one, and is refused.
     */
    fun compactU16(): Int? {
        var value = 0
        for (shift in 0 until 3) {
            val byte = byte() ?: return null
            val part = byte and 0x7f
            value = value or (part shl (shift * 7))
            if (byte and 0x80 == 0) {
                // A continuation byte that carried nothing would be a longer spelling of a value
                // that fits in fewer bytes; two encodings of one length must never both be read.
                if (shift > 0 && part == 0) return null
                return if (value <= 0xffff) value else null
            }
        }
        return null
    }
}
