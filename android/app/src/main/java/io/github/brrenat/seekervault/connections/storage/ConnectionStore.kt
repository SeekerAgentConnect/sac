package io.github.brrenat.seekervault.connections.storage

import android.util.AtomicFile
import io.github.brrenat.seekervault.connections.CheckOutcome
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.ConnectionRetirement
import io.github.brrenat.seekervault.connections.ServerColour
import io.github.brrenat.seekervault.connections.isConnectionId
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginId
import io.github.brrenat.seekervault.plugins.isPluginId
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.FeedAccess
import io.github.brrenat.seekervault.servers.MAX_REQUIRED_PLUGINS
import io.github.brrenat.seekervault.servers.ManifestProblem
import io.github.brrenat.seekervault.servers.PluginRequirement
import io.github.brrenat.seekervault.servers.ServerManifest
import io.github.brrenat.seekervault.servers.ServerRecord
import io.github.brrenat.seekervault.servers.ServerReference
import io.github.brrenat.seekervault.servers.manifest
import java.io.File
import java.io.IOException
import java.time.Instant
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Connection metadata (docs/security.md#local-storage-and-recovery): one JSON file per connection,
 * `<dir>/<connection ID>.json`, written atomically. No file holds another connection's data, so
 * removing one connection leaves the others as they were. Credentials live elsewhere, in the
 * [CredentialVault].
 *
 * Version 2 added the connection's mode and the server manifest it caches (SEE-88). A version 1
 * file is still read, as what it is: a direct connection whose server has not been asked for a
 * manifest yet. The owner paired it and nothing about it changed, so it keeps working exactly as it
 * did, and the next refresh finds out whether its server publishes one.
 *
 * Version 3 added the environment the connection keeps (SEE-97). An older file is read as
 * production, which is exactly what this build did before the field existed: every connection was
 * production, and one whose server did not serve it was unexecutable and said so. So nothing a
 * phone already holds changes meaning when the app is updated.
 *
 * Version 5 retires gateway-private records. Versions 2–4 are read just far enough to recognize the
 * literal old mode, then rewritten without an active mode, credential state, or cached private
 * manifest. No endpoint or credential is converted to another transport.
 *
 * Version 6 added the connection's marker colour (SEE-83). An older file is read without one, which
 * is exactly what it held: the field is cosmetic and the repository assigns a colour to any active
 * connection missing one.
 */
class ConnectionStore(private val dir: File) {
    /** Every readable connection, oldest pairing first. A damaged file is skipped. */
    fun list(): List<Connection> =
        dir.listFiles { file -> file.name.endsWith(SUFFIX) }
            .orEmpty()
            .mapNotNull { read(it.name.removeSuffix(SUFFIX)) }
            .sortedWith(compareBy({ it.pairedAt }, { it.id }))

    fun get(id: String): Connection? = read(id)

    /**
     * Atomically rewrites every legacy private record and returns all durable retirement markers.
     */
    fun migrateRetired(): List<Connection> {
        dir.listFiles { file -> file.name.endsWith(SUFFIX) }
            .orEmpty()
            .forEach { file ->
                try {
                    val text = String(AtomicFile(file).readFully(), Charsets.UTF_8)
                    val json = JSONObject(text)
                    if (
                        json.optInt("version") in FIRST_VERSION until VERSION &&
                            json.optString("mode") == LEGACY_GATEWAY_PRIVATE
                    ) {
                        decode(text)?.let(::put)
                    }
                } catch (_: IOException) {
                    // Damaged records are skipped exactly as list() skips them.
                } catch (_: JSONException) {
                    // A file that is not readable connection metadata cannot be migrated safely.
                } catch (_: IllegalArgumentException) {
                    // A malformed timestamp or enum is not guessed at.
                }
            }
        return list().filter { it.retirement != null }
    }

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
        const val VERSION = 6
        const val LEGACY_GATEWAY_PRIVATE = "gateway_private"
        /**
         * Version 1 files predate the mode and the manifest, and are read as direct and unasked.
         */
        const val FIRST_VERSION = 1
        /** Version 3 added the environment; before it, every connection was production. */
        const val ENVIRONMENT_VERSION = 3
        /** Version 6 added the marker colour; before it, records had none (SEE-83). */
        const val COLOUR_VERSION = 6

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
                .putOpt("mode", connection.mode?.code)
                .putOpt("retirement", connection.retirement?.code)
                .put("environment", connection.environment.code)
                .putOpt("colour", connection.colour?.name)
                .put("server", encodeServer(connection.server))
                .toString()

        fun encodeServer(record: ServerRecord): JSONObject =
            when (record) {
                is ServerRecord.Unknown -> JSONObject().put("state", "unknown")
                is ServerRecord.Legacy -> JSONObject().put("state", "legacy")
                is ServerRecord.Refused ->
                    JSONObject().put("state", "refused").put("problem", record.problem.code)
                is ServerRecord.Known ->
                    JSONObject()
                        .put("state", "known")
                        .put("manifest", encodeManifest(record.manifest))
            }

        fun encodeManifest(manifest: ServerManifest): JSONObject =
            JSONObject()
                .put("serverId", manifest.serverId)
                .put("protocolVersion", manifest.protocolVersion)
                .put("settingsRevision", manifest.settingsRevision)
                .put("mode", manifest.mode.code)
                .apply {
                    when (val reference = manifest.reference) {
                        is ServerReference.Direct -> put("url", reference.url)
                        is ServerReference.Feed -> {
                            put("gatewayUrl", reference.gatewayUrl)
                            put("channel", reference.channel)
                            // Additive (SEE-156): a public feed writes nothing new, so the record
                            // is what it always was, and the format version does not move — an
                            // older build reads a restricted feed as public, and the gateway
                            // refuses its reads, which is the safe way round.
                            (reference.access as? FeedAccess.Restricted)?.let {
                                put("access", "restricted")
                                put("authOrigin", it.authOrigin)
                            }
                        }
                    }
                }
                .put(
                    "required",
                    JSONArray().apply {
                        manifest.required.forEach { requirement ->
                            put(
                                JSONObject()
                                    .put("id", requirement.id.value)
                                    .put("least", requirement.contracts.first)
                                    .put("most", requirement.contracts.last)
                            )
                        }
                    },
                )
                .put(
                    "environments",
                    JSONArray().apply { manifest.environments.forEach { put(it.code) } },
                )
                .put("name", manifest.name)

        fun decode(text: String): Connection? {
            val json = JSONObject(text)
            val version = json.getInt("version")
            if (version !in FIRST_VERSION..VERSION) return null
            val retirement =
                when {
                    version < VERSION && json.optString("mode") == LEGACY_GATEWAY_PRIVATE ->
                        ConnectionRetirement.GatewayPrivateRemoved
                    version >= VERSION ->
                        ConnectionRetirement.entries.firstOrNull {
                            it.code == json.optString("retirement")
                        }
                    else -> null
                }
            if (retirement != null) {
                return Connection(
                    id = json.getString("id"),
                    label = json.getString("label"),
                    serverUrl = json.getString("serverUrl"),
                    serverId = json.getString("serverId"),
                    deviceName = json.getString("deviceName"),
                    pairedAt = Instant.parse(json.getString("pairedAt")),
                    revokedAt =
                        json.optString("revokedAt").takeIf { it.isNotEmpty() }?.let(Instant::parse),
                    lastCheck = null,
                    hasCredential = false,
                    mode = null,
                    retirement = retirement,
                    server = ServerRecord.Unknown,
                    environment = PluginEnvironment.Production,
                )
            }
            // An unreadable mode is never guessed at: there is no safe default between a server
            // the phone calls with a credential and one it only listens to.
            val mode =
                if (version < 2) ConnectionMode.Direct
                else
                    ConnectionMode.entries.firstOrNull { it.code == json.optString("mode") }
                        ?: return null
            // A manifest this phone cached but can no longer read is dropped rather than trusted,
            // and for a direct connection that is all it is: the server is asked again on the next
            // refresh. A feed has nothing left without it, so it is the whole record that goes.
            val server = decodeServer(json.optJSONObject("server")) ?: ServerRecord.Unknown
            if (mode != ConnectionMode.Direct && server !is ServerRecord.Known) return null
            if (server.manifest?.mode?.equals(mode) == false) return null
            // A direct connection is production whatever the file says, so nothing an edited or
            // half-written file could hold puts the legacy sidecar path in a simulated mode. A
            // feed preserves its manifest's environment; an unreadable value makes the
            // whole record unreadable rather than resolving it toward real money.
            val environment =
                when {
                    mode == ConnectionMode.Direct -> PluginEnvironment.Production
                    version < ENVIRONMENT_VERSION -> PluginEnvironment.Production
                    else ->
                        PluginEnvironment.entries.firstOrNull {
                            it.code == json.optString("environment")
                        } ?: return null
                }
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
                // Never stored: a direct connection's credential is in the vault, which the
                // repository reads, and a feed has none to hold (SEE-88).
                hasCredential = mode != ConnectionMode.GatewayFeed,
                mode = mode,
                server = server,
                environment = environment,
                // A missing or unrecognised name is no colour. The record stays readable and the
                // repository assigns one (SEE-83).
                colour =
                    if (version < COLOUR_VERSION) null
                    else
                        json
                            .optString("colour")
                            .takeIf { it.isNotEmpty() }
                            ?.let { name ->
                                ServerColour.entries.firstOrNull { it.name == name }
                            },
            )
        }

        /** The cached record, or null when this phone can't read what it wrote. */
        fun decodeServer(json: JSONObject?): ServerRecord? =
            when (json?.optString("state")) {
                "unknown" -> ServerRecord.Unknown
                "legacy" -> ServerRecord.Legacy
                "refused" ->
                    ManifestProblem.entries
                        .firstOrNull { it.code == json.optString("problem") }
                        ?.let(ServerRecord::Refused)
                "known" -> decodeManifest(json.optJSONObject("manifest"))?.let(ServerRecord::Known)
                else -> null
            }

        /**
         * The cached manifest, or null when anything about it is unreadable. Every rule
         * `manifestFrom` applied when it arrived is applied again here, because a file on disk is
         * not evidence about itself: an edited or half-written one is refused the same way a bad
         * manifest from a server would be.
         */
        fun decodeManifest(json: JSONObject?): ServerManifest? {
            if (json == null) return null
            val serverId = json.optString("serverId")
            if (!isConnectionId(serverId)) return null
            val protocolVersion = json.optInt("protocolVersion")
            if (protocolVersion <= 0) return null
            val revision = json.optLong("settingsRevision")
            if (revision <= 0L) return null
            val mode =
                ConnectionMode.entries.firstOrNull { it.code == json.optString("mode") }
                    ?: return null
            val reference =
                when (mode) {
                    ConnectionMode.Direct ->
                        ServerReference.Direct(
                            json.optString("url").ifEmpty {
                                return null
                            }
                        )
                    ConnectionMode.GatewayFeed -> {
                        val channel = json.optString("channel")
                        val gateway = json.optString("gatewayUrl")
                        if (gateway.isEmpty() || channel.isEmpty()) return null
                        val access =
                            when (json.optString("access")) {
                                "" -> FeedAccess.Public
                                "restricted" ->
                                    FeedAccess.Restricted(
                                        json.optString("authOrigin").ifEmpty {
                                            return null
                                        }
                                    )
                                else -> return null
                            }
                        ServerReference.Feed(gateway, channel, access)
                    }
                }
            val requirements = json.optJSONArray("required") ?: JSONArray()
            if (requirements.length() > MAX_REQUIRED_PLUGINS) return null
            val required = mutableListOf<PluginRequirement>()
            for (index in 0 until requirements.length()) {
                val requirement = requirements.optJSONObject(index) ?: return null
                val id = requirement.optString("id")
                val least = requirement.optInt("least")
                val most = requirement.optInt("most")
                if (!isPluginId(id) || least < 1 || most < least) return null
                if (required.any { it.id.value == id }) return null
                required += PluginRequirement(PluginId(id), least..most)
            }
            val named = json.optJSONArray("environments") ?: JSONArray()
            val environments = mutableSetOf<PluginEnvironment>()
            for (index in 0 until named.length()) {
                val environment =
                    PluginEnvironment.entries.firstOrNull { it.code == named.optString(index) }
                        ?: return null
                if (!environments.add(environment)) return null
            }
            if (environments.isEmpty()) return null
            return ServerManifest(
                serverId = serverId,
                protocolVersion = protocolVersion,
                settingsRevision = revision,
                mode = mode,
                reference = reference,
                required = required.toList(),
                environments = environments.toSet(),
                name = json.optString("name"),
            )
        }
    }
}
