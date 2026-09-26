package io.github.brrenat.seekervault.wallet

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import com.google.protobuf.ByteString
import com.solana.mobilewalletadapter.clientlib.AdapterOperations
import com.solana.mobilewalletadapter.clientlib.Blockchain
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.LocalAdapterOperations
import com.solana.mobilewalletadapter.clientlib.Solana
import com.solana.mobilewalletadapter.clientlib.protocol.JsonRpc20Client
import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient.AuthorizationResult
import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient.NotSubmittedException
import com.solana.mobilewalletadapter.clientlib.scenario.LocalAssociationIntentCreator
import com.solana.mobilewalletadapter.clientlib.scenario.LocalAssociationScenario
import com.solana.mobilewalletadapter.clientlib.scenario.Scenario
import com.solana.mobilewalletadapter.common.ProtocolContract
import com.solana.mobilewalletadapter.common.protocol.SessionProperties
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * [WalletAdapter] over Mobile Wallet Adapter. It associates with the wallet the owner already has,
 * such as Seed Vault Wallet on the Seeker, from the current activity: the session's [sender] gives
 * the [WalletIntentSender] that `MainActivity` registered, waiting for the next one while a
 * rotation replaces the screen (SAW-017). No separate activity and no foreground service is needed,
 * and the app never becomes a wallet itself.
 *
 * One wallet session is one [WalletSessionClient], kept across connecting, signing and sending
 * (SEE-84). The session is dropped when the owner disconnects, when the wallet refuses this phone's
 * authorization, when the network changes, and when the wallet app it was aimed at changes — never
 * silently reused for another wallet or another chain.
 *
 * Every association is aimed at the wallet app the owner connected, from the route stored beside
 * their account (SEE-159). Before it, routing lived only inside Mobile Wallet Adapter's own client
 * object, which learns the wallet's endpoint while it authorizes and keeps it in a private field:
 * so the first association in any process — and so the first approval after every restart — went
 * out with no wallet named, and Android asked the owner which app to open. The route is read back
 * from storage instead, so the wallet the owner chose is the one that opens, the first time and
 * every time.
 */
