package io.github.brrenat.seekervault.activity.storage

import android.util.AtomicFile
import io.github.brrenat.seekervault.activity.ActivityKind
import io.github.brrenat.seekervault.activity.ActivityOutcome
import io.github.brrenat.seekervault.activity.ActivityRecord
import io.github.brrenat.seekervault.activity.ReviewedDailyCheck
import io.github.brrenat.seekervault.activity.ReviewedOperation
import io.github.brrenat.seekervault.activity.ReviewedPolicy
import io.github.brrenat.seekervault.activity.ReviewedRuleSource
import io.github.brrenat.seekervault.activity.ReviewedSpending
import io.github.brrenat.seekervault.activity.ReviewedStaking
import io.github.brrenat.seekervault.activity.ReviewedTransfer
import io.github.brrenat.seekervault.activity.ReviewedValue
import io.github.brrenat.seekervault.confirmations.storage.TrackingStore
import io.github.brrenat.seekervault.connections.isConnectionId
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.request.v1.Network
import java.io.File
import java.io.IOException
import java.time.DateTimeException
import java.time.Instant
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One complete attempt to read Activity, including files whose contents could not be decoded. */
data class ActivitySnapshot(
    val records: List<ActivityRecord>,
    val unreadableRecords: Int,
)

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
     * Every readable record, newest first. A single damaged file is skipped here: one unreadable
     * record is not a reason to lose the rest. [snapshot] retains the count for policy evaluation.
     * A directory that exists and can't be listed is a different thing — that is the history itself
     * being unreadable, and it throws rather than coming back looking empty, because an empty
     * history and an unreadable one must never read the same.
     */
    fun list(): List<ActivityRecord> = snapshot().records

    /**
     * Every readable record and the number that were present but unreadable.
     *
     * Activity can omit a damaged row from the owner's list and keep showing the rest, but a policy
     * counter cannot treat that omission as zero: the row might be spending in the scope being
     * checked. Keeping the count beside the records lets evaluation report the total as unverified.
     */
    fun snapshot(): ActivitySnapshot {
        var unreadable = 0
        val records =
            connectionIds()
                .flatMap { connectionId ->
                    entriesOf(connectionDir(connectionId))
                        .filter { it.name.endsWith(SUFFIX) }
                        .mapNotNull { file ->
                            read(connectionId, file.name.removeSuffix(SUFFIX)).also {
                                if (it == null) unreadable++
                            }
                        }
                }
                .sortedWith(
                    compareByDescending<ActivityRecord> { it.answeredAt }.thenBy { it.requestId }
                )
        return ActivitySnapshot(records, unreadable)
    }

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

    /**
     * Removes everything. The owner asked; nothing else calls it.
     *
     * It also writes down when (SEE-165), because a record is written from more than one place and
     * some of them answer late: a server's reply, a sync, a chain check that was in flight. None of
     * them may bring back a record the owner cleared, and [clearedAt] is how they know.
     */
    fun clear(at: Instant) {
        connectionIds().forEach { File(dir, it).deleteRecursively() }
        dir.mkdirs()
        val file = AtomicFile(File(dir, CLEARED))
        val stream = file.startWrite()
        try {
            stream.write(at.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: IOException) {
            file.failWrite(stream)
            throw e
        }
    }

    /** When the owner last cleared History, or null if they never have. */
    fun clearedAt(): Instant? {
        val file = AtomicFile(File(dir, CLEARED))
        if (!file.baseFile.exists()) return null
        return try {
            Instant.parse(String(file.readFully(), Charsets.UTF_8).trim())
        } catch (_: IOException) {
            null
        } catch (_: DateTimeException) {
            null
        }
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
        const val CLEARED = "cleared-at"
        // SAW-028 added the assessment the owner was shown, and left the version alone. The field
        // is optional and additive, so a file with one reads the same on a build that has never
        // heard of it. A bump would not: a build that refuses the version drops the whole record,
        // and a dropped record is a transfer the day's counters never see
        // (docs/policy.md#counters).
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
                // SEE-89 added the operation the owner executed from a shared proposal, and left
                // the version alone for the same reason SAW-028 did: the field is optional and
                // additive, and a bump would make an older build drop the whole record.
                .putOpt("operation", record.operation?.let(::encodeOperation))
                // Codes, never rules: what the owner read, not what they wrote.
                .putOpt("policy", record.policy?.let(::encodePolicy))
                // The signature is public the moment the wallet makes it, like the address.
                .putOpt("signature", record.signature)
                .putOpt("detail", record.detail)
                .putOpt("checkedWith", record.checkedWith)
                // SEE-165 added a staking action's terms and what the phone itself found on chain,
                // and left the version alone for the reasons above: both are optional and
                // additive, and an older build reading them skips them rather than the record.
                .putOpt("staking", record.staking?.let(::encodeStaking))
                .putOpt("chain", record.chain?.let(TrackingStore::encodeCheck))
                .toString()

        fun encodeStaking(staking: ReviewedStaking): JSONObject =
            JSONObject()
                .put("wallet", staking.wallet)
                .put("network", staking.network.name)
                .put("operation", staking.operation)
                .put("amount", staking.amount)

        fun decodeStaking(json: JSONObject?): ReviewedStaking? = json?.let {
            ReviewedStaking(
                wallet = it.getString("wallet"),
                network = Network.valueOf(it.getString("network")),
                operation = it.getString("operation"),
                amount = it.optString("amount"),
            )
        }

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

        fun encodeOperation(operation: ReviewedOperation): JSONObject =
            JSONObject()
                .put("operation", operation.operation)
                .put("plugin", operation.plugin)
                .put("contract", operation.contract)
                .put("revision", operation.revision)
                .put("wallet", operation.wallet)
                .put("network", operation.network.name)
                // SEE-97 added the promise it was bound under, and left the version alone for the
                // same reason SAW-028 and SEE-94 did: the field is additive, and a record written
                // before it is a production one, which is what every execution was then.
                .put("environment", operation.environment.code)
                .put("preparedVersion", operation.preparedVersion)
                .put(
                    "values",
                    JSONArray().apply {
                        operation.values.forEach {
                            put(JSONObject().put("key", it.key).put("text", it.text))
                        }
                    },
                )
                // SEE-94 added the provider's own identifiers for an order. A record written
                // before them simply has none, which is what `optJSONArray` reads back.
                .put(
                    "references",
                    JSONArray().apply {
                        operation.references.forEach {
                            put(JSONObject().put("key", it.key).put("text", it.text))
                        }
                    },
                )
                // SEE-181 added what the operation spends, additively: an older build ignores it,
                // and a record without it reads as one that does not say.
                .putOpt("spending", operation.spending?.let(::encodeSpending))

        fun decodeOperation(json: JSONObject?): ReviewedOperation? = json?.let {
            ReviewedOperation(
                operation = it.getString("operation"),
                plugin = it.getString("plugin"),
                contract = it.getInt("contract"),
                revision = it.getLong("revision"),
                wallet = it.getString("wallet"),
                network = Network.valueOf(it.getString("network")),
                environment =
                    PluginEnvironment.entries.firstOrNull { named ->
                        named.code == it.optString("environment")
                    } ?: PluginEnvironment.Production,
                preparedVersion = it.getInt("preparedVersion"),
                values = decodeValues(it.optJSONArray("values")),
                references = decodeValues(it.optJSONArray("references")),
                spending = decodeSpending(it.optJSONObject("spending")),
            )
        }

        fun decodeValues(array: JSONArray?): List<ReviewedValue> =
            (0 until (array?.length() ?: 0)).map { index ->
                val value = checkNotNull(array).getJSONObject(index)
                ReviewedValue(value.getString("key"), value.getString("text"))
            }

        fun encodePolicy(policy: ReviewedPolicy): JSONObject =
            JSONObject()
                .put("assessment", policy.assessment)
                .put("reasons", JSONArray(policy.reasons))
                .put("notChecked", JSONArray(policy.notChecked))
                .put("assessedAt", policy.assessedAt.toString())
                .put("approvedAnyway", policy.approvedAnyway)
                .put(
                    "ruleSources",
                    JSONArray(
                        policy.ruleSources.map {
                            JSONObject().put("check", it.check).put("source", it.source)
                        }
                    ),
                )
                .put(
                    "dailyChecks",
                    JSONArray(
                        policy.dailyChecks.map {
                            JSONObject()
                                .put("scope", it.scope)
                                .put("source", it.source)
                                .put("status", it.status)
                                .putOpt("reason", it.reason)
                        }
                    ),
                )
                .put("unreadableSources", JSONArray(policy.unreadableSources))

        fun decodePolicy(json: JSONObject?): ReviewedPolicy? = json?.let {
            ReviewedPolicy(
                assessment = it.getString("assessment"),
                reasons = codes(it.optJSONArray("reasons")),
                notChecked = codes(it.optJSONArray("notChecked")),
                assessedAt = Instant.parse(it.getString("assessedAt")),
                approvedAnyway = it.optBoolean("approvedAnyway"),
                ruleSources = ruleSources(it.optJSONArray("ruleSources")),
                dailyChecks = dailyChecks(it.optJSONArray("dailyChecks")),
                unreadableSources = codes(it.optJSONArray("unreadableSources")),
            )
        }

        fun ruleSources(array: JSONArray?): List<ReviewedRuleSource> =
            (0 until (array?.length() ?: 0)).mapNotNull { index ->
                array?.optJSONObject(index)?.let { value ->
                    val check = value.optString("check")
                    val source = value.optString("source")
                    if (check.isEmpty() || source.isEmpty()) null
                    else ReviewedRuleSource(check, source)
                }
            }

        fun dailyChecks(array: JSONArray?): List<ReviewedDailyCheck> =
            (0 until (array?.length() ?: 0)).mapNotNull { index ->
                array?.optJSONObject(index)?.let { value ->
                    val scope = value.optString("scope")
                    val source = value.optString("source")
                    val status = value.optString("status")
                    if (scope.isEmpty() || source.isEmpty() || status.isEmpty()) null
                    else
                        ReviewedDailyCheck(
                            scope = scope,
                            source = source,
                            status = status,
                            reason = value.optString("reason").takeIf(String::isNotEmpty),
                        )
                }
            }

        /** A list of codes, with anything that isn't one left out rather than guessed at. */
        fun codes(array: JSONArray?): List<String> =
            (0 until (array?.length() ?: 0))
                .mapNotNull { array?.optString(it) }
                .filter {
                    it.isNotEmpty()
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
                operation = decodeOperation(json.optJSONObject("operation")),
                policy = decodePolicy(json.optJSONObject("policy")),
                signature = json.optString("signature").takeIf(String::isNotEmpty),
                detail = json.optString("detail").takeIf(String::isNotEmpty),
                checkedWith = json.optString("checkedWith").takeIf(String::isNotEmpty),
                staking = decodeStaking(json.optJSONObject("staking")),
                // A chain state a later build named and this one can't read is left out, not the
                // record: the record is still the owner's history without it.
                chain = json.optJSONObject("chain")?.let(TrackingStore::decodeCheck),
            )
        }
    }
}

