package io.github.brrenat.seekervault

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.brrenat.seekervault.activity.ActivityViewModel
import io.github.brrenat.seekervault.connections.ConnectionsViewModel
import io.github.brrenat.seekervault.inbox.InboxViewModel
import io.github.brrenat.seekervault.live.LiveCommandViewModel
import io.github.brrenat.seekervault.policy.GlobalPolicyEditorViewModel
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
                )
            }
        }
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

@Immutable
data class SeekerExtraColors(
    val primaryText: Color,
    val dim: Color,
    val errorText: Color,
)

private val LocalSeekerExtraColors = staticCompositionLocalOf {
    SeekerExtraColors(Color.Unspecified, Color.Unspecified, Color.Unspecified)
}

object SeekerTheme {
    val colors: SeekerExtraColors
        @Composable get() = LocalSeekerExtraColors.current
}

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFFE7FC6E),
        onPrimary = Color(0xFF1B1B1B),
        primaryContainer = Color(0xFFC2E60F),
        onPrimaryContainer = Color(0xFF1B1B1B),
        tertiary = Color(0xFFFFB27A),
        onTertiary = Color(0xFF2E1200),
        tertiaryContainer = Color(0xFFFF7A1A),
        onTertiaryContainer = Color(0xFF2E1200),
        error = Color(0xFFF83959),
        onError = Color(0xFF2B0008),
        errorContainer = Color(0xFF4D0011),
        onErrorContainer = Color(0xFFFFD9DE),
        background = Color(0xFF121212),
        onBackground = Color.White,
        surface = Color(0xFF121212),
        onSurface = Color.White,
        surfaceVariant = Color(0xFF232323),
        onSurfaceVariant = Color(0xFFCACACA),
        outline = Color(0xFF6F6F6F),
        outlineVariant = Color(0xFF3A3A3A),
        surfaceContainerLowest = Color(0xFF121212),
        surfaceContainerLow = Color(0xFF1C1C1C),
        surfaceContainer = Color(0xFF1C1C1C),
        surfaceContainerHigh = Color(0xFF232323),
        surfaceContainerHighest = Color(0xFF2E2E2E),
        surfaceBright = Color(0xFF2E2E2E),
        surfaceDim = Color(0xFF0A0A0A),
        inverseSurface = Color(0xFFF7F7F7),
        inverseOnSurface = Color(0xFF1B1B1B),
        inversePrimary = Color(0xFF4F5C00),
        scrim = Color(0xFF0A0A0A),
    )

private val LightColors =
    lightColorScheme(
        primary = Color(0xFFF1FFA0),
        onPrimary = Color(0xFF1B1B1B),
        primaryContainer = Color(0xFFE9FF7A),
        onPrimaryContainer = Color(0xFF2C3400),
        tertiary = Color(0xFF8A3C00),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFFE0C2),
        onTertiaryContainer = Color(0xFF4A2600),
        error = Color(0xFFF83959),
        onError = Color(0xFF2B0008),
        errorContainer = Color(0xFFFFE1E5),
        onErrorContainer = Color(0xFF5C0014),
        background = Color(0xFFF7F7F7),
        onBackground = Color(0xFF1B1B1B),
        surface = Color(0xFFF7F7F7),
        onSurface = Color(0xFF1B1B1B),
        surfaceVariant = Color(0xFFEEEEEE),
        onSurfaceVariant = Color(0xFF45464A),
        outline = Color(0xFF76767F),
        outlineVariant = Color(0xFFC6C6C9),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFFFFFF),
        surfaceContainer = Color(0xFFFFFFFF),
        surfaceContainerHigh = Color(0xFFEEEEEE),
        surfaceContainerHighest = Color(0xFFE4E4E4),
        surfaceBright = Color(0xFFFFFFFF),
        surfaceDim = Color(0xFFCFCFD2),
        inverseSurface = Color(0xFF1C1C1C),
        inverseOnSurface = Color.White,
        inversePrimary = Color(0xFFE7FC6E),
        scrim = Color(0xFFCFCFD2),
    )

private val SeekerTypography =
    Typography(
        displaySmall =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 36.sp,
                lineHeight = 42.sp,
            ),
        headlineLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 28.sp,
                lineHeight = 34.sp,
            ),
        headlineMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 24.sp,
                lineHeight = 30.sp,
            ),
        headlineSmall =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 22.sp,
                lineHeight = 28.sp,
            ),
        titleLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 22.sp,
                lineHeight = 28.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                lineHeight = 22.sp,
            ),
        titleSmall =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 24.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
        bodySmall =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            ),
        labelLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
        labelSmall =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
    )

/** Material 3 v4 tokens from the SEE-64 design; the system selects light or dark. */
@Composable
fun SeekerVaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val dark = darkTheme
    CompositionLocalProvider(
        LocalSeekerExtraColors provides
            if (dark) {
                SeekerExtraColors(
                    primaryText = Color(0xFFE7FC6E),
                    dim = Color(0xFF0A0A0A),
                    errorText = Color(0xFFF83959),
                )
            } else {
                SeekerExtraColors(
                    primaryText = Color(0xFF4F5C00),
                    dim = Color(0xFFCFCFD2),
                    errorText = Color(0xFFC4142F),
                )
            }
    ) {
        val colors = if (dark) DarkColors else LightColors
        MaterialTheme(
            colorScheme = colors,
            typography = SeekerTypography,
            shapes =
                Shapes(
                    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                ),
        ) {
            // Raw Box/Column app bars do not infer a foreground from their background.
            // Keep their unqualified title and icon content on the scheme's surface ink.
            CompositionLocalProvider(LocalContentColor provides colors.onSurface, content = content)
        }
    }
}
