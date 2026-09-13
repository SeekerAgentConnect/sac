package io.github.brrenat.seekervault.policy.storage

import android.util.AtomicFile
import io.github.brrenat.seekervault.connections.isConnectionId
import io.github.brrenat.seekervault.policy.Allowlist
import io.github.brrenat.seekervault.policy.AssetLimits
import io.github.brrenat.seekervault.policy.ConnectionPolicy
import io.github.brrenat.seekervault.policy.PolicyAction
import io.github.brrenat.seekervault.policy.PolicyAsset
import io.github.brrenat.seekervault.policy.policyProblems
import io.github.brrenat.seekervault.request.v1.Network
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.DateTimeException
import java.time.Instant
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The owner's rules, as this phone holds them (docs/policy.md#storage): one JSON file per
 * connection, `<dir>/<connection ID>.json`, written atomically. One connection's rules are in one
 * file and nowhere else, so they can never be read for another connection, and removing one
 * connection's rules leaves every other connection's exactly as they were.
 *
 * Nothing here is encrypted: a policy holds no credential and no key. It holds public addresses and
 * the owner's own thresholds, in `filesDir`, and nothing on this phone is backed up.
 *
 * The rules never leave the phone. No code here writes them to a request, a result, or a log.
 */
class PolicyStore(private val dir: File) {
    /** The rules for [connectionId], or why they couldn't be read. */
    fun get(connectionId: String): StoredPolicy {
        if (!isConnectionId(connectionId)) return StoredPolicy.None
        val text =
            try {
                String(atomicFile(connectionId).readFully(), Charsets.UTF_8)
            } catch (e: FileNotFoundException) {
                return StoredPolicy.None
            } catch (e: IOException) {
                return StoredPolicy.Unreadable(UnreadableReason.Damaged)
            }
        return decode(connectionId, text)
    }

    /**
     * Writes [policy] whole, replacing the connection's previous rules.
     *
     * A policy with a problem in it is refused rather than stored: the editor shows the owner what
     * is wrong, and a file this app can't read back is never the thing it wrote.
     */
    fun put(policy: ConnectionPolicy) {
        val problems = policyProblems(policy)
        require(problems.isEmpty()) { "policy has $problems" }
        val file = atomicFile(policy.connectionId)
        dir.mkdirs()
        val stream = file.startWrite()
        try {
            stream.write(encode(policy).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: IOException) {
            file.failWrite(stream)
            throw e
        }
    }

    /** Forgets one connection's rules. The connection's rules go when the connection does. */
    fun delete(connectionId: String) {
        if (!isConnectionId(connectionId)) return
        atomicFile(connectionId).delete()
    }

    /** Every connection that has rules stored, readable or not. */
    fun connectionIds(): Set<String> =
        dir.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(SUFFIX) }
            .map { it.name.removeSuffix(SUFFIX) }
            .filter(::isConnectionId)
            .toSet()

    // The ID names the file, so it must be the UUID the sidecar assigned, never a path.
    private fun atomicFile(connectionId: String): AtomicFile {
        require(isConnectionId(connectionId)) { "not a connection ID" }
        return AtomicFile(File(dir, fileName(connectionId)))
    }

