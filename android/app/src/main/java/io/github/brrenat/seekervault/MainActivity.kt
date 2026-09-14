package io.github.brrenat.seekervault

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.brrenat.seekervault.activity.ActivityViewModel
import io.github.brrenat.seekervault.connections.ConnectionsViewModel
import io.github.brrenat.seekervault.inbox.InboxViewModel
import io.github.brrenat.seekervault.live.LiveCommandViewModel
import io.github.brrenat.seekervault.notifications.RequestNotificationIntent
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

    private var nextNotificationTap = 0L
    private val _notificationTaps = MutableStateFlow<NotificationTap?>(null)
    private val notificationTaps = _notificationTaps.asStateFlow()

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
                    cleartextPermitted = app::isCleartextPermitted,
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptNotificationTap(intent)
        // Mobile Wallet Adapter starts the wallet from an Activity, and its sender has to be
        // registered before the activity is started.
        (application as SeekerVaultApplication).attachWalletActivity(this)
        enableEdgeToEdge()
        setContent {
            SeekerVaultTheme {
                SeekerVaultApp(
                    connections,
                    inbox,
                    wallet,
                    history,
                    policy,
                    globalPolicy,
                    viewModel,
                    notificationTaps,
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
        val key = RequestNotificationIntent.destination(intent) ?: return
        _notificationTaps.value = NotificationTap(++nextNotificationTap, key)
        // The saved Compose route survives rotation. Do not interpret the same Activity intent as
        // another owner tap when Android recreates only the screen.
        intent?.action = null
    }

    override fun onDestroy() {
        super.onDestroy()
        (application as SeekerVaultApplication).detachWalletActivity(this)
    }

    override fun onStart() {
        super.onStart()
        (application as SeekerVaultApplication).foregroundUpdates.onForeground()
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
            viewModel.onAppHidden()
            connections.onAppHidden()
            wallet.onAppHidden()
        }
    }
}

/** Stock Material 3 defaults, selecting only the matching system light or dark scheme. */
@Composable
fun SeekerVaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize(), content = content)
    }
}
