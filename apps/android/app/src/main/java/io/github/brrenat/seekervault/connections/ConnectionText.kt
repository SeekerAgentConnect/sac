package io.github.brrenat.seekervault.connections

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.access.storage.FeedAccessStore
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.feeds.FeedAvailability
import io.github.brrenat.seekervault.feeds.FeedListenerState
import io.github.brrenat.seekervault.notifications.LocalInAppNotices
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.FeedAccess
import io.github.brrenat.seekervault.servers.FeedReferenceProblem
import io.github.brrenat.seekervault.servers.ServerSupport
import io.github.brrenat.seekervault.servers.executable
import io.github.brrenat.seekervault.servers.feedAccess
import io.github.brrenat.seekervault.servers.manifest
import io.github.brrenat.seekervault.sync.ForegroundConnectionState
import io.github.brrenat.seekervault.sync.UpdateAvailability
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Test tags for the connection screens' controls. */
object ConnectionsTags {
    /** The connection's wallet row on its detail sheet (SEE-174). */
    const val WALLET_ROW = "connectionWallet"
    const val LIVE_TEST = "liveTest"
    const val ADD = "addConnection"
    const val EMPTY = "connectionsEmpty"
    const val ACTIVITY = "activityRow"
    const val BACK = "back"
    const val CLOSE = "close"
    const val STATUS = "connectionStatus"
    const val REFRESH = "refresh"
    const val RENAME = "rename"
    /** Where a feed's proposals are reviewed, in the place a direct connection shows requests. */
    const val SIGNALS = "connectionSignals"
    const val DISCONNECT = "disconnectConnection"
    const val REMOVE = "removeConnection"
    const val LABEL_FIELD = "labelField"
    const val DIALOG_CONFIRM = "dialogConfirm"
    const val DIALOG_DISMISS = "dialogDismiss"
    const val SCAN = "scan"
    const val STOP_SCAN = "stopScan"
    const val CODE_FIELD = "codeField"
    const val CONTINUE = "continue"
    const val CODE_PROBLEM = "codeProblem"
    const val CAMERA_DENIED = "cameraDenied"
    const val NO_CAMERA = "noCamera"
    const val OPEN_SETTINGS = "openSettings"
    const val CONFIRM_SERVER = "confirmServer"
    const val CONFIRM_FEED = "confirmFeed"
    const val PAIR_DIRECT = "pairDirect"
    /** Sends a restricted feed's access request, which opens the wallet once (SEE-156). */
    const val REQUEST_ACCESS = "requestAccess"
    const val CONFIRM_NOTE = "confirmNote"
    const val PAIR = "pair"
    const val CANCEL_PAIRING = "cancelPairing"
    const val PAIRING_FAILURE = "pairingFailure"
    const val ADD_FEED = "addFeed"
    const val FEED_FAILURE = "feedFailure"
    const val OPEN_FEED = "openFeed"
    const val INBOX = "inbox"
    const val WALLET = "walletRow"
    const val WALLET_COPY = "walletCopy"
    const val PENDING = "pendingRequests"
    const val PENDING_EMPTY = "pendingRequestsEmpty"
    const val CAROUSEL = "requestCarousel"
    const val GLOBAL_RULES = "globalRules"
    const val LIST = "connectionsList"
    /** Which promise a feed keeps, and the owner's switch between them (SEE-97). */
    const val ENVIRONMENT = "connectionEnvironment"

    fun item(id: String) = "connection:$id"

    fun environment(code: String) = "environment:$code"

    fun request(key: RequestKey) = "request:${key.connectionId}/${key.requestId}"

    fun field(name: String) = "field:$name"
}

/** Keeps the plugin-owned environment type behind the existing connection boundary. */
internal fun Connection.isSandboxEnvironment(): Boolean = environment == PluginEnvironment.Sandbox

/** Which promise a connection keeps, in a word (SEE-97, docs/wiki/environments.md). */
@StringRes
fun environmentText(environment: PluginEnvironment): Int =
    when (environment) {
        PluginEnvironment.Production -> R.string.connection_environment_production
        PluginEnvironment.Sandbox -> R.string.connection_environment_sandbox
    }

/** And what it means, in one sentence, because the word alone is not a promise anybody can read. */
@StringRes
fun environmentNote(environment: PluginEnvironment): Int =
    when (environment) {
        PluginEnvironment.Production -> R.string.connection_environment_production_note
        PluginEnvironment.Sandbox -> R.string.connection_environment_sandbox_note
    }

