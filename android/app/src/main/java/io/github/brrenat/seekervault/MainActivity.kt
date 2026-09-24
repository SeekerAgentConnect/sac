package io.github.brrenat.seekervault

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.brrenat.seekervault.activity.ActivityViewModel
import io.github.brrenat.seekervault.connections.ConnectionsViewModel
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.inbox.InboxViewModel
import io.github.brrenat.seekervault.live.LiveCommandViewModel
import io.github.brrenat.seekervault.notifications.ProposalNotificationIntent
import io.github.brrenat.seekervault.notifications.ProposalRef
import io.github.brrenat.seekervault.notifications.RequestNotificationIntent
import io.github.brrenat.seekervault.operations.OperationViewModel
import io.github.brrenat.seekervault.policy.GlobalPolicyEditorViewModel
import io.github.brrenat.seekervault.policy.PolicyEditorViewModel
import io.github.brrenat.seekervault.wallet.WalletViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainActivity : ComponentActivity() {
    data class NotificationTap(
        val sequence: Long,
        val key: io.github.brrenat.seekervault.connections.RequestKey,
    )

    /** The same for a proposal alert (SEE-92): the feed it is on, and the document on it. */
    data class FeedTap(val sequence: Long, val ref: ProposalRef)

    /** A cold or warm pairing link, or a retired invite recognized only for its explanation. */
    data class ConnectionLinkTap(val sequence: Long, val uri: String)

    private var nextNotificationTap = 0L
    private val _notificationTaps = MutableStateFlow<NotificationTap?>(null)
    private val notificationTaps = _notificationTaps.asStateFlow()
    private val _feedTaps = MutableStateFlow<FeedTap?>(null)
    private val feedTaps = _feedTaps.asStateFlow()
    private val _connectionLinkTaps = MutableStateFlow<ConnectionLinkTap?>(null)
    private val connectionLinkTaps = _connectionLinkTaps.asStateFlow()

    private val viewModel: LiveCommandViewModel by viewModels {
        viewModelFactory {
            initializer {
                LiveCommandViewModel((application as SeekerVaultApplication).liveCommandTransports)
            }
        }
    }

    private val connections: ConnectionsViewModel by viewModels {
        viewModelFactory {
            initializer {
                val app = application as SeekerVaultApplication
                ConnectionsViewModel(
                    repository = app.connectionRepository,
                    foregroundUpdates = app.foregroundUpdates.state,
                    foregroundFeeds = app.foregroundFeeds.state,
                    foregroundFeedStatus = app.foregroundFeedStatus.state,
                    cleartextPermitted = app::isCleartextPermitted,
                    // The one registry for the process, so a server's requirements are matched
                    // against the same plugins here as when a request is reviewed (SEE-88).
                    plugins = app.providerRegistry,
                )
            }
        }
    }

    private val inbox: InboxViewModel by viewModels {
        viewModelFactory {
            initializer {
                val app = application as SeekerVaultApplication
                InboxViewModel(
                    app.connectionRepository,
                    app.walletRepository,
                    app.policyEvaluator,
                    app.activityLog,
                    plugins = app.providerRegistry,
                    chain = app.solanaAccounts(),
                    io = app.connectionIo,
                )
            }
        }
    }

    private val history: ActivityViewModel by viewModels {
        viewModelFactory {
            initializer {
                ActivityViewModel((application as SeekerVaultApplication).activityLog)
            }
        }
    }

    private val policy: PolicyEditorViewModel by viewModels {
        viewModelFactory {
            initializer {
                val app = application as SeekerVaultApplication
                PolicyEditorViewModel(app.policyStore, io = app.connectionIo)
            }
        }
    }

    /** Kept separate so a local unsaved draft survives a visit to Global rules. */
    private val globalPolicy: GlobalPolicyEditorViewModel by viewModels {
        viewModelFactory {
            initializer {
                val app = application as SeekerVaultApplication
                GlobalPolicyEditorViewModel(app.policyStore, io = app.connectionIo)
            }
        }
    }

    private val wallet: WalletViewModel by viewModels {
        viewModelFactory {
            initializer {
                val app = application as SeekerVaultApplication
                WalletViewModel(app.walletRepository, app.connectionRepository)
            }
        }
    }

    /** Reviewing a publisher's proposal and acting on it (SEE-93). */
    private val operations: OperationViewModel by viewModels {
        viewModelFactory {
            initializer {
                val app = application as SeekerVaultApplication
                OperationViewModel(
                    proposals = app.proposalRepository,
                    connections = app.connectionRepository.connections,
                    connectionsLoaded = app.connectionRepository.loaded,
                    wallet = app.walletRepository,
                    policies = app.policyEvaluator,
                    history = app.activityLog,
                    // The one registry for the process, so the plugin that prepares an
                    // operation's bytes is the same one a server's requirements were matched
                    // against (SEE-86, SEE-88).
                    providers = app.providerRegistry,
                    io = app.connectionIo,
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptNotificationTap(intent)
        // Mobile Wallet Adapter starts the wallet from an Activity, and its sender has to be
        // registered before the activity is started.
        (application as SeekerVaultApplication).attachWalletActivity(this)
        enableEdgeToEdge()
        setContent {
            SeekerTheme {
                SeekerVaultApp(
                    connections,
                    inbox,
                    wallet,
                    history,
                    policy,
                    globalPolicy,
                    viewModel,
                    notificationTaps,
                    feedTaps,
                    connectionLinkTaps,
                    operations,
                    startInLiveTest = intent.getBooleanExtra(EXTRA_LIVE_TEST, false),
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptNotificationTap(intent)
    }

    private fun acceptNotificationTap(intent: Intent?) {
        // Two routes, and one intent carries one action: each is validated by the object that
        // built it, and an intent that is neither — a launch, say — is left exactly as it is.
        val request = RequestNotificationIntent.destination(intent)
        val proposal = ProposalNotificationIntent.destination(intent)
        val connectionLink =
            intent
                ?.takeIf { it.action == Intent.ACTION_VIEW }
                ?.data
                ?.toString()
                ?.takeIf {
                    intent.data?.scheme.equals("seekervault", ignoreCase = true) &&
                        intent.data?.host in setOf("pair", "invite")
                }
        if (request == null && proposal == null && connectionLink == null) return
        request?.let { _notificationTaps.value = NotificationTap(++nextNotificationTap, it) }
        proposal?.let { _feedTaps.value = FeedTap(++nextNotificationTap, it) }
        connectionLink?.let {
            _connectionLinkTaps.value = ConnectionLinkTap(++nextNotificationTap, it)
        }
        // The saved Compose route survives rotation. Do not interpret the same Activity intent as
        // another owner tap when Android recreates only the screen.
        intent?.action = null
        intent?.data = null
    }

    override fun onDestroy() {
        super.onDestroy()
        (application as SeekerVaultApplication).detachWalletActivity(this)
    }

    override fun onStart() {
        super.onStart()
        (application as SeekerVaultApplication).foregroundUpdates.onForeground()
        // The feeds' own listener, which is a separate transport to a separate service and shares
        // no state with the one above (SEE-91).
        (application as SeekerVaultApplication).foregroundFeeds.onForeground()
        // And whether the publishers behind those feeds are running, which is the other question
        // and
        // the one a reachable gateway says nothing about (SEE-150).
        (application as SeekerVaultApplication).foregroundFeedStatus.onForeground()
        viewModel.onAppVisible()
        connections.onAppVisible()
        // Also after coming back from the wallet app: an approval whose answer never arrived is
        // settled here rather than left waiting (docs/testing/wallet-lifecycle.md).
        inbox.onAppVisible()
        wallet.onAppVisible()
        // The history is read again when the app comes back: an answer settled while it was away
        // changed a record.
        history.refresh()
    }

    override fun onStop() {
        super.onStop()
        // A rotation recreates the activity but keeps the ViewModels, the open stream, and the
        // fetched inbox.
        if (!isChangingConfigurations) {
            (application as SeekerVaultApplication).foregroundUpdates.onBackground()
            (application as SeekerVaultApplication).foregroundFeeds.onBackground()
            (application as SeekerVaultApplication).foregroundFeedStatus.onBackground()
            viewModel.onAppHidden()
            connections.onAppHidden()
            wallet.onAppHidden()
        }
    }

    companion object {
        /** Explicit diagnostic entry retained without adding a non-design item to Home. */
        internal const val EXTRA_LIVE_TEST = "io.github.brrenat.seekervault.extra.LIVE_TEST"
    }
}
