package io.github.brrenat.seekervault

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.brrenat.seekervault.activity.ActivityViewModel
import io.github.brrenat.seekervault.connections.ConnectionsViewModel
import io.github.brrenat.seekervault.inbox.InboxViewModel
import io.github.brrenat.seekervault.live.LiveCommandViewModel
import io.github.brrenat.seekervault.policy.PolicyEditorViewModel
import io.github.brrenat.seekervault.wallet.WalletViewModel

class MainActivity : ComponentActivity() {
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
                ConnectionsViewModel(app.connectionRepository, app::isCleartextPermitted)
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
        // Mobile Wallet Adapter starts the wallet from an Activity, and its sender has to be
        // registered before the activity is started.
        (application as SeekerVaultApplication).attachWalletActivity(this)
        enableEdgeToEdge()
        setContent {
            SeekerVaultTheme {
                SeekerVaultApp(connections, inbox, wallet, history, policy, viewModel)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        (application as SeekerVaultApplication).detachWalletActivity(this)
    }

    override fun onStart() {
        super.onStart()
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
            viewModel.onAppHidden()
            connections.onAppHidden()
            wallet.onAppHidden()
        }
    }
}

/** Stock Material 3 with its baseline light and dark color schemes. */
@Composable
fun SeekerVaultTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