/**
 * Direct live-update state belongs only to paired sidecars. A GatewayFeed is not usable in that
 * sense, so [ForegroundUpdateManager] publishes [ForegroundConnectionState.Revoked] for it; that
 * value must not be read as the feed's own reachability (SEE-143).
 */
internal fun Connection.directTransport(
    live: ForegroundConnectionState?
): ForegroundConnectionState? = live.takeIf { mode == ConnectionMode.Direct }

/**
 * Gateway listener state is keyed by origin. A Direct connection at the same URL must not inherit a
 * feed's Connecting/Live/Unreachable row (SEE-143).
 */
internal fun Connection.feedTransport(feed: FeedListenerState?): FeedListenerState? = feed.takeIf {
    mode == ConnectionMode.GatewayFeed
}

/** What the phone knows about the connection, in one sentence. */
@Composable
fun statusText(
    connection: Connection,
    live: ForegroundConnectionState? = null,
    /**
     * Whether this build supports the connection's server (SEE-88); null when it hasn't been worked
     * out. It is said before anything about reachability, because it is the more basic fact: a
     * server this app can't act for is not one whose pending count means much.
     */
    support: ServerSupport? = null,
    feed: FeedListenerState? = null,
    /**
     * Whether the publisher behind this feed is running (SEE-150). It is said after the states that
     * are about *this phone's* connection and before the ordinary "feed available", because it is
     * the more specific fact: a gateway that answers is no evidence a publisher is up, and a
     * gateway that does not answer is what the owner needs to hear about first.
     */
    availability: FeedAvailability = FeedAvailability.Unknown,
    /**
     * Where this phone's request to read a restricted feed stands (SEE-156), or null for a public
     * feed and for one nothing has been asked of yet. It is said before anything about the
     * transport, because it is the more basic fact: a device waiting for approval is not
     * "unreachable", and telling the owner to wait for a connection that will never open is telling
     * them the wrong thing to wait for.
     */
    access: FeedAccessStore.Record? = null,
): String {
    val check = connection.lastCheck
    val liveState = connection.directTransport(live)
    val feedState = connection.feedTransport(feed)
    val feedAvailability =
        availability.takeIf { connection.mode == ConnectionMode.GatewayFeed }
            ?: FeedAvailability.Unknown
    return when {
        connection.retirement == ConnectionRetirement.GatewayPrivateRemoved ->
            stringResource(R.string.connection_status_gateway_private_retired)
        connection.revokedAt != null -> stringResource(R.string.connection_status_revoked)
        support != null && !support.executable -> supportText(support)
        connection.mode == ConnectionMode.Direct && !connection.hasCredential ->
            stringResource(R.string.connection_status_credential_missing)
        connection.accessNotRequested(access) ->
            stringResource(R.string.connection_status_access_not_requested)
        access?.state == FeedAccessStore.State.Pending ->
            stringResource(R.string.connection_status_access_pending)
        access?.state == FeedAccessStore.State.Approved ->
            stringResource(R.string.connection_status_access_approved)
        access?.state == FeedAccessStore.State.Rejected ->
            stringResource(R.string.connection_status_access_rejected)
        access?.state == FeedAccessStore.State.Revoked ->
            stringResource(R.string.connection_status_access_revoked)
        access?.state == FeedAccessStore.State.Expired ->
            stringResource(R.string.connection_status_access_expired)
        feedState == FeedListenerState.Connecting ->
            stringResource(R.string.connection_status_connecting)
        feedState is FeedListenerState.Live && feedAvailability == FeedAvailability.Offline ->
            stringResource(R.string.connection_status_feed_offline)
        feedState is FeedListenerState.Live -> stringResource(R.string.connection_status_live)
        feedState is FeedListenerState.Reconnecting ->
            stringResource(R.string.connection_status_reconnecting)
        feedState is FeedListenerState.Unreachable -> outcomeText(feedState.outcome)
        // A gateway with no broker is a working deployment, so the feed's own state is the whole of
        // what there is to say about it — and an offline publisher there is exactly the case the
        // owner could not see before: no stream to look wrong, and a feed that had stopped moving.
        feedState == FeedListenerState.NoStream && feedAvailability == FeedAvailability.Offline ->
            stringResource(R.string.connection_status_feed_offline)
        feedState == FeedListenerState.NoStream ->
            stringResource(R.string.connection_status_feed_available)
        feedState is FeedListenerState.Refused -> stringResource(R.string.connection_status_failed)
        liveState == ForegroundConnectionState.Background ->
            stringResource(R.string.connection_status_background)
        liveState == ForegroundConnectionState.Connecting ->
            stringResource(R.string.connection_status_connecting)
        liveState == ForegroundConnectionState.Live ->
            stringResource(R.string.connection_status_live)
        liveState is ForegroundConnectionState.Reconnecting ->
            stringResource(R.string.connection_status_reconnecting)
        liveState is ForegroundConnectionState.Unreachable -> outcomeText(liveState.failure)
        liveState == ForegroundConnectionState.Revoked ->
            stringResource(R.string.connection_status_revoked)
        liveState is ForegroundConnectionState.Unsupported ->
            availabilityText(liveState.availability)
        check == null -> stringResource(R.string.connection_status_not_checked)
        check.outcome == CheckOutcome.Ok && check.morePending ->
            stringResource(R.string.connection_status_ok_more, check.pending ?: 0)
        check.outcome == CheckOutcome.Ok ->
            stringResource(R.string.connection_status_ok, check.pending ?: 0)
        else -> outcomeText(check.outcome)
    }
}

