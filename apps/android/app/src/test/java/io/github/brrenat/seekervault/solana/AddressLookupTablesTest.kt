package io.github.brrenat.seekervault.solana

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.jupiter.FakeChain
import io.github.brrenat.seekervault.jupiter.OWNER
import io.github.brrenat.seekervault.jupiter.PROTOCOL_SIGNER
import io.github.brrenat.seekervault.jupiter.SOMEONE_ELSE
import io.github.brrenat.seekervault.jupiter.Step
import io.github.brrenat.seekervault.jupiter.TABLE_ONE
import io.github.brrenat.seekervault.jupiter.TABLE_TWO
import io.github.brrenat.seekervault.jupiter.compact
import io.github.brrenat.seekervault.jupiter.tableFor
import io.github.brrenat.seekervault.jupiter.versioned
import io.github.brrenat.seekervault.transactions.DecodeResult
import io.github.brrenat.seekervault.transactions.SYSTEM_PROGRAM
import io.github.brrenat.seekervault.transactions.TOKEN_PROGRAM
import io.github.brrenat.seekervault.transactions.decodeTransaction
import io.github.brrenat.seekervault.wallet.decodeBase58
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Resolving a versioned message's accounts, and every way it must refuse to (SEE-94).
 *
 * `PredictionFixturesTest` proves this reads the real tables of a real transaction. These are the
 * refusals, which no chain would ever serve on request: a table that is missing, one owned by the
 * wrong program, one that has been deactivated, one whose bytes are the wrong length, an index past
 * the end, and a message that reaches past the rebuilt list.
 *
 * The order the list is rebuilt in has its own test, because getting it wrong would resolve every
 * instruction to the wrong addresses — silently, which is the worst way for a review to be wrong.
 */
@RunWith(AndroidJUnit4::class)
class AddressLookupTablesTest {

    private val far = "9vZKp1SBxvxdSNASBM9qUnDQH1ygXD1XS3SYmShPeE8z"

    /** A message whose accounts are split between itself and one table. */
    private fun message(table: String = TABLE_ONE) =
        versioned(
            payer = OWNER,
            signers = listOf(OWNER, PROTOCOL_SIGNER),
            filled = listOf(1),
            steps =
                listOf(
                    Step(TOKEN_PROGRAM, listOf(OWNER, SOMEONE_ELSE, far), byteArrayOf(1)),
                    Step(SYSTEM_PROGRAM, listOf(OWNER, far), byteArrayOf(2)),
                ),
            table = table,
        )

    private fun decoded(bytes: com.google.protobuf.ByteString) =
        (decodeTransaction(bytes.toByteArray(), resolvable = true) as DecodeResult.Decoded)
            .transaction

    private fun resolve(chain: SolanaAccounts, table: String = TABLE_ONE) = runBlocking {
        resolveLookups(decoded(message(table).transaction), chain)
    }

    private fun problem(chain: SolanaAccounts): LookupProblem =
        try {
            resolve(chain)
            throw AssertionError("it resolved")
        } catch (e: LookupException) {
            e.problem
        }

    @Test
    fun aMessageThatNamesNoTableIsResolvedWithoutReadingAnything() {
        // The point of this component is the tables. A self-contained message needs no endpoint,
        // and asks for none — which is also why a transfer and a swap never touch the chain.
        val chain = FakeChain()
        val selfContained =
            io.github.brrenat.seekervault.jupiter.swapTransaction(
                io.github.brrenat.seekervault.jupiter.usdcTerms(),
                5UL,
                io.github.brrenat.seekervault.jupiter.quoteFor(
                    io.github.brrenat.seekervault.jupiter.usdcTerms(),
                    5UL,
                ),
            )

        val resolved = runBlocking { resolveLookups(decoded(selfContained), chain) }

        assertEquals(emptyList<List<String>>(), chain.asked)
        assertEquals(resolved.transaction.accounts, resolved.accounts)
        assertEquals(resolved.accounts.size, resolved.static)
    }

    @Test
    fun theTablesAskedAboutAreTheOnesTheMessageNames() {
        val built = message()
        val chain = FakeChain(built.tables)

        val resolved = resolve(chain)

        assertEquals(listOf(listOf(TABLE_ONE)), chain.asked)
        // The static accounts keep their places, and the table's follow them.
        assertEquals(resolved.transaction.accounts, resolved.accounts.take(resolved.static))
        assertTrue(resolved.accounts.size > resolved.static)
        assertTrue(resolved.fromTable(far))
        assertTrue(!resolved.fromTable(OWNER))
        // And every instruction now names addresses rather than numbers.
        val instruction = resolved.transaction.instructions.first()
        assertEquals(TOKEN_PROGRAM, resolved.programOf(instruction))
        assertEquals(listOf(OWNER, SOMEONE_ELSE, far), resolved.accountsOf(instruction))
    }

    @Test
    fun everyWayATableCanBeUnusableIsRefused() {
        val built = message()
        assertEquals(
            LookupProblem.Missing,
            problem(FakeChain(built.tables).apply { missing = setOf(TABLE_ONE) }),
        )
        assertEquals(
            LookupProblem.NotATable,
            problem(FakeChain(built.tables).apply { owner = TOKEN_PROGRAM }),
        )
        assertEquals(
            LookupProblem.Deactivated,
            problem(FakeChain(built.tables).apply { deactivated = setOf(TABLE_ONE) }),
        )
        assertEquals(
            LookupProblem.Malformed,
            problem(FakeChain(built.tables).apply { truncate = setOf(TABLE_ONE) }),
        )
        // A table that no longer holds what the message takes from it.
        assertEquals(
            LookupProblem.IndexOutOfRange,
            problem(FakeChain(built.tables).apply { replace = mapOf(TABLE_ONE to listOf(OWNER)) }),
        )
    }

