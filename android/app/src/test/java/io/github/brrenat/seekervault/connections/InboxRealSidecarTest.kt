package io.github.brrenat.seekervault.connections

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.connectrpc.okhttp.ConnectOkHttpClient
import io.github.brrenat.seekervault.connections.storage.ConnectionStore
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import io.github.brrenat.seekervault.connections.storage.ResultStore
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginRegistry
import io.github.brrenat.seekervault.request.v1.Acknowledgement
import io.github.brrenat.seekervault.request.v1.RequestState
import io.github.brrenat.seekervault.request.v1.requestRef
import io.github.brrenat.seekervault.request.v1.submitResultRequest
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.PluginRequirement
import io.github.brrenat.seekervault.servers.SERVER_PROTOCOL
import io.github.brrenat.seekervault.servers.ServerRecord
import io.github.brrenat.seekervault.servers.ServerReference
import io.github.brrenat.seekervault.servers.ServerSupport
import io.github.brrenat.seekervault.servers.manifest
import io.github.brrenat.seekervault.servers.serverSupport
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * The inbox against the real sidecar: a request an agent made while the app was closed is fetched
 * when the app opens, answered, and read back by the agent. Needs Node 24 and `pnpm install`.
 */
@RunWith(AndroidJUnit4::class)
class InboxRealSidecarTest {
    @get:Rule val folder = TemporaryFolder()

    private val sidecar = RealSidecar()
    private val key = softwareKey()
    private val gateway =
        ConnectConnectionGateway(
            ConnectOkHttpClient.configureClient(OkHttpClient.Builder()).build()
        )

    @After fun stop() = sidecar.close()

    private fun vault() = CredentialVault(File(folder.root, "credentials")) { key }

    // A new repository over the same files is the app starting again.
    private fun repository() =
        ConnectionRepository(
            store = ConnectionStore(File(folder.root, "connections")),
            vault = vault(),
            results = ResultStore(File(folder.root, "results")),
            gateway = gateway,
            deviceName = "Seeker",
            io = Dispatchers.IO,
        )

    private fun code(): PairingCode =
        (PairingCodes.parse(sidecar.pairingCode()) { it == "127.0.0.1" } as PairingCodeResult.Valid)
            .code

    @Test
    fun theRealSidecarDescribesItselfAsADirectServerAndThePhoneAcceptsIt() = runBlocking {
        // End to end for SEE-88: the manifest this repository's sidecar publishes passes the rules
        // the phone applies to one, and pairing is what the phone checks it against — the server
        // ID from the pairing code, the URL the owner paired, and the direct mode.
        val app = repository()
        val connection = app.pair(code())

        val manifest = checkNotNull(app.connection(connection.id)?.server?.manifest)
        assertEquals(connection.serverId, manifest.serverId)
        assertEquals(SERVER_PROTOCOL, manifest.protocolVersion)
        assertEquals(ConnectionMode.Direct, manifest.mode)
        assertEquals(ServerReference.Direct(sidecar.url), manifest.reference)
        // It needs no client plugin: an acknowledgement, a message signature and a transfer are
        // actions this app carries out itself, so any build supports this server.
        assertEquals(emptyList<PluginRequirement>(), manifest.required)
        assertEquals(setOf(PluginEnvironment.Production), manifest.environments)
        assertEquals(
            ServerSupport.Supported,
            serverSupport(
                checkNotNull(app.connection(connection.id)).server,
                PluginRegistry.bundled(),
                PluginEnvironment.Production,
            ),
        )

        // And the revision it publishes stands while its settings do, across a restart, so the
        // phone keeps what it cached rather than rewriting it.
        sidecar.restart()
        val reopened = repository()
        reopened.load()
        reopened.refresh(connection.id)

        assertEquals(
            ServerRecord.Known(manifest),
            reopened.connection(connection.id)?.server,
        )
    }

    @Test
    fun aRequestMadeWhileTheAppWasClosedIsFetchedAnsweredAndReadBack() = runBlocking {
        val connection = repository().pair(code())
        // The app is closed; the agent asks.
        val requestId = sidecar.requestAck("Deploy finished", "deploy-42")

        // The app opens and fetches, which answers nothing.
        val app = repository()
        app.load()
        app.refresh(connection.id)
        val request = app.inbox.value.pending[connection.id].orEmpty().single()
        assertEquals(requestId, request.ref.requestId)
        assertEquals("Deploy finished", request.action.ack.text)
        assertEquals("PENDING", sidecar.status(requestId))

        val result = app.answer(RequestKey(connection.id, requestId), Answer.Acknowledge)
        assertEquals(Delivery.Accepted, result.delivery)
        assertEquals(RequestState.REQUEST_STATE_COMPLETED, result.request.state)
        assertEquals("COMPLETED", sidecar.status(requestId))

        // Sent again, as after a lost response: the sidecar recognizes the repeat.
        val again =
            gateway.submitResult(
                sidecar.url,
                checkNotNull(vault().get(connection.id)),
                submitResultRequest {
                    ref = requestRef {
                        connectionId = connection.id
                        this.requestId = requestId
                    }
                    acknowledgement = Acknowledgement.getDefaultInstance()
                },
            )
        assertEquals(RequestState.REQUEST_STATE_COMPLETED, again.state)
    }

    @Test
    fun rejectsAndLearnsWhenTheAgentCancelledFirst() = runBlocking {
        val app = repository()
        val connection = app.pair(code())
        val rejected = sidecar.requestAck("Rotate the keys?", "rotate-1")
        val cancelled = sidecar.requestAck("Never mind", "never-mind-1")
        app.refresh(connection.id)
        assertEquals(2, app.inbox.value.pending[connection.id]?.size)

        app.answer(RequestKey(connection.id, rejected), Answer.Reject)
        assertEquals("REJECTED", sidecar.status(rejected))

        // The agent withdraws the other request before the owner's answer arrives.
        assertEquals("CANCELLED", sidecar.cancel(cancelled))
        val late = app.answer(RequestKey(connection.id, cancelled), Answer.Acknowledge)
        assertEquals(Delivery.Superseded, late.delivery)
        assertEquals(RequestState.REQUEST_STATE_CANCELLED, late.request.state)
        assertEquals("CANCELLED", sidecar.status(cancelled))
    }
}
