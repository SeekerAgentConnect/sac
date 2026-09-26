package io.github.brrenat.seekervault.policy.storage

import android.util.AtomicFile
import io.github.brrenat.seekervault.connections.isConnectionId
import io.github.brrenat.seekervault.policy.Allowlist
import io.github.brrenat.seekervault.policy.AssetLimits
import io.github.brrenat.seekervault.policy.ConnectionAssetLimits
import io.github.brrenat.seekervault.policy.ConnectionPolicy
import io.github.brrenat.seekervault.policy.ConnectionPolicyOverrides
import io.github.brrenat.seekervault.policy.GlobalPolicy
import io.github.brrenat.seekervault.policy.PolicyAction
import io.github.brrenat.seekervault.policy.PolicyAsset
import io.github.brrenat.seekervault.policy.RuleOverride
import io.github.brrenat.seekervault.policy.localPolicyOf
import io.github.brrenat.seekervault.policy.overridesOf
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
 * The owner's phone-local rules (docs/policy.md#storage): one global JSON document and one override
 * document per connection, all written atomically under [dir].
 *
 * Nothing here is encrypted: a policy holds no credential and no key. It holds public addresses and
 * the owner's own thresholds in `filesDir`, and nothing on this phone is backed up. The rules never
 * leave the phone; no code here writes them to a request, a result, or a log.
 */
class PolicyStore(private val dir: File) {
    /**
     * Stage 5's local-only view of one connection's rules.
     *
     * This compatibility API keeps the completed evaluator and editor unchanged while Stage 5.1 is
     * split across tickets. Code that needs to distinguish inheritance from an explicit no-check
     * uses [getOverrides] and the effective-policy resolver.
     */
    fun get(connectionId: String): StoredPolicy =
        when (val stored = getOverrides(connectionId)) {
            StoredConnectionOverrides.None -> StoredPolicy.None
            is StoredConnectionOverrides.Policy ->
                StoredPolicy.Policy(localPolicyOf(stored.overrides))
            is StoredConnectionOverrides.Unreadable -> StoredPolicy.Unreadable(stored.why)
        }

    /**
     * Writes Stage 5's flat connection policy as Stage 5.1 overrides: configured values replace
     * global values, absent fields inherit, and daily thresholds remain connection-scoped.
     */
    fun put(policy: ConnectionPolicy) {
        val problems = policyProblems(policy)
        require(problems.isEmpty()) { "policy has $problems" }
        putOverrides(overridesOf(policy))
    }

    /** The explicit overrides for [connectionId], or why their document couldn't be read. */
    fun getOverrides(connectionId: String): StoredConnectionOverrides {
        if (!isConnectionId(connectionId)) return StoredConnectionOverrides.None
        val text =
            try {
                String(atomicFile(connectionId).readFully(), Charsets.UTF_8)
            } catch (e: FileNotFoundException) {
                return StoredConnectionOverrides.None
            } catch (e: IOException) {
                return StoredConnectionOverrides.Unreadable(UnreadableReason.Damaged)
            }
        val decoded = decodeConnection(connectionId, text)
        val readable =
            when (decoded) {
                is DecodedConnection.Policy -> decoded
                is DecodedConnection.Unreadable ->
                    return StoredConnectionOverrides.Unreadable(decoded.why)
            }
        if (readable.needsMigration) {
            // A failed atomic replacement leaves version 1 readable and is retried next time. The
            // already-decoded rules are still fit to use now; a storage interruption does not turn
            // a valid policy into a blank one or create a global document from it.
            try {
                write(atomicFile(connectionId), encode(readable.overrides))
            } catch (_: IOException) {}
        }
        return StoredConnectionOverrides.Policy(readable.overrides)
    }

    /** Writes one connection's overrides whole, replacing only that connection's document. */
    fun putOverrides(overrides: ConnectionPolicyOverrides) {
        val problems = policyProblems(overrides)
        require(problems.isEmpty()) { "policy has $problems" }
        dir.mkdirs()
        write(atomicFile(overrides.connectionId), encode(overrides))
    }

    /** Forgets one connection's overrides. The global policy and every other override remain. */
    fun delete(connectionId: String) {
        if (!isConnectionId(connectionId)) return
        atomicFile(connectionId).delete()
    }

    /** The phone's global policy, or why its separate document couldn't be read. */
    fun getGlobal(): StoredGlobalPolicy {
        val text =
            try {
                String(globalFile().readFully(), Charsets.UTF_8)
            } catch (e: FileNotFoundException) {
                return StoredGlobalPolicy.None
            } catch (e: IOException) {
                return StoredGlobalPolicy.Unreadable(UnreadableReason.Damaged)
            }
        return when (val decoded = decodeGlobal(text)) {
            is DecodedGlobal.Policy -> StoredGlobalPolicy.Policy(decoded.policy)
            is DecodedGlobal.Unreadable -> StoredGlobalPolicy.Unreadable(decoded.why)
        }
    }

