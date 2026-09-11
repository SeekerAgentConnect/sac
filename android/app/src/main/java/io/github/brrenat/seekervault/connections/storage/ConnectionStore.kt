package io.github.brrenat.seekervault.connections.storage

import android.util.AtomicFile
import io.github.brrenat.seekervault.connections.CheckOutcome
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.isConnectionId
import java.io.File
import java.io.IOException
import java.time.Instant
import org.json.JSONException
import org.json.JSONObject

/**
 * Connection metadata (docs/security.md#local-storage-and-recovery): one JSON file per connection,
 * `<dir>/<connection ID>.json`, written atomically. No file holds another connection's data, so
 * removing one connection leaves the others as they were. Credentials live elsewhere, in the
 * [CredentialVault].
 */
class ConnectionStore(private val dir: File) {
    /** Every readable connection, oldest pairing first. A damaged file is skipped. */
    fun list(): List<Connection> =
        dir.listFiles { file -> file.name.endsWith(SUFFIX) }
            .orEmpty()
            .mapNotNull { read(it.name.removeSuffix(SUFFIX)) }
            .sortedWith(compareBy({ it.pairedAt }, { it.id }))

    fun get(id: String): Connection? = read(id)

    /** Writes [connection] whole, replacing its previous version. */
    fun put(connection: Connection) {
        val file = atomicFile(connection.id)
        dir.mkdirs()
        val stream = file.startWrite()
        try {
            stream.write(encode(connection).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: IOException) {
            file.failWrite(stream)
            throw e
        }
    }

    fun delete(id: String) {
        atomicFile(id).delete()
    }

    private fun read(id: String): Connection? {
        if (!isConnectionId(id)) return null
        val file = atomicFile(id)
        return try {
            decode(String(file.readFully(), Charsets.UTF_8))?.takeIf { it.id == id }
        } catch (e: IOException) {
            null
        } catch (e: JSONException) {
            null
        } catch (e: IllegalArgumentException) {
            null // an unknown enum value or a malformed timestamp
        }
    }

    // The ID names the file, so it must be the UUID the sidecar assigned, never a path.
    private fun atomicFile(id: String): AtomicFile {
        require(isConnectionId(id)) { "not a connection ID" }
        return AtomicFile(File(dir, "$id$SUFFIX"))
    }

    private companion object {
        const val SUFFIX = ".json"
        const val VERSION = 1

        fun encode(connection: Connection): String =
            JSONObject()
                .put("version", VERSION)
                .put("id", connection.id)
                .put("label", connection.label)
                .put("serverUrl", connection.serverUrl)
                .put("serverId", connection.serverId)
                .put("deviceName", connection.deviceName)
                .put("pairedAt", connection.pairedAt.toString())
                .putOpt("revokedAt", connection.revokedAt?.toString())
                .putOpt(
                    "lastCheck",
                    connection.lastCheck?.let { check ->
                        JSONObject()
                            .put("at", check.at.toString())
                            .put("outcome", check.outcome.name)
                            .putOpt("pending", check.pending)
                            .put("morePending", check.morePending)
                    },
                )
                .toString()

        fun decode(text: String): Connection? {
            val json = JSONObject(text)
            if (json.getInt("version") != VERSION) return null
            return Connection(
                id = json.getString("id"),
                label = json.getString("label"),
                serverUrl = json.getString("serverUrl"),
                serverId = json.getString("serverId"),
                deviceName = json.getString("deviceName"),
                pairedAt = Instant.parse(json.getString("pairedAt")),
                revokedAt =
                    json.optString("revokedAt").takeIf { it.isNotEmpty() }?.let(Instant::parse),
                lastCheck =
                    json.optJSONObject("lastCheck")?.let { check ->
                        Connection.Check(
                            at = Instant.parse(check.getString("at")),
                            outcome = CheckOutcome.valueOf(check.getString("outcome")),
                            pending = if (check.has("pending")) check.getInt("pending") else null,
                            morePending = check.optBoolean("morePending"),
                        )
                    },
            )
        }
    }
}
