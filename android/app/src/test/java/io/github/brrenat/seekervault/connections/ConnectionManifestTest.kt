package io.github.brrenat.seekervault.connections

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.connections.storage.ConnectionStore
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import io.github.brrenat.seekervault.connections.storage.ResultStore
import io.github.brrenat.seekervault.server.v1.ServerManifest as WireManifest
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.FeedReference
import io.github.brrenat.seekervault.servers.GATEWAY
import io.github.brrenat.seekervault.servers.ManifestProblem
import io.github.brrenat.seekervault.servers.SERVER_B
import io.github.brrenat.seekervault.servers.SERVER_PROTOCOL
import io.github.brrenat.seekervault.servers.ServerRecord
import io.github.brrenat.seekervault.servers.ServerReference
import io.github.brrenat.seekervault.servers.channelFor
import io.github.brrenat.seekervault.servers.directManifest
import io.github.brrenat.seekervault.servers.feedManifest
import io.github.brrenat.seekervault.servers.manifest
import java.io.File
import java.time.Instant
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * What the phone does with a server's manifest (SEE-88): reading one, caching it by identity and
 * revision, refusing one that would move a connection, and adding a publisher's feed through the
 * shared gateway without ever contacting the publisher.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionManifestTest {
    @get:Rule val folder = TemporaryFolder()

    private val gateway = FakeConnectionGateway()
    private val server = gateway.serve(URL, FakeConnectionGateway.Server(SERVER))
    private val feeds = FakeFeedGateway()
    private val key: () -> SecretKey = softwareKey().let { key -> { key } }
    private var clock = Instant.parse("2026-09-17T12:00:00Z")

    private fun repository(withGateway: Boolean = true) =
        ConnectionRepository(
            store = ConnectionStore(File(folder.root, "files/connections")),
            vault = CredentialVault(File(folder.root, "no_backup/credentials")) { key() },
            results = ResultStore(File(folder.root, "files/results")),
            gateway = gateway,
            feeds = if (withGateway) feeds else null,
            deviceName = "Seeker",
            now = { clock },
            io = Dispatchers.Unconfined,
        )

    private val repository by lazy { repository() }

    /** A gateway that answers with whatever a test set, and records what it was asked for. */
    private class FakeFeedGateway : FeedGateway {
        var answer: WireManifest? = null
        var failure: GatewayException.Kind? = null
        val resolved = mutableListOf<FeedReference>()

        override suspend fun resolve(reference: FeedReference): WireManifest {
            resolved += reference
            failure?.let { throw GatewayException(it, "fake $it") }
            return checkNotNull(answer) { "no manifest was published for $reference" }
        }
    }

    @Test
    fun readsTheManifestAtPairingAndKeepsItWhileTheRevisionStands() = runBlocking {
        server.manifest = directManifest(serverId = SERVER, url = URL, revision = 3)

        val connection = repository.pair(server.issue(URL))

        assertEquals(ConnectionMode.Direct, connection.mode)
        val manifest = checkNotNull(connection.server.manifest)
        assertEquals(3L, manifest.settingsRevision)
        assertEquals(ServerReference.Direct(URL), manifest.reference)
        assertEquals(SERVER_PROTOCOL, manifest.protocolVersion)
        // Reading it again while the server's settings stand leaves the phone with what it has.
        repository.refresh(connection.id)
        assertEquals(ServerRecord.Known(manifest), repository.connection(connection.id)?.server)
    }

    @Test
    fun aHigherRevisionReplacesWhatWasCachedAndALowerOneIsRefused() = runBlocking {
        server.manifest = directManifest(serverId = SERVER, url = URL, revision = 3)
        val connection = repository.pair(server.issue(URL))

        // The operator changed a setting and restarted: one step up, and the phone reads it.
        server.manifest =
            directManifest(serverId = SERVER, url = URL, revision = 4, name = "Home server")
        repository.refresh(connection.id)

        assertEquals(4L, repository.connection(connection.id)?.server?.manifest?.settingsRevision)
        assertEquals("Home server", repository.connection(connection.id)?.server?.manifest?.name)

        // A revision that went backwards would restore settings the server has moved past, so it
        // is refused rather than applied.
        server.manifest = directManifest(serverId = SERVER, url = URL, revision = 3)
        repository.refresh(connection.id)

        assertEquals(
            ServerRecord.Refused(ManifestProblem.StaleRevision),
            repository.connection(connection.id)?.server,
        )
    }

    @Test
    fun contentThatChangedWhileTheRevisionStoodStillIsAContradiction() = runBlocking {
        server.manifest = directManifest(serverId = SERVER, url = URL, revision = 3)
        val connection = repository.pair(server.issue(URL))

        // The revision is the server's promise about the content. The phone keeps neither version,
        // because it has no way to tell which one the server meant.
        server.manifest =
            directManifest(serverId = SERVER, url = URL, revision = 3, name = "Something else")
        repository.refresh(connection.id)

        assertEquals(
            ServerRecord.Refused(ManifestProblem.ChangedWithoutRevision),
            repository.connection(connection.id)?.server,
        )
    }

    @Test
    fun aServerThatPublishesNoManifestIsTheLegacyPathAndKeepsWorking() = runBlocking {
        // The default: a sidecar from before Stage 7.1 answers UNIMPLEMENTED, and the phone reads
        // that as "this server has no manifest" rather than as a failure.
        val connection = repository.pair(server.issue(URL))
        server.addPending(connection.id)
        repository.refresh(connection.id)

        assertEquals(ServerRecord.Legacy, repository.connection(connection.id)?.server)
        assertTrue(repository.get(connection.id).usable)
        assertEquals(1, repository.connection(connection.id)?.lastCheck?.pending)
    }

    @Test
    fun aManifestThatWouldMoveTheConnectionIsRecordedAndNeverFollowed() = runBlocking {
        val connection = repository.pair(server.issue(URL))
        val credential = checkNotNull(vault().get(connection.id))

        // Another server's identity, another origin, and another mode. None of them changes where
        // the credential goes: the phone records that it refused and carries on as it was.
        for (message in
            listOf(
                directManifest(serverId = SERVER_B, url = URL) to ManifestProblem.OtherServer,
                directManifest(serverId = SERVER, url = "https://elsewhere.example.com") to
                    ManifestProblem.OtherEndpoint,
                feedManifest(serverId = SERVER, required = emptyList()) to
                    ManifestProblem.OtherMode,
            )) {
            server.manifest = message.first
            repository.refresh(connection.id)

            assertEquals(
                ServerRecord.Refused(message.second),
                repository.connection(connection.id)?.server,
            )
            assertEquals(URL, repository.get(connection.id).serverUrl)
        }
        // And every call still went to the URL the owner paired, with that URL's own credential.
        assertTrue(gateway.sent.all { it.first == URL })
        assertEquals(credential, checkNotNull(vault().get(connection.id)))
    }

    @Test
    fun aServerThatCannotBeReachedLeavesWhatThePhoneLastValidated() = runBlocking {
        server.manifest = directManifest(serverId = SERVER, url = URL, revision = 2)
        val connection = repository.pair(server.issue(URL))
        val held = repository.connection(connection.id)?.server

        server.failure = GatewayException.Kind.Unreachable
        repository.refresh(connection.id)

        // Not hearing an answer is not an answer.
        assertEquals(held, repository.connection(connection.id)?.server)
    }

    @Test
    fun addsAFeedThroughItsGatewayWithoutContactingThePublisher() = runBlocking {
        feeds.answer = feedManifest(name = "Copy trading")
        val reference = FeedReference(GATEWAY, SERVER_B)

        val outcome = repository.addFeed(reference)

        val added = (outcome as FeedOutcome.Added).connection
        assertEquals(ConnectionMode.GatewayFeed, added.mode)
        assertEquals(SERVER_B, added.serverId)
        assertEquals(GATEWAY, added.serverUrl)
        assertEquals("Copy trading", added.label)
        assertEquals(
            ServerReference.Feed(GATEWAY, channelFor(SERVER_B)),
            added.server.manifest?.reference,
        )
        // The gateway was asked, and the publisher's own server was not — there is nothing in a
        // reference to contact, and nothing here holds a credential for one.
        assertEquals(listOf(reference), feeds.resolved)
        assertEquals(emptyList<Pair<String, String>>(), gateway.sent)
        assertNull(vault().get(added.id))
        assertFalse(added.hasCredential)
        // It survives a restart as the feed it is.
        assertEquals(added, repository().also { it.load() }.connection(added.id))
    }

    @Test
    fun aFeedTheGatewayDescribesBadlyAddsNothing() = runBlocking {
        // The gateway is not trusted to describe a publisher either: the channel has to be the
        // one that publisher owns, and the identity has to be the one the reference named.
        feeds.answer = feedManifest(channel = channelFor(SERVER))

        val outcome = repository.addFeed(FeedReference(GATEWAY, SERVER_B))

        assertEquals(FeedOutcome.Refused(ManifestProblem.ForeignChannel), outcome)
        assertEquals(emptyList<Connection>(), repository.connections.value)
    }

    @Test
    fun aGatewayThatCannotBeReachedAddsNothingAndSaysSo() = runBlocking {
        feeds.failure = GatewayException.Kind.Unreachable

        assertEquals(
            FeedOutcome.Failed(CheckOutcome.Unreachable),
            repository.addFeed(FeedReference(GATEWAY, SERVER_B)),
        )
        assertEquals(emptyList<Connection>(), repository.connections.value)
    }

    @Test
    fun withoutAGatewayNoFeedIsAddedAndTheAppSaysWhy() = runBlocking {
        // This build resolves no feed: the gateway that answers one is SEE-90. Saying so is the
        // honest state of it — a feed that looked added and read nothing would be worse.
        val repository = repository(withGateway = false)

        assertEquals(FeedOutcome.NoGateway, repository.addFeed(FeedReference(GATEWAY, SERVER_B)))
        assertEquals(emptyList<Connection>(), repository.connections.value)
    }

    @Test
    fun theSameFeedIsNotAddedTwice() = runBlocking {
        feeds.answer = feedManifest()
        val added = (repository.addFeed(FeedReference(GATEWAY, SERVER_B)) as FeedOutcome.Added)

        val again = repository.addFeed(FeedReference(GATEWAY, SERVER_B))

        assertEquals(FeedOutcome.Already(added.connection), again)
        assertEquals(1, repository.connections.value.size)
    }

    @Test
    fun aDirectConnectionAndAFeedAreIndependentOfEachOther() = runBlocking {
        server.manifest = directManifest(serverId = SERVER, url = URL, revision = 1)
        val direct = repository.pair(server.issue(URL))
        clock = clock.plusSeconds(1)
        feeds.answer = feedManifest(required = listOf("jupiter.prediction" to 1..1))
        val feed = (repository.addFeed(FeedReference(GATEWAY, SERVER_B)) as FeedOutcome.Added)
        val sentAfterPairing = gateway.sent.size

        // Refreshing every connection touches the sidecar and not the feed: the phone never calls
        // a publisher, and a feed's requirements say nothing about the paired server.
        server.addPending(direct.id)
        repository.connections.value.forEach { repository.refresh(it.id) }

        assertTrue(gateway.sent.drop(sentAfterPairing).all { it.first == URL })
        assertEquals(emptyList<Instant>(), listOfNotNull(feed.connection.lastCheck?.at))
        assertEquals(
            emptyList<Any>(),
            repository.connection(direct.id)?.server?.manifest?.required.orEmpty(),
        )
        assertEquals(1, repository.connection(direct.id)?.lastCheck?.pending)

        // And disconnecting one leaves the other exactly as it was.
        repository.remove(direct.id)

        assertEquals(listOf(feed.connection.id), repository.connections.value.map { it.id })
        assertEquals(feed.connection.server, repository.connection(feed.connection.id)?.server)
    }

    @Test
    fun aFeedHoldsNoSecretAnywhereOnDisk() = runBlocking {
        feeds.answer = feedManifest()
        val added = (repository.addFeed(FeedReference(GATEWAY, SERVER_B)) as FeedOutcome.Added)

        val files = folder.root.walk().filter { it.isFile }.toList()
        assertTrue(files.any { it.name == "${added.connection.id}.json" })
        // No credential file was created for it, because there is no credential: a feed is a
        // broadcast the phone subscribes to, not a server it authenticates to.
        assertNull(vault().get(added.connection.id))
    }

    private fun vault() = CredentialVault(File(folder.root, "no_backup/credentials")) { key() }

    private fun ConnectionRepository.get(id: String) = checkNotNull(connection(id))

    private companion object {
        const val URL = "https://vault.example.com"
        const val SERVER = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
    }
}