/**
 * A restricted feed this phone holds no access request for (SEE-156): the owner closed the wallet
 * instead of signing, or the request never reached the publisher. The feed is stored, and nothing
 * on it can be read, so it is not connected and it is not paired — it needs the request sent.
 */
fun Connection.accessNotRequested(access: FeedAccessStore.Record?): Boolean =
    access == null && server.manifest?.feedAccess is FeedAccess.Restricted

/**
 * Whether sending an access request is the thing to do for this feed: none was sent, or the
 * publisher rejected the last one (SEE-156). Either way a new request opens the wallet once.
 */
fun Connection.canRequestAccess(access: FeedAccessStore.Record?): Boolean =
    accessNotRequested(access) ||
        (access?.state == FeedAccessStore.State.Rejected &&
            server.manifest?.feedAccess is FeedAccess.Restricted)

/** Whether the connection needs the owner's attention. */
fun hasProblem(
    connection: Connection,
    live: ForegroundConnectionState? = null,
    support: ServerSupport? = null,
    feed: FeedListenerState? = null,
    availability: FeedAvailability = FeedAvailability.Unknown,
    access: FeedAccessStore.Record? = null,
): Boolean {
    val liveState = connection.directTransport(live)
    val feedState = connection.feedTransport(feed)
    // A feed is not "usable" in the credential sense — it has no credential — so its problems are
    // the manifest this build cannot act on or the gateway listener actually failing (SEE-139).
    // Direct transport Revoked is ignored for feeds; feed listener state is ignored for Direct.
    // Waiting for a decision is not a problem — it is the flow working. The three that end
    // access are, and each of them needs the owner to do something (SEE-156).
    val accessDenied =
        access?.state == FeedAccessStore.State.Rejected ||
            access?.state == FeedAccessStore.State.Revoked ||
            access?.state == FeedAccessStore.State.Expired
    return connection.retirement != null ||
        accessDenied ||
        connection.accessNotRequested(access) ||
        (connection.mode == ConnectionMode.Direct && !connection.usable) ||
        support?.executable == false ||
        // A publisher that has stopped is the owner's business even though nothing on this phone is
        // wrong: the feed has quietly stopped moving, which is the state SEE-150 was reported
        // about.
        // Unknown is not a problem — not having been told is not bad news.
        (connection.mode == ConnectionMode.GatewayFeed &&
            availability == FeedAvailability.Offline) ||
        feedState is FeedListenerState.Unreachable ||
        feedState is FeedListenerState.Refused ||
        when (liveState) {
            is ForegroundConnectionState.Unreachable,
            ForegroundConnectionState.Revoked,
            is ForegroundConnectionState.Unsupported -> true
            ForegroundConnectionState.Background,
            ForegroundConnectionState.Connecting,
            ForegroundConnectionState.Live,
            is ForegroundConnectionState.Reconnecting -> false
            null ->
                connection.mode == ConnectionMode.Direct &&
                    connection.lastCheck.let { it != null && it.outcome != CheckOutcome.Ok }
        }
}

