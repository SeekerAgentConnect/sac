package io.github.brrenat.seekervault

import android.app.Application
import android.os.Build
import android.security.NetworkSecurityPolicy
import com.connectrpc.okhttp.ConnectOkHttpClient
import io.github.brrenat.seekervault.connections.ConnectConnectionGateway
import io.github.brrenat.seekervault.connections.ConnectionGateway
import io.github.brrenat.seekervault.connections.ConnectionRepository
import io.github.brrenat.seekervault.connections.storage.AndroidKeystoreKey
import io.github.brrenat.seekervault.connections.storage.ConnectionStore
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import io.github.brrenat.seekervault.live.ConnectLiveCommandTransport
import io.github.brrenat.seekervault.live.LiveCommandTransportFactory
import java.io.File
import javax.crypto.SecretKey
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
     * The phone's connections (docs/security.md#local-storage-and-recovery): metadata in
     * `filesDir`, and credentials, encrypted, in `noBackupFilesDir`.
     */
    val connectionRepository: ConnectionRepository by lazy {
        ConnectionRepository(
            store = ConnectionStore(File(filesDir, "connections")),
            vault = CredentialVault(File(noBackupFilesDir, "credentials")) { credentialKey() },
            gateway = connectionGateway(),
            deviceName = Build.MODEL,
        )
    }

    /** Whether this build lets plain HTTP reach [host]: debug builds allow only loopback. */
    fun isCleartextPermitted(host: String): Boolean =
        NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)
}
