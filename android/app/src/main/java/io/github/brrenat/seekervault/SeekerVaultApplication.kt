package io.github.brrenat.seekervault

import android.app.Application
import android.os.Build
import android.security.NetworkSecurityPolicy
import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import com.connectrpc.okhttp.ConnectOkHttpClient
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import io.github.brrenat.seekervault.connections.ConnectConnectionGateway
import io.github.brrenat.seekervault.connections.ConnectionGateway
import io.github.brrenat.seekervault.connections.ConnectionRepository
import io.github.brrenat.seekervault.connections.storage.AndroidKeystoreKey
import io.github.brrenat.seekervault.connections.storage.ConnectionStore
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import io.github.brrenat.seekervault.connections.storage.ResultStore
import io.github.brrenat.seekervault.live.ConnectLiveCommandTransport
import io.github.brrenat.seekervault.live.LiveCommandTransportFactory
import io.github.brrenat.seekervault.wallet.MwaWalletAdapter
import io.github.brrenat.seekervault.wallet.WalletAdapter
import io.github.brrenat.seekervault.wallet.WalletRepository
import io.github.brrenat.seekervault.wallet.storage.WalletStore
import java.io.File
import javax.crypto.SecretKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient

class SeekerVaultApplication : Application() {
    // One HTTP client for the whole app, without OkHttp's read timeout, so an idle
    // WatchCommands stream stays open (Connect-Kotlin enforces the RPC deadlines).
    private val httpClient by lazy {
        ConnectOkHttpClient.configureClient(OkHttpClient.Builder()).build()
    }

    /** Opens the live-test screen's connection to the sidecar. Tests replace it with a fake. */
    var liveCommandTransports = LiveCommandTransportFactory { serverUrl, phoneToken ->
        ConnectLiveCommandTransport(serverUrl, phoneToken, httpClient)
    }

    /** How connections reach their sidecars. Tests replace it before the first activity starts. */
    var connectionGateway: () -> ConnectionGateway = { ConnectConnectionGateway(httpClient) }

    /**
     * The key that encrypts phone credentials. Tests replace it, since Robolectric has no Keystore.
     */
    var credentialKey: () -> SecretKey = AndroidKeystoreKey::get

    /**
     * The phone's connections and their requests (docs/security.md#local-storage-and-recovery):
     * metadata and answers in `filesDir`, and credentials, encrypted, in `noBackupFilesDir`.
     */
    val connectionRepository: ConnectionRepository by lazy {
        ConnectionRepository(
            store = ConnectionStore(File(filesDir, "connections")),
            vault = CredentialVault(File(noBackupFilesDir, "credentials")) { credentialKey() },
            results = ResultStore(File(filesDir, "results")),
            gateway = connectionGateway(),
            deviceName = Build.MODEL,
            io = connectionIo,
        )
    }

    /** Where storage and network calls run. Tests replace it, to run them in step. */
    var connectionIo: CoroutineDispatcher = Dispatchers.IO

    /**
     * The Mobile Wallet Adapter sender of the activity that is on screen. MWA runs the wallet from
     * an Activity, so [MainActivity] registers one in `onCreate` and clears it in `onDestroy`;
     * there is no separate wallet activity and no foreground service.
     */
    private var walletSender: ActivityResultSender? = null

    fun attachWalletActivity(activity: ComponentActivity) {
        walletSender = ActivityResultSender(activity)
    }

    fun detachWalletActivity() {
        walletSender = null
    }

    /** How the app reaches the installed wallet. Tests replace it with a fake adapter. */
    var walletAdapter: () -> WalletAdapter = {
        MwaWalletAdapter(
            ConnectionIdentity(
                identityUri = IDENTITY_URI.toUri(),
                iconUri = ICON_URI.toUri(),
                identityName = getString(R.string.app_name),
            ),
            sender = { walletSender },
        )
    }

    /**
     * The wallet the owner selected (docs/guides/wallet-setup.md): the selection in `filesDir`, and
     * the wallet's authorization, encrypted, in `noBackupFilesDir`.
     */
    val walletRepository: WalletRepository by lazy {
        WalletRepository(
            store =
                WalletStore(
                    dir = File(filesDir, "wallet"),
                    secretDir = File(noBackupFilesDir, "wallet"),
                    key = { credentialKey() },
                ),
            adapter = walletAdapter(),
            connections = connectionRepository,
            io = connectionIo,
        )
    }

    /** Whether this build lets plain HTTP reach [host]: debug builds allow only loopback. */
    fun isCleartextPermitted(host: String): Boolean =
        NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)

    private companion object {
        // What the wallet shows the owner while they decide. The app has no website yet, so the
        // wallet can't verify the identity and says so; that's honest, not a claim of trust.
        const val IDENTITY_URI = "https://github.com/brrenat/SeekerAgentWallet"
        const val ICON_URI = "favicon.ico"
    }
}