/**
 * Why this build can't act for the connection's server. Each state is said as itself: what is
 * missing, and whether it is missing here or promised there (docs/wiki/server-manifests.md).
 */
@Composable
private fun supportText(support: ServerSupport): String =
    stringResource(
        when (support) {
            is ServerSupport.PluginMissing -> R.string.connection_status_unsupported_plugin
            is ServerSupport.PluginIncompatible -> R.string.connection_status_unsupported_version
            is ServerSupport.ProtocolUnsupported -> R.string.connection_status_unsupported_protocol
            is ServerSupport.EnvironmentUnsupported ->
                R.string.connection_status_unsupported_environment
            is ServerSupport.ManifestRefused -> R.string.connection_status_manifest_refused
            // Not reached: these are the executable states, and this is only asked about the
            // others. Named rather than left to an else, so a state added later is decided here.
            is ServerSupport.Supported,
            is ServerSupport.LegacyDirect,
            is ServerSupport.Unknown -> R.string.connection_status_not_checked
        }
    )

@Composable
private fun availabilityText(availability: UpdateAvailability): String =
    stringResource(
        when (availability) {
            UpdateAvailability.NotConfigured -> R.string.connection_status_updates_not_configured
            UpdateAvailability.UpgradeRequired -> R.string.connection_status_upgrade_required
            UpdateAvailability.Incompatible -> R.string.connection_status_updates_incompatible
            UpdateAvailability.Unknown,
            UpdateAvailability.Available -> R.string.connection_status_failed
        }
    )

@Composable
fun outcomeText(outcome: CheckOutcome): String =
    stringResource(
        when (outcome) {
            CheckOutcome.Ok -> R.string.connection_status_not_checked
            CheckOutcome.Unreachable -> R.string.connection_status_unreachable
            CheckOutcome.CertificateRejected -> R.string.connection_status_certificate
            CheckOutcome.CleartextBlocked -> R.string.connection_status_cleartext
            CheckOutcome.Failed -> R.string.connection_status_failed
        }
    )

@Composable
fun problemText(problem: PairingCodeProblem): String =
    stringResource(
        when (problem) {
            PairingCodeProblem.NotACode -> R.string.code_not_a_code
            PairingCodeProblem.NotSeekerVault -> R.string.code_not_seeker_vault
            PairingCodeProblem.OtherVersion -> R.string.code_other_version
            PairingCodeProblem.BadServerUrl -> R.string.code_bad_server_url
            PairingCodeProblem.InsecureServerUrl -> R.string.code_insecure_server_url
            PairingCodeProblem.BadServerId -> R.string.code_bad_server_id
            PairingCodeProblem.BadToken -> R.string.code_bad_token
        }
    )

/** Feed references have their own vocabulary: a public subscription is never called a pairing. */
@Composable
fun feedReferenceProblemText(problem: FeedReferenceProblem): String =
    stringResource(
        when (problem) {
            FeedReferenceProblem.NotAReference -> R.string.feed_reference_not_a_reference
            FeedReferenceProblem.NotSeekerVault -> R.string.feed_reference_not_seeker_vault
            FeedReferenceProblem.OtherVersion -> R.string.feed_reference_other_version
            FeedReferenceProblem.BadGatewayUrl -> R.string.feed_reference_bad_gateway
            FeedReferenceProblem.InsecureGatewayUrl -> R.string.feed_reference_insecure_gateway
            FeedReferenceProblem.BadServerId -> R.string.feed_reference_bad_server_id
            FeedReferenceProblem.BadAccess -> R.string.feed_reference_bad_access
            FeedReferenceProblem.BadInvitation -> R.string.feed_reference_bad_invitation
        }
    )

@Composable
fun feedFailureText(failure: FeedAddFailure, gatewayUrl: String): String =
    when (failure) {
        is FeedAddFailure.Refused ->
            stringResource(R.string.feed_failed_manifest, failure.problem.code)
        is FeedAddFailure.Check ->
            stringResource(
                when (failure.outcome) {
                    CheckOutcome.CertificateRejected -> R.string.feed_failed_certificate
                    CheckOutcome.CleartextBlocked -> R.string.feed_failed_cleartext
                    CheckOutcome.Unreachable -> R.string.feed_failed_unreachable
                    CheckOutcome.Failed -> R.string.feed_failed_response
                    CheckOutcome.Ok -> R.string.feed_failed_other
                },
                *if (failure.outcome == CheckOutcome.Unreachable) arrayOf(gatewayUrl)
                else emptyArray(),
            )
        FeedAddFailure.NoGateway -> stringResource(R.string.feed_failed_no_gateway)
        FeedAddFailure.Storage -> stringResource(R.string.feed_failed_storage)
    }

