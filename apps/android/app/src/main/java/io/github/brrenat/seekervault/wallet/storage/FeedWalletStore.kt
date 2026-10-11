package io.github.brrenat.seekervault.wallet.storage

import android.util.AtomicFile
import io.github.brrenat.seekervault.request.v1.Network
import java.io.File
import java.io.IOException
import org.json.JSONException
import org.json.JSONObject

/**
 * Which saved wallet profile the owner last chose for a feed's operations on each network (SEE-185,
 * docs/guides/wallet-setup.md#feed-operations).
 *
 * A remembered preference and nothing more: it is never the feed's access identity, it is never
 * sent anywhere, and an operation still shows the wallet it will use and binds it before anything
 * is signed. A preference naming a profile that is gone or on another network chooses nothing — no
 * other account is put in its place. One small JSON file, written whole:
 * `{"version":1,"choices":{"<connection>|NETWORK_MAINNET":"<profile>"}}`. A file this build cannot
 * read is no preference at all.
 */
class FeedWalletStore(private val dir: File) {

    /** A feed and a network: the key a preference is kept under. */
    data class Key(val connectionId: String, val network: Network)

    fun read(): Map<Key, String> {
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
        val choices = json.optJSONObject(CHOICES) ?: return emptyMap()
        return buildMap {
            for (name in choices.keys()) {
                val key = keyOf(name) ?: continue
                choices.optString(name).takeIf(String::isNotEmpty)?.let { put(key, it) }
            }
        }
    }

    fun write(choices: Map<Key, String>) {
        val held = JSONObject()
        choices.forEach { (key, profileId) ->
            held.put("${key.connectionId}$SEPARATOR${key.network.name}", profileId)
        }
        val bytes =
            JSONObject().put(VERSION_KEY, VERSION).put(CHOICES, held).toString().toByteArray()
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
        const val FILE = "feed-wallets.json"
        const val VERSION_KEY = "version"
        const val VERSION = 1
        const val CHOICES = "choices"
        const val SEPARATOR = "|"

        fun keyOf(name: String): Key? {
            val parts = name.split(SEPARATOR)
            if (parts.size != 2) return null
            val network =
                Network.entries.firstOrNull {
                    it.name == parts[1] &&
                        it != Network.NETWORK_UNSPECIFIED &&
                        it != Network.UNRECOGNIZED
                } ?: return null
            return Key(parts[0], network)
        }
    }
}
