package io.github.brrenat.seekervault.confirmations.storage

import android.util.AtomicFile
import io.github.brrenat.seekervault.confirmations.ChainCheck
import io.github.brrenat.seekervault.confirmations.ChainLevel
import io.github.brrenat.seekervault.confirmations.ChainReason
import io.github.brrenat.seekervault.confirmations.ChainState
import io.github.brrenat.seekervault.confirmations.ChainTracking
import io.github.brrenat.seekervault.confirmations.TrackingOrigin
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.connections.isConnectionId
import io.github.brrenat.seekervault.request.v1.Network
import java.io.File
import java.io.IOException
import java.time.DateTimeException
import java.time.Instant
import java.util.Base64
import org.json.JSONException
import org.json.JSONObject

/**
 * The transactions this phone is following to the chain (SEE-165): one JSON file per request,
 * `<dir>/<connection ID>/<request ID>.json`, written atomically, exactly as Activity is laid out.
 *
 * It holds public facts only — addresses, a signature, the approved message, what an endpoint's
 * host said — and never a credential or an endpoint URL. It is not tied to a connection: removing
 * one leaves its tracking in place, because the owner's History outlives the connection and so does
 * the question of whether its transaction landed. Clearing History clears this too.
 */
class TrackingStore(private val dir: File) {

    fun list(): List<ChainTracking> =
        connectionDirs().flatMap { connection ->
            (connection.listFiles() ?: emptyArray())
                .filter { it.name.endsWith(SUFFIX) }
                .mapNotNull { read(RequestKey(connection.name, it.name.removeSuffix(SUFFIX))) }
        }

    fun get(key: RequestKey): ChainTracking? = read(key)

    fun put(tracking: ChainTracking) {
        val file = atomicFile(tracking.key)
        File(dir, tracking.key.connectionId).mkdirs()
        val stream = file.startWrite()
        try {
            stream.write(encode(tracking).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: IOException) {
            file.failWrite(stream)
            throw e
        }
    }

    fun delete(key: RequestKey) {
        if (!valid(key)) return
        atomicFile(key).delete()
    }

    fun clear() {
        connectionDirs().forEach { it.deleteRecursively() }
    }

    private fun connectionDirs(): List<File> =
        (dir.listFiles() ?: emptyArray()).filter { it.isDirectory && isConnectionId(it.name) }

    private fun read(key: RequestKey): ChainTracking? {
        if (!valid(key)) return null
        val file = atomicFile(key)
        if (!file.baseFile.exists()) return null
        return try {
            decode(String(file.readFully(), Charsets.UTF_8))?.takeIf { it.key == key }
        } catch (_: IOException) {
            null
        } catch (_: JSONException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun valid(key: RequestKey) =
        isConnectionId(key.connectionId) && isConnectionId(key.requestId)

    private fun atomicFile(key: RequestKey): AtomicFile {
        require(valid(key)) { "not a request key" }
        return AtomicFile(File(File(dir, key.connectionId), "${key.requestId}$SUFFIX"))
    }

    internal companion object {
        const val SUFFIX = ".json"
        const val VERSION = 1

        fun encode(tracking: ChainTracking): String =
            JSONObject()
                .put("version", VERSION)
                .put("connectionId", tracking.key.connectionId)
                .put("requestId", tracking.key.requestId)
                .put("origin", tracking.origin.code)
                .put("network", tracking.network.name)
                .put("wallet", tracking.wallet)
                .put("message", Base64.getEncoder().encodeToString(tracking.message))
                .putOpt("blockhash", tracking.blockhash)
                .put("capturedAt", tracking.capturedAt.toString())
                .putOpt("signature", tracking.signature)
                .putOpt("submittedAt", tracking.submittedAt?.toString())
                .put("check", encodeCheck(tracking.check))
                .put("attempts", tracking.attempts)
                .toString()

        fun decode(text: String): ChainTracking? {
            val json = JSONObject(text)
            if (json.getInt("version") != VERSION) return null
            return ChainTracking(
                key = RequestKey(json.getString("connectionId"), json.getString("requestId")),
                origin = TrackingOrigin.of(json.getString("origin")) ?: return null,
                network = Network.valueOf(json.getString("network")),
                wallet = json.getString("wallet"),
                message = Base64.getDecoder().decode(json.getString("message")),
                blockhash = json.text("blockhash"),
                capturedAt = Instant.parse(json.getString("capturedAt")),
                signature = json.text("signature"),
                submittedAt = json.text("submittedAt")?.let(Instant::parse),
                check = decodeCheck(json.getJSONObject("check")) ?: return null,
                attempts = json.optInt("attempts"),
            )
        }

        fun encodeCheck(check: ChainCheck): JSONObject =
            JSONObject()
                .put("state", check.state.code)
                .putOpt("level", check.level?.code)
                .putOpt("slot", check.slot)
                .putOpt("chainError", check.chainError)
                .putOpt("checkedAt", check.checkedAt?.toString())
                .put("checks", check.checks)
                .putOpt("host", check.host)
                .putOpt("reason", check.reason?.code)
                .putOpt("nextCheckAt", check.nextCheckAt?.toString())

        /** Null for a state this build has no name for, which the caller treats as unreadable. */
        fun decodeCheck(json: JSONObject): ChainCheck? =
            ChainCheck(
                state = ChainState.of(json.getString("state")) ?: return null,
                level = ChainLevel.of(json.text("level")),
                slot = if (json.has("slot") && !json.isNull("slot")) json.getLong("slot") else null,
                chainError = json.text("chainError"),
                checkedAt = json.text("checkedAt")?.let(Instant::parse),
                checks = json.optInt("checks"),
                host = json.text("host"),
                reason = ChainReason.of(json.text("reason")),
                nextCheckAt = json.text("nextCheckAt")?.let(Instant::parse),
            )

        // `optString` reads an explicit JSON null as "null" (SEE-94's lesson).
        private fun JSONObject.text(name: String): String? =
            if (!has(name) || isNull(name)) null else getString(name).takeIf(String::isNotEmpty)
    }
}
