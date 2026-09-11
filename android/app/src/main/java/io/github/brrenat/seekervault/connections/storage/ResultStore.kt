package io.github.brrenat.seekervault.connections.storage

import android.util.AtomicFile
import io.github.brrenat.seekervault.connections.Answer
import io.github.brrenat.seekervault.connections.CheckOutcome
import io.github.brrenat.seekervault.connections.Delivery
import io.github.brrenat.seekervault.connections.LocalResult
import io.github.brrenat.seekervault.connections.isConnectionId
import io.github.brrenat.seekervault.request.v1.ActionRequest
import java.io.File
import java.io.IOException
import java.time.DateTimeException
import java.time.Instant
import java.util.Base64
import org.json.JSONException
import org.json.JSONObject

/**
 * The owner's answers (docs/security.md#local-storage-and-recovery): one JSON file per answer,
 * `<dir>/<connection ID>/<request ID>.json`, written atomically. A request ID is unique only within
 * its connection, so each connection has its own directory, and removing a connection removes it
 * whole.
 */
class ResultStore(private val dir: File) {
    /** Every readable answer, oldest first. A damaged file is skipped. */
    fun list(): List<LocalResult> =
        connectionIds()
            .flatMap(::listFor)
            .sortedWith(compareBy({ it.answeredAt }, { it.requestId }))

    fun listFor(connectionId: String): List<LocalResult> =
        connectionDir(connectionId)
            .listFiles { file -> file.name.endsWith(SUFFIX) }
            .orEmpty()
            .mapNotNull { read(connectionId, it.name.removeSuffix(SUFFIX)) }

    fun get(connectionId: String, requestId: String): LocalResult? = read(connectionId, requestId)

    /** Writes [result] whole, replacing its previous version. */
    fun put(result: LocalResult) {
        val file = atomicFile(result.connectionId, result.requestId)
        connectionDir(result.connectionId).mkdirs()
        val stream = file.startWrite()
        try {
            stream.write(encode(result).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: IOException) {
            file.failWrite(stream)
            throw e
        }
    }

    fun delete(connectionId: String, requestId: String) {
        atomicFile(connectionId, requestId).delete()
    }

    fun deleteConnection(connectionId: String) {
        connectionDir(connectionId).deleteRecursively()
    }

    /** The connections that have answers stored. */
    fun connectionIds(): Set<String> =
        dir.listFiles { file -> file.isDirectory && isConnectionId(file.name) }
            .orEmpty()
            .map { it.name }
            .toSet()

    private fun read(connectionId: String, requestId: String): LocalResult? {
        if (!isConnectionId(connectionId) || !isConnectionId(requestId)) return null
        return try {
            decode(String(atomicFile(connectionId, requestId).readFully(), Charsets.UTF_8))
                ?.takeIf { it.connectionId == connectionId && it.requestId == requestId }
        } catch (e: IOException) {
            null // also a request that isn't valid Protobuf
        } catch (e: JSONException) {
            null
        } catch (e: IllegalArgumentException) {
            null // an unknown enum value, or bad base64
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

        fun encode(result: LocalResult): String =
            JSONObject()
                .put("version", VERSION)
                .put("connectionId", result.connectionId)
                .put("requestId", result.requestId)
                .put("answer", result.answer.name)
                .put("answeredAt", result.answeredAt.toString())
                .put("request", Base64.getEncoder().encodeToString(result.request.toByteArray()))
                .put("delivery", result.delivery.name)
                .putOpt("lastFailure", result.lastFailure?.name)
                .putOpt("settledAt", result.settledAt?.toString())
                .toString()

        fun decode(text: String): LocalResult? {
            val json = JSONObject(text)
            if (json.getInt("version") != VERSION) return null
            return LocalResult(
                connectionId = json.getString("connectionId"),
                requestId = json.getString("requestId"),
                answer = Answer.valueOf(json.getString("answer")),
                answeredAt = Instant.parse(json.getString("answeredAt")),
                request =
                    ActionRequest.parseFrom(Base64.getDecoder().decode(json.getString("request"))),
                delivery = Delivery.valueOf(json.getString("delivery")),
                lastFailure =
                    json
                        .optString("lastFailure")
                        .takeIf { it.isNotEmpty() }
                        ?.let(CheckOutcome::valueOf),
                // Absent from answers stored before it existed, which then count from answeredAt.
                settledAt =
                    json.optString("settledAt").takeIf { it.isNotEmpty() }?.let(Instant::parse),
            )
        }
    }
}