class MwaWalletAdapter(
    private val identity: ConnectionIdentity,
    private val sender: suspend () -> WalletIntentSender?,
    private val targets: WalletTargets,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clients: (WalletNetwork, WalletTarget) -> WalletSessionClient = { network, target ->
        MwaSession(identity, sender, network, target, io)
    },
) : WalletAdapter {
    /**
     * One wallet interaction at a time, and one session for it. [WalletRepository] serializes
     * wallet calls too; this keeps the session's own state right whoever holds the adapter.
     */
    private val lock = Mutex()
    private var open: WalletSessionClient? = null

    override suspend fun installed(): List<InstalledWallet> =
        withContext(io) { targets.installed() }

    override suspend fun connect(
        network: WalletNetwork,
        authToken: String?,
        route: WalletRouting?,
    ): WalletResult = lock.withLock {
        // Connecting is the owner asking for a wallet, so a route pointing at an app that has gone
        // doesn't stop them: it is dropped, and Android is asked. Signing is the opposite — see
        // [aimedAt].
        val aim = aimedAt(route).takeUnless { it == WalletTarget.Missing } ?: WalletTarget.Wide
        val session = session(network, aim, authToken)
        val result =
            when (val outcome = session.connect()) {
                is WalletOutcome.Answered -> connected(outcome.authorization, route, aim)
                WalletOutcome.NoWallet -> WalletResult.NoWallet
                WalletOutcome.NoActivity -> WalletResult.Failed(NO_ACTIVITY)
                is WalletOutcome.Failed ->
                    classify(outcome.error, hadAuthorization = authToken != null)
            }
        // An authorization the wallet refused ends the session it belonged to: the next
        // attempt starts one afresh, and asks the owner.
        if (result == WalletResult.AuthorizationExpired) open = null
        result
    }

    override suspend fun signMessage(
        message: ByteString,
        wallet: SelectedWallet,
        authToken: String,
        route: WalletRouting?,
    ): SigningAnswer = lock.withLock {
        val account =
            decodeBase58(wallet.address)?.takeIf { it.size == PUBLIC_KEY_BYTES }
                ?: return@withLock SigningAnswer(
                    SignResult.Failed("the selected wallet isn't an address")
                )
        // The wallet app the owner connected isn't on this phone any more. Nothing is opened:
        // resolving the association wide from here would put their approval in front of whichever
        // other wallet Android found, which is the one switch this app must never make (SEE-159).
        val aim = aimedAt(route)
        if (aim == WalletTarget.Missing) return@withLock SigningAnswer(SignResult.NoWallet)
        val asked = message.toByteArray()
        // The wallet reauthorizes this app at the start of the session, before it is asked for
        // a signature, and it may replace this phone's authorization then. It is read here,
        // inside the one session the owner sees, so a signature the wallet then declines still
        // carries it and the wallet is never opened a second time just to learn it.
        var refused: SignResult? = null
        val outcome =
            session(wallet.network, aim, authToken).transact { authorization ->
                // The account the owner reviewed has to be one the wallet still authorizes.
                // Nothing is put in front of them for another account, on any outcome.
                if (authorizes(authorization, wallet) != AccountCheck.Matches) {
                    refused = SignResult.Changed
                    null
                } else {
                    signMessages(listOf(asked), listOf(account)).firstOrNull()
                }
            }
        val stopped = refused
        val result =
            when (outcome) {
                is WalletOutcome.Answered ->
                    stopped ?: signed(outcome.payload, wallet.address, account, asked)
                WalletOutcome.NoWallet -> SignResult.NoWallet
                WalletOutcome.NoActivity -> SignResult.Failed(NO_ACTIVITY)
                is WalletOutcome.Failed -> classifySigning(outcome.error)
            }
        if (result == SignResult.AuthorizationExpired) open = null
        val reported = outcome.reported()
        SigningAnswer(result, reported?.token, reported?.uriBase)
    }

    override suspend fun signAndSendTransaction(
        transaction: ByteString,
        wallet: SelectedWallet,
        authToken: String,
        route: WalletRouting?,
    ): SendingAnswer = lock.withLock {
        // As for a signature, and for the same reason: an approved transaction is never offered to
        // a wallet the owner didn't connect, so a route whose app has gone stops here (SEE-159).
        // Nothing reached a wallet, so nothing can have been sent.
        val aim = aimedAt(route)
        if (aim == WalletTarget.Missing) return@withLock SendingAnswer(SendResult.NoWallet)
        var refused: SendResult? = null
        // Whether the request reached the wallet at all. Until it does, a failure proves
        // nothing was sent; from the moment it does, this phone can no longer rule a
        // submission out, whatever went wrong afterwards.
        var dispatched = false
        val outcome =
            session(wallet.network, aim, authToken).transact { authorization ->
                if (authorizes(authorization, wallet) != AccountCheck.Matches) {
                    refused = SendResult.Changed
                    null
                } else {
                    dispatched = true
                    // The bytes go over as they are. This app hands the wallet exactly what
                    // the owner approved, and the wallet signs and submits it; nothing here
                    // builds or alters one.
                    signAndSend(listOf(transaction.toByteArray()))
                }
            }
        val stopped = refused
        val result =
            when (outcome) {
                is WalletOutcome.Answered -> stopped ?: sent(outcome.payload)
                WalletOutcome.NoWallet -> SendResult.NoWallet
                // The screen was gone before the wallet was opened, so nothing was sent.
                WalletOutcome.NoActivity -> SendResult.Failed(NO_ACTIVITY)
                is WalletOutcome.Failed -> classifySending(outcome.error, dispatched)
            }
        if (result == SendResult.AuthorizationExpired) open = null
        // The wallet reauthorizes before it sends, exactly as it does before it signs a
        // message, and the replacement it hands back is the one to keep whatever it then did
        // with the transaction (SEE-84). Losing it here would leave the next operation
        // offering a token the wallet has already replaced.
        val reported = outcome.reported()
        SendingAnswer(result, reported?.token, reported?.uriBase)
    }

    override suspend fun disconnect(
        wallet: SelectedWallet,
        authToken: String,
        route: WalletRouting?,
    ) = lock.withLock {
        // Whatever the wallet says, the phone forgets the authorization; there is nothing to
        // undo. The session goes with it, so nothing is left targeting that wallet. A wallet
        // app that is gone can't be told, and doesn't need to be.
        val aim = aimedAt(route)
        if (aim != WalletTarget.Missing) {
            session(wallet.network, aim, authToken).close()
        }
        open = null
    }

    /** Where an association for [route] goes, against the wallet apps this phone has now. */
    private suspend fun aimedAt(route: WalletRouting?): WalletTarget =
        targetOf(route, withContext(io) { targets.installed() }.map { it.packageName }.toSet())

    /**
     * The session for [network] aimed at [target], which is the one already open when it is for
     * both. Another network is another session — a client is bound to the chain it authorized on —
     * and so is another wallet app.
     */
    private fun session(
        network: WalletNetwork,
        target: WalletTarget,
        authToken: String?,
    ): WalletSessionClient {
        val session =
            open?.takeIf { it.network == network && it.target == target }
                ?: clients(network, target).also { open = it }
        session.authorization = authToken
        return session
    }

    // Internal, not private: every one of these checks the wallet's own answer, and each is worth
    // a test of its own (`MwaWalletAdapterTest`). Nothing here reaches the wallet or the network.
    internal companion object {
        const val NO_ACTIVITY = "the app's screen closed before the wallet answered"
        const val BEFORE_THE_WALLET = "the wallet session ended before the transaction reached it"

        /**
         * Whether the wallet just authorized the very account the owner reviewed (SEE-84). The
         * address is compared whole, so an account the wallet substituted is never signed with, and
         * the chains the wallet listed for it must not contradict the reviewed network — a wallet
         * that lists none has said nothing, which is not a contradiction and not a confirmation
         * either.
         */
        fun authorizes(authorization: WalletAuthorization, wallet: SelectedWallet): AccountCheck {
            val account =
                authorization.accounts.firstOrNull { it.address == wallet.address }
                    ?: return AccountCheck.NotAuthorized
            if (account.chains.isNotEmpty() && wallet.network.chain !in account.chains) {
                return AccountCheck.ChainMismatch
            }
            return AccountCheck.Matches
        }

        /**
         * What the owner connected: the account, its authorization, and the route to reach that
         * wallet app again. The route is what the owner aimed at, narrowed to what the association
         * actually used, with the wallet's own association URI folded in (SEE-159).
         */
        fun connected(
            authorization: WalletAuthorization?,
            asked: WalletRouting?,
            used: WalletTarget,
        ): WalletResult {
            val account =
                authorization?.accounts?.firstOrNull()
                    ?: return WalletResult.Failed("the wallet returned no account")
            if (decodeBase58(account.address)?.size != PUBLIC_KEY_BYTES) {
                return WalletResult.Failed("the wallet returned an address of the wrong size")
            }
            val token =
                authorization.token
                    ?: return WalletResult.Failed("the wallet returned no authorization")
            return WalletResult.Connected(account, token, routed(asked, used, authorization))
        }

        /**
         * The route a finished connection leaves behind. A connection Android resolved names no app
         * — this phone was not told which one answered — and one aimed at an app keeps it. Either
         * way the wallet's own association URI is taken from what it just reported.
         */
        fun routed(
            asked: WalletRouting?,
            used: WalletTarget,
            authorization: WalletAuthorization,
        ): WalletRouting {
            val app =
                when (used) {
                    is WalletTarget.App -> used.packageName
                    is WalletTarget.Endpoint -> used.packageName
                    WalletTarget.Wide,
                    WalletTarget.Missing -> null
                }
            val base = asked ?: WalletRouting.Untargeted
            return base
                .copy(packageName = app, appLabel = app?.let { base.appLabel })
                .withReported(authorization.uriBase)
        }

        const val PUBLIC_KEY_BYTES = 32
        const val SIGNATURE_BYTES = 64

        /** The authorization an outcome carries, from wherever in it the wallet reported one. */
        fun WalletOutcome<*>.reported(): WalletAuthorization? =
            when (this) {
                is WalletOutcome.Answered -> authorization
                is WalletOutcome.Failed -> authorization
                else -> null
            }

        /**
         * The wallet's answer, checked before it's believed: one signed message, signed by the
         * account that was asked, with a signature of the right size — and one that verifies as
         * [key]'s over exactly [asked]. What it says it signed is returned as it is, so the caller
         * can compare it with what it sent.
         *
         * The signature is verified here because the sidecar verifies every signature it is sent
         * and refuses anything else with INVALID_PARAMETERS, and the first outcome stored for an
         * approval stands: believing 64 bytes that aren't a signature would send that answer again
         * for ever, and leave the request PROCESSING with nothing able to settle it. A wallet whose
         * answer doesn't verify has failed to sign, which is an outcome both the owner and the
         * agent can be told once and be done with.
         */
        fun signed(
            signed: SignedMessage?,
            expected: String,
            key: ByteArray,
            asked: ByteArray,
        ): SignResult {
            if (signed == null) return SignResult.Failed("the wallet returned no signed message")
            val signature =
                signed.signatures.firstOrNull()
                    ?: return SignResult.Failed("the wallet returned no signature")
            if (signature.size != SIGNATURE_BYTES) {
                return SignResult.Failed("the wallet returned a signature of the wrong size")
            }
            val signer = signed.addresses.firstOrNull()?.let(::encodeBase58)
            if (signer != null && signer != expected) {
                return SignResult.Failed("the wallet signed with $signer, not the selected wallet")
            }
            if (!verifiesSignature(key, asked, signature)) {
                return SignResult.Failed(
                    "the wallet's answer is not this wallet's signature over this message"
                )
            }
            return SignResult.Signed(
                message = ByteString.copyFrom(signed.message ?: ByteArray(0)),
                address = expected,
                signature = ByteString.copyFrom(signature),
            )
        }

        /**
         * The wallet's answer about a transaction it sent, checked before it is believed: exactly
         * one signature, of the right size. The signature is the transaction's ID on chain.
         */
        fun sent(signatures: List<ByteArray?>?): SendResult {
            // The wallet was given one transaction, so anything but one signature is an answer
            // this phone can't match to what it asked, and it may still have been sent.
            if (signatures == null || signatures.size != 1) {
                return SendResult.Unknown("the wallet returned no signature for this transaction")
            }
            val signature = signatures.single()
            if (signature == null || signature.size != SIGNATURE_BYTES) {
                return SendResult.Unknown("the wallet returned a signature of the wrong size")
            }
            return SendResult.Sent(ByteString.copyFrom(signature))
        }

        /**
         * What a failure to sign and send means for the owner. Only the codes that say the wallet
         * stopped before it signed are failures, and so is a session that ended before the
         * transaction was ever put to the wallet — [dispatched] is false then, and nothing this app
         * asked for can have reached a network. Everything else leaves this phone unable to tell
         * whether the transaction reached the network, and an outcome nobody knows must be reported
         * as unknown rather than guessed at: a signed transaction stays valid until its blockhash
         * expires, so "the wallet says it didn't submit it" isn't "it can never land".
         */
        fun classifySending(error: WalletError, dispatched: Boolean): SendResult =
            when (error.code) {
                ProtocolContract.ERROR_NOT_SIGNED -> SendResult.Declined
                ProtocolContract.ERROR_AUTHORIZATION_FAILED -> SendResult.AuthorizationExpired
                ProtocolContract.ERROR_INVALID_PAYLOADS ->
                    SendResult.Failed("the wallet would not take this transaction")
                ProtocolContract.ERROR_TOO_MANY_PAYLOADS ->
                    SendResult.Failed("the wallet would not take this transaction")
                ProtocolContract.ERROR_CLUSTER_NOT_SUPPORTED ->
                    SendResult.Failed("the wallet doesn't serve this network")
                ProtocolContract.ERROR_NOT_SUBMITTED -> SendResult.Unknown(notSubmitted(error))
                // No code of the wallet's own: the session ended without an answer. Which side of
                // the wallet's sending that happened on is the whole question, and this phone can
                // answer it for itself — it knows whether it ever got as far as asking.
                else ->
                    if (dispatched) SendResult.Unknown(error.message)
                    else SendResult.Failed(error.message ?: BEFORE_THE_WALLET)
            }

        /** What the wallet said when it signed a transaction but reported no submission. */
        fun notSubmitted(error: WalletError): String =
            if (error.notSubmitted) {
                "the wallet signed the transaction but reported that it didn't send it"
            } else {
                error.message.orEmpty()
            }

        /**
         * What a failure to sign means for the owner. The wallet reports a refused signature as
         * NOT_SIGNED; an authorization it no longer honours comes back as AUTHORIZATION_FAILED, and
         * this app only ever signs with one it stored.
         */
        fun classifySigning(error: WalletError): SignResult =
            when (error.code) {
                ProtocolContract.ERROR_NOT_SIGNED -> SignResult.Declined
                ProtocolContract.ERROR_AUTHORIZATION_FAILED -> SignResult.AuthorizationExpired
                ProtocolContract.ERROR_INVALID_PAYLOADS ->
                    SignResult.Failed("the wallet would not take this message")
                else -> SignResult.Failed(error.message)
            }

        /**
         * What the wallet's error means for the owner. The wallet reports a refusal and an
         * authorization it no longer honours the same way, as AUTHORIZATION_FAILED, so
         * [hadAuthorization] tells them apart: a stored authorization was refused, and a fresh
         * request the owner saw was declined.
         */
        fun classify(error: WalletError, hadAuthorization: Boolean): WalletResult {
            return when (error.code) {
                ProtocolContract.ERROR_AUTHORIZATION_FAILED ->
                    if (hadAuthorization) WalletResult.AuthorizationExpired
                    else WalletResult.Declined
                ProtocolContract.ERROR_CLUSTER_NOT_SUPPORTED -> WalletResult.NetworkUnsupported
                else -> WalletResult.Failed(error.message)
            }
        }

        /** The wallet's own error code, from wherever in the chain of causes it is. */
        fun remoteCode(error: Exception?): Int? =
            causes(error)
                .filterIsInstance<JsonRpc20Client.JsonRpc20RemoteException>()
                .firstOrNull()
                ?.code

        /** What Mobile Wallet Adapter reported, in the terms this app classifies. */
        fun walletError(error: Exception?): WalletError =
            WalletError(
                code = remoteCode(error),
                notSubmitted = causes(error).any { it is NotSubmittedException },
                message = error?.message,
            )

        private fun causes(error: Exception?): Sequence<Throwable> =
            generateSequence(error as Throwable?) { it.cause }
    }
}

