package io.github.brrenat.seekervault.config.storage

import android.util.AtomicFile
import io.github.brrenat.seekervault.config.ClientConfig
import io.github.brrenat.seekervault.config.SwapFeeConfig
import io.github.brrenat.seekervault.request.v1.Network
import java.io.File
import java.io.IOException
import java.time.Instant
import org.json.JSONException
import org.json.JSONObject

/**
 * The last client configuration this phone validated from its trusted gateway, and when it was
 * fetched (SEE-185, docs/wiki/client-config.md#the-cache).
 *
 * It is a cache of what the gateway said and nothing else: every value in it was validated before
 * it was written, and nothing a server, feed or owner types ever reaches it. One small JSON file,
 * written whole. A file this build cannot read is no cache at all — never a zero fee and never an
 * empty set of endpoints standing in for a real answer.
 */
class ClientConfigStore(private val dir: File) {

    /** What was cached and when, or null when nothing usable is. */
    data class Cached(val config: ClientConfig, val fetchedAt: Instant)

    fun read(): Cached? {
        val text =
            try {
                file().readFully().toString(Charsets.UTF_8)
            } catch (_: IOException) {
                return null
            }
        return try {
            decode(JSONObject(text))
        } catch (_: JSONException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun write(cached: Cached) {
        val bytes = encode(cached).toString().toByteArray()
        dir.mkdirs()
        val file = file()
        val out = file.startWrite()
        try {
            out.write(bytes)
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            throw e
        }
    }

    private fun file() = AtomicFile(File(dir, FILE))

    private fun encode(cached: Cached): JSONObject {
        val config = cached.config
        val rpc = JSONObject()
        for ((network, key) in KEYS) config.rpc[network]?.let { rpc.put(key, it) }
        val fee = config.swapFee
        val accounts = JSONObject()
        fee.accounts.forEach { (mint, account) -> accounts.put(mint, account) }
        return JSONObject()
            .put(VERSION_KEY, VERSION)
            .put("revision", config.revision)
            .put("fetchedAt", cached.fetchedAt.toEpochMilli())
            .put("rpc", rpc)
            .put(
                "swapFee",
                JSONObject()
                    .put("bps", fee.bps)
                    .put("network", fee.network?.let(::keyOf) ?: JSONObject.NULL)
                    .put("owner", fee.owner ?: JSONObject.NULL)
                    .put("accounts", accounts),
            )
    }

    private fun decode(json: JSONObject): Cached? {
        if (json.optInt(VERSION_KEY) != VERSION) return null
        val rpcJson = json.getJSONObject("rpc")
        val rpc = buildMap {
            for ((network, key) in KEYS) {
                if (rpcJson.has(key) && !rpcJson.isNull(key)) put(network, rpcJson.getString(key))
            }
        }
        val feeJson = json.getJSONObject("swapFee")
        val bps = feeJson.getInt("bps")
        val network =
            if (feeJson.isNull("network")) null
            else networkOf(feeJson.getString("network")) ?: return null
        val owner = if (feeJson.isNull("owner")) null else feeJson.getString("owner")
        val accountsJson = feeJson.getJSONObject("accounts")
        val accounts = accountsJson.keys().asSequence().associateWith { accountsJson.getString(it) }
        return Cached(
            config =
                ClientConfig(
                    revision = json.getLong("revision"),
                    rpc = rpc,
                    swapFee =
                        if (bps == 0) SwapFeeConfig.Zero
                        else SwapFeeConfig(bps, network, owner, accounts.toSortedMap()),
                ),
            fetchedAt = Instant.ofEpochMilli(json.getLong("fetchedAt")),
        )
    }

    private companion object {
        const val FILE = "client.json"
        const val VERSION_KEY = "version"
        const val VERSION = 1

        val KEYS =
            listOf(
                Network.NETWORK_MAINNET to "mainnet",
                Network.NETWORK_DEVNET to "devnet",
                Network.NETWORK_TESTNET to "testnet",
            )

        fun keyOf(network: Network): String = KEYS.first { it.first == network }.second

        fun networkOf(key: String): Network? = KEYS.firstOrNull { it.second == key }?.first
    }
}
