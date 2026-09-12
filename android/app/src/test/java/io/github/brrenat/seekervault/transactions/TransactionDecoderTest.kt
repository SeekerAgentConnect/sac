package io.github.brrenat.seekervault.transactions

import io.github.brrenat.seekervault.wallet.decodeBase58
import io.github.brrenat.seekervault.wallet.encodeBase58
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decoder's own rules (SAW-020). The shared fixtures prove it reads the sidecar's real
 * transactions; these prove it refuses everything else, which is the half that keeps a
 * half-understood transaction off the review screen.
 */
class TransactionDecoderTest {
    private val key = ByteArray(32) { 7 }

    /** A minimal but well-formed v0 transaction with one instruction and no accounts in it. */
    private fun message(
        accounts: Int = 2,
        instructions: List<Triple<Int, List<Int>, ByteArray>> =
            listOf(Triple(1, listOf(), byteArrayOf(9))),
        lookups: Int = 0,
        versioned: Boolean = true,
        signatures: Int = 1,
        signed: Boolean = false,
        trailing: ByteArray = ByteArray(0),
    ): ByteArray {
        val out = ArrayList<Byte>()
        out += compact(signatures)
        repeat(signatures) { out += ByteArray(64) { if (signed) 3 else 0 }.toList() }
        if (versioned) out += 0x80.toByte()
        out += listOf(1.toByte(), 0.toByte(), 1.toByte())
        out += compact(accounts)
        repeat(accounts) { index -> out += ByteArray(32) { (index + 1).toByte() }.toList() }
        out += ByteArray(32) { 5 }.toList()
        out += compact(instructions.size)
        for ((program, indexes, data) in instructions) {
            out += program.toByte()
            out += compact(indexes.size)
            indexes.forEach { out += it.toByte() }
            out += compact(data.size)
            out += data.toList()
        }
        if (versioned) out += compact(lookups)
        out += trailing.toList()
        return out.toByteArray()
    }

    private fun compact(value: Int): List<Byte> {
        var rest = value
        val out = ArrayList<Byte>()
        while (true) {
            val part = rest and 0x7f
            rest = rest shr 7
            if (rest == 0) {
                out += part.toByte()
                return out
            }
            out += (part or 0x80).toByte()
        }
    }

    private fun decoded(bytes: ByteArray): DecodedTransaction {
        val result = decodeTransaction(bytes)
        assertTrue("expected a decode, got $result", result is DecodeResult.Decoded)
        return (result as DecodeResult.Decoded).transaction
    }

    private fun failure(bytes: ByteArray): DecodeFailure {
        val result = decodeTransaction(bytes)
        assertTrue("expected a failure, got $result", result is DecodeResult.Failed)
        return (result as DecodeResult.Failed).failure
    }

    @Test
    fun readsAVersionedMessage() {
        val transaction = decoded(message())
        assertEquals(0, transaction.version)
        assertEquals(1, transaction.requiredSignatures)
        assertEquals(2, transaction.accounts.size)
        assertEquals(1, transaction.instructions.size)
        assertTrue(transaction.unsigned)
        assertEquals(transaction.accounts.first(), transaction.feePayer)
        assertEquals(listOf(transaction.accounts.first()), transaction.signers)
    }

    @Test
    fun readsALegacyMessage() {
        val transaction = decoded(message(versioned = false))
        assertNull(transaction.version)
        assertEquals(1, transaction.requiredSignatures)
    }

    @Test
    fun seesASignatureThatIsAlreadyThere() {
        assertTrue(decoded(message()).unsigned)
        assertTrue(!decoded(message(signed = true)).unsigned)
    }

    @Test
    fun refusesBytesLeftOverAtTheEnd() {
        // Content nobody read is exactly what must not reach the owner as understood.
        assertEquals(DecodeFailure.Malformed, failure(message(trailing = byteArrayOf(0))))
    }

    @Test
    fun refusesATruncatedMessage() {
        val sound = message()
        for (cut in 1..4) {
            assertEquals(
                "cut $cut",
                DecodeFailure.Malformed,
                failure(sound.copyOfRange(0, sound.size - cut)),
            )
        }
    }

    @Test
    fun refusesAMessageThatLoadsAccountsFromALookupTable() {
        // A lookup table names accounts this phone can't see, so what the transaction touches
        // can't be established offline at all.
        assertEquals(DecodeFailure.AddressTableLookup, failure(message(lookups = 1)))
    }

    @Test
    fun refusesAVersionItDoesNotRead() {
        val bytes = message()
        val versionIndex = 1 + 64
        bytes[versionIndex] = 0x81.toByte()
        assertEquals(DecodeFailure.UnsupportedVersion, failure(bytes))
    }

    @Test
    fun refusesAHeaderThatContradictsItself() {
        // More signers than there are accounts, and no signer at all, are both impossible.
        assertEquals(DecodeFailure.Malformed, failure(message(accounts = 0)))
    }

    @Test
    fun readsEveryCompactLengthOnlyOneWay() {
        // 0x80 0x00 is a second spelling of zero. Two encodings of one length would let the same
        // bytes be read two ways, which is how a parser and a signer come to disagree.
        assertNull(Reader(byteArrayOf(0x80.toByte(), 0x00)).compactU16())
        assertNull(Reader(byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x00)).compactU16())
        assertEquals(0, Reader(byteArrayOf(0x00)).compactU16())
        assertEquals(127, Reader(byteArrayOf(0x7f)).compactU16())
        assertEquals(128, Reader(byteArrayOf(0x80.toByte(), 0x01)).compactU16())
        assertEquals(
            0xffff,
            Reader(byteArrayOf(0xff.toByte(), 0xff.toByte(), 0x03)).compactU16(),
        )
        // Above 16 bits is a malformed length, not a large one.
        assertNull(Reader(byteArrayOf(0xff.toByte(), 0xff.toByte(), 0x04)).compactU16())
    }

    @Test
    fun readsLittleEndianNumbersExactly() {
        val max = ByteArray(8) { 0xff.toByte() }
        assertEquals(ULong.MAX_VALUE, Reader(max).u64())
        assertEquals(UInt.MAX_VALUE, Reader(ByteArray(4) { 0xff.toByte() }).u32())
        assertEquals(1UL, Reader(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0)).u64())
        assertNull(Reader(byteArrayOf(1, 2, 3)).u64())
    }

    @Test
    fun refusesAnInstructionThatPointsOutsideTheAccounts() {
        val transaction =
            decoded(message(instructions = listOf(Triple(9, listOf(9), byteArrayOf()))))
        assertNull(transaction.programOf(transaction.instructions.first()))
        assertNull(transaction.accountsOf(transaction.instructions.first()))
        assertNull(transaction.read(transaction.instructions.first()))
    }

    @Test
    fun writesEveryAccountAsBase58() {
        val transaction = decoded(message())
        assertEquals(encodeBase58(ByteArray(32) { 1 }), transaction.accounts.first())
        assertEquals(encodeBase58(ByteArray(32) { 5 }), transaction.recentBlockhash)
        assertEquals(32, decodeBase58(transaction.accounts.first())?.size)
        assertEquals(32, key.size)
    }
}
