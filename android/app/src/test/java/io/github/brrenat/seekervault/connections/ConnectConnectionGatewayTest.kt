package io.github.brrenat.seekervault.connections

import com.connectrpc.okhttp.ConnectOkHttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The real network client against the real sidecar, without a device: a pairing code printed by
 * `pnpm pair` is read by the phone's parser and paired over Connect. Needs Node 24 and `pnpm
 * install`.
 */
class ConnectConnectionGatewayTest {
    private val sidecar = RealSidecar()
    private val gateway =
        ConnectConnectionGateway(
            ConnectOkHttpClient.configureClient(OkHttpClient.Builder()).build()
        )

    @After fun stop() = sidecar.close()

    /** The CLI's code, read the way the app reads a scanned one (a debug build, over loopback). */
    private fun code(): PairingCode {
        val result = PairingCodes.parse(sidecar.pairingCode()) { it == "127.0.0.1" }
        return (result as PairingCodeResult.Valid).code
    }

    private suspend fun pending(paired: PairedConnection) =
        gateway.listPending(sidecar.url, paired.credential, paired.connectionId)

    private suspend fun failure(block: suspend () -> Unit): GatewayException =
        try {
            withTimeout(30_000) { block() }
            error("expected a GatewayException")
        } catch (e: GatewayException) {
            e
        }

    @Test
    fun pairsWithTheCodeThatPnpmPairPrintsAndSeesOnlyItsOwnRequests() = runBlocking {
        val code = code()
        assertEquals(sidecar.url, code.serverUrl)
        val paired = gateway.pair(code, "Seeker")
        assertTrue(isConnectionId(paired.connectionId))
        assertTrue(isSecret(paired.credential))
        assertEquals(code.serverId, paired.serverId)
        assertEquals(PendingRequests(emptyList(), more = false), pending(paired))

        sidecar.requestAck("Deploy finished", "deploy-1")
        val requests = pending(paired).requests
        assertEquals(listOf(paired.connectionId), requests.map { it.ref.connectionId })
        assertEquals("Deploy finished", requests.single().action.ack.text)

        // A code works once.
        assertEquals(
            GatewayException.Kind.Unauthenticated,
            failure { gateway.pair(code, "Seeker") }.kind,
        )
        // The sidecar's log carries neither the pairing token nor the credential.
        assertFalse(code.token in sidecar.output)
        assertFalse(paired.credential in sidecar.output)
    }

    @Test
    fun refusesACodeUsedAtAnotherAddressAndKeepsItUsable() = runBlocking {
        val code = code()
        val elsewhere = code.copy(serverUrl = sidecar.url.replace("127.0.0.1", "localhost"))
        assertEquals(
            GatewayException.Kind.Rejected,
            failure { gateway.pair(elsewhere, "Seeker") }.kind,
        )
        assertTrue(isConnectionId(gateway.pair(code, "Seeker").connectionId))
    }

    @Test
    fun refusesACredentialRevokedByThePhoneOrByTheOperator() = runBlocking {
        val first = gateway.pair(code(), "Seeker")
        gateway.revoke(sidecar.url, first.credential, first.connectionId)
        assertEquals(GatewayException.Kind.Unauthenticated, failure { pending(first) }.kind)

        val second = gateway.pair(code(), "Seeker")
        sidecar.revokePairedPhone()
        assertEquals(GatewayException.Kind.Unauthenticated, failure { pending(second) }.kind)
    }

    @Test
    fun aNewPairingRevokesThePreviousPhone() = runBlocking {
        val first = gateway.pair(code(), "Old phone")
        val second = gateway.pair(code(), "New phone")
        assertEquals(GatewayException.Kind.Unauthenticated, failure { pending(first) }.kind)
        assertEquals(emptyList<Any>(), pending(second).requests)
    }

    @Test
    fun answersAnotherConnectionsIdWithNotFound() = runBlocking {
        val paired = gateway.pair(code(), "Seeker")
        val other = "7c6b5a49-3827-4615-a0b9-c8d7e6f5a4b3"
        assertEquals(
            GatewayException.Kind.NotFound,
            failure { gateway.listPending(sidecar.url, paired.credential, other) }.kind,
        )
    }

    @Test
    fun reportsAStoppedSidecarAsUnreachable() = runBlocking {
        val paired = gateway.pair(code(), "Seeker")
        sidecar.close()
        assertEquals(GatewayException.Kind.Unreachable, failure { pending(paired) }.kind)
    }
}
