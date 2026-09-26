package io.github.brrenat.seekervault.confirmations

import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.solana.SolanaException
import io.github.brrenat.seekervault.solana.SolanaProblem
import java.io.IOException
import java.net.URI
import java.util.Base64
import java.util.concurrent.TimeUnit
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
 * Asking the chain what became of a signature, and nothing else (SEE-165).
 *
 * Five reads. There is no send, no simulate, no subscribe and no wallet: this is how the phone
 * learns whether a transaction its wallet already sent landed, and it has no way to send one
 * (`StageBoundaryTest.theConfirmationReaderOnlyReads`).
 *
 * Every method throws [SolanaException] when the endpoint could not be reached or answered with
 * something unusable. That is never evidence about a transaction: the caller keeps what it had.
 */
interface ChainReader {
    /** The endpoint's host, which is all that is ever stored or shown about it. */
    val host: String

    /** The cluster's genesis hash, which says which chain the endpoint serves. */
    suspend fun genesisHash(): String

    /**
     * One status per signature, in order, null where the endpoint has none. [searchHistory] asks
     * the endpoint to look past its recent status cache into the ledger.
     */
    suspend fun statuses(signatures: List<String>, searchHistory: Boolean): List<SignatureStatus?>

    /** The transaction under [signature] at the confirmed level, or null when not served. */
    suspend fun transaction(signature: String): ChainTransaction?

    /** Whether [blockhash] can still be used by a transaction, judged by the finalized chain. */
    suspend fun blockhashValid(blockhash: String): Boolean
}

data class SignatureStatus(val slot: Long, val level: ChainLevel, val chainError: String?)

class ChainTransaction(val slot: Long, val transaction: ByteArray, val chainError: String?)

/** Each cluster's genesis hash, as `solana genesis-hash` reports it. */
val GENESIS_HASHES: Map<Network, String> =
    mapOf(
        Network.NETWORK_MAINNET to "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d",
        Network.NETWORK_DEVNET to "EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG",
        Network.NETWORK_TESTNET to "4uhcVJyU9pJkvQyS88uRDiswHXSCkY3zQawwpjk2NsNY",
    )

/**
 * One configured endpoint, and the clusters it may be asked about.
 *
 * [network] is null for the build's general endpoint (`seekervault.solanaRpc`), which is asked
 * about a record only once its genesis hash names that record's cluster. A per-cluster endpoint
 * (`seekervault.solanaRpc.devnet` and so on) must not serve a *different known* cluster either; a
 * genesis hash nobody knows — a local test validator — is accepted only when [allowUnknownGenesis]
 * says this build was configured for one, which only a debug build is.
 */
data class ChainEndpoint(
    val url: String,
    val network: Network?,
    val allowUnknownGenesis: Boolean = false,
)

/** Where each cluster is asked, given the endpoints this build was configured with. */
class ChainEndpoints(
    private val endpoints: List<ChainEndpoint>,
    private val reader: (String) -> ChainReader,
) {
    private val genesis = mutableMapOf<String, String>()
    private val readers = mutableMapOf<String, ChainReader>()

    /** Whether any endpoint could ever be asked about [network]. */
    fun configured(network: Network): Boolean = endpoints.any {
        it.url.isNotEmpty() && (it.network == null || it.network == network)
    }

    /**
     * A reader that serves [network], proven by its genesis hash. Throws [SolanaException]:
     * [SolanaProblem.NoEndpoint] when nothing is configured for it, and the endpoint's own problem
     * — or [WRONG_CLUSTER] — when the one configured can't be shown to serve it.
     */
    suspend fun readerFor(network: Network): ChainReader {
        val candidates =
            endpoints
                .filter { it.url.isNotEmpty() }
                .filter { it.network == network || it.network == null }
                // A cluster's own endpoint first: it is the one somebody chose for it.
                .sortedBy { if (it.network == network) 0 else 1 }
        if (candidates.isEmpty()) throw SolanaException(SolanaProblem.NoEndpoint)
        var failure: SolanaException? = null
        for (endpoint in candidates) {
            val client =
                synchronized(readers) { readers.getOrPut(endpoint.url) { reader(endpoint.url) } }
            val hash =
                try {
                    synchronized(genesis) { genesis[endpoint.url] }
                        ?: client.genesisHash().also {
                            synchronized(genesis) { genesis[endpoint.url] = it }
                        }
                } catch (e: SolanaException) {
                    failure = failure ?: e
                    continue
                }
            val served = GENESIS_HASHES.entries.firstOrNull { it.value == hash }?.key
            when {
                served == network -> return client
                served == null && endpoint.network == network && endpoint.allowUnknownGenesis ->
                    return client
                else -> failure = failure ?: SolanaException(SolanaProblem.Refused, WRONG_CLUSTER)
            }
        }
        throw checkNotNull(failure)
    }

    companion object {
        /** The detail a [SolanaProblem.Refused] carries when an endpoint serves another cluster. */
        const val WRONG_CLUSTER = "the endpoint serves another cluster"
    }
}

