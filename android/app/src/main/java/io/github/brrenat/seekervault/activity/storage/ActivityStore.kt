package io.github.brrenat.seekervault.activity.storage

import android.util.AtomicFile
import io.github.brrenat.seekervault.activity.ActivityKind
import io.github.brrenat.seekervault.activity.ActivityOutcome
import io.github.brrenat.seekervault.activity.ActivityRecord
import io.github.brrenat.seekervault.activity.ReviewedTransfer
import io.github.brrenat.seekervault.connections.isConnectionId
import io.github.brrenat.seekervault.request.v1.Network
import java.io.File
import java.io.IOException
import java.time.DateTimeException
import java.time.Instant
import org.json.JSONException
import org.json.JSONObject

/**
 * The owner's own record of what this phone did (docs/security.md#local-storage-and-recovery): one
 * JSON file per request, `<dir>/<connection ID>/<request ID>.json`, written atomically. A request
 * ID is unique only within its connection, so each connection has its own directory, exactly as the
 * answers do.
 *
 * Nothing prunes it and removing a connection doesn't empty it: the record of a payment outlives
 * the agent that asked for it. The owner clears it themselves, and that is the only way it goes.
 */
class ActivityStore(private val dir: File) {
    /**
     * Every readable record, newest first. A single damaged file is skipped: one unreadable record
     * is not a reason to lose the rest. A directory that exists and can't be listed is a different
     * thing — that is the history itself being unreadable, and it throws rather than coming back
     * looking empty, because an empty history and an unreadable one must never read the same.
     */
    fun list(): List<ActivityRecord> =
        connectionIds()
            .flatMap(::listFor)
            .sortedWith(
                compareByDescending<ActivityRecord> { it.answeredAt }.thenBy { it.requestId }
            )

    fun listFor(connectionId: String): List<ActivityRecord> =
        entriesOf(connectionDir(connectionId))
            .filter { it.name.endsWith(SUFFIX) }
            .mapNotNull { read(connectionId, it.name.removeSuffix(SUFFIX)) }

    fun get(connectionId: String, requestId: String): ActivityRecord? =
        read(connectionId, requestId)

    /**
     * Writes [record] whole, replacing the one stored under the same request.
     *
     * A record whose identity disagrees with the stored one is not the same thing that was recorded
     * — another wallet, another cluster, or another asset under a request ID this phone already has
     * a record for — and it is refused rather than merged. It returns what is stored afterwards.
     */
    fun put(record: ActivityRecord): ActivityRecord {
        val existing = get(record.connectionId, record.requestId)
        if (existing != null && existing.identity != record.identity) return existing
        val file = atomicFile(record.connectionId, record.requestId)
        connectionDir(record.connectionId).mkdirs()
        val stream = file.startWrite()
        try {
            stream.write(encode(record).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: IOException) {
            file.failWrite(stream)
            throw e
        }
        return record
    }

    /** Removes everything. The owner asked; nothing else calls it. */
    fun clear() {
        connectionIds().forEach { File(dir, it).deleteRecursively() }
    }

    fun connectionIds(): Set<String> =
        entriesOf(dir).filter { it.isDirectory && isConnectionId(it.name) }.map { it.name }.toSet()

    /**
     * What is in [directory]. A directory that isn't there yet is empty — that is a phone that has
     * recorded nothing. One that is there and can't be listed is unreadable, and says so.
     */
    private fun entriesOf(directory: File): List<File> =
        directory.listFiles()?.toList()
            ?: if (directory.exists()) throw IOException("$directory can't be read")
            else emptyList()

    private fun read(connectionId: String, requestId: String): ActivityRecord? {
        if (!isConnectionId(connectionId) || !isConnectionId(requestId)) return null
        return try {
            decode(String(atomicFile(connectionId, requestId).readFully(), Charsets.UTF_8))
                ?.takeIf { it.connectionId == connectionId && it.requestId == requestId }
        } catch (e: IOException) {
            null
        } catch (e: JSONException) {
            null
        } catch (e: IllegalArgumentException) {
            null // an unknown enum value
        } catch (e: DateTimeException) {
            null // a malformed timestamp
        }
    }

    // Both IDs name files, so each must be a UUID the sidecar assigned, never a path.
    private fun connectionDir(connectionId: String): File {
        require(isConnectionId(connectionId)) { "not a connection ID" }
        return File(dir, connectionId)
    }

    private fun atomicFile(connectionId: String, requestId: String): AtomicFile {
        require(isConnectionId(requestId)) { "not a request ID" }
        return AtomicFile(File(connectionDir(connectionId), "$requestId$SUFFIX"))
    }

    private companion object {
        const val SUFFIX = ".json"
        const val VERSION = 1
        const val OLDEST_VERSION = 1

        fun encode(record: ActivityRecord): String =
            JSONObject()
                .put("version", VERSION)
                .put("connectionId", record.connectionId)
                .put("requestId", record.requestId)
                .put("source", record.source)
                .put("serverHost", record.serverHost)
                .put("kind", record.kind.name)
                .put("answeredAt", record.answeredAt.toString())
                .put("recordedAt", record.recordedAt.toString())
                .put("outcome", record.outcome.name)
                .putOpt("transfer", record.transfer?.let(::encodeTransfer))
                // The signature is public the moment the wallet makes it, like the address.
                .putOpt("signature", record.signature)
                .putOpt("detail", record.detail)
                .putOpt("checkedWith", record.checkedWith)
                .toString()

        fun encodeTransfer(transfer: ReviewedTransfer): JSONObject =
            JSONObject()
                .put("wallet", transfer.wallet)
                .put("network", transfer.network.name)
                .put("recipient", transfer.recipient)
                .put("amount", transfer.amount)
                .putOpt("mint", transfer.mint)
                .put("preparedVersion", transfer.preparedVersion)

        fun decodeTransfer(json: JSONObject?): ReviewedTransfer? = json?.let {
            ReviewedTransfer(
                wallet = it.getString("wallet"),
                network = Network.valueOf(it.getString("network")),
                recipient = it.getString("recipient"),
                amount = it.getString("amount"),
                mint = it.optString("mint").takeIf(String::isNotEmpty),
                preparedVersion = it.optInt("preparedVersion"),
            )
        }

        fun decode(text: String): ActivityRecord? {
            val json = JSONObject(text)
            if (json.getInt("version") !in OLDEST_VERSION..VERSION) return null
            return ActivityRecord(
                connectionId = json.getString("connectionId"),
                requestId = json.getString("requestId"),
                source = json.getString("source"),
                serverHost = json.getString("serverHost"),
                kind = ActivityKind.valueOf(json.getString("kind")),
                answeredAt = Instant.parse(json.getString("answeredAt")),
                recordedAt = Instant.parse(json.getString("recordedAt")),
                outcome = ActivityOutcome.valueOf(json.getString("outcome")),
                transfer = decodeTransfer(json.optJSONObject("transfer")),
                signature = json.optString("signature").takeIf(String::isNotEmpty),
                detail = json.optString("detail").takeIf(String::isNotEmpty),
                checkedWith = json.optString("checkedWith").takeIf(String::isNotEmpty),
            )
        }
    }
}
