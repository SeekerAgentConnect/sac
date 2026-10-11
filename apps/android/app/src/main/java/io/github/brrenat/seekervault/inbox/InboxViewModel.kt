package io.github.brrenat.seekervault.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.activity.ActivityLog
import io.github.brrenat.seekervault.connections.Answer
import io.github.brrenat.seekervault.connections.ApprovalOutcome
import io.github.brrenat.seekervault.connections.ApprovedTransaction
import io.github.brrenat.seekervault.connections.CheckOutcome
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.ConnectionRepository
import io.github.brrenat.seekervault.connections.Delivery
import io.github.brrenat.seekervault.connections.GatewayException
import io.github.brrenat.seekervault.connections.Inbox
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.connections.SigningOutcome
import io.github.brrenat.seekervault.connections.messageBytes
import io.github.brrenat.seekervault.connections.resultDetail
import io.github.brrenat.seekervault.connections.signMessage
import io.github.brrenat.seekervault.connections.toOutcome
import io.github.brrenat.seekervault.plugins.ActionOwner
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.ProviderRegistry
import io.github.brrenat.seekervault.plugins.SWAP_SCHEMA_VERSION
import io.github.brrenat.seekervault.plugins.actionFacts
import io.github.brrenat.seekervault.plugins.actionOwner
import io.github.brrenat.seekervault.policy.EffectivePolicy
import io.github.brrenat.seekervault.policy.PolicyDecision
import io.github.brrenat.seekervault.policy.PolicyEvaluator
import io.github.brrenat.seekervault.policy.RequestFacts
import io.github.brrenat.seekervault.policy.policyFacts
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.request.v1.PreparedTransaction
import io.github.brrenat.seekervault.servers.ServerRecord
import io.github.brrenat.seekervault.servers.ServerSupport
import io.github.brrenat.seekervault.servers.executable
import io.github.brrenat.seekervault.servers.serverSupport
import io.github.brrenat.seekervault.skr.StakingInspection
import io.github.brrenat.seekervault.skr.inspectStaking
import io.github.brrenat.seekervault.skr.readSkrPosition
import io.github.brrenat.seekervault.skr.staking
import io.github.brrenat.seekervault.solana.NetworkAccounts
import io.github.brrenat.seekervault.transactions.TransferInspection
import io.github.brrenat.seekervault.transactions.inspectTransfer
import io.github.brrenat.seekervault.transactions.transfer
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.SendResult
import io.github.brrenat.seekervault.wallet.SignResult
import io.github.brrenat.seekervault.wallet.WalletReadiness
import io.github.brrenat.seekervault.wallet.WalletRepository
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Why the app didn't ask the wallet to sign. Nothing was approved and nothing was sent. */
enum class SigningProblem {
    /** No wallet is connected on this phone. */
    NoWallet,
    /** The request names a wallet other than the one connected. */
    OtherWallet,
    /** The connected wallet isn't the one on screen any more: the request needs another review. */
    Changed,
    /**
     * The transaction on screen isn't the one this phone holds any more, or this phone couldn't
     * account for all of it. Nothing that failed its own inspection is put to a wallet: that is
     * input validation, not a policy verdict (SAW-020).
     */
    NotVerified,
    /**
     * The sidecar refused the approval because a newer preparation exists, or because this one can
     * no longer land. It has been read again, and the new version needs its own review.
     */
    Stale,
    /**
     * The approval never reached the sidecar, so nothing was approved and no wallet was opened. The
     * transfer can be approved again once the server answers.
     */
    NotApproved,
    /**
     * The owner's approval of a message is stored on this phone, and the sidecar hasn't taken it
     * yet, so no wallet was opened: it is asked only for an approval the sidecar has accepted,
     * which is the point where the request is PROCESSING there. The approval is sent again by
     * itself, and what became of it is shown under the request.
     */
    NotSentYet,
    /**
     * The status couldn't be checked: the server didn't answer, or it couldn't read the chain. It
     * says nothing about the transaction, which stands exactly as it did (SAW-022).
     */
    NotChecked,
    /**
     * The rules, or what this app has recorded, changed while the request was on screen, so the
     * assessment the owner read isn't the one that stands now. Nothing was answered and no wallet
     * was opened: the review on screen has been replaced with the current one, to be read again
     * (SAW-028).
     */
    RulesChanged,
    /**
     * The assessment warns about something and the owner hasn't said they want to go ahead anyway.
     * A warning is theirs to overrule, and overruling it is a thing they do on purpose.
     */
    NotAcknowledged,
    /**
     * This build doesn't support what the request's server needs (SEE-88): a client plugin it
     * doesn't carry, one at a contract version it doesn't call, a protocol version it doesn't
     * speak, or a manifest it refused. The request can be read and rejected; nothing about it is
     * approved, nothing is prepared, and no wallet is opened. It is not a warning the owner can
     * overrule — there is nothing on this phone that would carry the operation out
     * (docs/wiki/server-manifests.md#viewing-without-executing).
     */
    ServerUnsupported,
}

/**
 * A transfer's prepared transaction, as far as this phone has got with it. Nothing here is stored:
 * a preparation is only good while its blockhash is, so it lives for as long as the screen does and
 * is fetched again next time (docs/security.md#inspecting-a-transfer).
 */
sealed interface Preparation {
    /** The sidecar is building one. */
    data object Running : Preparation

