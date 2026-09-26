package io.github.brrenat.seekervault.sync.storage

import android.util.AtomicFile
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.connections.isConnectionId
import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.sync.ConnectionSyncState
import io.github.brrenat.seekervault.sync.ServerRequest
import io.github.brrenat.seekervault.sync.UpdateAvailability
import io.github.brrenat.seekervault.sync.UpdateEndpoint
import io.github.brrenat.seekervault.update.v1.RemovalReason
import java.io.File
import java.io.IOException
import java.time.DateTimeException
import java.time.Instant
import java.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Minimal server state, one atomic JSON document per connection in `filesDir/sync/`. The phone
 * token is never accepted by this API and therefore cannot enter this cache, WorkManager input, or
 * a log. A snapshot, all buffered events applied after it, and the cursor that covers them are one
 * write, so a killed process sees either the old complete state or the new complete state.
 */
class SyncStore(private val dir: File) {
    fun list(): List<ConnectionSyncState> =
        dir.listFiles { file -> file.name.endsWith(SUFFIX) }
            .orEmpty()
            .mapNotNull { get(it.name.removeSuffix(SUFFIX)) }

    fun connectionIds(): Set<String> =
        dir.listFiles { file -> file.name.endsWith(SUFFIX) }
            .orEmpty()
            .map { it.name.removeSuffix(SUFFIX) }
            .filter(::isConnectionId)
            .toSet()

    fun get(connectionId: String): ConnectionSyncState? {
        if (!isConnectionId(connectionId)) return null
        return try {
            decode(String(atomicFile(connectionId).readFully(), Charsets.UTF_8))?.takeIf {
                it.connectionId == connectionId
            }
        } catch (e: IOException) {
            null
        } catch (e: JSONException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: DateTimeException) {
            null
        }
    }

    fun put(state: ConnectionSyncState) {
        require(isConnectionId(state.connectionId)) { "not a connection ID" }
        require((state.availability == UpdateAvailability.Available) == (state.endpoint != null)) {
            "available state requires an endpoint"
        }
        require(state.serverInstanceId.isEmpty() == state.cursor.isEmpty()) {
            "cursor and server instance must be stored together"
        }
        require(
            state.requests.all { (requestId, item) ->
                requestId == item.key.requestId && item.key.connectionId == state.connectionId
            }
        ) {
            "request cache belongs to another connection"
        }
        val file = atomicFile(state.connectionId)
        dir.mkdirs()
        val stream = file.startWrite()
        try {
            stream.write(encode(state).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: IOException) {
            file.failWrite(stream)
            throw e
        }
    }

    fun delete(connectionId: String) {
        atomicFile(connectionId).delete()
    }

    private fun atomicFile(connectionId: String): AtomicFile {
        require(isConnectionId(connectionId)) { "not a connection ID" }
        return AtomicFile(File(dir, "$connectionId$SUFFIX"))
    }

    private companion object {
        const val VERSION = 1
        const val SUFFIX = ".json"

        fun encode(state: ConnectionSyncState): String =
            JSONObject()
                .put("version", VERSION)
                .put("connectionId", state.connectionId)
                .put("availability", state.availability.name)
                .putOpt(
                    "endpoint",
                    state.endpoint?.let {
                        JSONObject()
                            .put("protocolVersion", it.protocolVersion)
                            .put("grpcUrl", it.grpcUrl)
                    },
                )
                .put("serverInstanceId", state.serverInstanceId)
                .put("cursor", state.cursor)
                .putOpt("lastSuccessfulSync", state.lastSuccessfulSync?.toString())
                .put("nextKnownIndex", state.nextKnownIndex)
                .put(
                    "requests",
                    JSONArray().apply {
                        state.requests.values
                            .sortedBy { it.key.requestId }
                            .forEach { item ->
                                put(
                                    JSONObject()
                                        .put("requestId", item.key.requestId)
                                        .put("revision", item.revision)
                                        .putOpt(
                                            "request",
                                            item.request?.let {
                                                Base64.getEncoder().encodeToString(it.toByteArray())
                                            },
                                        )
                                        .putOpt("removed", item.removed?.name)
                                )
                            }
                    },
                )
                .toString()

        fun decode(text: String): ConnectionSyncState? {
            val json = JSONObject(text)
            // A newer document is not guessed at. The caller performs a full Sync and replaces it.
            if (json.getInt("version") != VERSION) return null
            val connectionId = json.getString("connectionId")
            val entries = linkedMapOf<String, ServerRequest>()
            val array = json.getJSONArray("requests")
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val requestId = item.getString("requestId")
                val request =
                    item.optString("request").takeIf(String::isNotEmpty)?.let {
                        ActionRequest.parseFrom(Base64.getDecoder().decode(it))
                    }
                val removed =
                    item.optString("removed").takeIf(String::isNotEmpty)?.let {
                        RemovalReason.valueOf(it)
                    }
                val stored =
                    ServerRequest(
                        RequestKey(connectionId, requestId),
                        item.getLong("revision"),
                        request,
                        removed,
                    )
                require(entries.put(requestId, stored) == null) { "duplicate request" }
            }
            val endpoint =
                json.optJSONObject("endpoint")?.let {
                    UpdateEndpoint(it.getInt("protocolVersion"), it.getString("grpcUrl"))
                }
            val availability = UpdateAvailability.valueOf(json.getString("availability"))
            require((availability == UpdateAvailability.Available) == (endpoint != null))
            val serverInstanceId = json.getString("serverInstanceId")
            val cursor = json.getString("cursor")
            require(serverInstanceId.toByteArray(Charsets.UTF_8).size <= 256)
            require(cursor.toByteArray(Charsets.UTF_8).size <= 256)
            require(serverInstanceId.isEmpty() == cursor.isEmpty())
            val nextKnownIndex = json.optInt("nextKnownIndex")
            require(nextKnownIndex >= 0)
            return ConnectionSyncState(
                connectionId = connectionId,
                availability = availability,
                endpoint = endpoint,
                serverInstanceId = serverInstanceId,
                cursor = cursor,
                lastSuccessfulSync =
                    json
                        .optString("lastSuccessfulSync")
                        .takeIf(String::isNotEmpty)
                        ?.let(Instant::parse),
                nextKnownIndex = nextKnownIndex,
                requests = entries,
                // Runtime attempt state is never persisted.
                syncing = false,
                failure = null,
                fullSyncRequired = cursor.isEmpty(),
            )
        }
    }
}
