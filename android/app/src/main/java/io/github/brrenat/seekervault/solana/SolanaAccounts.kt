package io.github.brrenat.seekervault.solana

import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Reading accounts from the chain, and nothing else (SEE-94).
 *
 * The app has never had a chain endpoint of its own. This adds one, for one purpose, because of one
 * fact: a provider whose transactions load their accounts from address lookup tables cannot be
 * reviewed without those tables, and a table's contents live on the chain
 * (docs/security.md#resolving-a-lookup-table).
 *
 * ## What this can do, and what it cannot
 *
 * One method, one request, one kind of answer: the current contents of accounts named by address.
 * There is no send, no simulate, no subscribe, no signature lookup and no balance query — not
 * because those are unreachable over the same wire, but because a component with one method cannot
 * grow a second use by accident, and a boundary test holds it to that.
 *
 * ## Whose endpoint it is
 *
 * The application's or its host's, and **never a publisher's**. A server whose document the owner
 * is reviewing has no say in where the phone checks that document's transaction, for the obvious
 * reason: an endpoint chosen by the thing being reviewed is not a second opinion. Nothing in a
 * manifest, a proposal or a plugin's own answer can set it.
 *
 * ## And what trusting it means
 *
 * A review that resolves a lookup table is only as accurate as the endpoint that served it. That is
 * a real dependency and it is written down as one: it is not offline verification, and this app
 * does not describe it as trustless (docs/security.md#resolving-a-lookup-table).
 */
interface SolanaAccounts {
    /**
     * The current contents of [addresses], in the same order, with null for an account that does
     * not exist.
     *
     * Throws [SolanaException] when the endpoint could not be reached or answered with something
     * unusable. It never returns a partial answer: a caller that asked about four accounts is given
     * four entries or an exception, because "three of the four tables" is not a review.
     */
    suspend fun accounts(addresses: List<String>): List<AccountSnapshot?>
}

/** One account as the chain currently holds it: who owns it, and what is in it. */
data class AccountSnapshot(
    /** The program that owns the account, which is what says what the data means. */
    val owner: String,
    val data: ByteArray,
    val executable: Boolean,
) {
    override fun equals(other: Any?) =
        other is AccountSnapshot &&
            owner == other.owner &&
            executable == other.executable &&
            data.contentEquals(other.data)

    override fun hashCode() =
        (owner.hashCode() * 31 + executable.hashCode()) * 31 + data.contentHashCode()
}

/** Why nothing could be read from the chain. */
enum class SolanaProblem(val code: String) {
    /** No endpoint is configured in this build, so there is nothing to ask. */
    NoEndpoint("no_rpc_endpoint"),
    /** The endpoint could not be reached, or the answer never arrived. */
    Unreachable("rpc_unreachable"),
    /** It is asking for fewer requests. */
    RateLimited("rpc_rate_limited"),
    /** It refused, and said so with a status this phone can report. */
    Refused("rpc_refused"),
    /**
     * The answer arrived and could not be used: a JSON-RPC error, a missing field, a short list.
     */
    Unusable("rpc_unusable"),
}

class SolanaException(val problem: SolanaProblem, val detail: String? = null) :
    Exception("solana: ${problem.code}${detail?.let { ": $it" } ?: ""}")

/**
 * The real reader, over the app's shared HTTP client.
 *
 * [endpoint] is empty in a build nobody configured, and then every call reports
 * [SolanaProblem.NoEndpoint] rather than reaching a default somewhere: a check that quietly
 * contacted a public cluster would be a check nobody chose, and the tests in this repository
 * deliberately reach no cluster (`docs/development/android.md`).
 */
class HttpSolanaAccounts(
    private val httpClient: OkHttpClient,
    private val endpoint: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SolanaAccounts {

    override suspend fun accounts(addresses: List<String>): List<AccountSnapshot?> {
        if (endpoint.isEmpty()) throw SolanaException(SolanaProblem.NoEndpoint)
        if (addresses.isEmpty()) return emptyList()
        if (addresses.size > MOST_ACCOUNTS) {
            throw SolanaException(SolanaProblem.Unusable, "too many accounts at once")
        }
        val call =
            JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "getMultipleAccounts")
                put(
                    "params",
                    JSONArray().apply {
                        put(JSONArray(addresses))
                        // Base64 because the data is bytes, and confirmed because a table read at
                        // the processed commitment could be from a slot that never sticks.
                        put(
                            JSONObject().apply {
                                put("encoding", "base64")
                                put("commitment", "confirmed")
                            }
                        )
                    },
                )
            }
        val body =
            fetch(Request.Builder().url(endpoint).post(call.toString().toRequestBody(JSON)).build())
        return read(body, addresses.size)
    }

    private suspend fun fetch(request: Request): String =
        withContext(io) {
            val response =
                try {
                    httpClient.newCall(request).execute()
                } catch (e: IOException) {
                    throw SolanaException(SolanaProblem.Unreachable, e.message)
                }
            response.use {
                val text =
                    try {
                        it.body?.string().orEmpty()
                    } catch (e: IOException) {
                        throw SolanaException(SolanaProblem.Unreachable, e.message)
                    }
                when {
                    it.isSuccessful -> text
                    it.code == 429 -> throw SolanaException(SolanaProblem.RateLimited)
                    // A status, never the body: an endpoint's error page is not something to quote
                    // back to somebody.
                    else -> throw SolanaException(SolanaProblem.Refused, "HTTP ${it.code}")
                }
            }
        }

    private fun read(body: String, expected: Int): List<AccountSnapshot?> {
        val answer =
            try {
                JSONObject(body)
            } catch (_: JSONException) {
                throw SolanaException(SolanaProblem.Unusable, "the answer is not an object")
            }
        answer.optJSONObject("error")?.let {
            // A JSON-RPC error arrives with HTTP 200. Its code is reported; its message is not
            // quoted, for the same reason a body never is.
            throw SolanaException(SolanaProblem.Unusable, "error ${it.optInt("code")}")
        }
        val values =
            answer.optJSONObject("result")?.optJSONArray("value")
                ?: throw SolanaException(SolanaProblem.Unusable, "no accounts")
        if (values.length() != expected) {
            throw SolanaException(SolanaProblem.Unusable, "a partial answer")
        }
        return (0 until values.length()).map { index ->
            val account = values.optJSONObject(index) ?: return@map null
            val owner =
                account.optString("owner").takeIf { it.isNotEmpty() }
                    ?: throw SolanaException(SolanaProblem.Unusable, "an account with no owner")
            val encoded =
                account.optJSONArray("data")?.optString(0)
                    ?: throw SolanaException(SolanaProblem.Unusable, "an account with no data")
            val bytes =
                try {
                    Base64.getDecoder().decode(encoded)
                } catch (_: IllegalArgumentException) {
                    throw SolanaException(SolanaProblem.Unusable, "data that is not base64")
                }
            AccountSnapshot(owner, bytes, account.optBoolean("executable", false))
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()

        /**
         * The most accounts one read asks about. A message may name at most 256 tables by its own
         * encoding, and no supported transaction comes near it; this is a bound on the request
         * rather than a limit anybody should meet.
         */
        const val MOST_ACCOUNTS = 32
    }
}