    /**
     * One arrived, and the phone read it. What is here is what the bytes say, never the sidecar.
     *
     * Exactly one of [inspection] and [staking] is present, because a request is one action and an
     * action is read by the code that knows its program. Keeping them as separate fields rather
     * than one interface keeps each review screen reading the facts it actually understands.
     */
    data class Ready(
        val prepared: PreparedTransaction,
        val inspection: TransferInspection?,
        /** The wallet it was checked against; null when none was connected. */
        val wallet: SelectedWallet?,
        val staking: StakingInspection? = null,
    ) : Preparation {
        /** Whether this may be put in front of the owner to approve. */
        val approvable: Boolean
            get() = inspection?.approvable == true || staking?.approvable == true

        /**
         * The transfer reading, for the screen that only ever shows a transfer.
         *
         * It throws rather than being nullable because which review a request gets is decided by
         * its kind: a transfer review holding no transfer reading is a routing mistake, and a null
         * here would draw an empty review instead of saying so.
         */
        val transferReading: TransferInspection
            get() = checkNotNull(inspection) { "a transfer review needs a transfer inspection" }
    }

    /** The sidecar couldn't be asked, or wouldn't build one. */
    data class Failed(val outcome: CheckOutcome, val detail: String? = null) : Preparation
}

/**
 * What this phone's rules made of one request, as the owner was shown it (SAW-028).
 *
 * It is a reading and not a decision. Nothing is kept to act on later: every assessment is made
 * afresh from the rules and the records as they stand, and the one held here exists so that what is
 * on screen can be compared with what is true at the moment the owner answers.
 */
data class RequestAssessment(
    val decision: PolicyDecision,
    /** What the phone established about the request, which is all the rules were applied to. */
    val facts: RequestFacts,
    /** When it was made. */
    val at: Instant,
    /** The effective rules applied to this request, held only to invalidate stale consent. */
    val applicablePolicy: EffectivePolicy? = null,
    /** The exact prepared transaction this assessment was about; null for non-transfers. */
    val preparation: PreparedTransaction? = null,
) {
    /** What going ahead anyway would be consent to: the reasons, and the thing they are about. */
    val consent: Consent
        get() = Consent(decision, facts, applicablePolicy, preparation)
}

/**
 * What an acknowledgement is given for (SAW-028).
 *
 * The reasons the owner read, and the preparation they read them about. Both, because either one
 * changing makes it a different thing to have consented to — and the two do not always change
 * together: a transaction prepared again can carry another blockhash, another priority fee, or
 * another version while what the rules make of it is word for word the same, and a rule the owner
 * edits can leave this request's every check exactly as it was. What the owner said yes to is this
 * assessment of this preparation.
 *
 * The moment it was made is deliberately not part of it. The same reasons about the same bytes,
 * read again a second later, are the same reasons.
 */
data class Consent(
    val decision: PolicyDecision,
    val facts: RequestFacts,
    /** Rules stay in memory on the phone and are never copied into Activity or a payload. */
    val applicablePolicy: EffectivePolicy?,
    /** Exact bytes, version, and hash; another preparation is another thing to consent to. */
    val preparation: PreparedTransaction?,
)

/** Everything the inbox screens show. */
data class InboxUiState(
    val connections: List<Connection> = emptyList(),
    val inbox: Inbox = Inbox(),
    /**
     * Where each connection stands with its own wallet profile (SEE-174). A request is signed only
     * with its own connection's profile, and only when that is [WalletReadiness.Ready].
     */
    val wallets: Map<String, WalletReadiness> = emptyMap(),
    /** A refresh started from the inbox is running. */
    val refreshing: Boolean = false,
    /** Answers being sent now: their buttons stay disabled until the send ends. */
    val sending: Set<RequestKey> = emptySet(),
    /** Why the last approval didn't reach the wallet, and which request it was about. */
    val problem: SigningProblem? = null,
    val problemKey: RequestKey? = null,
    /** Each transfer the owner has opened, and what this phone made of its transaction. */
    val preparations: Map<RequestKey, Preparation> = emptyMap(),
    /** Transfers whose status is being checked on chain now (SAW-022). */
    val checking: Set<RequestKey> = emptySet(),
    /** What the rules make of each request the owner has opened (SAW-028). */
    val assessments: Map<RequestKey, RequestAssessment> = emptyMap(),
    /**
     * The assessment the owner said they want to go ahead past, per request. It is the [Consent]
     * itself and not a flag, because consent is to the reasons that were on screen and to the
     * preparation they were about: a different assessment, or a transaction read again, is a
     * different thing to consent to, and this stops matching it.
     */
    val acknowledged: Map<RequestKey, Consent> = emptyMap(),
    /** A notification tap remains read-only until its paired sidecar has answered a fresh fetch. */
    val notificationOpen: NotificationOpen? = null,
) {
    /**
     * The wallet that would sign for [connectionId]: its own profile when it is ready, and null
     * otherwise — never another connection's.
     */
    fun walletFor(connectionId: String?): SelectedWallet? =
        (connectionId?.let(wallets::get) as? WalletReadiness.Ready)?.profile?.selected()
}

enum class NotificationOpenStatus {
    Loading,
    Current,
    /** Answered on this phone and closed since: it opens as a History record (SEE-161). */
    Closed,
    Gone,
    Removed,
    Revoked,
    Unavailable,
}

