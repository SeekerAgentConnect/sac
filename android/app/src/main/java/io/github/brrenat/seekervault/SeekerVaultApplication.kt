package io.github.brrenat.seekervault

import android.app.Application
import com.connectrpc.okhttp.ConnectOkHttpClient
import io.github.brrenat.seekervault.live.ConnectLiveCommandTransport
import io.github.brrenat.seekervault.live.LiveCommandTransportFactory
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
}
