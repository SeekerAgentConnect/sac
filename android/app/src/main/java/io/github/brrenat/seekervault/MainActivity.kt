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
import io.github.brrenat.seekervault.connections.ConnectionsViewModel
import io.github.brrenat.seekervault.inbox.InboxViewModel
import io.github.brrenat.seekervault.live.LiveCommandViewModel

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
                InboxViewModel((application as SeekerVaultApplication).connectionRepository)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SeekerVaultTheme { SeekerVaultApp(connections, inbox, viewModel) } }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppVisible()
    }

    override fun onStop() {
        super.onStop()
        // A rotation recreates the activity but keeps the ViewModel and its open stream.
        if (!isChangingConfigurations) viewModel.onAppHidden()
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
