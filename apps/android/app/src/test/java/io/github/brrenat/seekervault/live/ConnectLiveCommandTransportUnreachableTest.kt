package io.github.brrenat.seekervault.live

import com.connectrpc.okhttp.ConnectOkHttpClient
import java.net.ServerSocket
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * With nothing listening (no sidecar, or no `adb reverse` on a phone), the real transport reports
 * Unreachable, which the screen turns into its hint to start the sidecar and run `adb reverse`.
 */
class ConnectLiveCommandTransportUnreachableTest {
    @Test
    fun reportsAClosedPortAsUnreachable() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val httpClient = ConnectOkHttpClient.configureClient(OkHttpClient.Builder()).build()
        val transport =
            ConnectLiveCommandTransport("http://127.0.0.1:$port", "any-token", httpClient)
        val error =
            try {
                withTimeout(20_000) { transport.watch().first() }
                null
            } catch (e: LiveTransportException) {
                e
            }
        assertEquals(LiveTransportException.Kind.Unreachable, error?.kind)
    }
}
