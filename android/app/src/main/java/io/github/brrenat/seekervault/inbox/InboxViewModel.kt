package io.github.brrenat.seekervault.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.activity.ActivityLog
import io.github.brrenat.seekervault.activity.ReviewedDailyCheck
import io.github.brrenat.seekervault.activity.ReviewedPolicy
import io.github.brrenat.seekervault.activity.ReviewedRuleSource
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
import io.github.brrenat.seekervault.policy.EffectivePolicy
import io.github.brrenat.seekervault.policy.PolicyCheck
import io.github.brrenat.seekervault.policy.PolicyDecision
import io.github.brrenat.seekervault.policy.PolicyEvaluator
import io.github.brrenat.seekervault.policy.RequestFacts
import io.github.brrenat.seekervault.policy.policyFacts
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.request.v1.PreparedTransaction
import io.github.brrenat.seekervault.transactions.TransferInspection
import io.github.brrenat.seekervault.transactions.inspectTransfer
import io.github.brrenat.seekervault.transactions.transfer
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.SendResult
import io.github.brrenat.seekervault.wallet.SignResult
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
}

/**
 * A transfer's prepared transaction, as far as this phone has got with it. Nothing here is stored:
 * a preparation is only good while its blockhash is, so it lives for as long as the screen does and
 * is fetched again next time (docs/security.md#inspecting-a-transfer).
 */
sealed interface Preparation {
    /** The sidecar is building one. */
    data object Running : Preparation

    /** One arrived, and the phone read it. [inspection] is what the bytes say, not the sidecar. */
    data class Ready(
        val prepared: PreparedTransaction,
        val inspection: TransferInspection,
        /** The wallet it was checked against; null when none was connected. */
        val wallet: SelectedWallet?,
    ) : Preparation

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
    /** The wallet that would sign an approved message; null when none is connected. */
    val wallet: SelectedWallet? = null,
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
)

enum class NotificationOpenStatus {
    Loading,
    Current,
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
        combine(repository.connections, repository.inbox, wallet.wallet, activity) {
                connections,
                inbox,
                selected,
                now ->
                InboxUiState(
                    connections,
                    inbox,
                    selected,
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
                    wallet.wallet.value,
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
                    repository.inbox.value.result(key) != null ||
                        repository.inbox.value.pendingRequest(key) != null ->
                        NotificationOpenStatus.Current
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
     */
    private fun factsFor(key: RequestKey): RequestFacts? {
        val inbox = repository.inbox.value
        val request = inbox.pendingRequest(key) ?: inbox.result(key)?.request ?: return null
        val prepared = activity.value.preparations[key] as? Preparation.Ready
        return policyFacts(
            connectionId = key.connectionId,
            request = request,
            network = wallet.wallet.value?.network?.network ?: Network.NETWORK_UNSPECIFIED,
            inspection = prepared?.inspection,
        )
    }

    private fun preparedFor(key: RequestKey): PreparedTransaction? =
        (activity.value.preparations[key] as? Preparation.Ready)?.prepared

    /**
     * Keeps the assessment the owner was shown beside the record of what they did (SAW-028). Codes
     * and nothing else: the rules stay in the one place they are stored, and neither they nor this
     * ever goes near the sidecar.
     */
    private fun note(key: RequestKey, assessment: RequestAssessment?, wentAhead: Boolean) {
        val decision = assessment?.decision ?: return
        history.reviewed(
            key,
            ReviewedPolicy(
                assessment = decision.assessment.code,
                reasons = decision.reasonCodes,
                notChecked = decision.notChecked.map { it.code },
                assessedAt = assessment.at,
                approvedAnyway = wentAhead && decision.warns,
                ruleSources =
                    decision.checks
                        .filterNot {
                            decision.dailyChecks.isNotEmpty() && it.check == PolicyCheck.DailyLimit
                        }
                        .map { ReviewedRuleSource(it.check.code, it.source.code) },
                dailyChecks =
                    decision.dailyChecks.map {
                        ReviewedDailyCheck(
                            scope = it.scope.code,
                            source = it.result.source.code,
                            status = it.result.status.code,
                            reason = it.result.reason?.code,
                        )
                    },
                unreadableSources = decision.unreadableSources.map { it.code },
            ),
        )
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
        val selected = wallet.wallet.value
        val problem =
            when {
                selected == null -> SigningProblem.NoWallet
                reviewed == null ||
                    selected.address != reviewed.address ||
                    selected.network != reviewed.network -> SigningProblem.Changed
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
                    withTimeoutOrNull(walletTimeout.toMillis()) { wallet.sign(bytes, selected) }
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
        if (request.transfer() == null) return
        activity.update { it.copy(preparations = it.preparations + (key to Preparation.Running)) }
        viewModelScope.launch {
            val outcome =
                try {
                    val prepared = repository.prepare(key)
                    val selected = wallet.wallet.value
                    Preparation.Ready(
                        prepared,
                        inspectTransfer(request, prepared, selected),
                        selected,
                    )
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
    fun approveTransfer(key: RequestKey, reviewed: Preparation.Ready?) {
        val inbox = repository.inbox.value
        if (key in activity.value.sending || inbox.result(key) != null) return
        val request = inbox.pendingRequest(key) ?: return
        val transfer = request.transfer() ?: return
        val held = activity.value.preparations[key]
        // What they reviewed must be what this phone holds now: a version read again while they
        // were reading is a different transaction, and has to be reviewed on its own.
        if (
            reviewed == null ||
                held !is Preparation.Ready ||
                held.prepared != reviewed.prepared ||
                !reviewed.inspection.approvable
        ) {
            return problem(key, SigningProblem.NotVerified)
        }
        val selected = wallet.wallet.value
        val mismatch =
            when {
                selected == null -> SigningProblem.NoWallet
                reviewed.wallet == null ||
                    selected.address != reviewed.wallet.address ||
                    selected.network != reviewed.wallet.network -> SigningProblem.Changed
                transfer.wallet != selected.address -> SigningProblem.OtherWallet
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
                    val fresh = cleared(key) ?: return@withWallet
                    note(key, fresh, wentAhead = true)
                    if (!stillFresh(reviewed.prepared)) {
                        // Nothing has been approved anywhere yet, so the request is still the
                        // sidecar's and still PENDING: the owner reviews a new preparation.
                        problem(key, SigningProblem.Stale)
                        prepare(key, force = true)
                        return@withWallet
                    }
                    when (val outcome = repository.approveTransfer(key, approved)) {
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
                                    session.signAndSend(approved.transaction, selected)
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
