package io.github.brrenat.seekervault

import android.app.Application
import android.os.Build
import android.security.NetworkSecurityPolicy
import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import com.connectrpc.okhttp.ConnectOkHttpClient
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import io.github.brrenat.seekervault.activity.ActivityLog
import io.github.brrenat.seekervault.activity.storage.ActivityStore
import io.github.brrenat.seekervault.connections.ConnectConnectionGateway
import io.github.brrenat.seekervault.connections.ConnectionGateway
import io.github.brrenat.seekervault.connections.ConnectionRepository
import io.github.brrenat.seekervault.connections.storage.AndroidKeystoreKey
import io.github.brrenat.seekervault.connections.storage.ConnectionStore
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import io.github.brrenat.seekervault.connections.storage.ResultStore
import io.github.brrenat.seekervault.live.ConnectLiveCommandTransport
import io.github.brrenat.seekervault.live.LiveCommandTransportFactory
import io.github.brrenat.seekervault.policy.PolicyEvaluator
import io.github.brrenat.seekervault.policy.storage.PolicyStore
import io.github.brrenat.seekervault.sync.BackgroundSyncScheduler
import io.github.brrenat.seekervault.sync.ConnectUpdateTransport
import io.github.brrenat.seekervault.sync.ForegroundUpdateManager
import io.github.brrenat.seekervault.sync.UpdateTransport
import io.github.brrenat.seekervault.sync.storage.SyncStore
import io.github.brrenat.seekervault.wallet.MwaWalletAdapter
import io.github.brrenat.seekervault.wallet.WalletAdapter
import io.github.brrenat.seekervault.wallet.WalletRepository
import io.github.brrenat.seekervault.wallet.storage.WalletStore
import java.io.File
import javax.crypto.SecretKey
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
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

    /** The durable update endpoint. It shares the process HTTP client but never a credential. */
    var updateTransport: () -> UpdateTransport = { ConnectUpdateTransport(httpClient) }

    /**
     * The key that encrypts phone credentials. Tests replace it, since Robolectric has no Keystore.
     */
    var credentialKey: () -> SecretKey = AndroidKeystoreKey::get

    /**
     * The owner's own record of what this phone did (SAW-023), in `filesDir`. It outlives the
     * answers: an answer is dropped a week after it settles, and a record is not.
     */
    val activityLog: ActivityLog by lazy {
        ActivityLog(ActivityStore(File(filesDir, "activity")))
    }

    /**
     * The global rules and connection overrides the owner set (docs/policy.md#storage), in
     * `filesDir`. Nothing here is encrypted, because a policy holds no credential and no key.
     */
    val policyStore: PolicyStore by lazy { PolicyStore(File(filesDir, "policies")) }

    /**
     * What the rules make of a request (docs/policy.md#re-evaluation). It caches no policy or
     * verdict: every evaluation reads both rule documents and uses the complete Activity snapshot
     * that InboxViewModel reloaded from disk immediately beforehand. It reads, and does nothing
     * else.
     */
    val policyEvaluator: PolicyEvaluator by lazy {
        PolicyEvaluator(
            policyStore,
            // Null until the history has actually been read, and again if a read fails. A day's
            // total that nobody could read is reported as unverified rather than as nothing spent.
            records = { if (activityLog.loaded.value) activityLog.records.value else null },
            unreadableRecords = { activityLog.unreadableRecords.value },
        )
    }

    /**
     * The phone's connections and their requests (docs/security.md#local-storage-and-recovery):
     * metadata and answers in `filesDir`, and credentials, encrypted, in `noBackupFilesDir`.
     */
    val connectionRepository: ConnectionRepository by lazy {
        val repository =
            ConnectionRepository(
                store = ConnectionStore(File(filesDir, "connections")),
                vault = CredentialVault(File(noBackupFilesDir, "credentials")) { credentialKey() },
                results = ResultStore(File(filesDir, "results")),
                gateway = connectionGateway(),
                history = activityLog,
                // A connection's overrides go when it does. Global rules are a separate document.
                rules = policyStore,
                deviceName = Build.MODEL,
                io = connectionIo,
                syncStore = SyncStore(File(filesDir, "sync")),
                updateTransport = updateTransport(),
            )
        backgroundSync =
            BackgroundSyncScheduler.create(
                    context = this,
                    loaded = repository.loaded,
                    connections = repository.connections,
                    scope = CoroutineScope(SupervisorJob() + connectionIo),
                )
                .also(BackgroundSyncScheduler::start)
        repository
    }

    /** One foreground owner for every paired sidecar, independent of activities and navigation. */
    val foregroundUpdates: ForegroundUpdateManager by lazy {
        ForegroundUpdateManager(
            connections = connectionRepository.connections,
            synchronization = checkNotNull(connectionRepository.synchronization),
            dispatcher = connectionIo,
        )
    }

    /** Keeps the scheduler and its application-scoped observer alive with the storage owner. */
    private var backgroundSync: BackgroundSyncScheduler? = null

    /**
     * Where storage and network calls run — the connections' and the policy editor's alike. Tests
     * replace it, to run them in step.
     */
    var connectionIo: CoroutineDispatcher = Dispatchers.IO

    /**
     * The Mobile Wallet Adapter sender of the activity that is on screen. MWA runs the wallet from
     * an Activity, so [MainActivity] registers one in `onCreate` and clears it in `onDestroy`;
     * there is no separate wallet activity and no foreground service.
     *
     * A rotation destroys the activity and creates another, which leaves a moment with no sender at
     * all. [walletSender] waits that moment out instead of failing an approval the owner just gave
     * (SAW-017), and only the activity that registered a sender clears it, so a screen closing
     * behind a newer one can't take the newer one's sender away.
     */
    private val senders = MutableStateFlow<Pair<ComponentActivity, ActivityResultSender>?>(null)

    fun attachWalletActivity(activity: ComponentActivity) {
        senders.value = activity to ActivityResultSender(activity)
    }

    fun detachWalletActivity(activity: ComponentActivity) {
        senders.update { current -> current?.takeIf { it.first !== activity } }
    }

    /**
     * The sender on screen, waiting up to [SENDER_WAIT] for one while a screen is being replaced.
     */
    suspend fun walletSender(): ActivityResultSender? =
        withTimeoutOrNull(SENDER_WAIT.inWholeMilliseconds) {
            senders.filterNotNull().first().second
        }

    /** How the app reaches the installed wallet. Tests replace it with a fake adapter. */
    var walletAdapter: () -> WalletAdapter = {
        MwaWalletAdapter(
            ConnectionIdentity(
                identityUri = IDENTITY_URI.toUri(),
                iconUri = ICON_URI.toUri(),
                identityName = getString(R.string.app_name),
            ),
            sender = ::walletSender,
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

        /**
         * How long a wallet call waits for the next screen's sender while one is replacing another.
         */
        val SENDER_WAIT = 5.seconds
    }
}