/** What a wallet's reauthorization says about the account the owner reviewed (SEE-84). */
internal enum class AccountCheck {
    /**
     * The wallet still authorizes that exact account, and contradicts nothing about its network.
     */
    Matches,
    /** The wallet no longer lists the reviewed account at all. */
    NotAuthorized,
    /** The wallet lists the account, and says it isn't on the reviewed network. */
    ChainMismatch,
}

private fun WalletNetwork.blockchain(): Blockchain =
    when (this) {
        WalletNetwork.Mainnet -> Solana.Mainnet
        WalletNetwork.Devnet -> Solana.Devnet
        WalletNetwork.Testnet -> Solana.Testnet
    }

/**
 * The authorization as this app reads it. The label a wallet leaves blank is no label, the chains
 * it lists are taken exactly as they are — an empty list says nothing about any network — and its
 * association URI is kept only when Mobile Wallet Adapter would take it back as an association
 * prefix, which means an `https` one (SEE-159).
 */
internal fun authorizationOf(result: AuthorizationResult?): WalletAuthorization =
    WalletAuthorization(
        token = result?.authToken?.takeIf { it.isNotEmpty() },
        accounts =
            result?.accounts?.filterNotNull().orEmpty().map { account ->
                WalletAccount(
                    address = encodeBase58(account.publicKey),
                    label = account.accountLabel?.takeIf { it.isNotBlank() },
                    chains = account.chains?.filterNotNull().orEmpty(),
                )
            },
        uriBase = result?.walletUriBase?.toString()?.takeIf(WalletRouting::usableUriBase),
    )