@Composable
fun failureText(failure: PairingFailure, serverUrl: String): String =
    when (failure) {
        PairingFailure.CodeRefused -> stringResource(R.string.pair_failed_code)
        PairingFailure.WrongAddress -> stringResource(R.string.pair_failed_address)
        PairingFailure.CertificateRejected -> stringResource(R.string.pair_failed_certificate)
        PairingFailure.CleartextBlocked -> stringResource(R.string.pair_failed_cleartext)
        PairingFailure.Unreachable -> stringResource(R.string.pair_failed_unreachable, serverUrl)
        PairingFailure.BadResponse -> stringResource(R.string.pair_failed_response)
        PairingFailure.Storage -> stringResource(R.string.pair_failed_storage)
        PairingFailure.Other -> stringResource(R.string.pair_failed_other)
    }

@Composable
fun labelProblemText(problem: LabelProblem): String =
    stringResource(
        when (problem) {
            LabelProblem.Blank -> R.string.rename_blank
            LabelProblem.TooLong -> R.string.rename_too_long
        }
    )

@Composable
fun messageText(message: ConnectionMessage): String =
    when (message) {
        is ConnectionMessage.Paired -> stringResource(R.string.message_paired, message.label)
        is ConnectionMessage.FeedAdded -> stringResource(R.string.message_feed_added, message.label)
        is ConnectionMessage.Disconnected ->
            stringResource(R.string.message_disconnected, message.label)
        is ConnectionMessage.Removed -> stringResource(R.string.message_removed, message.label)
        is ConnectionMessage.Renamed -> stringResource(R.string.message_renamed, message.label)
        is ConnectionMessage.AccessNotAsked -> accessProblemText(message.reason)
        is ConnectionMessage.AccessApproved ->
            stringResource(R.string.message_access_approved, message.label)
        is ConnectionMessage.AccessRejected ->
            stringResource(R.string.message_access_rejected, message.label)
        is ConnectionMessage.AccessRevoked ->
            stringResource(R.string.message_access_revoked, message.label)
    }

/**
 * Why asking a restricted feed's publisher for access stopped short (SEE-156). Each one says what
 * happened and, where there is one, what the owner can do about it — and each of them means nothing
 * was sent and nothing changed.
 */
@Composable
private fun accessProblemText(reason: AccessProblem): String =
    stringResource(
        when (reason) {
            AccessProblem.NoWallet -> R.string.access_problem_no_wallet
            AccessProblem.NotSigned -> R.string.access_problem_not_signed
            AccessProblem.WalletChanged -> R.string.access_problem_wallet_changed
            AccessProblem.BadChallenge -> R.string.access_problem_bad_challenge
            AccessProblem.Unreachable -> R.string.access_problem_unreachable
            AccessProblem.Refused -> R.string.access_problem_refused
        }
    )

@Composable
fun formatInstant(instant: Instant): String =
    remember(instant) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withZone(ZoneId.systemDefault())
            .format(instant)
    }

/** Shows [message] once as a service banner at the top of the app, then reports it shown. */
@Composable
fun MessageEffect(message: ConnectionMessage?, onShown: () -> Unit) {
    val text = message?.let { messageText(it) }
    val notices = LocalInAppNotices.current
    LaunchedEffect(message) {
        if (text != null) {
            notices.show(text)
            onShown()
        }
    }
}

@Composable
fun BackButton(onBack: () -> Unit) {
    Box(
        modifier =
            Modifier.size(SeekerTheme.dimensions.dp48)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onBack,
                )
                .testTag(ConnectionsTags.BACK),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_arrow_back),
            contentDescription = stringResource(R.string.back),
        )
    }
}

/** The trailing close action used by every detail sheet. */
@Composable
fun CloseButton(
    onClose: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surface,
) {
    Box(
        modifier =
            Modifier.size(SeekerTheme.dimensions.dp48)
                .clip(CircleShape)
                .background(containerColor)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onClose,
                )
                .testTag(ConnectionsTags.CLOSE),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Close,
            contentDescription = stringResource(R.string.close),
        )
    }
}
