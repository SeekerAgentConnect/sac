package io.github.brrenat.seekervault.wallet

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The base58 encoder writes what the sidecar's `requests/action.ts` writes: the vectors below are
 * the public keys the cross-runtime fixtures use, and the leading-zero rule Solana follows.
 */
class Base58Test {
    @Test
    fun writesTheFixtureAddresses() {
        for ((base64, expected) in VECTORS) {
            assertEquals(expected, encodeBase58(Base64.getDecoder().decode(base64)))
        }
    }

    @Test
    fun writesEachLeadingZeroByteAsOne() {
        assertEquals("", encodeBase58(ByteArray(0)))
        assertEquals("1", encodeBase58(byteArrayOf(0)))
        assertEquals("111", encodeBase58(ByteArray(3)))
        // The all-zero 32-byte key is the system program, 32 ones.
        assertEquals("1".repeat(32), encodeBase58(ByteArray(32)))
        assertEquals("12", encodeBase58(byteArrayOf(0, 1)))
    }

    @Test
    fun treatsBytesAsUnsigned() {
        assertEquals("5Q", encodeBase58(byteArrayOf(-1)))
        assertEquals("LUv", encodeBase58(byteArrayOf(-1, -1)))
    }

    @Test
    fun readsBackEveryAddressItWrites() {
        // The wallet takes an account as its raw key bytes, so an address has to decode again.
        for ((base64, address) in VECTORS) {
            val bytes = Base64.getDecoder().decode(base64)
            assertArrayEquals(bytes, decodeBase58(address))
            assertEquals(32, decodeBase58(address)?.size)
        }
        assertArrayEquals(ByteArray(32), decodeBase58("1".repeat(32)))
        assertArrayEquals(byteArrayOf(0, 1), decodeBase58("12"))
        assertArrayEquals(byteArrayOf(-1, -1), decodeBase58("LUv"))
    }

    @Test
    fun refusesAnythingOutsideTheAlphabet() {
        // 0, O, I, and l are left out of base58 on purpose, and so is everything else.
        for (text in
            listOf("", "0", "O", "I", "l", "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4fa+")) {
            assertNull(text, decodeBase58(text))
        }
    }

    private companion object {
        /** Base64 of the 32 key bytes, and the base58 address the fixtures spell them with. */
        val VECTORS =
            listOf(
                "38qu3GgrPX+Ve7u/UkVGHUWhUo828Xn7I9SLEGKql1c=" to
                    "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW",
                "Jb1DzJft7a1pN/gA8T0VerxjKZKXA3nVir1mZB4drZo=" to
                    "3YKUMU99pedShDEe76HuSAHo3dt9CXjBwjN8w8NUo9Wh",
                "xvp6877brTo9ZfNqq8l0MbG75MLS9uDkfKYCA0UvXWE=" to
                    "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
            )
    }
}
