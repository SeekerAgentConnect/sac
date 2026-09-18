package io.github.brrenat.seekervault.connections.storage

import android.util.AtomicFile
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.connections.isConnectionId
import io.github.brrenat.seekervault.plugins.OperationId
import io.github.brrenat.seekervault.plugins.ParameterChoice
import io.github.brrenat.seekervault.plugins.ParameterKey
import io.github.brrenat.seekervault.plugins.ParameterValue
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginId
import io.github.brrenat.seekervault.proposals.ExecutionBinding
import io.github.brrenat.seekervault.proposals.Proposal
import io.github.brrenat.seekervault.proposals.ProposalDismissal
import io.github.brrenat.seekervault.proposals.ProposalExecution
import io.github.brrenat.seekervault.proposals.ProposalKey
import io.github.brrenat.seekervault.proposals.ProposalOutcome
import io.github.brrenat.seekervault.proposals.ProposalProblem
import io.github.brrenat.seekervault.proposals.ProposalRecord
import io.github.brrenat.seekervault.proposals.ProposalReview
import io.github.brrenat.seekervault.proposals.ProposalStatus
import io.github.brrenat.seekervault.proposals.ProposalValue
import io.github.brrenat.seekervault.request.v1.Network
import java.io.File
import java.io.IOException
import java.time.DateTimeException
import java.time.Instant
import java.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * What this phone holds about a publisher's proposals (SEE-89,
 * docs/wiki/shared-proposals.md#where-it-is-kept): one JSON file per proposal, `<dir>/<connection
 * ID>/<proposal ID>.json`, written atomically.
 *
 * A proposal ID is the publisher's, so each feed has its own directory and removing the feed
 * removes its proposals whole — the documented retention: the proposals go with the feed, and the
 * owner's own Activity records of what they executed outlive both (SAW-023).
 *
 * Every file holds both halves: the publisher's document as this phone validated it, and what this
 * device decided about it. Neither is written without the other, so a restart can never produce a
 * decision about a proposal the phone no longer has, or a proposal whose decision was lost.
 *
 * Nothing in here is a secret. A proposal is a broadcast anyone subscribed can read, and this
 * device's half is its own record of public facts: an address, base-unit quantities, and a
 * signature that is public the moment the wallet makes it. The wallet's authorization token is not
 * here and never was (SEE-84).
 */
class ProposalStore(private val dir: File) {
    /** Every readable record for one feed. A damaged file is skipped. */
    fun listFor(connectionId: String): List<ProposalRecord> =
        connectionDir(connectionId)
            .listFiles { file -> file.name.endsWith(SUFFIX) }
            .orEmpty()
            .mapNotNull { read(connectionId, it.name.removeSuffix(SUFFIX)) }
            .sortedWith(compareBy({ it.proposal.createdAt }, { it.key.proposalId }))

    fun get(connectionId: String, proposalId: String): ProposalRecord? =
        read(connectionId, proposalId)

    /** Writes [record] whole, replacing its previous version. */
    fun put(record: ProposalRecord) {
        val file = atomicFile(record.connectionId, record.key.proposalId)
        connectionDir(record.connectionId).mkdirs()
        val stream = file.startWrite()
        try {
            stream.write(encode(record).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: IOException) {
            file.failWrite(stream)
            throw e
        }
    }

    fun delete(connectionId: String, proposalId: String) {
        atomicFile(connectionId, proposalId).delete()
    }

    fun deleteConnection(connectionId: String) {
        connectionDir(connectionId).deleteRecursively()
    }

    /** The feeds that have proposals stored. */
    fun connectionIds(): Set<String> =
        dir.listFiles { file -> file.isDirectory && isConnectionId(file.name) }
            .orEmpty()
            .map { it.name }
            .toSet()

    private fun read(connectionId: String, proposalId: String): ProposalRecord? {
        if (!isConnectionId(connectionId) || !isConnectionId(proposalId)) return null
        return try {
            decode(String(atomicFile(connectionId, proposalId).readFully(), Charsets.UTF_8))
                ?.takeIf {
                    it.connectionId == connectionId && it.key.proposalId == proposalId
                }
        } catch (e: IOException) {
            null
        } catch (e: JSONException) {
            null
        } catch (e: IllegalArgumentException) {
            // An unknown enum value, bad base64, a channel the publisher doesn't own, or a name
            // that isn't an operation, a plugin, or a parameter: every rule the model holds itself
            // to is applied again to what came off the disk, because a file is not a promise.
            null
        } catch (e: DateTimeException) {
            null
        }
    }

    // Both IDs name files, so each must be a UUID: the connection's, and the publisher's own for
    // the proposal. Never a path.
    private fun connectionDir(connectionId: String): File {
        require(isConnectionId(connectionId)) { "not a connection ID" }
        return File(dir, connectionId)
    }

    private fun atomicFile(connectionId: String, proposalId: String): AtomicFile {
        require(isConnectionId(proposalId)) { "not a proposal ID" }
        return AtomicFile(File(connectionDir(connectionId), "$proposalId$SUFFIX"))
    }

    private companion object {
        const val SUFFIX = ".json"
        // Version 2 added the environment an execution was bound in, and the simulated outcome
        // (SEE-97). A version 1 record is read as what it is: every execution then was a real one.
        const val VERSION = 2
        const val OLDEST_VERSION = 1

        fun encode(record: ProposalRecord): String =
            JSONObject()
                .put("version", VERSION)
                .put("connectionId", record.connectionId)
                .put("proposal", encodeProposal(record.proposal))
                .putOpt("dismissed", record.dismissed?.let(::encodeDismissal))
                .putOpt("review", record.review?.let(::encodeReview))
                .putOpt("execution", record.execution?.let(::encodeExecution))
                // Which rule a delivery broke, when the publisher contradicted itself about the
                // terms. It is kept so a restart does not start acting on a proposal this phone
                // had stopped acting on (ProposalRecord.refused).
                .putOpt("refused", record.refused?.name)
                .toString()

        fun encodeProposal(proposal: Proposal): JSONObject =
            JSONObject()
                .put("serverId", proposal.key.serverId)
                .put("channel", proposal.key.channel)
                .put("proposalId", proposal.key.proposalId)
                .put("revision", proposal.revision)
                .put("operation", proposal.operation.value)
                .put("plugin", proposal.plugin.value)
                .put("status", proposal.status.name)
                .put("createdAt", proposal.createdAt.toString())
                .put("updatedAt", proposal.updatedAt.toString())
                .put("expiresAt", proposal.expiresAt.toString())
                .put("note", proposal.note)
                .put(
                    "values",
                    JSONArray().apply {
                        proposal.values.forEach {
                            put(JSONObject().put("key", it.key).put("text", it.text))
                        }
                    },
                )

        fun decodeProposal(json: JSONObject): Proposal =
            Proposal(
                key =
                    ProposalKey(
                        serverId = json.getString("serverId"),
                        channel = json.getString("channel"),
                        proposalId = json.getString("proposalId"),
                    ),
                revision = json.getLong("revision"),
                operation = OperationId(json.getString("operation")),
                plugin = PluginId(json.getString("plugin")),
                status = ProposalStatus.valueOf(json.getString("status")),
                createdAt = Instant.parse(json.getString("createdAt")),
                updatedAt = Instant.parse(json.getString("updatedAt")),
                expiresAt = Instant.parse(json.getString("expiresAt")),
                note = json.optString("note"),
                values =
                    json.optJSONArray("values").let { array ->
                        (0 until (array?.length() ?: 0)).map {
                            val value = checkNotNull(array).getJSONObject(it)
                            ProposalValue(value.getString("key"), value.getString("text"))
                        }
                    },
            )

        fun encodeDismissal(dismissal: ProposalDismissal): JSONObject =
            JSONObject().put("revision", dismissal.revision).put("at", dismissal.at.toString())

        fun decodeDismissal(json: JSONObject?): ProposalDismissal? = json?.let {
            ProposalDismissal(it.getLong("revision"), Instant.parse(it.getString("at")))
        }

        fun encodeReview(review: ProposalReview): JSONObject =
            JSONObject()
                .put("revision", review.revision)
                .put("at", review.at.toString())
                .put("choice", encodeChoice(review.choice))

        fun decodeReview(json: JSONObject?): ProposalReview? = json?.let {
            ProposalReview(
                revision = it.getLong("revision"),
                choice = decodeChoice(it.getJSONObject("choice")),
                at = Instant.parse(it.getString("at")),
            )
        }

        /**
         * The owner's own parameters. They are written here and nowhere else: no request carries
         * them, and no publisher or gateway is told them (SEE-89).
         */
        fun encodeChoice(choice: ParameterChoice): JSONObject =
            JSONObject().apply {
                choice.values.forEach { (key, value) -> put(key.value, encodeValue(value)) }
            }

        fun decodeChoice(json: JSONObject): ParameterChoice =
            ParameterChoice(
                json.keys().asSequence().associate { key ->
                    ParameterKey(key) to decodeValue(json.getJSONObject(key))
                }
            )

        fun encodeValue(value: ParameterValue): JSONObject =
            when (value) {
                // Base units as a decimal string, as the protocol writes every quantity: the
                // largest a transaction can carry doesn't fit in a signed JSON number.
                is ParameterValue.Amount ->
                    JSONObject().put("kind", "amount").put("baseUnits", value.baseUnits.toString())
                is ParameterValue.Selected ->
                    JSONObject().put("kind", "selected").put("option", value.option.value)
                is ParameterValue.Count ->
                    JSONObject().put("kind", "count").put("value", value.value.toString())
            }

        fun decodeValue(json: JSONObject): ParameterValue =
            when (val kind = json.getString("kind")) {
                "amount" -> ParameterValue.Amount(json.getString("baseUnits").toULong())
                "selected" -> ParameterValue.Selected(ParameterKey(json.getString("option")))
                "count" -> ParameterValue.Count(json.getString("value").toUInt())
                else -> throw IllegalArgumentException("not a parameter value: $kind")
            }

        fun encodeExecution(execution: ProposalExecution): JSONObject =
            JSONObject()
                .put("binding", encodeBinding(execution.binding))
                .put("startedAt", execution.startedAt.toString())
                .put("outcome", encodeOutcome(execution.outcome))
                .putOpt("settledAt", execution.settledAt?.toString())

        fun decodeExecution(json: JSONObject?): ProposalExecution? = json?.let {
            ProposalExecution(
                binding = decodeBinding(it.getJSONObject("binding")),
                startedAt = Instant.parse(it.getString("startedAt")),
                outcome = decodeOutcome(it.getJSONObject("outcome")),
                settledAt =
                    it.optString("settledAt").takeIf(String::isNotEmpty)?.let(Instant::parse),
            )
        }

        /**
         * Exactly what was bound before the wallet was opened. The content hash is kept because it
         * is the evidence of what was signed: the bytes themselves belong to the plugin that
         * prepared them, and the wallet was handed those and no others.
         */
        fun encodeBinding(binding: ExecutionBinding): JSONObject =
            JSONObject()
                .put("revision", binding.revision)
                .put("environment", binding.environment.code)
                .put("choice", encodeChoice(binding.choice))
                .put("wallet", binding.wallet)
                .put("network", binding.network.name)
                .put("plugin", binding.plugin.value)
                .put("contract", binding.contract)
                .put("preparedVersion", binding.preparedVersion)
                .put(
                    "contentHash",
                    Base64.getEncoder().encodeToString(binding.contentHash.toByteArray()),
                )
                .putOpt("expiresAt", binding.expiresAtEpochSeconds)

        fun decodeBinding(json: JSONObject): ExecutionBinding =
            ExecutionBinding(
                revision = json.getLong("revision"),
                // A record written before the environment was part of a binding is a production
                // one: that is what every execution was then, and a simulated one could not exist
                // (SEE-97). An unreadable value is not resolved into either, because the two are
                // the whole difference between a rehearsal and money.
                environment =
                    if (!json.has("environment")) PluginEnvironment.Production
                    else
                        PluginEnvironment.entries.firstOrNull {
                            it.code == json.getString("environment")
                        } ?: throw IllegalArgumentException("not an environment"),
                choice = decodeChoice(json.getJSONObject("choice")),
                wallet = json.getString("wallet"),
                network = Network.valueOf(json.getString("network")),
                plugin = PluginId(json.getString("plugin")),
                contract = json.getInt("contract"),
                preparedVersion = json.getInt("preparedVersion"),
                contentHash =
                    ByteString.copyFrom(Base64.getDecoder().decode(json.getString("contentHash"))),
                expiresAtEpochSeconds =
                    if (json.has("expiresAt")) json.getLong("expiresAt") else null,
            )

        fun encodeOutcome(outcome: ProposalOutcome): JSONObject =
            when (outcome) {
                ProposalOutcome.Pending -> JSONObject().put("outcome", "Pending")
                // The transaction's ID on chain, which is public the moment it is sent.
                is ProposalOutcome.Submitted ->
                    JSONObject()
                        .put("outcome", "Submitted")
                        .put(
                            "signature",
                            Base64.getEncoder().encodeToString(outcome.signature.toByteArray()),
                        )
                ProposalOutcome.Declined -> JSONObject().put("outcome", "Declined")
                // No signature and no detail, because there is nothing to say: the operation was
                // rehearsed and the record's own environment says under which promise.
                ProposalOutcome.Simulated -> JSONObject().put("outcome", "Simulated")
                is ProposalOutcome.Failed ->
                    JSONObject().put("outcome", "Failed").put("detail", outcome.detail)
                is ProposalOutcome.Unresolved ->
                    JSONObject().put("outcome", "Unresolved").put("detail", outcome.detail)
            }

        fun decodeOutcome(json: JSONObject): ProposalOutcome =
            when (val outcome = json.getString("outcome")) {
                "Pending" -> ProposalOutcome.Pending
                "Submitted" ->
                    ProposalOutcome.Submitted(
                        ByteString.copyFrom(Base64.getDecoder().decode(json.getString("signature")))
                    )
                "Declined" -> ProposalOutcome.Declined
                "Simulated" -> ProposalOutcome.Simulated
                "Failed" -> ProposalOutcome.Failed(json.getString("detail"))
                "Unresolved" -> ProposalOutcome.Unresolved(json.getString("detail"))
                else -> throw IllegalArgumentException("not an outcome: $outcome")
            }

        fun decode(text: String): ProposalRecord? {
            val json = JSONObject(text)
            if (json.getInt("version") !in OLDEST_VERSION..VERSION) return null
            return ProposalRecord(
                connectionId = json.getString("connectionId"),
                proposal = decodeProposal(json.getJSONObject("proposal")),
                dismissed = decodeDismissal(json.optJSONObject("dismissed")),
                review = decodeReview(json.optJSONObject("review")),
                execution = decodeExecution(json.optJSONObject("execution")),
                refused =
                    json
                        .optString("refused")
                        .takeIf(String::isNotEmpty)
                        ?.let(ProposalProblem::valueOf),
            )
        }
    }
}
