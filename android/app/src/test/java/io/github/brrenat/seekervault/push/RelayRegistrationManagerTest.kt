package io.github.brrenat.seekervault.push

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.push.storage.RelayStore
import java.nio.file.Files
import java.time.Instant
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The phone's half of the gateway push relay (SEE-144).
 *
 * What is asserted here is the lifecycle rather than the protocol: which servers this phone
 * authorizes, what it does when it cannot reach the gateway, what it does when the gateway has
 * forgotten it, and what it never does — which is register with a relay a server named.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class RelayRegistrationManagerTest {

    /**
     * The rule the whole design rests on, at the surface a hostile server would actually reach.
     *
     * A server advertises a relay of its own. The phone is configured with a different one, so it
     * ignores the advertisement entirely: it does not enroll there, it does not authorize anything,
     * and its Firebase registration never leaves the gateway it was built to trust.
     */
    @Test
    fun aServerCannotPointThisPhoneAtARelayOfItsOwn() = runTest {
        val relay = FakeRelay()
        val one = fixture(relay)
        one.advertised[A] = RelayCoordinates("https://not-our-gateway.example.com", SERVER)
        one.manager.start()
        one.manager.onRegistered(TARGET)
        advanceUntilIdle()

        // It enrolled with its own gateway — that is unconditional and harmless — and authorized
        // nothing, because no connection advertised a relay it trusts.
        assertEquals(listOf(GATEWAY), relay.enrolledWith)
        assertTrue(relay.bound.isEmpty())
        assertTrue(one.published.isEmpty())
        one.manager.close()
    }

    /**
     * The ordinary path: a server that advertises this phone's own gateway is authorized once, for
     * the one connection it is paired over, and the handle goes to that server and nowhere else.
     */
    @Test
    fun authorizesOneServerPerConnectionAndHandsItTheHandle() = runTest {
        val relay = FakeRelay()
        val one = fixture(relay)
        one.advertised[A] = RelayCoordinates(GATEWAY, SERVER)
        one.advertised[B] = RelayCoordinates(GATEWAY, OTHER_SERVER)
        one.manager.start()
        one.manager.onRegistered(TARGET)
        advanceUntilIdle()

        assertEquals(setOf(A to SERVER, B to OTHER_SERVER), relay.bound.keys.toSet())
        // Each handle went to the connection its binding was made for, over that connection's own
        // authenticated channel.
        assertEquals(setOf(A, B), one.published.map { it.first }.toSet())
        for ((connectionId, update) in one.published) {
            assertTrue(update is RelayHandleUpdate.Register)
            assertEquals(
                relay.bound.getValue(connectionId to relay.serverOf(connectionId)).handle,
                update.handle,
            )
        }
        // A second pass over an unchanged world authorizes nothing again: the phone remembers
        // which connection holds which authorization, and re-authorizing would leave the old one
        // live at the gateway for no reason.
        val before = relay.bindCalls
        one.manager.onRegistered(TARGET)
        advanceUntilIdle()
        assertEquals(before, relay.bindCalls)
        one.manager.close()
    }

    /** A rotation reaches the gateway even though nothing else changed. */
    @Test
    fun aRotatedRegistrationIsGivenToTheGateway() = runTest {
        val relay = FakeRelay()
        val one = fixture(relay)
        one.advertised[A] = RelayCoordinates(GATEWAY, SERVER)
        one.manager.start()
        one.manager.onRegistered(TARGET)
        advanceUntilIdle()
        assertEquals(TARGET, relay.target)

        one.manager.onRegistered(ROTATED)
        advanceUntilIdle()
        assertEquals(ROTATED, relay.target)
        // And the authorization it already made is untouched: the target moved, not the binding.
        assertEquals(1, relay.bindCalls)
        one.manager.close()
    }

    /**
     * A connection the owner removed is revoked at the gateway, and a revocation that could not be
     * made is kept as work rather than dropped — because a disconnection must be final from the
     * owner's point of view even when it happened on a train.
     */
    @Test
    fun aRemovedConnectionIsRevokedAndARevocationThatFailedIsRetried() = runTest {
        val relay = FakeRelay()
        val one = fixture(relay)
        one.advertised[A] = RelayCoordinates(GATEWAY, SERVER)
        one.manager.start()
        one.manager.onRegistered(TARGET)
        advanceUntilIdle()
        val binding = relay.bound.getValue(A to SERVER).binding

        // The revocation itself fails, which is the case this is about: the gateway is reachable,
        // and the one call that would end the authorization does not land.
        relay.refuseUnbind = true
        one.connections.value = emptyList()
        advanceUntilIdle()
        assertTrue(binding in one.store.read().owed)
        assertTrue(relay.revoked.isEmpty())

        relay.refuseUnbind = false
        one.manager.onRegistered(TARGET)
        advanceUntilIdle()
        assertEquals(listOf(binding), relay.revoked)
        assertTrue(one.store.read().owed.isEmpty())
        one.manager.close()
    }

    /**
     * A gateway that lost its database is recoverable without re-pairing anything: the phone is
     * told its enrollment is gone, enrolls again, and re-authorizes the connections it holds. The
     * direct connections were never the gateway's and are untouched.
     */
    @Test
    fun aGatewayThatForgotThisPhoneIsReEnrolledWithoutRePairing() = runTest {
        val relay = FakeRelay()
        val one = fixture(relay)
        one.advertised[A] = RelayCoordinates(GATEWAY, SERVER)
        one.manager.start()
        one.manager.onRegistered(TARGET)
        advanceUntilIdle()
        val first = checkNotNull(one.store.read().enrollment).installation

        relay.forget()
        one.manager.onRegistered(TARGET)
        advanceUntilIdle()
        // Two passes: one to discover the enrollment is gone, one to make a new one.
        one.manager.onRegistered(TARGET)
        advanceUntilIdle()

        val second = checkNotNull(one.store.read().enrollment).installation
        assertNotEquals(first, second)
        assertEquals(setOf(A to SERVER), relay.bound.keys.toSet())
        // The connection itself never changed, which is the whole point: nobody re-paired.
        assertTrue(A in one.connections.value.map(Connection::id))
        one.manager.close()
    }

    /** A gateway that cannot be reached at all leaves this phone exactly as it was. */
    @Test
    fun anUnreachableGatewayChangesNothing() = runTest {
        val relay = FakeRelay()
        relay.offline = true
        val one = fixture(relay)
        one.advertised[A] = RelayCoordinates(GATEWAY, SERVER)
        one.manager.start()
        one.manager.onRegistered(TARGET)
        advanceUntilIdle()

        assertNull(one.store.read().enrollment)
        assertTrue(one.published.isEmpty())
        // And it recovers on its own when the gateway comes back, without the owner doing anything.
        relay.offline = false
        one.manager.onRegistered(TARGET)
        advanceUntilIdle()
        assertEquals(setOf(A to SERVER), relay.bound.keys.toSet())
        one.manager.close()
    }

    /** A build with no relay configured does nothing at all, and says so. */
    @Test
    fun aBuildWithNoRelayDoesNothing() = runTest {
        val relay = FakeRelay()
        val one = fixture(relay, relayUrl = "")
        one.advertised[A] = RelayCoordinates(GATEWAY, SERVER)
        one.manager.start()
        one.manager.onRegistered(TARGET)
        advanceUntilIdle()

        assertEquals(false, one.manager.configured)
        assertTrue(relay.enrolledWith.isEmpty())
        assertNull(one.store.read().enrollment)
        one.manager.close()
    }

    /**
     * A server that would not take the handle gets no live authorization left behind: the binding
     * is revoked rather than kept, because a handle nobody holds is an authorization nobody can use
     * and everybody has to expire.
     */
    @Test
    fun anAuthorizationTheServerWouldNotTakeIsRevokedAgain() = runTest {
        val relay = FakeRelay()
        val one = fixture(relay)
        one.advertised[A] = RelayCoordinates(GATEWAY, SERVER)
        one.refusePublish = true
        one.manager.start()
        one.manager.onRegistered(TARGET)
        advanceUntilIdle()

        // Every authorization it made was given back, so nothing live is left at the gateway. It
        // tried more than once, because a server that will not take a handle is exactly the kind
        // of failure that is usually temporary.
        assertTrue(relay.bindCalls > 0)
        assertEquals(relay.bindCalls, relay.revoked.size)
        assertTrue(relay.bound.isEmpty())
        assertTrue(one.store.read().bindings.isEmpty())
        one.manager.close()
    }

    // --- the fixture -------------------------------------------------------------

    private class Fixture(
        val manager: RelayRegistrationManager,
        val connections: MutableStateFlow<List<Connection>>,
        val store: RelayStore,
        val advertised: MutableMap<String, RelayCoordinates>,
        val published: MutableList<Pair<String, RelayHandleUpdate>>,
    ) {
        var refusePublish = false
    }

    private fun TestScope.fixture(relay: FakeRelay, relayUrl: String = GATEWAY): Fixture {
        val connections = MutableStateFlow(listOf(connection(A), connection(B)))
        val advertised = mutableMapOf<String, RelayCoordinates>()
        val published = mutableListOf<Pair<String, RelayHandleUpdate>>()
        val store = RelayStore(Files.createTempDirectory("relay").toFile()) { KEY }
        lateinit var fixture: Fixture
        val manager =
            RelayRegistrationManager(
                relayUrl = relayUrl,
                loaded = MutableStateFlow(true),
                connections = connections,
                client = relay,
                store = store,
                loadConnections = {},
                coordinates = { advertised[it] },
                publish = { id, update ->
                    if (fixture.refusePublish) false
                    else {
                        published += id to update
                        true
                    }
                },
                dispatcher = StandardTestDispatcher(testScheduler),
            )
        fixture = Fixture(manager, connections, store, advertised, published)
        return fixture
    }

    private fun connection(id: String) =
        Connection(
            id = id,
            label = id,
            serverUrl = "https://$id.example.com",
            serverId = id,
            deviceName = "Seeker",
            pairedAt = Instant.EPOCH,
        )

    /** A gateway in memory: it enrolls, retargets, binds and revokes, and can be switched off. */
    private class FakeRelay : RelayClient {
        val enrolledWith = mutableListOf<String>()
        val bound = mutableMapOf<Pair<String, String>, RelayBinding>()
        val revoked = mutableListOf<String>()
        var target: String? = null
        var offline = false
        /** Only the revocation fails. It is the one call whose failure has to be remembered. */
        var refuseUnbind = false
        var bindCalls = 0
        private var installation: RelayEnrollment? = null
        private var issued = 0

        fun forget() {
            installation = null
            bound.clear()
            target = null
        }

        fun serverOf(connectionId: String): String =
            bound.keys.first { it.first == connectionId }.second

        override suspend fun enroll(relayUrl: String, target: String): RelayEnrollment {
            if (offline) throw RelayException(gone = false, message = "offline")
            enrolledWith += relayUrl
            issued += 1
            this.target = target
            val fresh = RelayEnrollment("installation-$issued", "secret-$issued")
            installation = fresh
            return fresh
        }

        override suspend fun setTarget(
            relayUrl: String,
            installation: RelayEnrollment,
            target: String,
        ) {
            held(installation)
            this.target = target
        }

        override suspend fun read(
            relayUrl: String,
            installation: RelayEnrollment,
        ): RelayInstallation? {
            held(installation)
            return RelayInstallation(target != null, emptyList())
        }

        override suspend fun bind(
            relayUrl: String,
            installation: RelayEnrollment,
            serverId: String,
            connectionId: String,
        ): RelayBinding {
            held(installation)
            bindCalls += 1
            val issuedBinding =
                RelayBinding("binding-$bindCalls", "handle-$connectionId-$bindCalls")
            bound[connectionId to serverId] = issuedBinding
            return issuedBinding
        }

        override suspend fun unbind(
            relayUrl: String,
            installation: RelayEnrollment,
            bindingId: String,
        ) {
            held(installation)
            if (refuseUnbind) throw RelayException(gone = false, message = "offline")
            revoked += bindingId
            bound.entries.removeIf { it.value.binding == bindingId }
        }

        private fun held(presented: RelayEnrollment) {
            if (offline) throw RelayException(gone = false, message = "offline")
            val current = installation
            if (current == null || current.secret != presented.secret) {
                throw RelayException(gone = true, message = "no such installation")
            }
        }
    }

    private companion object {
        const val GATEWAY = "https://feeds.example.com"
        const val A = "00000000-0000-4000-8000-00000000000a"
        const val B = "00000000-0000-4000-8000-00000000000b"
        const val SERVER = "00000000-0000-4000-8000-0000000000aa"
        const val OTHER_SERVER = "00000000-0000-4000-8000-0000000000bb"
        const val TARGET = "fcm-registration-before-rotation"
        const val ROTATED = "fcm-registration-after-rotation"
        val KEY: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
}