data class NotificationOpen(val key: RequestKey, val status: NotificationOpenStatus)

/**
 * State and actions of Pending requests and Request details. It fetches only when asked, and it
 * sends only the owner's own answers: loading the inbox answers nothing.
 */
class InboxViewModel(
    private val repository: ConnectionRepository,
    private val wallet: WalletRepository,
    /**
     * The owner's rules, applied to the request in front of them (SAW-026). Every call re-reads
     * both policy documents; [assess] reloads Activity first. There is no stored verdict here, and
     * nothing acts on the one it returns.
     */
    private val policies: PolicyEvaluator,
    /**
     * The owner's own history, where the assessment they read is kept beside their answer
     * (SAW-028). SAW-046 also reloads it to derive fresh counters; stored policy snapshots are
     * never read back as rules.
     */
    private val history: ActivityLog,
    /**
     * The bundled client plugins this build carries (SEE-86). It is asked about the operations core
     * doesn't carry out itself, and about nothing else: an acknowledgement, a message and a
     * transfer never reach it. Resolving is a lookup — it opens no wallet and sends nothing.
     */
    private val plugins: ProviderRegistry = ProviderRegistry.of(),
    /**
     * This phone's own Solana endpoints, used to read a staking position for itself (SEE-146) on
     * the network the request is bound to (SEE-184).
     *
     * Null when none is configured, and then a staking review says what it could not read rather
     * than taking the server's word for a share price. An unstake or a withdrawal is refused in
     * that state; a stake is still fully readable, because its amount and all of its accounts come
     * from the bytes and from this app's own derivations.
     */
    private val chain: NetworkAccounts? = null,
    /**
     * How long the app waits for the wallet before it gives up on an approval. It is the owner's
     * own time in the wallet app, so it is generous; a wallet that never answers at all must still
     * not hold a request open for the rest of the session.
     */
    private val walletTimeout: Duration = WALLET_TIMEOUT,
    /** The clock the blockhash window is judged against; tests move it. */
    private val now: () -> Instant = Instant::now,
    /** Where the rules are read from disk. Tests replace it, to run them in step. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private data class Activity(
        val refreshing: Boolean = false,
        val sending: Set<RequestKey> = emptySet(),
        val problem: SigningProblem? = null,
        val problemKey: RequestKey? = null,
        val preparations: Map<RequestKey, Preparation> = emptyMap(),
        val checking: Set<RequestKey> = emptySet(),
        val assessments: Map<RequestKey, RequestAssessment> = emptyMap(),
        val acknowledged: Map<RequestKey, Consent> = emptyMap(),
        val notificationOpen: NotificationOpen? = null,
    )

    private val activity = MutableStateFlow(Activity())

    /**
     * One complete history/rules read at a time, so an older assessment cannot replace a newer one.
     */
    private val assessmentLock = Mutex()

    val state: StateFlow<InboxUiState> =
        combine(
                repository.connections,
                repository.inbox,
                wallet.readinessByConnection(),
                activity,
            ) { connections, inbox, wallets, now ->
                InboxUiState(
                    connections,
                    inbox,
                    wallets,
                    now.refreshing,
                    now.sending,
                    now.problem,
                    now.problemKey,
                    now.preparations,
                    now.checking,
                    now.assessments,
                    now.acknowledged,
                    now.notificationOpen,
                )
            }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                InboxUiState(
                    repository.connections.value,
                    repository.inbox.value,
                    repository.connections.value.associate { it.id to wallet.readiness(it.id) },
                ),
            )

    /** Fetches one connection's requests, or every usable connection's at once. */
    fun refresh(connectionId: String? = null) {
        if (activity.value.refreshing) return
        activity.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            try {
                val ids =
                    connectionId?.let(::listOf)
                        ?: repository.connections.value.filter { it.usable }.map { it.id }
                coroutineScope { ids.forEach { launch { repository.refresh(it) } } }
            } finally {
                activity.update { it.copy(refreshing = false) }
            }
        }
    }

    /**
     * Resolves a notification's opaque IDs against phone storage and then the paired sidecar. The
     * fetch is the only automatic operation: it cannot choose or create an answer, prepare,
     * approve, or reach a wallet. As with every Sync, it may retry only a result the owner already
     * stored. Review controls remain off-screen until the result is known to be current.
     */
    fun openFromNotification(key: RequestKey) {
        activity.update {
            it.copy(notificationOpen = NotificationOpen(key, NotificationOpenStatus.Loading))
        }
        viewModelScope.launch {
            repository.load()
            val initial = repository.connections.value.firstOrNull { it.id == key.connectionId }
            if (initial == null) {
                updateNotificationOpen(key, NotificationOpenStatus.Removed)
                return@launch
            }
            if (!initial.usable) {
                updateNotificationOpen(key, NotificationOpenStatus.Revoked)
                return@launch
            }
            repository.refresh(key.connectionId)
            val connection = repository.connections.value.firstOrNull { it.id == key.connectionId }
            val status =
                when {
                    connection == null -> NotificationOpenStatus.Removed
                    !connection.usable -> NotificationOpenStatus.Revoked
                    connection.lastCheck?.outcome != CheckOutcome.Ok ->
                        NotificationOpenStatus.Unavailable
                    repository.inbox.value.pendingRequest(key) != null ->
                        NotificationOpenStatus.Current
                    // Answered here and no longer waiting: its History record, not a review.
                    repository.inbox.value.result(key) != null -> NotificationOpenStatus.Closed
                    else -> NotificationOpenStatus.Gone
                }
            updateNotificationOpen(key, status)
        }
    }

    private fun updateNotificationOpen(key: RequestKey, status: NotificationOpenStatus) {
        activity.update { current ->
            if (current.notificationOpen?.key == key) {
                current.copy(notificationOpen = NotificationOpen(key, status))
            } else {
                current
            }
        }
    }

    /**
     * The owner's answer to a pending request: an acknowledgement, or a refusal of any request. The
     * first tap sends it; later taps are ignored while it's sent, and after it's stored.
     */
    fun answer(key: RequestKey, answer: Answer) {
        val inbox = repository.inbox.value
        if (key in activity.value.sending || inbox.result(key) != null) return
        if (inbox.pendingRequest(key) == null) return
        // Rejecting always works: the owner must be able to clear a request whatever its server
        // is. An affirmative answer to a server this build doesn't support does not (SEE-88), and
        // it stops here as well as in the screen, so the rule holds wherever the call came from.
        if (answer != Answer.Reject && !support(key.connectionId).executable) {
            return problem(key, SigningProblem.ServerUnsupported)
        }
        activity.update { it.copy(sending = it.sending + key) }
        viewModelScope.launch {
            try {
                // Saying no is always the safe answer, and never asks the owner to go past a
                // warning. Saying yes does, and the rules are read again before it is sent.
                if (answer == Answer.Reject) {
                    note(key, assess(key), wentAhead = false)
                } else {
                    note(key, cleared(key) ?: return@launch, wentAhead = true)
                }
                repository.answer(key, answer)
            } finally {
                activity.update { it.copy(sending = it.sending - key) }
            }
        }
    }

    /**
     * What the owner's rules make of the request they are looking at (SAW-028). It runs when they
     * open it, again whenever a new preparation has been read, and again when the app comes back to
     * the front, so the review on screen is what the rules say now and not what they said when it
     * was opened.
     *
     * It reads the rules and this app's own records, and that is all it does: it opens no wallet,
     * sends nothing, answers nothing, and writes nothing down (docs/policy.md#re-evaluation).
     */
    fun review(key: RequestKey) {
        viewModelScope.launch { assess(key) }
    }

    /**
     * The owner says they have read the warnings and want to go ahead anyway, or takes it back.
     *
     * What is kept is the assessment itself, not a tick: consent is to the reasons that were on
     * screen and to the preparation they were about, so either changing afterwards leaves nothing
     * consented to.
     */
    fun acknowledge(key: RequestKey, accepted: Boolean) {
        val consent = activity.value.assessments[key]?.consent ?: return
        activity.update {
            it.copy(
                problem = if (it.problemKey == key) null else it.problem,
                problemKey = if (it.problemKey == key) null else it.problemKey,
                acknowledged =
                    if (accepted) it.acknowledged + (key to consent) else it.acknowledged - key,
            )
        }
    }

    /**
     * Assesses [key] against the rules and the records as they stand now, and puts the result on
     * screen. Null when the request isn't here any more.
     *
     * An acknowledgement given for another assessment is dropped here: the owner said they wanted
     * to go ahead past the reasons they were shown, and these are not those reasons.
     */
    private suspend fun assess(key: RequestKey): RequestAssessment? = assessmentLock.withLock {
        while (true) {
            val facts = factsFor(key) ?: return@withLock null
            val preparation = preparedFor(key)
            val evaluated =
                withContext(io) {
                    // A review uses a complete disk read, not the Activity rows this process last
                    // happened to see. Failure deliberately leaves the daily history unknown.
                    try {
                        history.load()
                    } catch (_: IOException) {
                        // ActivityLog has already marked this read unknown, so a configured daily
                        // threshold becomes unverified instead of seeing an empty day.
                    } catch (_: SecurityException) {
                        // Treat denied storage exactly like any other incomplete history read.
                    }
                    policies.evaluateCurrent(facts)
                }
            // Preparation can finish while Activity is being read. Never publish an assessment of
            // the old facts over the newer preparation; read everything again for the facts now.
            if (factsFor(key) != facts || preparedFor(key) != preparation) continue
            val assessment =
                RequestAssessment(
                    decision = evaluated.decision,
                    facts = facts,
                    at = now(),
                    applicablePolicy = evaluated.applicablePolicy,
                    preparation = preparation,
                )
            activity.update {
                val ticked = it.acknowledged[key]
                it.copy(
                    assessments = it.assessments + (key to assessment),
                    acknowledged =
                        if (ticked != null && ticked != assessment.consent) it.acknowledged - key
                        else it.acknowledged,
                )
            }
            return@withLock assessment
        }
        @Suppress("UNREACHABLE_CODE") null
    }

    /**
     * Reads the rules again, right now, and says whether an affirmative answer may go ahead. Null
     * means it may not, and why is on screen.
     *
     * Nothing here is taken on trust from the screen. The assessment is made afresh and compared
     * with the one the owner was actually shown: one that changed while they were reading is not
     * one they read, so it replaces what is on screen and the answer stops there. One that warns
     * needs their word for it, and their word was given for a particular set of reasons.
     *
     * This is advisory and it comes second. A preparation that failed this phone's own inspection
     * was refused before any of it ran, and is never relabelled as a rule the owner could overrule
     * (docs/security.md#verification-versus-advisory-rules).
     */
    private suspend fun cleared(key: RequestKey): RequestAssessment? {
        val shown = activity.value.assessments[key]?.consent
        val fresh = assess(key) ?: return null
        if (shown != null && shown != fresh.consent) {
            problem(key, SigningProblem.RulesChanged)
            return null
        }
        if (fresh.decision.warns && activity.value.acknowledged[key] != fresh.consent) {
            problem(key, SigningProblem.NotAcknowledged)
            return null
        }
        return fresh
    }

    /**
     * What the phone itself established about [key], which is all a policy is ever applied to. The
     * chain comes from the wallet the owner connected, and the rest from the structured request and
     * from the transaction's own bytes when one has been read.
     *
     * An action the app carries out itself is read the way it always has been. One a plugin would
     * carry out is read by that plugin, and an operation this build carries none for establishes
     * nothing — which is what a swap has always come to here, since nothing yet serves it (SEE-86,
     * docs/wiki/client-plugins.md).
     */
    private fun factsFor(key: RequestKey): RequestFacts? {
        val inbox = repository.inbox.value
        val request = inbox.pendingRequest(key) ?: inbox.result(key)?.request ?: return null
        val prepared = activity.value.preparations[key] as? Preparation.Ready
        val network =
            wallet.walletFor(key.connectionId)?.network?.network ?: Network.NETWORK_UNSPECIFIED
        val owner = actionOwner(request)
        if (owner is ActionOwner.Provider) {
            return actionFacts(
                connectionId = key.connectionId,
                request = request,
                network = network,
                // A private `ActionRequest` names no execution provider — the v1 action carries no
                // field for one — so nothing resolves for it and nothing is established. That is
                // the same answer this path has always given (this app has never prepared a swap
                // from a private request), reached explicitly rather than by picking whichever
                // provider happened to serve the action (SEE-145).
                resolution =
                    plugins.resolve(
                        provider = null,
                        action = owner.action,
                        schemaVersion = SWAP_SCHEMA_VERSION,
                        network = network,
                        environment = environmentOf(key.connectionId),
                    ),
            )
        }
        return policyFacts(
            connectionId = key.connectionId,
            request = request,
            network = network,
            inspection = prepared?.inspection,
            staking = prepared?.staking,
        )
    }

    /**
     * Whether this build supports the server [connectionId] belongs to (SEE-88).
     *
     * Derived on every read from the manifest the connection caches and the plugins compiled into
     * this build, so a build that carries more plugins than the last one supports more servers
     * without anything stored having to change. A connection this phone no longer has supports
     * nothing.
     */
    fun support(connectionId: String): ServerSupport =
        serverSupport(
            repository.connection(connectionId)?.server ?: ServerRecord.Unknown,
            plugins,
            environmentOf(connectionId),
        )

    /**
     * Which promise this connection keeps (SEE-97). For everything this screen is about it is
     * always [PluginEnvironment.Production], and the reason is a rule rather than a default: a
     * direct connection cannot be anything else, because the agent that asked for a signature is
     * waiting for one and cannot be handed a rehearsal (`Connection.environment`).
     *
     * It is read rather than assumed all the same, so that the one place this app decides what an
     * approval means is the connection it is for.
     */
    private fun environmentOf(connectionId: String): PluginEnvironment =
        repository.connection(connectionId)?.environment ?: PluginEnvironment.Production

    private fun preparedFor(key: RequestKey): PreparedTransaction? =
        (activity.value.preparations[key] as? Preparation.Ready)?.prepared

    /**
     * Keeps the assessment the owner was shown beside the record of what they did (SAW-028). Codes
     * and nothing else: the rules stay in the one place they are stored, and neither they nor this
     * ever goes near the sidecar.
     */
    private fun note(key: RequestKey, assessment: RequestAssessment?, wentAhead: Boolean) {
        val decision = assessment?.decision ?: return
        history.reviewed(key, reviewedPolicy(decision, assessment.at, wentAhead))
    }

    /**
     * The owner's approval of a message-signing request (docs/guides/message-signing.md).
     * [reviewed] is the wallet the screen showed them, so a wallet that changed in the meantime
     * stops the approval instead of signing for something they didn't see. The order is fixed: the
     * approval is stored and sent first, and only then is the wallet asked.
     */
    fun approve(key: RequestKey, reviewed: SelectedWallet?) {
        val inbox = repository.inbox.value
        if (key in activity.value.sending || inbox.result(key) != null) return
        val request = inbox.pendingRequest(key) ?: return
        val message = request.signMessage() ?: return
        // The request's own connection's wallet, and nothing else (SEE-174).
        val selected = wallet.walletFor(key.connectionId)
        val problem =
            when {
                // Before the wallet is even looked at: a server this build doesn't support has
                // nothing here that would carry its operations out, and an approval is not a
                // smaller version of one (SEE-88).
                !support(key.connectionId).executable -> SigningProblem.ServerUnsupported
                selected == null -> SigningProblem.NoWallet
                reviewed == null ||
                    selected.address != reviewed.address ||
                    selected.network != reviewed.network ||
                    selected.profileId != reviewed.profileId -> SigningProblem.Changed
                message.wallet != selected.address -> SigningProblem.OtherWallet
                else -> null
            }
        if (problem != null || selected == null) {
            activity.update { it.copy(problem = problem, problemKey = key) }
            return
        }
        activity.update {
            it.copy(sending = it.sending + key, problem = null, problemKey = null)
        }
        viewModelScope.launch {
            try {
                // The rules are read again here, and an assessment that changed while they were
                // reading stops the approval rather than being approved unseen.
                note(key, cleared(key) ?: return@launch, wentAhead = true)
                val stored = repository.answer(key, Answer.Approve)
                // Only a stored approval that is still on its way leads to the wallet: one the
                // sidecar refused, because the request had moved on, is finished.
                if (stored.delivery != Delivery.Waiting) return@launch
                // And only one the sidecar has taken. That is the point where the request is
                // PROCESSING there and this signature is the one thing it waits for
                // (docs/testing/wallet-lifecycle.md). An approval still sitting on this phone —
                // the server couldn't be reached, or never answered — may belong to a request that
                // has since been cancelled or expired, and the wallet is not opened for one of
                // those. It is sent again by itself, and an approval with no wallet answer settles
                // as unresolved, which tells the agent the request failed rather than leaving it
                // open for ever.
                if (!stored.approved) {
                    problem(key, SigningProblem.NotSentYet)
                    return@launch
                }
                val bytes = message.messageBytes()
                // A wallet that never answers leaves the request unresolved rather than open: the
                // signature, if there ever was one, reached nothing and no one.
                val signed =
                    withTimeoutOrNull(walletTimeout.toMillis()) {
                        wallet.sign(bytes, selected, key.connectionId)
                    }
                repository.recordSigning(
                    key,
                    signed?.let { outcomeOf(it, bytes) } ?: SigningOutcome.Unresolved(NO_ANSWER),
                )
            } finally {
                activity.update { it.copy(sending = it.sending - key) }
            }
        }
    }

    /**
     * Fetches a fresh transaction for a PENDING transfer and reads it here. It runs when the owner
     * opens the request, and again only when they ask: each call makes the sidecar build a new
     * version, and every version has to be reviewed on its own.
     *
     * The inspection is done against the transaction's own bytes. What the sidecar says it built is
     * not consulted, and the agent's note never is.
     */
    fun prepare(key: RequestKey, force: Boolean = false) {
        val existing = activity.value.preparations[key]
        if (existing == Preparation.Running) return
        if (!force && existing != null) return
        val request = repository.inbox.value.pendingRequest(key) ?: return
        val staking = request.staking()
        if (request.transfer() == null && staking == null) return
        // Preparing is the first step of executing, so it stops with everything else: a server
        // this build doesn't support gets no transaction built for it to sign (SEE-88).
        if (!support(key.connectionId).executable) {
            return problem(key, SigningProblem.ServerUnsupported)
        }
        activity.update { it.copy(preparations = it.preparations + (key to Preparation.Running)) }
        viewModelScope.launch {
            val outcome =
                try {
                    // The position is read before the preparation is asked for, so the reading is
                    // never newer than the bytes it is used to judge: a share price fetched after
                    // the server built the transaction could make an honest unstake look wrong.
                    val position =
                        if (staking == null) null
                        else chain?.let { readSkrPosition(it.on(staking.network), staking.wallet) }
                    val prepared = repository.prepare(key)
                    val selected = wallet.walletFor(key.connectionId)
                    if (staking == null) {
                        Preparation.Ready(
                            prepared,
                            inspectTransfer(request, prepared, selected),
                            selected,
                        )
                    } else {
                        Preparation.Ready(
                            prepared,
                            null,
                            selected,
                            inspectStaking(
                                request,
                                prepared,
                                selected,
                                position,
                                Instant.now().epochSecond,
                            ),
                        )
                    }
                } catch (e: GatewayException) {
                    Preparation.Failed(e.kind.toOutcome(), e.message)
                }
            activity.update { it.copy(preparations = it.preparations + (key to outcome)) }
            // A new preparation is a new set of facts, so the rules are applied to it again. An
            // assessment the owner had already agreed to go past no longer matches, and goes.
            assess(key)
        }
    }

    /**
     * The owner's approval of a transfer (docs/guides/transfers.md). [reviewed] is the preparation
     * the screen showed them, and everything is checked against it before anything happens: the
     * same preparation this phone still holds, one its own inspection passed, and the wallet they
     * saw. Then, in this order, the approval is stored, the sidecar accepts it, and only then is
     * the wallet asked — with the bytes from the stored approval, never with bytes fetched again.
     */
    fun approveTransaction(key: RequestKey, reviewed: Preparation.Ready?) {
        val inbox = repository.inbox.value
        if (key in activity.value.sending || inbox.result(key) != null) return
        val request = inbox.pendingRequest(key) ?: return
        // Whichever kind it is, the wallet it is bound to is the one thing this needs from it: the
        // rest was established by the inspection the owner read.
        val boundWallet = request.transfer()?.wallet ?: request.staking()?.wallet ?: return
        if (!support(key.connectionId).executable) {
            return problem(key, SigningProblem.ServerUnsupported)
        }
        val held = activity.value.preparations[key]
        // What they reviewed must be what this phone holds now: a version read again while they
        // were reading is a different transaction, and has to be reviewed on its own.
        if (
            reviewed == null ||
                held !is Preparation.Ready ||
                held.prepared != reviewed.prepared ||
                !reviewed.approvable
        ) {
            return problem(key, SigningProblem.NotVerified)
        }
        val selected = wallet.walletFor(key.connectionId)
        val mismatch =
            when {
                selected == null -> SigningProblem.NoWallet
                reviewed.wallet == null ||
                    selected.address != reviewed.wallet.address ||
                    selected.network != reviewed.wallet.network ||
                    selected.profileId != reviewed.wallet.profileId -> SigningProblem.Changed
                boundWallet != selected.address -> SigningProblem.OtherWallet
                else -> null
            }
        if (mismatch != null || selected == null) return problem(key, mismatch)
        val approved =
            ApprovedTransaction(
                version = reviewed.prepared.version,
                contentHash = reviewed.prepared.contentHash,
                transaction = reviewed.prepared.transaction,
            )
        activity.update {
            it.copy(sending = it.sending + key, problem = null, problemKey = null)
        }
        viewModelScope.launch {
            try {
                // The inspection above already refused anything this phone couldn't account for,
                // and that refusal is not a rule to overrule.
                // Everything from here runs holding the one wallet lock. That lock is where the
                // waiting happens — another wallet interaction can hold it for as long as the
                // owner is in the wallet app — so the freshness of what is being approved is
                // checked on this side of the wait, not before it.
                wallet.withWallet<Unit> { session ->
                    // The owner's rules and every required Activity record are read after the wait
                    // for the lock and immediately before any answer. A change elsewhere while the
                    // review was open therefore stops here, before the sidecar or wallet is asked.
                    // The connection's wallet is read again on this side of the wait too: a
                    // rebinding, a removed profile or an expired authorization while another
                    // wallet interaction held the lock stops here, before anything is approved
                    // (SEE-174). The wallet call below checks it once more.
                    val bound = wallet.walletFor(key.connectionId)
                    if (bound == null || bound != selected) {
                        problem(key, SigningProblem.Changed)
                        return@withWallet
                    }
                    val fresh = cleared(key) ?: return@withWallet
                    note(key, fresh, wentAhead = true)
                    if (!stillFresh(reviewed.prepared)) {
                        // Nothing has been approved anywhere yet, so the request is still the
                        // sidecar's and still PENDING: the owner reviews a new preparation.
                        problem(key, SigningProblem.Stale)
                        prepare(key, force = true)
                        return@withWallet
                    }
                    when (val outcome = repository.approveTransaction(key, approved)) {
                        is ApprovalOutcome.Accepted -> {
                            // The last thing before the wallet, with the lock still held: the
                            // commit itself took time, and a transaction that can no longer land
                            // is never put in front of the wallet.
                            if (!stillFresh(reviewed.prepared)) {
                                repository.recordSigning(
                                    key,
                                    SigningOutcome.Failed(EXPIRED_BEFORE_THE_WALLET),
                                )
                                problem(key, SigningProblem.Stale)
                                return@withWallet
                            }
                            // A wallet that never answers leaves the outcome unknown rather than
                            // open: it may have sent the transaction, and this phone must not say
                            // otherwise.
                            val sent =
                                withTimeoutOrNull(walletTimeout.toMillis()) {
                                    session.signAndSend(
                                        approved.transaction,
                                        selected,
                                        key.connectionId,
                                    )
                                }
                            repository.recordSigning(
                                key,
                                sent?.let(::outcomeOf)
                                    ?: SigningOutcome.Unresolved(NO_ANSWER_SENDING),
                            )
                        }
                        // Nothing was approved: read the transfer again so the owner reviews the
                        // preparation as it is now, rather than the one that has gone.
                        ApprovalOutcome.Stale -> {
                            problem(key, SigningProblem.Stale)
                            prepare(key, force = true)
                        }
                        is ApprovalOutcome.Superseded -> Unit // the inbox shows where it went
                        is ApprovalOutcome.Refused -> problem(key, SigningProblem.NotApproved)
                    }
                }
            } finally {
                activity.update { it.copy(sending = it.sending - key) }
            }
        }
    }

    /**
     * Whether [prepared] could still land if the wallet were asked now, with the margin the sidecar
     * applies when it accepts an approval. A blockhash window that has closed, or is about to,
     * means the wallet would be handed a transaction no block can include any more.
     *
     * A preparation with no expiry is taken as fresh: the sidecar states one for every transfer it
     * builds, and inventing a deadline for bytes that carry none would refuse them for a reason
     * this phone made up.
     */
    private fun stillFresh(prepared: PreparedTransaction): Boolean {
        if (!prepared.hasEstimatedExpiry()) return true
        val expiry =
            Instant.ofEpochSecond(
                prepared.estimatedExpiry.seconds,
                prepared.estimatedExpiry.nanos.toLong(),
            )
        return now().plus(APPROVAL_MARGIN).isBefore(expiry)
    }

    private fun problem(key: RequestKey, problem: SigningProblem?) = activity.update {
        it.copy(problem = problem, problemKey = key)
    }

    fun problemShown() = activity.update { it.copy(problem = null, problemKey = null) }

    /**
     * The app is in the foreground again, which includes coming back from the wallet app. Any
     * approval whose wallet answer this phone never received is settled as unresolved (SAW-017):
     * the app died in the wallet, or the wallet never answered. The signings still in flight here
     * are left alone, and nothing is ever sent to the wallet a second time.
     */
    fun onAppVisible() {
        viewModelScope.launch {
            // Resolve records first: an outcome learned while the app was away can change either
            // daily scope, so the foreground assessment must include it rather than race it.
            repository.resolveAbandonedSignings(activity.value.sending)
            // Including after a trip to the Rules screen or to the wallet: whatever is open is
            // assessed against the rules and Activity on disk now, not as they were when opened.
            activity.value.assessments.keys.forEach { assess(it) }
        }
    }

    /**
     * Asks the sidecar what became of a transfer the wallet sent (SAW-022). It reaches no wallet
     * and sends nothing: the sidecar looks the signature up on chain and answers with the request
     * as it stands. A check that can't be made leaves the transfer exactly as it was.
     */
    fun checkStatus(key: RequestKey) {
        if (key in activity.value.checking) return
        if (repository.inbox.value.result(key)?.awaitingChain != true) return
        activity.update { it.copy(checking = it.checking + key) }
        viewModelScope.launch {
            try {
                repository.checkStatus(key)
                problem(key, null)
            } catch (_: GatewayException) {
                problem(key, SigningProblem.NotChecked)
            } finally {
                activity.update { it.copy(checking = it.checking - key) }
            }
        }
    }

    /** Sends a waiting answer again now. */
    fun sendAgain(key: RequestKey) {
        if (key in activity.value.sending) return
        activity.update { it.copy(sending = it.sending + key) }
        viewModelScope.launch {
            try {
                repository.deliver(key)
            } finally {
                activity.update { it.copy(sending = it.sending - key) }
            }
        }
    }

    private companion object {
        val WALLET_TIMEOUT: Duration = Duration.ofMinutes(10)

        /**
         * How much of a prepared transaction's window must be left when the wallet is asked. It is
         * the sidecar's own margin (requests/lifecycle.ts APPROVAL_MARGIN_MS): the sidecar applies
         * it when it accepts the approval, and the phone applies it again at the wallet, because
         * the wait for the wallet lock happens in between.
         */
        val APPROVAL_MARGIN: Duration = Duration.ofSeconds(15)

        const val EXPIRED_BEFORE_THE_WALLET =
            "The transaction's blockhash window closed before the wallet could be asked, so " +
                "nothing was signed and nothing was sent."
        const val NO_ANSWER =
            "The wallet didn't answer, so nothing reached this phone and nothing was signed."
        // The same silence means something else for a transaction: the wallet may have sent it.
        const val NO_ANSWER_SENDING =
            "The wallet didn't answer, so this phone never learned whether the transaction was sent."

        /**
         * What the wallet said, as this phone records it. A signature is kept only if it is over
         * exactly the bytes that were sent: a wallet that signed anything else has signed nothing
         * this request asked for.
         */
        /**
         * What the wallet said about a transaction, as this phone records it. Only an outcome the
         * wallet stated is recorded as one: anything else is unresolved, because a transaction that
         * may have been sent must never be reported as one that wasn't.
         */
        fun outcomeOf(result: SendResult): SigningOutcome =
            when (result) {
                is SendResult.Sent -> SigningOutcome.Sent(result.signature)
                SendResult.Declined -> SigningOutcome.Declined
                SendResult.NoWallet ->
                    SigningOutcome.Failed("No wallet app answered on this phone.")
                SendResult.AuthorizationExpired ->
                    SigningOutcome.Failed(
                        "The wallet no longer accepts this phone's authorization, so nothing was sent."
                    )
                SendResult.NotConnected ->
                    SigningOutcome.Failed("No wallet is connected on the phone any more.")
                SendResult.Changed ->
                    SigningOutcome.Failed(
                        "The owner's wallet changed before it could sign, so nothing was sent."
                    )
                is SendResult.Failed ->
                    SigningOutcome.Failed(
                        listOfNotNull("The wallet could not send this", result.message)
                            .joinToString(": ")
                    )
                is SendResult.Unknown ->
                    SigningOutcome.Unresolved(
                        listOfNotNull(
                                "This phone can't tell whether the transaction was sent",
                                result.message,
                            )
                            .joinToString(": ")
                    )
            }

        fun outcomeOf(result: SignResult, asked: ByteString): SigningOutcome =
            when (result) {
                is SignResult.Signed ->
                    if (result.message == asked) SigningOutcome.Signed(result.signature)
                    else SigningOutcome.Failed("The wallet signed other bytes than the message.")
                SignResult.Declined -> SigningOutcome.Declined
                SignResult.NoWallet ->
                    SigningOutcome.Failed("No wallet app answered on this phone.")
                SignResult.AuthorizationExpired ->
                    SigningOutcome.Failed(
                        "The wallet no longer accepts this phone's authorization, so nothing was signed."
                    )
                SignResult.NotConnected ->
                    SigningOutcome.Failed("No wallet is connected on the phone any more.")
                SignResult.Changed ->
                    SigningOutcome.Failed(
                        "The owner's wallet changed before it could sign, so nothing was signed."
                    )
                is SignResult.Failed ->
                    // What the wallet said is its own text, of a length it decides: it is cut to
                    // what the protocol takes, so this answer can always be delivered.
                    SigningOutcome.Failed(
                        resultDetail(
                            listOfNotNull("The wallet could not sign", result.message)
                                .joinToString(": ")
                        )
                    )
            }
    }
}
