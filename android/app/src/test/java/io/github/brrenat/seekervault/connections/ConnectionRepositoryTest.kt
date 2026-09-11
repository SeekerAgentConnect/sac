package io.github.brrenat.seekervault.connections

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.connections.storage.ConnectionStore
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import io.github.brrenat.seekervault.connections.storage.ResultStore
import java.io.File
import java.security.GeneralSecurityException
import java.time.Instant
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLog

/**
 * Connections on the phone against fake sidecars: isolation between connections, revocation,
 * re-pairing, restarts, and what reaches the disk and the log.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionRepositoryTest {
    @get:Rule val folder = TemporaryFolder()

    private val gateway = FakeConnectionGateway()
    private val serverA = gateway.serve(URL_A)
    private val serverB = gateway.serve(URL_B)
    private var key: () -> SecretKey = softwareKey().let { key -> { key } }
    private var clock = Instant.parse("2026-09-11T12:00:00Z")

    private fun repository() =
        ConnectionRepository(
            store = ConnectionStore(File(folder.root, "files/connections")),
            vault = CredentialVault(File(folder.root, "no_backup/credentials")) { key() },
            results = ResultStore(File(folder.root, "files/results")),
            gateway = gateway,
            deviceName = "Seeker",
            now = { clock },
            io = Dispatchers.Unconfined,
        )

    // Lazy: the temporary folder exists only once the rule has run.
    private val repository by lazy { repository() }

    private fun vault() = CredentialVault(File(folder.root, "no_backup/credentials")) { key() }

    private fun credentialOf(id: String) = checkNotNull(vault().get(id))

    private fun connections() = repository.connections.value

    private fun ConnectionRepository.get(id: String) = checkNotNull(connection(id))

    @Test
    fun pairsTwoServersAndKeepsTheirConnectionsApart() = runBlocking {
        val a = repository.pair(serverA.issue(URL_A))
        clock = clock.plusSeconds(1) // connections are listed oldest pairing first
        val b = repository.pair(serverB.issue(URL_B))
        assertEquals(listOf(a.id, b.id), connections().map { it.id })
        assertEquals(listOf("a.example.com", "b.example.com"), connections().map { it.label })
        assertEquals("Seeker", a.deviceName)
        serverA.addPending(a.id)
        serverA.addPending(a.id)
        repository.refresh(a.id)
        repository.refresh(b.id)
        assertEquals(2, repository.get(a.id).lastCheck?.pending)
        assertEquals(0, repository.get(b.id).lastCheck?.pending)
        // Each credential went to its own server and nowhere else.
        val credentialA = credentialOf(a.id)
        val credentialB = credentialOf(b.id)
        assertEquals(
            setOf(URL_A),
            gateway.sent.filter { it.second == credentialA }.map { it.first }.toSet(),
        )
        assertEquals(
            setOf(URL_B),
            gateway.sent.filter { it.second == credentialB }.map { it.first }.toSet(),
        )
    }

    @Test
    fun keepsConnectionsAndCredentialsAcrossARestart() = runBlocking {
        val a = repository.pair(serverA.issue(URL_A))
        repository.rename(a.id, "  Home Mac  ")
        val reopened = repository()
        reopened.load()
        val stored = reopened.get(a.id)
        assertEquals("Home Mac", stored.label)
        assertTrue(stored.usable)
        reopened.refresh(a.id)
        assertEquals(CheckOutcome.Ok, reopened.get(a.id).lastCheck?.outcome)
    }

    @Test
    fun refusesAnInvalidName() = runBlocking {
        val a = repository.pair(serverA.issue(URL_A))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.rename(a.id, " ") }
        }
        assertEquals(LabelProblem.TooLong, labelProblem("x".repeat(MAX_LABEL_LENGTH + 1)))
        assertNull(labelProblem("🙂".repeat(MAX_LABEL_LENGTH)))
        assertEquals("a.example.com", repository.get(a.id).label)
    }

    @Test
    fun marksARevokedConnectionAndNeverSendsItsCredentialAgain() = runBlocking {
        val a = repository.pair(serverA.issue(URL_A))
        val credential = credentialOf(a.id)
        serverA.revoke(a.id) // pnpm pair revoke on the sidecar
        clock = clock.plusSeconds(60)
        repository.refresh(a.id)
        val revoked = repository.get(a.id)
        assertEquals(clock, revoked.revokedAt)
        assertFalse(revoked.usable)
        assertFalse(vault().contains(a.id))
        val calls = gateway.sent.count { it.second == credential }
        repository.refresh(a.id)
        repository.disconnect(a.id) // removes it locally, without calling the sidecar
        assertEquals(calls, gateway.sent.count { it.second == credential })
        assertTrue(connections().isEmpty())
    }

    @Test
    fun aCodeForAKnownServerAtANewAddressMakesANewConnection() = runBlocking {
        val old = repository.pair(serverA.issue(URL_A))
        val oldCredential = credentialOf(old.id)
        // The same sidecar, now also reachable at another address.
        gateway.serve(URL_A_MOVED, serverA)
        val new = repository.pair(serverA.issue(URL_A_MOVED))
        assertNotEquals(old.id, new.id)
        assertEquals(URL_A, repository.get(old.id).serverUrl)
        assertEquals(URL_A_MOVED, new.serverUrl)
        // The sidecar revoked the old connection; the phone finds out at the old address.
        repository.refresh(old.id)
        assertNotNull(repository.get(old.id).revokedAt)
        assertTrue(repository.get(new.id).usable)
        assertFalse(gateway.sent.any { it.first == URL_A_MOVED && it.second == oldCredential })
    }

    @Test
    fun refusesAnUnusablePairResponseAndStoresNothing() = runBlocking {
        val tampering =
            listOf<(PairedConnection) -> PairedConnection>(
                { PairedConnection("../../shared_prefs/x", it.credential, it.serverId) },
                { PairedConnection(it.connectionId.uppercase(), it.credential, it.serverId) },
                { PairedConnection(it.connectionId, "short", it.serverId) },
                { PairedConnection(it.connectionId, "${it.credential}\r\nX: y", it.serverId) },
                { PairedConnection(it.connectionId, it.credential, serverB.serverId) },
            )
        for (tamper in tampering) {
            gateway.tamperPair = tamper
            val error = runCatching { repository.pair(serverA.issue(URL_A)) }.exceptionOrNull()
            assertEquals(GatewayException.Kind.BadResponse, (error as GatewayException).kind)
        }
        assertTrue(connections().isEmpty())
        assertEquals(emptyList<File>(), folder.root.walk().filter { it.isFile }.toList())
    }

    @Test
    fun removingOneConnectionLeavesTheOtherAndNeverGivesItTheOthersRequests() = runBlocking {
        val a = repository.pair(serverA.issue(URL_A))
        val b = repository.pair(serverB.issue(URL_B))
        val credentialB = credentialOf(b.id)
        serverA.addPending(a.id)
        serverB.addPending(b.id)
        // A sidecar that returned another connection's request: the phone ignores it.
        gateway.foreignRequests += FakeConnectionGateway.request(a.id)
        repository.refresh(a.id)
        repository.refresh(b.id)
        val before = repository.get(b.id)
        assertEquals(1, before.lastCheck?.pending)

        repository.disconnect(a.id)
        assertTrue(a.id in serverA.revoked)
        assertNull(repository.connection(a.id))
        assertFalse(vault().contains(a.id))
        assertEquals(before, repository.get(b.id))
        assertEquals(credentialB, credentialOf(b.id))
        repository.refresh(b.id)
        assertEquals(1, repository.get(b.id).lastCheck?.pending)
        assertEquals(setOf(b.id), vault().ids())
    }

    @Test
    fun keepsTheConnectionWhenItsServerCantBeTold() = runBlocking {
        val a = repository.pair(serverA.issue(URL_A))
        serverA.failure = GatewayException.Kind.Unreachable
        val error = runCatching { repository.disconnect(a.id) }.exceptionOrNull()
        assertEquals(GatewayException.Kind.Unreachable, (error as GatewayException).kind)
        assertTrue(repository.get(a.id).usable)
        repository.remove(a.id)
        assertTrue(connections().isEmpty())
        assertEquals(emptySet<String>(), vault().ids())
    }

    @Test
    fun recordsWhyARefreshFailedAndKeepsTheCredential() = runBlocking {
        val a = repository.pair(serverA.issue(URL_A))
        for ((kind, outcome) in
            listOf(
                GatewayException.Kind.Unreachable to CheckOutcome.Unreachable,
                GatewayException.Kind.CertificateRejected to CheckOutcome.CertificateRejected,
                GatewayException.Kind.CleartextBlocked to CheckOutcome.CleartextBlocked,
                GatewayException.Kind.Other to CheckOutcome.Failed,
            )) {
            serverA.failure = kind
            repository.refresh(a.id)
            assertEquals(outcome, repository.get(a.id).lastCheck?.outcome)
            assertTrue(repository.get(a.id).usable)
        }
    }

    @Test
    fun aCredentialThisPhoneCantReadMeansPairAgain() = runBlocking {
        val a = repository.pair(serverA.issue(URL_A))
        key = softwareKey().let { other -> { other } } // the Keystore lost its key
        repository.refresh(a.id)
        val stored = repository.get(a.id)
        assertFalse(stored.hasCredential)
        assertFalse(stored.usable)
        assertEquals(emptySet<String>(), vault().ids())
    }

    @Test
    fun deletesCredentialsThatNoConnectionOwns() = runBlocking {
        val orphan = "7c6b5a49-3827-4615-a0b9-c8d7e6f5a4b3"
        vault().put(orphan, newSecret())
        repository.load()
        assertEquals(emptySet<String>(), vault().ids())
    }

    @Test
    fun storesNothingWhenTheCredentialCantBeEncrypted() = runBlocking {
        key = { throw GeneralSecurityException("Keystore unavailable") }
        val error = runCatching { repository.pair(serverA.issue(URL_A)) }.exceptionOrNull()
        assertTrue(error is StorageException)
        assertTrue(connections().isEmpty())
        assertEquals(emptyList<File>(), folder.root.walk().filter { it.isFile }.toList())
    }

    @Test
    fun keepsSecretsOutOfTheLogAndTheMetadata() = runBlocking {
        val code = serverA.issue(URL_A)
        val a = repository.pair(code)
        repository.refresh(a.id)
        val credential = credentialOf(a.id)
        val logs = ShadowLog.getLogs().joinToString("\n") { "${it.tag}: ${it.msg} ${it.throwable}" }
        for (secret in listOf(code.token, credential)) {
            assertFalse(secret in logs)
            for (file in folder.root.walk().filter { it.isFile }) {
                assertFalse(file.path, secret in String(file.readBytes(), Charsets.ISO_8859_1))
            }
            assertFalse(secret in connections().toString())
        }
    }

    private companion object {
        const val URL_A = "https://a.example.com"
        const val URL_A_MOVED = "https://a-new.example.com"
        const val URL_B = "https://b.example.com"
    }
}
