package io.github.brrenat.seekervault.rpc.storage

import android.util.AtomicFile
import io.github.brrenat.seekervault.request.v1.Network
import java.io.File
import java.io.IOException
import org.json.JSONException
import org.json.JSONObject

/**
 * The owner's own Solana endpoint for each network that has one (SEE-184).
 *
 * The one place on this phone where an endpoint URL is kept, and every URL in it was typed by the
 * owner and proven by its genesis hash before it was saved. Nothing a server, feed or provider
 * sends is ever written here. It is the app's private storage, like every other store; the URL is
 * shown back only in the settings sheet's own field, and anywhere else — history, tracking, a log —
 * an endpoint is only ever its host.
 *
 * One small JSON file, written whole: `{"version":1,"endpoints":{"devnet":"https://…"}}`. A network
 * absent from it uses this build's own endpoint. A file this build can't read is treated as empty,
 * which falls back to the build's endpoints rather than to anything chosen here.
 */
class RpcSettingsStore(private val dir: File) {

    fun read(): Map<Network, String> {
        val text =
            try {
                file().readFully().toString(Charsets.UTF_8)
            } catch (_: IOException) {
                return emptyMap()
            }
        val json =
            try {
                JSONObject(text)
            } catch (_: JSONException) {
                return emptyMap()
            }
        if (json.optInt(VERSION_KEY) != VERSION) return emptyMap()
        val endpoints = json.optJSONObject(ENDPOINTS) ?: return emptyMap()
        return buildMap {
            for ((network, key) in KEYS) {
                if (endpoints.isNull(key)) continue
                endpoints.optString(key).trim().takeIf(String::isNotEmpty)?.let { put(network, it) }
            }
        }
    }

    fun write(endpoints: Map<Network, String>) {
        val held = JSONObject()
        for ((network, key) in KEYS) endpoints[network]?.let { held.put(key, it) }
        val bytes =
            JSONObject().put(VERSION_KEY, VERSION).put(ENDPOINTS, held).toString().toByteArray()
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

    private companion object {
        const val FILE = "settings.json"
        const val VERSION_KEY = "version"
        const val VERSION = 1
        const val ENDPOINTS = "endpoints"

        val KEYS =
            listOf(
                Network.NETWORK_MAINNET to "mainnet",
                Network.NETWORK_DEVNET to "devnet",
                Network.NETWORK_TESTNET to "testnet",
            )
    }
}