    private companion object {
        const val SUFFIX = ".json"

        /**
         * The document format. Version 1 is the first this app ever wrote, so there is no older
         * document to upgrade and anything that isn't 1 is refused.
         *
         * When a version 2 arrives it reads a version 1 document and upgrades it here, rather than
         * refusing it — an upgrade path is added for every version this app has written. A *newer*
         * version is always refused: reading a document this build only half understands would show
         * the owner fewer rules than they set, and saving it back would delete the rest.
         */
        const val VERSION = 1

        fun fileName(connectionId: String) = "$connectionId$SUFFIX"

        fun encode(policy: ConnectionPolicy): String {
            val json =
                JSONObject()
                    .put("version", VERSION)
                    .put("connectionId", policy.connectionId)
                    .put("updatedAt", policy.updatedAt.toString())
            // A list is written only when the owner configured one. An absent key and an empty
            // array are different rules, and this is where that difference is kept.
            policy.actions?.let { json.put("actions", JSONArray(it.values.map { a -> a.code })) }
            policy.assets?.let { json.put("assets", JSONArray(it.values.map(::encodeAsset))) }
            policy.recipients?.let { json.put("recipients", JSONArray(it.values.toList())) }
            policy.programs?.let { json.put("programs", JSONArray(it.values.toList())) }
            if (policy.limits.isNotEmpty()) {
                val limits = JSONArray()
                for ((asset, value) in policy.limits) {
                    limits.put(
                        JSONObject()
                            .put("asset", encodeAsset(asset))
                            .putOpt("perOperation", value.perOperation?.toString())
                            .putOpt("daily", value.daily?.toString())
                    )
                }
                json.put("limits", limits)
            }
            return json.toString()
        }

        fun encodeAsset(asset: PolicyAsset): JSONObject =
            JSONObject().put("network", asset.network.name).putOpt("mint", asset.mint)

        fun decode(connectionId: String, text: String): StoredPolicy =
            try {
                read(connectionId, JSONObject(text))
            } catch (e: Unreadable) {
                StoredPolicy.Unreadable(e.why)
            } catch (e: JSONException) {
                StoredPolicy.Unreadable(UnreadableReason.Damaged)
            } catch (e: DateTimeException) {
                StoredPolicy.Unreadable(UnreadableReason.Damaged)
            }

        fun read(connectionId: String, json: JSONObject): StoredPolicy {
            val version = json.optInt("version", 0)
            if (version > VERSION) throw Unreadable(UnreadableReason.NewerVersion)
            if (version != VERSION) throw Unreadable(UnreadableReason.Damaged)
            // The file is named by its connection, and it says which connection it is for. A file
            // that disagrees is not this connection's policy, whatever else it is.
            if (json.getString("connectionId") != connectionId) {
                throw Unreadable(UnreadableReason.Damaged)
            }
            val policy =
                ConnectionPolicy(
                    connectionId = connectionId,
                    actions =
                        json.allowlist("actions") { (it as? String)?.let(PolicyAction::byCode) },
                    assets = json.allowlist("assets", ::decodeAsset),
                    recipients = json.allowlist("recipients") { it as? String },
                    programs = json.allowlist("programs") { it as? String },
                    limits = json.limits(),
                    updatedAt = Instant.parse(json.getString("updatedAt")),
                )
            // What this app refuses to write, it refuses to read back as the owner's rules.
            if (policyProblems(policy).isNotEmpty()) throw Unreadable(UnreadableReason.Damaged)
            return StoredPolicy.Policy(policy)
        }

        fun JSONObject.limits(): Map<PolicyAsset, AssetLimits> {
            val stored = optJSONArray("limits") ?: return emptyMap()
            val limits = mutableMapOf<PolicyAsset, AssetLimits>()
            for (index in 0 until stored.length()) {
                val entry =
                    stored.optJSONObject(index) ?: throw Unreadable(UnreadableReason.Damaged)
                val asset =
                    decodeAsset(entry.opt("asset"))
                        ?: throw Unreadable(UnreadableReason.UnknownRule)
                limits[asset] = AssetLimits(entry.amount("perOperation"), entry.amount("daily"))
            }
            return limits
        }

        fun decodeAsset(value: Any?): PolicyAsset? {
            val json = value as? JSONObject ?: return null
            val network = Network.entries.firstOrNull { it.name == json.optString("network") }
            if (network == null || network == Network.NETWORK_UNSPECIFIED) return null
            val mint = json.optString("mint").takeIf { it.isNotEmpty() }
            return PolicyAsset(network, mint)
        }

        /**
         * A limit, in base units. An absent key is no limit configured; anything that isn't a plain
         * decimal number is a damaged file, never a limit of zero.
         */
        fun JSONObject.amount(key: String): ULong? {
            if (!has(key) || isNull(key)) return null
            return optString(key).toULongOrNull() ?: throw Unreadable(UnreadableReason.Damaged)
        }

        /**
         * Reads one list. An absent key is no list at all, and an empty array is a list that allows
         * nothing. An entry this build doesn't recognize makes the whole document unreadable rather
         * than a shorter list, because a rule silently dropped on read is a rule silently deleted
         * on the next save.
         */
        fun <T : Any> JSONObject.allowlist(key: String, item: (Any?) -> T?): Allowlist<T>? {
            if (!has(key)) return null
            val array = optJSONArray(key) ?: throw Unreadable(UnreadableReason.Damaged)
            val values = mutableSetOf<T>()
            for (index in 0 until array.length()) {
                values += item(array.opt(index)) ?: throw Unreadable(UnreadableReason.UnknownRule)
            }
            return Allowlist(values)
        }
    }

    /** Thrown while reading a document, and turned into a [StoredPolicy.Unreadable]. */
    private class Unreadable(val why: UnreadableReason) : RuntimeException()
}

/** What is stored for one connection. */
sealed interface StoredPolicy {
    /** No rules were ever saved for this connection. */
    data object None : StoredPolicy

    data class Policy(val policy: ConnectionPolicy) : StoredPolicy

    /**
     * Rules are stored and this build can't read them. Never treated as [None]: the owner set
     * rules, and a phone that lost track of them must say so rather than report a clean slate.
     */
    data class Unreadable(val why: UnreadableReason) : StoredPolicy
}

enum class UnreadableReason {
    /** The file isn't the document this app writes. */
    Damaged,
    /** Written by a later version of the app. Reading it would show fewer rules than were set. */
    NewerVersion,
    /** A rule names something this build has no name for. */
    UnknownRule,
}