/**
 * What an execution spends, as JSON (SEE-181). Shared with the proposal store, which pins the same
 * fact in the binding before the wallet is opened, so the two can never spell it differently.
 *
 * A kind or an amount this build cannot read decodes as null — "the record does not say" — which
 * the day's counters treat as unknown, never as nothing spent.
 */
internal fun encodeSpending(spending: ReviewedSpending): JSONObject =
    when (spending) {
        is ReviewedSpending.Outgoing ->
            JSONObject()
                .put("kind", "outgoing")
                .put("wallet", spending.wallet)
                .put("network", spending.network.name)
                .putOpt("mint", spending.mint)
                // Base units as a decimal string: a JSON number cannot hold every ULong.
                .put("amount", spending.amount.toString())
        ReviewedSpending.None -> JSONObject().put("kind", "none")
    }

internal fun decodeSpending(json: JSONObject?): ReviewedSpending? {
    json ?: return null
    return when (json.optString("kind")) {
        "none" -> ReviewedSpending.None
        "outgoing" -> {
            val wallet = json.optString("wallet").takeIf(String::isNotEmpty) ?: return null
            val network =
                runCatching { Network.valueOf(json.optString("network")) }
                    .getOrNull()
                    ?.takeIf { it != Network.UNRECOGNIZED } ?: return null
            val amount = json.optString("amount").toULongOrNull() ?: return null
            ReviewedSpending.Outgoing(
                wallet = wallet,
                network = network,
                mint = json.optString("mint").takeIf(String::isNotEmpty),
                amount = amount,
            )
        }
        else -> null
    }
}
