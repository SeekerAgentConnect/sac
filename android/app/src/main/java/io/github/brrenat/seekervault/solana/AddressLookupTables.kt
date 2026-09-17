package io.github.brrenat.seekervault.solana

import io.github.brrenat.seekervault.transactions.DecodedInstruction
import io.github.brrenat.seekervault.transactions.DecodedTransaction
import io.github.brrenat.seekervault.transactions.PUBLIC_KEY_BYTES
import io.github.brrenat.seekervault.wallet.encodeBase58

/**
 * Resolving the accounts a versioned message loads from address lookup tables (SEE-94).
 *
 * A versioned Solana message carries some of its accounts and refers to the rest by index into
 * tables stored on the chain. Until those tables are read, an instruction's account indexes are
 * numbers with no meaning, and a review of such a transaction cannot say whose accounts it touches
 * — which is why every reviewer in this app refuses one until it has been resolved
 * (docs/security.md#resolving-a-lookup-table).
 *
 * ## What is checked, and why each check is here
 *
 * - **The table is a table.** Its account is owned by the address lookup table program and its
 *   state discriminator says so. Without this, any account whatever could be presented as a table
 *   and its bytes read as addresses.
 * - **Its format holds.** The header is the length it must be and what follows is a whole number of
 *   addresses. A table one byte short would otherwise shift every address after the gap.
 * - **It is still usable.** A table being deactivated is recorded, because the runtime will stop
 *   loading from it and a transaction built against it will fail — which the owner should hear
 *   before they sign rather than afterwards.
 * - **Every index exists.** An index past the end of a table resolves to nothing, and a message
 *   with one is not a message this app will review.
 * - **The rebuilt list is in the runtime's own order**: the static accounts, then every table's
 *   writable indexes in the order the message names the tables, then every table's readonly indexes
 *   in that same order. Any other order resolves each instruction to the wrong addresses, silently,
 *   which is the worst way for a review to be wrong.
 *
 * None of that says the transaction is safe or is what the owner asked for. It says the account
 * list is the one the chain will use. What the instructions then do with it is the reviewer's
 * business, and resolving changes nothing about how strict that has to be.
 */

/** The program that owns every address lookup table. */
const val ADDRESS_LOOKUP_TABLE_PROGRAM: String = "AddressLookupTab1e1111111111111111111111111"

/**
 * The fixed part of a table's account data: a `u32` state discriminator, the slot it was
 * deactivated at, the slot it was last extended at, the index that extension started from, an
 * optional authority, and two bytes of padding. The addresses follow it.
 */
const val LOOKUP_TABLE_HEADER_BYTES: Int = 56

/** The discriminator of an initialized lookup table, as the program writes it. */
private const val LOOKUP_TABLE_STATE: Int = 1

/** A table that has not been deactivated records the largest slot there is. */
private const val NEVER_DEACTIVATED: ULong = ULong.MAX_VALUE

/** Why a message's accounts could not be resolved. Each one blocks signing and says which it is. */
enum class LookupProblem(val code: String) {
    /** The chain could not be read at all; [SolanaProblem] says why. */
    Unread("tables_unread"),
    /** A table the message names does not exist. */
    Missing("table_missing"),
    /** An account that is not owned by the address lookup table program. */
    NotATable("not_a_table"),
    /** Owned by the right program, but not an initialized table, or not a readable shape. */
    Malformed("table_malformed"),
    /** The table has been deactivated, so the runtime will not load from it. */
    Deactivated("table_deactivated"),
    /** The message takes an index the table does not have. */
    IndexOutOfRange("index_out_of_range"),
    /** An instruction refers to an account beyond the rebuilt list. */
    AccountOutOfRange("account_out_of_range"),
}

class LookupException(val problem: LookupProblem, val detail: String? = null) :
    Exception("lookup: ${problem.code}${detail?.let { ": $it" } ?: ""}")

/**
 * A message whose every account index resolves to an address.
 *
 * It is a separate type from [DecodedTransaction] on purpose. A decoded transaction is what the
 * bytes say; this is what the bytes say *plus* what the chain says, and the difference matters
 * enough to be visible in the type a reviewer is handed.
 */