/**
 * One wallet session over Mobile Wallet Adapter (SEE-84, SEE-159), aimed at one wallet app.
 *
 * The association is built here rather than by Mobile Wallet Adapter's `MobileWalletAdapter`
 * facade, because that facade keeps the wallet's endpoint in a private field with no way to set it:
 * it can learn a wallet within one process and never be told one. Everything the handshake itself
 * needs is the library's and is used as it comes — `LocalAssociationScenario` for the local
 * transport, `LocalAssociationIntentCreator` for the intent, `LocalAdapterOperations` for the
 * requests. What this class adds is where the intent is pointed.
 *
 * It holds no transport open between calls: each one associates, does its work, and closes.
 */
private class MwaSession(
    private val identity: ConnectionIdentity,
    private val sender: suspend () -> WalletIntentSender?,
    override val network: WalletNetwork,
    override val target: WalletTarget,
    private val io: CoroutineDispatcher,
) : WalletSessionClient {
    override var authorization: String? = null

    /**
     * What the wallet reported while reauthorizing this app, read inside the association rather
     * than after it: an association that then fails still tells this phone which token to keep, and
     * where the wallet said it lives. One association runs at a time under the adapter's lock.
     */
    private var reported: WalletAuthorization? = null

    override suspend fun connect(): WalletOutcome<Unit> = associate { operations, properties ->
        authorize(operations, properties)
        Unit
    }

    override suspend fun <T : Any> transact(
        block: suspend WalletRequests.(WalletAuthorization) -> T?
    ): WalletOutcome<T> = associate { operations, properties ->
        MwaRequests(operations).block(authorize(operations, properties))
    }

    override suspend fun close() {
        val held = authorization ?: return
        associate { operations, _ ->
            operations.deauthorize(held)
            Unit
        }
    }

    /**
     * Opens the wallet, hands [work] the session's requests, and closes. It does what Mobile Wallet
     * Adapter's own `associate` does, with its timeouts, and reports outcomes in this app's own
     * terms: the one failure it can prove happened before any wallet saw anything is an association
     * nothing on this phone would open.
     *
     * An owner who comes back out of the wallet without deciding leaves the handshake with nobody
     * to answer it, and [HANDSHAKE_TIMEOUT_MS] is what ends it — the same bound Mobile Wallet
     * Adapter puts on it. They are told the wallet didn't answer, and the request is still theirs
     * to review.
     */
    private suspend fun <T : Any> associate(
        work: suspend (AdapterOperations, SessionProperties) -> T?
    ): WalletOutcome<T> = coroutineScope {
        val screen = sender() ?: return@coroutineScope WalletOutcome.NoActivity
        reported = null
        val scenario = LocalAssociationScenario(Scenario.DEFAULT_CLIENT_TIMEOUT_MS)
        try {
            val intent = association(scenario)
            try {
                withTimeout(SEND_INTENT_TIMEOUT_MS) { screen.send(intent) }
            } catch (e: TimeoutCancellationException) {
                return@coroutineScope WalletOutcome.Failed(
                    MwaWalletAdapter.walletError(e),
                    reported,
                )
            }
            withContext(io) {
                try {
                    val client = scenario.start().get(HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    WalletOutcome.Answered(
                        work(
                            LocalAdapterOperations(io, client),
                            scenario.session.sessionProperties,
                        ),
                        reported,
                    )
                } finally {
                    scenario.close().get(HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                }
            }
        } catch (e: ActivityNotFoundException) {
            // Nothing on this phone would open the association: no wallet app at all, or not the
            // one it was aimed at. Either way no wallet saw anything.
            WalletOutcome.NoWallet
        } catch (e: Exception) {
            WalletOutcome.Failed(MwaWalletAdapter.walletError(e), reported)
        }
    }

    /**
     * The association intent for [scenario], pointed at this session's [target]. The wallet's own
     * association URI comes first, because that is the routing Mobile Wallet Adapter defines, and
     * the package narrows it to the app the owner connected; a URI the library would refuse is
     * dropped rather than thrown over, and the package still names the wallet.
     */
    private fun association(scenario: LocalAssociationScenario): Intent {
        val endpoint = (target as? WalletTarget.Endpoint)?.uriBase?.toUri()?.takeIf(::associable)
        val intent =
            LocalAssociationIntentCreator.createAssociationIntent(
                endpoint,
                scenario.port,
                scenario.session,
            )
        val app =
            when (target) {
                is WalletTarget.App -> target.packageName
                is WalletTarget.Endpoint -> target.packageName
                WalletTarget.Wide,
                WalletTarget.Missing -> null
            }
        return if (app == null) intent else intent.setPackage(app)
    }

    /**
     * Asks the wallet to authorize this app, with the token this phone holds when it has one. Which
     * request that is belongs to the protocol version the wallet just agreed to, exactly as Mobile
     * Wallet Adapter's own client decides it.
     */
    @Suppress("DEPRECATION")
    private suspend fun authorize(
        operations: AdapterOperations,
        properties: SessionProperties,
    ): WalletAuthorization {
        val held = authorization
        val chain = network.blockchain()
        val result =
            if (properties.protocolVersion == SessionProperties.ProtocolVersion.V1) {
                operations.authorize(
                    identityUri = identity.identityUri,
                    iconUri = identity.iconUri,
                    identityName = identity.identityName,
                    chain = chain.fullName,
                    authToken = held,
                )
            } else if (held != null) {
                operations.reauthorize(
                    identity.identityUri,
                    identity.iconUri,
                    identity.identityName,
                    held,
                )
            } else {
                operations.authorize(
                    identity.identityUri,
                    identity.iconUri,
                    identity.identityName,
                    chain.rpcCluster,
                )
            }
        return authorizationOf(result).also { reported = it }
    }

    private companion object {
        /** What Mobile Wallet Adapter allows itself to open a wallet and to shake hands. */
        const val SEND_INTENT_TIMEOUT_MS = 20_000L
        const val HANDSHAKE_TIMEOUT_MS = 10_000L

        /** Whether the library would take [uri] as an association prefix. */
        fun associable(uri: Uri): Boolean =
            uri.scheme == "https" && uri.isHierarchical && !uri.authority.isNullOrEmpty()
    }
}

/** What this app asks a wallet for, over the session Mobile Wallet Adapter opened. */
private class MwaRequests(private val operations: AdapterOperations) : WalletRequests {
    override suspend fun signMessages(
        messages: List<ByteArray>,
        addresses: List<ByteArray>,
    ): List<SignedMessage> {
        val result =
            operations.signMessagesDetached(messages.toTypedArray(), addresses.toTypedArray())
        return result.messages.filterNotNull().map { signed ->
            SignedMessage(
                message = signed.message,
                signatures = signed.signatures.toList(),
                addresses = signed.addresses.toList(),
            )
        }
    }

    override suspend fun signAndSend(transactions: List<ByteArray>): List<ByteArray?> =
        operations.signAndSendTransactions(transactions.toTypedArray()).signatures.toList()
}
