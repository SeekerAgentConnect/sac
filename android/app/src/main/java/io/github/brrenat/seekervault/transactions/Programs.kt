package io.github.brrenat.seekervault.transactions

/**
 * The programs a supported transfer may call, and the instructions this app knows how to read
 * (docs/protocol.md#transfers-saw-019). Everything else stays unrecognized on purpose: an
 * instruction the app can't read is reported as unverified, never assumed harmless, and a program
 * being on this list never means every instruction it offers is allowed.
 */
const val SYSTEM_PROGRAM = "11111111111111111111111111111111"
const val TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
const val TOKEN_2022_PROGRAM = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
const val ASSOCIATED_TOKEN_PROGRAM = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"
const val COMPUTE_BUDGET_PROGRAM = "ComputeBudget111111111111111111111111111111"

/** What one instruction turned out to be. */
sealed interface ReadInstruction {
    /** System program 2: move lamports from one account to another. */
    data class SolTransfer(val from: String, val to: String, val lamports: ULong) : ReadInstruction

    /**
     * SPL Token 12, TransferChecked. It carries the mint and its decimals, and the program refuses
     * the transfer unless both match the mint on chain, so the decimals here are as good as read
     * from the mint itself.
     */
    data class TokenTransfer(
        val source: String,
        val mint: String,
        val destination: String,
        val authority: String,
        val amount: ULong,
        val decimals: Int,
    ) : ReadInstruction

    /** Associated Token Account 0 or 1: give [owner] their token account for [mint]. */
    data class CreateTokenAccount(
        val payer: String,
        val account: String,
        val owner: String,
        val mint: String,
        /** True for CreateIdempotent, which succeeds if the account already exists. */
        val idempotent: Boolean,
    ) : ReadInstruction

    /** Compute budget 2 or 3: a unit limit, or a price per unit that the fee payer pays. */
    data class ComputeBudget(val unitLimit: UInt?, val microLamportsPerUnit: ULong?) :
        ReadInstruction

    /**
     * A valid instruction this app doesn't read. It is neither safe nor unsafe here: it is simply
     * not covered, and the review says so.
     */
    data class Unrecognized(
        val program: String,
        val instruction: Int?,
        val accounts: List<String>,
    ) : ReadInstruction
}

/**
 * Reads one instruction of a decoded transaction, or null when its indexes point outside the
 * account list, which makes the whole transaction malformed.
 */
fun DecodedTransaction.read(instruction: DecodedInstruction): ReadInstruction? {
    val program = programOf(instruction) ?: return null
    val accounts = accountsOf(instruction) ?: return null
    val data = instruction.data
    val reader = Reader(data)
    return when (program) {
        SYSTEM_PROGRAM -> {
            // System instructions start with a little-endian u32 discriminator; 2 is Transfer.
            val kind = reader.u32()
            val lamports = reader.u64()
            if (kind == 2U && lamports != null && reader.exhausted && accounts.size == 2) {
                ReadInstruction.SolTransfer(accounts[0], accounts[1], lamports)
            } else {
                unrecognized(program, kind?.toInt(), accounts)
            }
        }
        TOKEN_PROGRAM -> {
            val kind = reader.byte()
            if (kind == 12) {
                val amount = reader.u64()
                val decimals = reader.byte()
                if (amount != null && decimals != null && reader.exhausted && accounts.size >= 4) {
                    ReadInstruction.TokenTransfer(
                        source = accounts[0],
                        mint = accounts[1],
                        destination = accounts[2],
                        authority = accounts[3],
                        amount = amount,
                        decimals = decimals,
                    )
                } else {
                    unrecognized(program, kind, accounts)
                }
            } else {
                unrecognized(program, kind, accounts)
            }
        }
        ASSOCIATED_TOKEN_PROGRAM -> {
            // Create is an empty payload or a single 0; CreateIdempotent is a single 1.
            val kind = if (data.isEmpty()) 0 else reader.byte()
            if ((kind == 0 || kind == 1) && reader.exhausted && accounts.size >= 4) {
                ReadInstruction.CreateTokenAccount(
                    payer = accounts[0],
                    account = accounts[1],
                    owner = accounts[2],
                    mint = accounts[3],
                    idempotent = kind == 1,
                )
            } else {
                unrecognized(program, kind, accounts)
            }
        }
        COMPUTE_BUDGET_PROGRAM -> {
            when (reader.byte()) {
                2 ->
                    reader
                        .u32()
                        .takeIf { reader.exhausted }
                        ?.let {
                            ReadInstruction.ComputeBudget(
                                unitLimit = it,
                                microLamportsPerUnit = null,
                            )
                        } ?: unrecognized(program, 2, accounts)
                3 ->
                    reader
                        .u64()
                        .takeIf { reader.exhausted }
                        ?.let {
                            ReadInstruction.ComputeBudget(
                                unitLimit = null,
                                microLamportsPerUnit = it,
                            )
                        } ?: unrecognized(program, 3, accounts)
                else -> unrecognized(program, data.firstOrNull()?.toInt()?.and(0xff), accounts)
            }
        }
        else -> unrecognized(program, data.firstOrNull()?.toInt()?.and(0xff), accounts)
    }
}

private fun unrecognized(program: String, instruction: Int?, accounts: List<String>) =
    ReadInstruction.Unrecognized(program, instruction, accounts)
