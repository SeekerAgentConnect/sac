package io.github.brrenat.seekervault.connections

import com.connectrpc.okhttp.ConnectOkHttpClient
import io.github.brrenat.seekervault.request.v1.pairResponse
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The phone's TLS checks (docs/security.md#transport-security), against an HTTPS server whose
 * certificate is for `localhost`. When either check fails, nothing reaches the server, the pairing
 * token included.
 */
class ConnectConnectionGatewayTlsTest {
    private val certificate =
        HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
    private val server = MockWebServer()

    // The app's client: the platform's trusted certificates and OkHttp's host name verifier.
    private val appClient = ConnectOkHttpClient.configureClient(OkHttpClient.Builder()).build()

    // A client that also trusts the test certificate, as the phone trusts a real CA's.
    private val trustingClient =
        HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build().let {
            ConnectOkHttpClient.configureClient(
                    OkHttpClient.Builder().sslSocketFactory(it.sslSocketFactory(), it.trustManager)
                )
                .build()
        }

    @Before
    fun start() {
        server.useHttps(
            HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory()
        )
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After fun stop() = server.close()

    private fun code(host: String) = PairingCode("https://$host:${server.port}", SERVER_ID, TOKEN)

    private suspend fun failure(block: suspend () -> Unit): GatewayException =
        try {
            withTimeout(30_000) { block() }
            error("expected a GatewayException")
        } catch (e: GatewayException) {
            e
        }

    @Test
    fun refusesACertificateThePhoneDoesNotTrust() = runBlocking {
        val error = failure {
            ConnectConnectionGateway(appClient).pair(code("localhost"), "Seeker")
        }
        assertEquals(GatewayException.Kind.CertificateRejected, error.kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun refusesACertificateForAnotherHostName() = runBlocking {
        // Trusted, but issued for localhost, and the phone asked for 127.0.0.1.
        val error = failure {
            ConnectConnectionGateway(trustingClient).pair(code("127.0.0.1"), "Seeker")
        }
        assertEquals(GatewayException.Kind.CertificateRejected, error.kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun pairsOverATrustedConnection() = runBlocking {
        val response = pairResponse {
            connectionId = CONNECTION_ID
            phoneToken = CREDENTIAL
            serverId = SERVER_ID
        }
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/proto")
                .body(Buffer().write(response.toByteArray()))
                .build()
        )
        val paired = ConnectConnectionGateway(trustingClient).pair(code("localhost"), "Seeker")
        assertEquals(CONNECTION_ID, paired.connectionId)
        assertEquals(CREDENTIAL, paired.credential)
        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/seekervault.request.v1.PairingService/Pair", request.url.encodedPath)
        assertEquals("Bearer $TOKEN", request.headers["Authorization"])
    }

    private companion object {
        const val SERVER_ID = "9fda5035-f3b4-4ec3-a68a-5e6caa02397a"
        const val CONNECTION_ID = "de03846e-d435-4705-b2e3-ec67da539f12"
        const val TOKEN = "Lq3v7Yk2Qm9XwTzR4bN8cJ1dH6fG0sA5eP-uV_iKoLw"
        const val CREDENTIAL = "Qm9XwTzR4bN8cJ1dH6fG0sA5eP-uV_iKoLwLq3v7Yk2"
    }
}