    @Test
    fun aChainThatCouldNotBeReadIsItsOwnFailure() {
        // It is not a lookup problem: nothing was established about the tables at all, and the
        // reason the owner is shown is that the endpoint could not be reached.
        val failure =
            try {
                resolve(FakeChain(message().tables).apply { fails = SolanaProblem.Unreachable })
                throw AssertionError("it resolved")
            } catch (e: SolanaException) {
                e
            }

        assertEquals(SolanaProblem.Unreachable, failure.problem)
    }

    @Test
    fun aMessageThatReachesPastTheRebuiltListIsRefused() {
        // An instruction whose account index is beyond everything the message and its table
        // supply. The runtime would refuse to load it; so does this, rather than reviewing the
        // part of it that happened to resolve.
        val chain = FakeChain(mapOf(TABLE_ONE to listOf(OWNER, SOMEONE_ELSE)))

        val problem =
            try {
                runBlocking { resolveLookups(decoded(outOfRange()), chain) }
                throw AssertionError("it resolved")
            } catch (e: LookupException) {
                e.problem
            }

        assertEquals(LookupProblem.AccountOutOfRange, problem)
    }

    /** A message with one instruction that names account 200, which nothing supplies. */
    private fun outOfRange(): com.google.protobuf.ByteString {
        val out = ArrayList<Byte>()
        out += compact(1)
        out += ByteArray(64).toList()
        out += 0x80.toByte()
        out += listOf(1.toByte(), 0.toByte(), 1.toByte())
        out += compact(2)
        listOf(OWNER, SYSTEM_PROGRAM).forEach { out += checkNotNull(decodeBase58(it)).toList() }
        out += ByteArray(32) { 5 }.toList()
        out += compact(1)
        out += 1.toByte()
        out += compact(1)
        out += 200.toByte()
        out += compact(1)
        out += 9.toByte()
        out += compact(1)
        out += checkNotNull(decodeBase58(TABLE_ONE)).toList()
        out += compact(1)
        out += 0.toByte()
        out += compact(0)
        return com.google.protobuf.ByteString.copyFrom(out.toByteArray())
    }

    @Test
    fun theRebuiltListIsInTheRuntimesOwnOrder() {
        // Two tables, writable and readonly indexes in both. The runtime takes every table's
        // writable indexes first, in the order the message names the tables, and then every
        // table's readonly ones. Any other order resolves each instruction to the wrong addresses.
        val first = listOf("11111111111111111111111111111111", OWNER, SOMEONE_ELSE)
        val second = listOf(far, PROTOCOL_SIGNER, TOKEN_PROGRAM)
        val bytes = twoTables()
        val chain = FakeChain(mapOf(TABLE_ONE to first, TABLE_TWO to second))

        val resolved = runBlocking { resolveLookups(decoded(bytes), chain) }

        assertEquals(
            listOf(
                // Static first, as the message wrote them.
                OWNER,
                SYSTEM_PROGRAM,
                // Then table one's writable index, then table two's.
                first[1],
                second[1],
                // Then table one's readonly index, then table two's.
                first[2],
                second[2],
            ),
            resolved.accounts,
        )
        assertEquals(2, resolved.static)
    }

    /** A message with two tables: one writable and one readonly index in each. */
    private fun twoTables(): com.google.protobuf.ByteString {
        val out = ArrayList<Byte>()
        out += compact(1)
        out += ByteArray(64).toList()
        out += 0x80.toByte()
        out += listOf(1.toByte(), 0.toByte(), 1.toByte())
        out += compact(2)
        listOf(OWNER, SYSTEM_PROGRAM).forEach {
            out += checkNotNull(decodeBase58(it)).toList()
        }
        out += ByteArray(32) { 5 }.toList()
        out += compact(1)
        out += 1.toByte()
        out += compact(1)
        out += 0.toByte()
        out += compact(1)
        out += 9.toByte()
        out += compact(2)
        listOf(TABLE_ONE, TABLE_TWO).forEach { table ->
            out += checkNotNull(decodeBase58(table)).toList()
            out += compact(1)
            out += 1.toByte()
            out += compact(1)
            out += 2.toByte()
        }
        return com.google.protobuf.ByteString.copyFrom(out.toByteArray())
    }

    @Test
    fun aTableAccountIsReadAsTheProgramWritesOne() {
        val addresses = listOf(OWNER, SOMEONE_ELSE)
        val account = tableFor(addresses)

        assertEquals(addresses, lookupTableAddresses(TABLE_ONE, account))
        // A table with no addresses yet is a table, and holds none.
        assertEquals(emptyList<String>(), lookupTableAddresses(TABLE_ONE, tableFor(emptyList())))
        // And the header is not optional.
        assertEquals(
            LookupProblem.Malformed,
            runCatching {
                lookupTableAddresses(
                    TABLE_ONE,
                    AccountSnapshot(ADDRESS_LOOKUP_TABLE_PROGRAM, ByteArray(10), false),
                )
            }
                .exceptionOrNull()
                .let { (it as LookupException).problem },
        )
        assertNull(runCatching { lookupTableAddresses(TABLE_ONE, null) }.getOrNull())
    }
}
