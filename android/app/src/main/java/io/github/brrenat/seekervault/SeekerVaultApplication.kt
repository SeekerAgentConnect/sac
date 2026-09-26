package io.github.brrenat.seekervault

import android.app.Application
import android.os.Build
import android.security.NetworkSecurityPolicy
import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import com.connectrpc.okhttp.ConnectOkHttpClient
import com.google.protobuf.ByteString
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import io.github.brrenat.seekervault.access.DeviceKeys
import io.github.brrenat.seekervault.access.FeedAccessApi
import io.github.brrenat.seekervault.access.FeedAccessManager
import io.github.brrenat.seekervault.access.FeedSessions
import io.github.brrenat.seekervault.access.KeystoreDeviceKeys
import io.github.brrenat.seekervault.access.OkHttpFeedAccessApi
import io.github.brrenat.seekervault.access.storage.FeedAccessStore
import io.github.brrenat.seekervault.activity.ActivityLog
import io.github.brrenat.seekervault.activity.storage.ActivityStore
import io.github.brrenat.seekervault.connections.ConnectConnectionGateway
import io.github.brrenat.seekervault.connections.ConnectionGateway
import io.github.brrenat.seekervault.connections.ConnectionRepository
import io.github.brrenat.seekervault.connections.ProposalRepository
import io.github.brrenat.seekervault.connections.storage.AndroidKeystoreKey
import io.github.brrenat.seekervault.connections.storage.ConnectionStore
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import io.github.brrenat.seekervault.connections.storage.ProposalStore
import io.github.brrenat.seekervault.connections.storage.ResultStore
import io.github.brrenat.seekervault.feeds.CentrifugoFeedStream
import io.github.brrenat.seekervault.feeds.ConnectFeedGateway
import io.github.brrenat.seekervault.feeds.FeedStatusManager
import io.github.brrenat.seekervault.feeds.FeedStream
import io.github.brrenat.seekervault.feeds.ForegroundFeedManager
import io.github.brrenat.seekervault.feeds.RepositoryFeedHost
import io.github.brrenat.seekervault.feeds.storage.FeedCursorStore
import io.github.brrenat.seekervault.jupiter.HttpJupiterPrediction
import io.github.brrenat.seekervault.jupiter.HttpJupiterProvider
import io.github.brrenat.seekervault.jupiter.JupiterExecutionProvider
import io.github.brrenat.seekervault.live.ConnectLiveCommandTransport
import io.github.brrenat.seekervault.live.LiveCommandTransportFactory
import io.github.brrenat.seekervault.notifications.ProposalNotificationManager
import io.github.brrenat.seekervault.notifications.ProposalRef
import io.github.brrenat.seekervault.notifications.RequestNotificationManager
import io.github.brrenat.seekervault.plugins.ProviderRegistry
import io.github.brrenat.seekervault.policy.PolicyEvaluator
import io.github.brrenat.seekervault.policy.storage.PolicyStore
import io.github.brrenat.seekervault.proposals.ProposalStanding
import io.github.brrenat.seekervault.push.FcmRegistrationClient
import io.github.brrenat.seekervault.push.FcmRegistrationManager
import io.github.brrenat.seekervault.push.FeedTopicClient
import io.github.brrenat.seekervault.push.FeedTopicManager
import io.github.brrenat.seekervault.push.FirebaseFcmRegistrationClient
import io.github.brrenat.seekervault.push.FirebaseFeedTopicClient
import io.github.brrenat.seekervault.push.HttpRelayClient
import io.github.brrenat.seekervault.push.RelayClient
import io.github.brrenat.seekervault.push.RelayRegistrationManager
import io.github.brrenat.seekervault.push.storage.RelayStore
import io.github.brrenat.seekervault.solana.HttpSolanaAccounts
import io.github.brrenat.seekervault.solana.SolanaAccounts
import io.github.brrenat.seekervault.sync.BackgroundSyncScheduler
import io.github.brrenat.seekervault.sync.ConnectUpdateTransport
import io.github.brrenat.seekervault.sync.FeedSyncRunner
import io.github.brrenat.seekervault.sync.FeedSyncScheduler
import io.github.brrenat.seekervault.sync.ForegroundUpdateManager
import io.github.brrenat.seekervault.sync.UpdateTransport
import io.github.brrenat.seekervault.sync.storage.SyncStore
import io.github.brrenat.seekervault.wallet.MwaWalletAdapter
import io.github.brrenat.seekervault.wallet.PackageWalletTargets
import io.github.brrenat.seekervault.wallet.WalletAdapter
import io.github.brrenat.seekervault.wallet.WalletIntentSender
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

class SeekerVaultApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        requestNotifications.createChannels()
        // The feeds' own channel, so the owner can silence one kind of alert and keep the other
        // (SEE-92).
        proposalNotifications.createChannels()
    }

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

    /** The optional Firebase registration client. Tests replace it without configuring Firebase. */
    var fcmRegistrationClient: () -> FcmRegistrationClient = {
        FirebaseFcmRegistrationClient(this)
    }

    /**
     * The optional Firebase topic client (SEE-92). Tests replace it too: joining a feed's public
     * topic is the only thing this app asks Firebase for besides its own registration.
     */
    var feedTopicClient: () -> FeedTopicClient = { FirebaseFeedTopicClient(this) }

    /**
     * How this phone talks to the gateway push relay (SEE-144). It reaches no Firebase API of its
     * own — what it carries is the registration Firebase already handed this app — so a test
     * replaces it with an ordinary fake and nothing here needs a project.
     */
    var relayClient: () -> RelayClient = { HttpRelayClient(httpClient) }

    /**
     * A restricted feed's authentication endpoint (SEE-156). The one publisher-operated address
     * this app ever posts to, and only the one the gateway stamped on that feed's manifest.
     */
    var feedAccessApi: () -> FeedAccessApi = { OkHttpFeedAccessApi(httpClient) }

    /**
     * The per-feed device keys a restricted feed's approval is bound to. Tests replace them, since
     * Robolectric has no Keystore — the same reason [credentialKey] is replaceable.
     */
    var deviceKeys: () -> DeviceKeys = { KeystoreDeviceKeys() }

    /**
     * The relay this build was configured to trust, or "" for a build with none. It is read here
     * rather than by the manager so a test can point one at a local server without a rebuild.
     */
    var relayUrl: () -> String = { BuildConfig.RELAY_URL }

    /**
     * User-visible alerts exist only in an APK built with an operator-supplied Firebase project.
     * The manager holds no Firebase dependency or request data and permission denial is a no-op.
     */
    val requestNotifications: RequestNotificationManager by lazy {
        RequestNotificationManager(this, BuildConfig.FIREBASE_CONFIGURED)
    }

    /**
     * The same, for the proposals a feed is waiting on (SEE-92). A separate manager and a separate
     * channel, because a broadcast and a paired sidecar's request are different things and the
     * owner may reasonably want to hear about one and not the other.
     */
    val proposalNotifications: ProposalNotificationManager by lazy {
        ProposalNotificationManager(this, BuildConfig.FIREBASE_CONFIGURED)
    }

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
     * The bundled client plugins this build carries (SEE-86, docs/wiki/client-plugins.md).
     *
     * This is the build's own list, and it is here rather than in `plugins/` because a real plugin
     * needs something built: `jupiter.swap` reaches a provider of its own, over the same HTTP
     * client everything else in this process uses (SEE-93). Nothing adds to the list at runtime and
     * nothing is downloaded; a plugin a server requires and this build doesn't have is reported as
     * missing rather than fetched. Tests replace it before the first activity starts, to exercise
     * the boundary without reaching a provider.
     */
    var providers: () -> ProviderRegistry = {
        ProviderRegistry.of(
            JupiterExecutionProvider(
                swapApi = HttpJupiterProvider(httpClient),
                predictionApi = HttpJupiterPrediction(httpClient),
                chain = solanaAccounts(),
            )
        )
    }

    /**
     * Where the app reads accounts from the chain (SEE-94).
     *
     * It exists for one purpose — resolving the address lookup tables a prediction order's
     * transaction names, without which the phone cannot see what it would be signing — and it is
     * **the application's endpoint, never a publisher's**: nothing in a manifest, a proposal or a
     * provider's answer can set it. A build with none configured prepares no order and says so
     * (`BuildConfig.SOLANA_RPC`, docs/wiki/jupiter-prediction.md#why-the-phone-reads-the-chain).
     */
    var solanaAccounts: () -> SolanaAccounts = {
        HttpSolanaAccounts(httpClient, BuildConfig.SOLANA_RPC)
    }

    /** One registry for the process, so every screen resolves an operation the same way. */
    val providerRegistry: ProviderRegistry by lazy { providers() }

    /**
     * The proposals the phone read from publishers' feeds (SEE-89,
     * docs/wiki/shared-proposals.md#where-it-is-kept), in `filesDir`: the publisher's own documents
     * and this device's decisions about them. Nothing here is encrypted, because a proposal is a
     * broadcast anyone subscribed can read and this device's half is its own record of public
     * facts.
     */
    val proposalStore: ProposalStore by lazy { ProposalStore(File(filesDir, "proposals")) }

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
                // And so do the proposals a feed read (SEE-89). The owner's Activity outlives both.
                proposals = proposalStore,
                // A feed's settings are resolved through the shared feed gateway, never by
                // contacting the publisher's own server (SEE-88, SEE-90).
                feeds = feedGateway(),
                deviceName = Build.MODEL,
                io = connectionIo,
                syncStore = SyncStore(File(filesDir, "sync")),
                updateTransport = updateTransport(),
                // A connection that is gone takes its alerts with it, of both kinds: a feed the
                // owner removed must not leave a proposal alert behind (SEE-92).
                onConnectionUnavailable = { id ->
                    requestNotifications.cancelConnection(id)
                    proposalNotifications.cancelConnection(id)
                },
                // A restricted feed the owner removed takes its access with it: the record, the
                // session and the device key the publisher's approval was bound to (SEE-156).
                onConnectionRemoved = { id -> feedAccessManager?.forget(id) },
            )
        backgroundSync =
            BackgroundSyncScheduler.create(
                    context = this,
                    loaded = repository.loaded,
                    connections = repository.connections,
                    scope = CoroutineScope(SupervisorJob() + connectionIo),
                )
                .also(BackgroundSyncScheduler::start)
        fcmRegistration =
            FcmRegistrationManager(
                    loaded = repository.loaded,
                    connections = repository.connections,
                    client = fcmRegistrationClient(),
                    loadConnections = repository::load,
                    publish = { id, update -> repository.setFcmToken(id, update) },
                    dispatcher = connectionIo,
                )
                .also(FcmRegistrationManager::start)
        feedTopicManager =
            FeedTopicManager(
                    loaded = repository.loaded,
                    connections = repository.connections,
                    // The same gateway client the feeds are read through: asking where hints
                    // arrive is one more read on that endpoint (SEE-92).
                    gateway = feedGateway(),
                    client = feedTopicClient(),
                    loadConnections = repository::load,
                    dispatcher = connectionIo,
                )
                .also(FeedTopicManager::start)
        relayRegistration =
            RelayRegistrationManager(
                    relayUrl = relayUrl(),
                    loaded = repository.loaded,
                    connections = repository.connections,
                    client = relayClient(),
                    // The installation secret is sealed with the same Keystore key a phone
                    // credential is, and in noBackupFilesDir for the same reason: it proves this
                    // device is this installation, and a restored backup is a different device.
                    store = RelayStore(File(noBackupFilesDir, "relay")) { credentialKey() },
                    loadConnections = repository::load,
                    coordinates = repository::relayCoordinates,
                    publish = { id, update -> repository.setRelayHandle(id, update) },
                    dispatcher = connectionIo,
                )
                .also(RelayRegistrationManager::start)
        feedAccess =
            FeedAccessManager(
                connections = { repository.connections.value },
                // What this phone asked for and where it stands. No secret is in it: the device
                // key is in the Keystore and the session is sealed beside a phone credential,
                // and both are out of backups because a restored backup is another device.
                store = FeedAccessStore(File(noBackupFilesDir, "feed-access")),
                sessions =
                    CredentialVault(File(noBackupFilesDir, "feed-sessions")) { credentialKey() },
                keys = deviceKeys(),
                api = feedAccessApi(),
                wallet = { walletRepository.wallet.value },
                // The one wallet signature in the whole flow, over text that says in its own
                // words that it is not a transaction (FeedAccessProof).
                sign = { message, reviewed ->
                    walletRepository.sign(ByteString.copyFrom(message.toByteArray()), reviewed)
                },
                // The owner's own phone, as a label for the admin's list. It is a claim and
                // shown as one: what proves the device is the key, not this.
                label = { Build.MODEL },
                pushTarget = { gatewayUrl, channel, session, target ->
                    feedGateway().setPushTarget(gatewayUrl, channel, session, target)
                },
                // Newly readable: read it now rather than at the next foreground pass.
                onConnected = { FeedSyncScheduler.enqueue(this@SeekerVaultApplication) },
                io = connectionIo,
            )
        CoroutineScope(SupervisorJob() + connectionIo).launch {
            checkNotNull(feedAccess).load()
        }
        repository
    }

    /**
     * What this phone holds about publishers' proposals, and everything the owner does about one
     * (SEE-89, docs/wiki/shared-proposals.md).
     *
     * It reads a feed through the gateway now (SEE-91). The plugins that read a proposal's terms
     * and prepare its bytes are SEE-93 and SEE-94, and there is still no screen that lists
     * proposals, so what this holds is read by the tests and by the listener rather than by a
     * person — for one more stage.
     */
    val proposalRepository: ProposalRepository by lazy {
        ProposalRepository(
            store = proposalStore,
            connections = { connectionRepository.connections.value },
            plugins = providerRegistry,
            // The same gateway the settings come from: one endpoint, one client (SEE-91).
            feed = feedGateway(),
            history = activityLog,
            io = connectionIo,
        )
    }

    /** Registration callbacks can start the process, so this getter also initializes the owner. */
    val fcmRegistrations: FcmRegistrationManager
        get() {
            connectionRepository
            return checkNotNull(fcmRegistration)
        }

    /** The same for a hint's topic, and for the same reason: a message can start the process. */
    val feedTopics: FeedTopicManager
        get() {
            connectionRepository
            return checkNotNull(feedTopicManager)
        }

    /**
     * And for the relay's enrollment, which a registration callback is also the moment to renew.
     */
    val relayRegistrations: RelayRegistrationManager
        get() {
            connectionRepository
            return checkNotNull(relayRegistration)
        }

    /**
     * Restricted-feed access (SEE-156). A registration callback reaches it too, to tell every
     * connected restricted feed where this device's hints now go.
     */
    val feedAccessManager: FeedAccessManager
        get() {
            connectionRepository
            return checkNotNull(feedAccess)
        }

    /**
     * The authoritative read a feed hint asks for (SEE-92), assembled here because this is where
     * everything it needs already lives: the connections, the cursors, the repositories' own apply
     * path, and what the owner is currently shown.
     *
     * It is deliberately the same documents and the same validators the foreground listener uses. A
     * hint changes when the app reads a feed, and nothing else about how it reads one.
     */
    internal fun feedReadRunner(): FeedSyncRunner {
        val host = RepositoryFeedHost(connectionRepository, proposalRepository)
        return FeedSyncRunner(
            load = {
                connectionRepository.load()
                proposalRepository.load()
                // Before any read: a restricted feed read without its session is refused, and a
                // background pass that raced the load would record a denial the gateway never
                // meant (SEE-156).
                feedAccessManager.load()
            },
            connections = { connectionRepository.connections.value },
            foreground = { foregroundFeeds.state.value },
            progress = { serverId -> feedCursors.get(serverId)?.sequence ?: 0L },
            read = { connectionId, known -> host.readFeed(connectionId, known) },
            remember = { serverId, sequence ->
                val held = feedCursors.get(serverId)
                feedCursors.put(FeedCursorStore.Progress(serverId, held?.cursor, sequence))
            },
            reviewable = ::reviewableProposals,
            reconcileNotifications = { before, after ->
                proposalNotifications.reconcile(
                    before,
                    after,
                    connectionRepository.connections.value,
                    proposalRepository.proposals.value,
                )
            },
        )
    }

    /**
     * The proposals waiting for the owner right now: the publisher stands behind them, this device
     * has done nothing about them, and this build can act on them.
     *
     * Everything that makes a proposal stop waiting — a withdrawal, an expiry, a dismissal here, an
     * operation already begun — takes it out of this set, which is what makes the notification
     * reconciliation in one place enough (`proposals/ProposalState.proposalStanding`).
     */
    private fun reviewableProposals(): Set<ProposalRef> =
        proposalRepository.proposals.value
            .filter { proposalRepository.standing(it) is ProposalStanding.Open }
            .mapTo(mutableSetOf()) { ProposalRef(it.connectionId, it.key.proposalId) }

    /** One foreground owner for every paired sidecar, independent of activities and navigation. */
    val foregroundUpdates: ForegroundUpdateManager by lazy {
        ForegroundUpdateManager(
            connections = connectionRepository.connections,
            synchronization = checkNotNull(connectionRepository.synchronization),
            dispatcher = connectionIo,
        )
    }

    /**
     * The feed gateway a feed is read from, and the stream it is listened to on (SEE-91). Both are
     * replaced in tests, which is why they are factories rather than singletons.
     */
    var feeds: () -> ConnectFeedGateway = {
        // The supplier is deliberate: the access manager registers this device's push target
        // through this same client, so neither can be constructed before the other (SEE-156).
        // Until the manager exists, no channel has a session, which is what a public feed is.
        ConnectFeedGateway(httpClient) { feedAccess ?: FeedSessions.None }
    }

    var feedStream: () -> FeedStream = { CentrifugoFeedStream(httpClient) }

    /**
     * One client for both feed seams, so a feed's settings and its proposals share a connection.
     */
    private val feedGateway: () -> ConnectFeedGateway by lazy {
        val resolved = feeds()
        ({ resolved })
    }

    /**
     * Where each feed's listener left off, in `filesDir`. It holds progress and no content: losing
     * it costs one snapshot (SEE-91).
     */
    val feedCursors: FeedCursorStore by lazy { FeedCursorStore(File(filesDir, "feeds")) }

    /**
     * One listener per gateway, for as long as the app is being looked at (SEE-91).
     *
     * There is no background worker beside it and no permanent connection: a shared feed has
     * nothing to deliver to a phone nobody is holding, and what a phone needs after a while away is
     * a snapshot. The direct path's own background sync is unaffected — it is about a paired
     * sidecar's requests, which do wait for an owner.
     */
    val foregroundFeeds: ForegroundFeedManager by lazy {
        ForegroundFeedManager(
            connections = connectionRepository.connections,
            host = RepositoryFeedHost(connectionRepository, proposalRepository),
            tickets = feedGateway(),
            stream = feedStream(),
            cursors = feedCursors,
            dispatcher = connectionIo,
        )
    }

    /**
     * Whether the publisher behind each feed is running, for as long as the app is being looked at
     * (SEE-150).
     *
     * Beside [foregroundFeeds] rather than inside it, because it answers the other question: that
     * one is whether this phone reaches the gateway, this one is whether the server behind a
     * channel is up, and the bug was treating the first as evidence for the second. It reads the
     * same gateway client, because it is the same endpoint.
     */
    val foregroundFeedStatus: FeedStatusManager by lazy {
        FeedStatusManager(
            connections = connectionRepository.connections,
            statuses = feedGateway(),
            dispatcher = connectionIo,
        )
    }

    /** Keeps the scheduler and its application-scoped observer alive with the storage owner. */
    private var backgroundSync: BackgroundSyncScheduler? = null

    /** Keeps the serialized registration owner alive with the application. */
    private var fcmRegistration: FcmRegistrationManager? = null

    /** And the one that keeps this phone's topic subscriptions in line with its feeds (SEE-92). */
    private var feedTopicManager: FeedTopicManager? = null

    /** The gateway relay's lifecycle (SEE-144), built beside the other two and for one reason. */
    private var relayRegistration: RelayRegistrationManager? = null

    /**
     * Restricted-feed access (SEE-156). Read directly rather than through [feedAccessManager] by
     * the gateway client's session supplier, because that runs while this block is still building.
     */
    private var feedAccess: FeedAccessManager? = null

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
    private val senders = MutableStateFlow<Pair<ComponentActivity, WalletIntentSender>?>(null)

    fun attachWalletActivity(activity: ComponentActivity) {
        senders.value = activity to WalletIntentSender(activity)
    }

    fun detachWalletActivity(activity: ComponentActivity) {
        senders.update { current -> current?.takeIf { it.first !== activity } }
    }

    /**
     * The sender on screen, waiting up to [SENDER_WAIT] for one while a screen is being replaced.
     */
    suspend fun walletSender(): WalletIntentSender? =
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
            // The wallet apps this phone has, so a signing opens the one the owner connected
            // instead of asking Android which of them to open (SEE-159).
            targets = PackageWalletTargets(packageManager),
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
