package io.github.brrenat.seekervault.access.storage

import android.util.AtomicFile
import io.github.brrenat.seekervault.connections.isConnectionId
import java.io.File
import java.io.IOException
import java.time.Instant
import org.json.JSONException
import org.json.JSONObject

/**
 * What this phone knows about its access to each restricted feed (SEE-156), one atomic JSON
 * document per feed connection in `noBackupFilesDir/feed-access/`.
 *
 * It holds the wallet the access was asked for, the device-key fingerprint that wallet bound, the
 * publisher's request ID and where the request stands. It holds no secret: the device key is in the
 * Keystore, and the session — the one thing that reads the feed — is sealed in its own vault
 * (`feed-sessions`, the same format as a phone credential). It is kept out of backups for the same
 * reason the key is: a restored backup is another device, and has to ask again.
 */
class FeedAccessStore(private val dir: File) {

    /** Where a request stands, as this phone last learned it. */
    enum class State(val code: String) {
        /** Waiting for the publisher's decision. */
        Pending("pending"),
        /** Approved, and not yet redeemed on this phone. */
        Approved("approved"),
        /** Rejected by the publisher. */
        Rejected("rejected"),
        /** Redeemed: this phone holds a session and the gateway admits it. */
        Connected("connected"),
        /** The publisher revoked this device. The session is gone. */
        Revoked("revoked"),
        /** The session is held, and the gateway no longer admits it: a grant ran out. */
        Expired("expired"),
    }

    data class Record(
        val connectionId: String,
        val serverId: String,
        /** The wallet the proof was made with. Access never moves to another wallet. */
        val wallet: String,
        /** The device-key fingerprint that wallet's signature bound. */
        val installation: String,
        val requestId: String,
        val state: State,
        val updatedAt: Instant,
        /** When the grant runs out unless the publisher renews it, once connected. */
        val grantUntil: Instant? = null,
    )

    fun get(connectionId: String): Record? {
        if (!isConnectionId(connectionId)) return null
        return try {
            decode(String(file(connectionId).readFully(), Charsets.UTF_8))?.takeIf {
                it.connectionId == connectionId
            }
        } catch (e: IOException) {
            null
        } catch (e: JSONException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun all(): List<Record> =
        dir.listFiles { file -> file.name.endsWith(SUFFIX) }
            .orEmpty()
            .map { it.name.removeSuffix(SUFFIX) }
            .filter(::isConnectionId)
            .mapNotNull(::get)

    fun put(record: Record) {
        require(isConnectionId(record.connectionId)) { "not a connection ID" }
        require(isConnectionId(record.serverId)) { "not a server ID" }
        val target = file(record.connectionId)
        dir.mkdirs()
        val stream = target.startWrite()
        try {
            stream.write(encode(record).toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
        } catch (e: IOException) {
            target.failWrite(stream)
            throw e
        }
    }

    fun delete(connectionId: String) {
        if (!isConnectionId(connectionId)) return
        file(connectionId).delete()
    }

    private fun file(connectionId: String): AtomicFile {
        require(isConnectionId(connectionId)) { "not a connection ID" }
        return AtomicFile(File(dir, "$connectionId$SUFFIX"))
    }

    private companion object {
        const val SUFFIX = ".json"
        const val VERSION = 1

        fun encode(record: Record): String =
            JSONObject()
                .put("version", VERSION)
                .put("connectionId", record.connectionId)
                .put("serverId", record.serverId)
                .put("wallet", record.wallet)
                .put("installation", record.installation)
                .put("requestId", record.requestId)
                .put("state", record.state.code)
                .put("updatedAt", record.updatedAt.toEpochMilli())
                .put("grantUntil", record.grantUntil?.toEpochMilli() ?: 0L)
                .toString()

        fun decode(text: String): Record? {
            val json = JSONObject(text)
            if (json.getInt("version") != VERSION) return null
            val state =
                State.entries.firstOrNull { it.code == json.getString("state") } ?: return null
            val until = json.optLong("grantUntil")
            return Record(
                connectionId = json.getString("connectionId"),
                serverId = json.getString("serverId"),
                wallet = json.getString("wallet"),
                installation = json.getString("installation"),
                requestId = json.getString("requestId"),
                state = state,
                updatedAt = Instant.ofEpochMilli(json.getLong("updatedAt")),
                grantUntil = if (until > 0) Instant.ofEpochMilli(until) else null,
            )
        }
    }
}
