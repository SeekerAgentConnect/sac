package io.github.brrenat.seekervault.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.connections.Answer
import io.github.brrenat.seekervault.connections.CheckOutcome
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.ConnectionRepository
import io.github.brrenat.seekervault.connections.Delivery
import io.github.brrenat.seekervault.connections.GatewayException
import io.github.brrenat.seekervault.connections.Inbox
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.connections.SigningOutcome
import io.github.brrenat.seekervault.connections.messageBytes
import io.github.brrenat.seekervault.connections.signMessage
import io.github.brrenat.seekervault.connections.toOutcome
import io.github.brrenat.seekervault.request.v1.PreparedTransaction
import io.github.brrenat.seekervault.transactions.TransferInspection
import io.github.brrenat.seekervault.transactions.inspectTransfer
import io.github.brrenat.seekervault.transactions.transfer
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.SignResult
import io.github.brrenat.seekervault.wallet.WalletRepository
import java.time.Duration
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Why the app didn't ask the wallet to sign. Nothing was approved and nothing was sent. */
enum class SigningProblem {
    /** No wallet is connected on this phone. */
    NoWallet,
    /** The request names a wallet other than the one connected. */
    OtherWallet,
    /** The connected wallet isn't the one on screen any more: the request needs another review. */
    Changed,
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
)

/**
 * State and actions of Pending requests and Request details. It fetches only when asked, and it
 * sends only the owner's own answers: loading the inbox answers nothing.
 */
class InboxViewModel(
    private val repository: ConnectionRepository,
    private val wallet: WalletRepository,
    /**
     * How long the app waits for the wallet before it gives up on an approval. It is the owner's
     * own time in the wallet app, so it is generous; a wallet that never answers at all must still
     * not hold a request open for the rest of the session.
     */
    private val walletTimeout: Duration = WALLET_TIMEOUT,
) : ViewModel() {
    private data class Activity(
        val refreshing: Boolean = false,
        val sending: Set<RequestKey> = emptySet(),
        val problem: SigningProblem? = null,
        val problemKey: RequestKey? = null,
        val preparations: Map<RequestKey, Preparation> = emptyMap(),
    )

    private val activity = MutableStateFlow(Activity())

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
                repository.answer(key, answer)
            } finally {
                activity.update { it.copy(sending = it.sending - key) }
            }
        }
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
                val stored = repository.answer(key, Answer.Approve)
                // Only a stored approval that is still on its way leads to the wallet: one the
                // sidecar refused, because the request had moved on, is finished.
                if (stored.delivery != Delivery.Waiting) return@launch
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
        }
    }

    fun problemShown() = activity.update { it.copy(problem = null, problemKey = null) }

    /**
     * The app is in the foreground again, which includes coming back from the wallet app. Any
     * approval whose wallet answer this phone never received is settled as unresolved (SAW-017):
     * the app died in the wallet, or the wallet never answered. The signings still in flight here
     * are left alone, and nothing is ever sent to the wallet a second time.
     */
    fun onAppVisible() {
        viewModelScope.launch { repository.resolveAbandonedSignings(activity.value.sending) }
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
        const val NO_ANSWER =
            "The wallet didn't answer, so nothing reached this phone and nothing was signed."

        /**
         * What the wallet said, as this phone records it. A signature is kept only if it is over
         * exactly the bytes that were sent: a wallet that signed anything else has signed nothing
         * this request asked for.
         */
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
                    SigningOutcome.Failed(
                        listOfNotNull("The wallet could not sign", result.message)
                            .joinToString(": ")
                    )
            }
    }
}