data class ResolvedTransaction(
    val transaction: DecodedTransaction,
    /** Every account the message can name, in the runtime's order. */
    val accounts: List<String>,
    /** How many of them were written into the message itself. */
    val static: Int,
) {
    /** Whether [address] came from a table rather than from the message. */
    fun fromTable(address: String): Boolean = accounts.indexOf(address) >= static

    /** The program an instruction runs. */
    fun programOf(instruction: DecodedInstruction): String? =
        accounts.getOrNull(instruction.programIdIndex)

    /** The accounts an instruction names, or null when any index is outside the resolved list. */
    fun accountsOf(instruction: DecodedInstruction): List<String>? =
        instruction.accountIndexes.map { accounts.getOrNull(it) ?: return null }
}

/** The addresses one table holds, as its account data carries them. */
fun lookupTableAddresses(table: String, account: AccountSnapshot?): List<String> {
    if (account == null) throw LookupException(LookupProblem.Missing, table)
    if (account.owner != ADDRESS_LOOKUP_TABLE_PROGRAM) {
        throw LookupException(LookupProblem.NotATable, table)
    }
    val data = account.data
    if (data.size < LOOKUP_TABLE_HEADER_BYTES) {
        throw LookupException(LookupProblem.Malformed, table)
    }
    val state = little(data, 0, 4)
    if (state != LOOKUP_TABLE_STATE.toULong()) {
        throw LookupException(LookupProblem.Malformed, table)
    }
    if (little(data, 4, 8) != NEVER_DEACTIVATED) {
        throw LookupException(LookupProblem.Deactivated, table)
    }
    val addresses = data.size - LOOKUP_TABLE_HEADER_BYTES
    if (addresses % PUBLIC_KEY_BYTES != 0) {
        throw LookupException(LookupProblem.Malformed, table)
    }
    return (0 until addresses / PUBLIC_KEY_BYTES).map {
        val at = LOOKUP_TABLE_HEADER_BYTES + it * PUBLIC_KEY_BYTES
        encodeBase58(data.copyOfRange(at, at + PUBLIC_KEY_BYTES))
    }
}

/**
 * Resolves [transaction]'s accounts, reading the tables it names through [chain].
 *
 * A transaction that names no table is returned as it is, with nothing read: the point of this is
 * the tables, and a self-contained message needs no endpoint and asks for none.
 *
 * Throws [LookupException] for anything that leaves an index unresolved, and [SolanaException] when
 * the chain could not be read. Either one blocks signing — there is no partial resolution and no
 * falling back to reviewing the part that happened to be legible.
 */
suspend fun resolveLookups(
    transaction: DecodedTransaction,
    chain: SolanaAccounts,
): ResolvedTransaction {
    if (transaction.lookups.isEmpty()) {
        return ResolvedTransaction(transaction, transaction.accounts, transaction.accounts.size)
    }
    // The tables the message itself names, in its own order, and never a list from anywhere else.
    val named = transaction.lookups.map { it.table }
    val read = chain.accounts(named)
    val contents =
        named.zip(read).associate { (table, account) ->
            table to lookupTableAddresses(table, account)
        }
    val resolved = transaction.accounts.toMutableList()
    // Writable first, across every table in order, then readonly across every table in order. This
    // is the runtime's own order and the reason this function exists.
    for (lookup in transaction.lookups) {
        resolved += lookup.writable.map { pick(contents, lookup.table, it) }
    }
    for (lookup in transaction.lookups) {
        resolved += lookup.readonly.map { pick(contents, lookup.table, it) }
    }
    val answer = ResolvedTransaction(transaction, resolved, transaction.accounts.size)
    // Every instruction has to land inside the list, including the program it runs. An index past
    // the end is a message this app does not review rather than one it reviews partly.
    for (instruction in transaction.instructions) {
        if (answer.programOf(instruction) == null || answer.accountsOf(instruction) == null) {
            throw LookupException(LookupProblem.AccountOutOfRange)
        }
    }
    return answer
}

private fun pick(contents: Map<String, List<String>>, table: String, index: Int): String {
    val addresses = contents.getValue(table)
    if (index !in addresses.indices) {
        throw LookupException(LookupProblem.IndexOutOfRange, "$table[$index]")
    }
    return addresses[index]
}

private fun little(data: ByteArray, at: Int, count: Int): ULong =
    (0 until count).fold(0UL) { value, index ->
        value or ((data[at + index].toULong() and 0xffUL) shl (index * 8))
    }