/**
 * The real reader, over the app's shared HTTP client with a bound on each call.
 *
 * The shared client has no read timeout, on purpose, for the sidecar's streams. A status check is
 * not a stream, and a check that hangs is a check that holds every other one up, so this one ends
 * each call after [CALL_TIMEOUT_SECONDS] and reports the endpoint unreachable.
 */
class HttpChainReader(
    httpClient: OkHttpClient,
    private val endpoint: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ChainReader {
    private val client =
        httpClient.newBuilder().callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()

    override val host: String = hostOf(endpoint)

    override suspend fun genesisHash(): String =
        call("getGenesisHash", JSONArray()).let { result ->
            (result as? String)?.takeIf { it.isNotEmpty() }
                ?: throw SolanaException(SolanaProblem.Unusable, "no genesis hash")
        }

    override suspend fun statuses(
        signatures: List<String>,
        searchHistory: Boolean,
    ): List<SignatureStatus?> {
        if (signatures.isEmpty()) return emptyList()
        require(signatures.size <= MOST_SIGNATURES) { "too many signatures at once" }
        val result =
            call(
                "getSignatureStatuses",
                JSONArray()
                    .put(JSONArray(signatures))
                    .put(JSONObject().put("searchTransactionHistory", searchHistory)),
            )
        val values =
            (result as? JSONObject)?.optJSONArray("value")
                ?: throw SolanaException(SolanaProblem.Unusable, "no statuses")
        if (values.length() != signatures.size) {
            throw SolanaException(SolanaProblem.Unusable, "a partial answer")
        }
        return (0 until values.length()).map { index ->
            if (values.isNull(index)) return@map null
            val status =
                values.optJSONObject(index)
                    ?: throw SolanaException(SolanaProblem.Unusable, "a status that isn't one")
            SignatureStatus(
                slot = status.count("slot"),
                level = levelOf(status),
                chainError = errorOf(status),
            )
        }
    }

    override suspend fun transaction(signature: String): ChainTransaction? {
        val result =
            call(
                "getTransaction",
                JSONArray()
                    .put(signature)
                    .put(
                        JSONObject()
                            .put("encoding", "base64")
                            .put("commitment", "confirmed")
                            // Versioned transactions are the ones this app's plugins sign. A
                            // version this build doesn't read is an error from the endpoint,
                            // which is inconclusive, never a match.
                            .put("maxSupportedTransactionVersion", 0)
                    ),
            )
        if (result == null || result == JSONObject.NULL) return null
        val value =
            result as? JSONObject
                ?: throw SolanaException(SolanaProblem.Unusable, "a transaction that isn't one")
        val encoded =
            value.optJSONArray("transaction")?.optString(0)?.takeIf { it.isNotEmpty() }
                ?: throw SolanaException(SolanaProblem.Unusable, "no transaction in base64")
        val bytes =
            try {
                Base64.getDecoder().decode(encoded)
            } catch (_: IllegalArgumentException) {
                throw SolanaException(SolanaProblem.Unusable, "a transaction that is not base64")
            }
        return ChainTransaction(
            slot = value.count("slot"),
            transaction = bytes,
            chainError = value.optJSONObject("meta")?.let(::errorOf),
        )
    }

    override suspend fun blockhashValid(blockhash: String): Boolean {
        val result =
            call(
                "isBlockhashValid",
                JSONArray().put(blockhash).put(JSONObject().put("commitment", "finalized")),
            )
        val value = (result as? JSONObject)?.opt("value")
        return value as? Boolean
            ?: throw SolanaException(SolanaProblem.Unusable, "no blockhash verdict")
    }

    /** One JSON-RPC call: its `result`, which may be JSON null. */
    private suspend fun call(method: String, params: JSONArray): Any? {
        val body =
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", method)
                .put("params", params)
                .toString()
        val text =
            withContext(io) {
                val response =
                    try {
                        client
                            .newCall(
                                Request.Builder()
                                    .url(endpoint)
                                    .post(body.toRequestBody(JSON))
                                    .build()
                            )
                            .execute()
                    } catch (e: IOException) {
                        throw SolanaException(SolanaProblem.Unreachable, e.message)
                    } catch (e: IllegalArgumentException) {
                        throw SolanaException(SolanaProblem.NoEndpoint, "not a URL")
                    }
                response.use {
                    val read =
                        try {
                            it.body?.string().orEmpty()
                        } catch (e: IOException) {
                            throw SolanaException(SolanaProblem.Unreachable, e.message)
                        }
                    when {
                        it.isSuccessful -> read
                        it.code == 429 -> throw SolanaException(SolanaProblem.RateLimited)
                        else -> throw SolanaException(SolanaProblem.Refused, "HTTP ${it.code}")
                    }
                }
            }
        val answer =
            try {
                JSONObject(text)
            } catch (_: JSONException) {
                throw SolanaException(SolanaProblem.Unusable, "the answer is not an object")
            }
        answer.optJSONObject("error")?.let {
            // A JSON-RPC error arrives with HTTP 200. Its code is reported; its message is not.
            val code = it.optInt("code")
            throw SolanaException(
                if (code == RATE_LIMITED_CODE) SolanaProblem.RateLimited
                else SolanaProblem.Unusable,
                "error $code",
            )
        }
        if (!answer.has("result")) throw SolanaException(SolanaProblem.Unusable, "no result")
        return answer.get("result")
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val CALL_TIMEOUT_SECONDS = 15L
        const val RATE_LIMITED_CODE = 429
        /** getSignatureStatuses takes at most 256 signatures per call. */
        const val MOST_SIGNATURES = 256

        fun JSONObject.count(name: String): Long =
            if (has(name) && !isNull(name))
                optLong(name, -1).takeIf { it >= 0 }
                    ?: throw SolanaException(SolanaProblem.Unusable, "no $name")
            else throw SolanaException(SolanaProblem.Unusable, "no $name")

        /**
         * The level a status reports. A node too old to name one says `confirmations: null` for a
         * rooted transaction and a number otherwise; the number is read as processed, which is the
         * conservative answer — it is not a result.
         */
        fun levelOf(status: JSONObject): ChainLevel {
            if (status.has("confirmationStatus") && !status.isNull("confirmationStatus")) {
                return ChainLevel.of(status.getString("confirmationStatus"))
                    ?: throw SolanaException(SolanaProblem.Unusable, "an unknown commitment")
            }
            return if (status.has("confirmations") && status.isNull("confirmations"))
                ChainLevel.Finalized
            else ChainLevel.Processed
        }

        /** The chain's own error, as text, bounded; null when there is none. */
        fun errorOf(json: JSONObject): String? {
            if (!json.has("err") || json.isNull("err")) return null
            return json.get("err").toString().take(MAX_ERROR_CHARS)
        }

        const val MAX_ERROR_CHARS = 200
    }
}

/** The endpoint's host, and only its host: a configured URL can carry an API key. */
fun hostOf(url: String): String =
    try {
        URI(url).host.orEmpty()
    } catch (_: Exception) {
        ""
    }