    /** Writes the global policy whole without reading or changing a connection document. */
    fun putGlobal(policy: GlobalPolicy) {
        val problems = policyProblems(policy)
        require(problems.isEmpty()) { "policy has $problems" }
        dir.mkdirs()
        write(globalFile(), encode(policy))
    }

    /** Removes only the global policy. Every connection override remains on disk. */
    fun deleteGlobal() {
        globalFile().delete()
    }

    /** Every connection that has overrides stored, readable or not. The global file is excluded. */
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

    private fun globalFile() = AtomicFile(File(dir, GLOBAL_FILE))

    private fun write(file: AtomicFile, text: String) {
        val stream = file.startWrite()
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: IOException) {
            file.failWrite(stream)
            throw e
        }
    }

    private companion object {
        const val SUFFIX = ".json"
        const val GLOBAL_FILE = "global.json"
        const val VERSION = 2

        fun fileName(connectionId: String) = "$connectionId$SUFFIX"

        fun encode(policy: GlobalPolicy): String {
            val json =
                JSONObject()
                    .put("version", VERSION)
                    .put("scope", "global")
                    .put("updatedAt", policy.updatedAt.toString())
            policy.actions?.let {
                json.put("actions", JSONArray(it.values.map { action -> action.code }))
            }
            policy.assets?.let { json.put("assets", JSONArray(it.values.map(::encodeAsset))) }
            policy.recipients?.let { json.put("recipients", JSONArray(it.values.toList())) }
            policy.programs?.let { json.put("programs", JSONArray(it.values.toList())) }
            putLimits(json, policy.limits)
            return json.toString()
        }

        fun encode(overrides: ConnectionPolicyOverrides): String {
            val json =
                JSONObject()
                    .put("version", VERSION)
                    .put("scope", "connection")
                    .put("connectionId", overrides.connectionId)
                    .put("updatedAt", overrides.updatedAt.toString())
            json.putOverride("actions", overrides.actions) { it.code }
            json.putOverride("assets", overrides.assets, ::encodeAsset)
            json.putOverride("recipients", overrides.recipients) { it }
            json.putOverride("programs", overrides.programs) { it }
            if (overrides.limits.isNotEmpty()) {
                val limits = JSONArray()
                for ((asset, value) in overrides.limits) {
                    val entry = JSONObject().put("asset", encodeAsset(asset))
                    entry.putThresholdOverride("perOperation", value.perOperation)
                    value.daily?.let { entry.put("daily", it.toString()) }
                    limits.put(entry)
                }
                json.put("limits", limits)
            }
            return json.toString()
        }

        fun putLimits(json: JSONObject, values: Map<PolicyAsset, AssetLimits>) {
            if (values.isEmpty()) return
            val limits = JSONArray()
            for ((asset, value) in values) {
                limits.put(
                    JSONObject()
                        .put("asset", encodeAsset(asset))
                        .putOpt("perOperation", value.perOperation?.toString())
                        .putOpt("daily", value.daily?.toString())
                )
            }
            json.put("limits", limits)
        }

        fun <T : Any> JSONObject.putOverride(
            key: String,
            override: RuleOverride<Allowlist<T>>,
            encode: (T) -> Any,
        ) {
            when (override) {
                RuleOverride.Inherit -> Unit
                RuleOverride.NoCheck -> put(key, JSONObject().put("mode", "no_check"))
                is RuleOverride.Replace ->
                    put(
                        key,
                        JSONObject()
                            .put("mode", "replace")
                            .put("values", JSONArray(override.value.values.map(encode))),
                    )
            }
        }

        fun JSONObject.putThresholdOverride(key: String, override: RuleOverride<ULong>) {
            when (override) {
                RuleOverride.Inherit -> Unit
                RuleOverride.NoCheck -> put(key, JSONObject().put("mode", "no_check"))
                is RuleOverride.Replace ->
                    put(
                        key,
                        JSONObject()
                            .put("mode", "replace")
                            .put("amount", override.value.toString()),
                    )
            }
        }

        fun encodeAsset(asset: PolicyAsset): JSONObject =
            JSONObject().put("network", asset.network.name).putOpt("mint", asset.mint)

        fun decodeConnection(connectionId: String, text: String): DecodedConnection =
            try {
                readConnection(connectionId, JSONObject(text))
            } catch (e: Unreadable) {
                DecodedConnection.Unreadable(e.why)
            } catch (e: JSONException) {
                DecodedConnection.Unreadable(UnreadableReason.Damaged)
            } catch (e: DateTimeException) {
                DecodedConnection.Unreadable(UnreadableReason.Damaged)
            }

        fun readConnection(connectionId: String, json: JSONObject): DecodedConnection {
            val version = json.optInt("version", 0)
            if (version > VERSION) throw Unreadable(UnreadableReason.NewerVersion)
            return when (version) {
                1 -> DecodedConnection.Policy(readVersion1(connectionId, json), true)
                VERSION -> DecodedConnection.Policy(readOverrides(connectionId, json), false)
                else -> throw Unreadable(UnreadableReason.Damaged)
            }
        }

        /** Stage 5's version 1 flat document, migrated without inventing a global policy. */
        fun readVersion1(connectionId: String, json: JSONObject): ConnectionPolicyOverrides {
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
            if (policyProblems(policy).isNotEmpty()) throw Unreadable(UnreadableReason.Damaged)
            return overridesOf(policy)
        }

        fun readOverrides(connectionId: String, json: JSONObject): ConnectionPolicyOverrides {
            if (json.getString("scope") != "connection") {
                throw Unreadable(UnreadableReason.Damaged)
            }
            if (json.getString("connectionId") != connectionId) {
                throw Unreadable(UnreadableReason.Damaged)
            }
            val overrides =
                ConnectionPolicyOverrides(
                    connectionId = connectionId,
                    actions =
                        json.allowlistOverride("actions") {
                            (it as? String)?.let(PolicyAction::byCode)
                        },
                    assets = json.allowlistOverride("assets", ::decodeAsset),
                    recipients = json.allowlistOverride("recipients") { it as? String },
                    programs = json.allowlistOverride("programs") { it as? String },
                    limits = json.connectionLimits(),
                    updatedAt = Instant.parse(json.getString("updatedAt")),
                )
            if (policyProblems(overrides).isNotEmpty()) {
                throw Unreadable(UnreadableReason.Damaged)
            }
            return overrides
        }

        fun decodeGlobal(text: String): DecodedGlobal =
            try {
                readGlobal(JSONObject(text))
            } catch (e: Unreadable) {
                DecodedGlobal.Unreadable(e.why)
            } catch (e: JSONException) {
                DecodedGlobal.Unreadable(UnreadableReason.Damaged)
            } catch (e: DateTimeException) {
                DecodedGlobal.Unreadable(UnreadableReason.Damaged)
            }

        fun readGlobal(json: JSONObject): DecodedGlobal {
            val version = json.optInt("version", 0)
            if (version > VERSION) throw Unreadable(UnreadableReason.NewerVersion)
            if (version != VERSION || json.getString("scope") != "global") {
                throw Unreadable(UnreadableReason.Damaged)
            }
            val policy =
                GlobalPolicy(
                    actions =
                        json.allowlist("actions") { (it as? String)?.let(PolicyAction::byCode) },
                    assets = json.allowlist("assets", ::decodeAsset),
                    recipients = json.allowlist("recipients") { it as? String },
                    programs = json.allowlist("programs") { it as? String },
                    limits = json.limits(),
                    updatedAt = Instant.parse(json.getString("updatedAt")),
                )
            if (policyProblems(policy).isNotEmpty()) throw Unreadable(UnreadableReason.Damaged)
            return DecodedGlobal.Policy(policy)
        }

        fun JSONObject.limits(): Map<PolicyAsset, AssetLimits> {
            if (!has("limits")) return emptyMap()
            val stored = optJSONArray("limits") ?: throw Unreadable(UnreadableReason.Damaged)
            val limits = linkedMapOf<PolicyAsset, AssetLimits>()
            for (index in 0 until stored.length()) {
                val entry =
                    stored.optJSONObject(index) ?: throw Unreadable(UnreadableReason.Damaged)
                val asset =
                    decodeAsset(entry.opt("asset"))
                        ?: throw Unreadable(UnreadableReason.UnknownRule)
                if (asset in limits) throw Unreadable(UnreadableReason.Damaged)
                limits[asset] = AssetLimits(entry.amount("perOperation"), entry.amount("daily"))
            }
            return limits
        }

        fun JSONObject.connectionLimits(): Map<PolicyAsset, ConnectionAssetLimits> {
            if (!has("limits")) return emptyMap()
            val stored = optJSONArray("limits") ?: throw Unreadable(UnreadableReason.Damaged)
            val limits = linkedMapOf<PolicyAsset, ConnectionAssetLimits>()
            for (index in 0 until stored.length()) {
                val entry =
                    stored.optJSONObject(index) ?: throw Unreadable(UnreadableReason.Damaged)
                val asset =
                    decodeAsset(entry.opt("asset"))
                        ?: throw Unreadable(UnreadableReason.UnknownRule)
                if (asset in limits) throw Unreadable(UnreadableReason.Damaged)
                limits[asset] =
                    ConnectionAssetLimits(
                        perOperation = entry.thresholdOverride("perOperation"),
                        daily = entry.amount("daily"),
                    )
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

        /** A plain whole number of base units; an absent key is no threshold. */
        fun JSONObject.amount(key: String): ULong? {
            if (!has(key) || isNull(key)) return null
            val stored = opt(key) as? String ?: throw Unreadable(UnreadableReason.Damaged)
            return stored.toULongOrNull() ?: throw Unreadable(UnreadableReason.Damaged)
        }

        fun JSONObject.thresholdOverride(key: String): RuleOverride<ULong> {
            if (!has(key)) return RuleOverride.Inherit
            val stored = optJSONObject(key) ?: throw Unreadable(UnreadableReason.Damaged)
            return when (stored.mode()) {
                "no_check" -> {
                    if (stored.has("amount")) throw Unreadable(UnreadableReason.Damaged)
                    RuleOverride.NoCheck
                }
                "replace" ->
                    RuleOverride.Replace(
                        stored.amount("amount") ?: throw Unreadable(UnreadableReason.Damaged)
                    )
                else -> throw Unreadable(UnreadableReason.UnknownRule)
            }
        }

        /** Reads a global or version 1 list. Absence is no check; an empty array allows nothing. */
        fun <T : Any> JSONObject.allowlist(key: String, item: (Any?) -> T?): Allowlist<T>? {
            if (!has(key)) return null
            return requiredAllowlist(key, item)
        }

        /** Reads inherit/no-check/replace without collapsing any two of those states. */
        fun <T : Any> JSONObject.allowlistOverride(
            key: String,
            item: (Any?) -> T?,
        ): RuleOverride<Allowlist<T>> {
            if (!has(key)) return RuleOverride.Inherit
            val stored = optJSONObject(key) ?: throw Unreadable(UnreadableReason.Damaged)
            return when (stored.mode()) {
                "no_check" -> {
                    if (stored.has("values")) throw Unreadable(UnreadableReason.Damaged)
                    RuleOverride.NoCheck
                }
                "replace" -> RuleOverride.Replace(stored.requiredAllowlist("values", item))
                else -> throw Unreadable(UnreadableReason.UnknownRule)
            }
        }

        fun <T : Any> JSONObject.requiredAllowlist(
            key: String,
            item: (Any?) -> T?,
        ): Allowlist<T> {
            val array = optJSONArray(key) ?: throw Unreadable(UnreadableReason.Damaged)
            val values = linkedSetOf<T>()
            for (index in 0 until array.length()) {
                values += item(array.opt(index)) ?: throw Unreadable(UnreadableReason.UnknownRule)
            }
            return Allowlist(values)
        }

        fun JSONObject.mode(): String {
            if (!has("mode") || opt("mode") !is String) {
                throw Unreadable(UnreadableReason.Damaged)
            }
            return getString("mode")
        }
    }

    private sealed interface DecodedConnection {
        data class Policy(
            val overrides: ConnectionPolicyOverrides,
            val needsMigration: Boolean,
        ) : DecodedConnection

        data class Unreadable(val why: UnreadableReason) : DecodedConnection
    }

    private sealed interface DecodedGlobal {
        data class Policy(val policy: GlobalPolicy) : DecodedGlobal

        data class Unreadable(val why: UnreadableReason) : DecodedGlobal
    }

    /** Thrown while reading a document and returned as an explicit unreadable state. */
    private class Unreadable(val why: UnreadableReason) : RuntimeException()
}

/**
 * Stage 5's local-only stored-policy view, retained until effective evaluation lands in SAW-044.
 */
sealed interface StoredPolicy {
    data object None : StoredPolicy

    data class Policy(val policy: ConnectionPolicy) : StoredPolicy

    data class Unreadable(val why: UnreadableReason) : StoredPolicy
}

/** What is stored for one connection in the Stage 5.1 format. */
sealed interface StoredConnectionOverrides {
    /** No override document: every rule inherits. */
    data object None : StoredConnectionOverrides

    data class Policy(val overrides: ConnectionPolicyOverrides) : StoredConnectionOverrides

    /** Stored overrides this build cannot read. Never treated as [None]. */
    data class Unreadable(val why: UnreadableReason) : StoredConnectionOverrides
}

/** What is stored in the global document, independently from every connection document. */
sealed interface StoredGlobalPolicy {
    data object None : StoredGlobalPolicy

    data class Policy(val policy: GlobalPolicy) : StoredGlobalPolicy

    /** Stored global rules this build cannot read. Never treated as [None]. */
    data class Unreadable(val why: UnreadableReason) : StoredGlobalPolicy
}

enum class UnreadableReason {
    /** The file isn't the document this app writes. */
    Damaged,
    /** Written by a later version of the app. Reading it would show fewer rules than were set. */
    NewerVersion,
    /** A rule names something this build has no name for. */
    UnknownRule,
}
