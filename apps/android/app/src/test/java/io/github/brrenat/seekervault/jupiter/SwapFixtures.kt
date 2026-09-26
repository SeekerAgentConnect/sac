package io.github.brrenat.seekervault.jupiter

import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.plugins.actions.SwapPayload
import java.util.Base64
import org.json.JSONObject

/**
 * The real swaps Jupiter built, as the tests read them (`fixtures/jupiter/swaps.json`).
 *
 * Recapture with `node scripts/capture-jupiter.mjs`. The blockhashes in them expired within the
 * minute they were captured and that is deliberately irrelevant: what the review establishes is
 * what a transaction *does*, which does not depend on when anyone built it.
 */
data class SwapFixture(
    val name: String,
    val description: String,
    val owner: String,
    val terms: SwapPayload,
    val amount: ULong,
    val slippageBps: Int,
    val quote: JupiterQuote,
    val transaction: ByteString,
    /** What the capture script's own reader made of it, named instruction by instruction. */
    val shape: List<String>,
    val signers: List<String>,
    val version: Int?,
)

fun swapFixtures(): List<SwapFixture> {
    val text =
        checkNotNull(SwapFixture::class.java.getResourceAsStream("/jupiter/swaps.json")) {
                "fixtures/jupiter/swaps.json is missing; run `node scripts/capture-jupiter.mjs`"
            }
            .use { it.readBytes().decodeToString() }
    val held = JSONObject(text)
    val cases = held.getJSONArray("cases")
    return (0 until cases.length()).map { index ->
        val one = cases.getJSONObject(index)
        val quoted = one.getJSONObject("quote")
        val terms =
            SwapPayload(
                inputMint = one.getString("inputMint"),
                inputDecimals = one.getInt("inputDecimals"),
                outputMint = one.getString("outputMint"),
                outputDecimals = one.getInt("outputDecimals"),
                // The publisher's ceiling is not part of the capture: it is the publisher's, and
                // these files are the provider's. It is set here at exactly what was asked for, so
                // the fixtures test the provider's answer rather than a bound of somebody else's.
                maxSlippageBps = one.getInt("slippageBps"),
                inputSymbol = one.getString("inputSymbol"),
                outputSymbol = one.getString("outputSymbol"),
            )
        val shape = one.getJSONObject("shape")
        val instructions = shape.getJSONArray("instructions")
        val signers = shape.getJSONArray("signers")
        SwapFixture(
            name = one.getString("name"),
            description = one.getString("description"),
            owner = one.getString("owner"),
            terms = terms,
            amount = one.getString("amount").toULong(),
            slippageBps = one.getInt("slippageBps"),
            quote =
                JupiterQuote(
                    inputMint = terms.inputMint,
                    outputMint = terms.outputMint,
                    inAmount = quoted.getString("inAmount").toULong(),
                    outAmount = quoted.getString("outAmount").toULong(),
                    minimumOut = quoted.getString("otherAmountThreshold").toULong(),
                    slippageBps = one.getInt("slippageBps"),
                    legs = quoted.getInt("legs"),
                    raw = quoted.toString(),
                ),
            transaction =
                ByteString.copyFrom(Base64.getDecoder().decode(one.getString("transaction"))),
            shape = (0 until instructions.length()).map(instructions::getString),
            signers = (0 until signers.length()).map(signers::getString),
            version = if (shape.isNull("version")) null else shape.getInt("version"),
        )
    }
}
