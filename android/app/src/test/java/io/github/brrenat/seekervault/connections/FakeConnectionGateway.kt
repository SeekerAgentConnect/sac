package io.github.brrenat.seekervault.connections

import io.github.brrenat.seekervault.request.v1.ActionRequest
import io.github.brrenat.seekervault.request.v1.actionRequest
import io.github.brrenat.seekervault.request.v1.requestRef
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Fake sidecars keyed by URL, each with its own connections and one active phone, like the real
 * one. It records every secret it's sent together with the URL it went to, so tests can check that
 * a credential never leaves its own server.
 */
class FakeConnectionGateway : ConnectionGateway {
    class Server(val serverId: String) {
        val pairingTokens = mutableSetOf<String>()
        /** Connection ID to credential, in pairing order. */
        val connections = linkedMapOf<String, String>()
        val revoked = mutableSetOf<String>()
        val pending = mutableMapOf<String, MutableList<ActionRequest>>()
        /** When set, every call to this server fails this way. */
        var failure: GatewayException.Kind? = null

        /** A fresh pairing code for this server at [url], as `pnpm pair` shows it. */
        fun issue(url: String): PairingCode {
            val token = newSecret()
            pairingTokens += token
            return PairingCode(url, serverId, token)
        }

        fun addPending(connectionId: String, requestId: String = UUID.randomUUID().toString()) {
            pending.getOrPut(connectionId) { mutableListOf() } += request(connectionId, requestId)
        }

        /** Revokes a connection, as `pnpm pair revoke` does. */
        fun revoke(connectionId: String) {
            revoked += connectionId
            pending.remove(connectionId)
        }

        fun authenticate(credential: String): String? =
            connections.entries.firstOrNull { it.value == credential && it.key !in revoked }?.key
    }

    private val servers = mutableMapOf<String, Server>()

    /** Every call's (URL, secret): pairing tokens for Pair, credentials for the rest. */
    val sent = mutableListOf<Pair<String, String>>()

    /** Changes the next PairResponse, to test the app's checks of it. */
    var tamperPair: ((PairedConnection) -> PairedConnection)? = null

    /** Requests every ListPending also returns, as if the sidecar mixed connections up. */
    val foreignRequests = mutableListOf<ActionRequest>()

    /** A server reachable at [url]. Passing an existing [server] makes it reachable there too. */
    fun serve(url: String, server: Server = Server(UUID.randomUUID().toString())): Server {
        servers[url] = server
        return server
    }

    override suspend fun pair(code: PairingCode, deviceName: String): PairedConnection {
        val server = reach(code.serverUrl, code.token)
        if (!server.pairingTokens.remove(code.token)) {
            throw GatewayException(GatewayException.Kind.Unauthenticated, "unknown pairing token")
        }
        server.connections.keys.forEach { server.revoke(it) } // one active phone
        val paired =
            PairedConnection(UUID.randomUUID().toString(), newSecret(), server.serverId).also {
                server.connections[it.connectionId] = it.credential
            }
        return tamperPair?.let {
            tamperPair = null
            it(paired)
        } ?: paired
    }

    override suspend fun listPending(
        serverUrl: String,
        credential: String,
        connectionId: String,
    ): PendingRequests {
        val server = reach(serverUrl, credential)
        val id = authenticated(server, credential, connectionId)
        return PendingRequests(server.pending[id].orEmpty() + foreignRequests, more = false)
    }

    override suspend fun revoke(serverUrl: String, credential: String, connectionId: String) {
        val server = reach(serverUrl, credential)
        server.revoke(authenticated(server, credential, connectionId))
    }

    private fun reach(url: String, secret: String): Server {
        sent += url to secret
        val server =
            servers[url] ?: throw GatewayException(GatewayException.Kind.Unreachable, "no server")
        server.failure?.let { throw GatewayException(it, "fake $it") }
        return server
    }

    private fun authenticated(server: Server, credential: String, connectionId: String): String {
        val id =
            server.authenticate(credential)
                ?: throw GatewayException(GatewayException.Kind.Unauthenticated, "revoked")
        if (id != connectionId) throw GatewayException(GatewayException.Kind.NotFound, "no such")
        return id
    }

    companion object {
        fun request(connectionId: String, requestId: String = UUID.randomUUID().toString()) =
            actionRequest {
                ref = requestRef {
                    this.connectionId = connectionId
                    this.requestId = requestId
                }
            }
    }
}

/** A pairing token or credential: 32 random bytes in base64url, as the sidecar makes them. */
fun newSecret(): String =
    Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

/** An AES key in software, standing in for the Keystore key that Robolectric doesn't have. */
fun softwareKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
