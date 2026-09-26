package io.github.brrenat.seekervault.access

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.access.storage.FeedAccessStore
import io.github.brrenat.seekervault.access.storage.FeedAccessStore.State
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.softwareKey
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.FeedAccess
import io.github.brrenat.seekervault.servers.ServerManifest
import io.github.brrenat.seekervault.servers.ServerRecord
import io.github.brrenat.seekervault.servers.ServerReference
import io.github.brrenat.seekervault.servers.channelFor
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.SignResult
import io.github.brrenat.seekervault.wallet.WalletNetwork
import io.github.brrenat.seekervault.wallet.encodeBase58
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.EdECPublicKey
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Restricted-feed access on the phone, end to end against a publisher that answers (SEE-156,
 * docs/wiki/restricted-feeds.md#on-the-phone).
 *
 * The claim these defend is that nothing on this phone treats a publisher's word as authority. The
 * challenge is rebuilt here and compared before the wallet is opened, so a publisher cannot get a
 * signature over text this app did not write; the session is held only while the gateway keeps
 * admitting it, so a publisher that still says "approved" after a revocation does not get the feed
 * read anyway; and access belongs to the wallet that proved it, so selecting another one starts
 * again rather than inheriting.
 *
 * The wallet here is a real Ed25519 key, because the manager verifies the signature it gets back
 * and a stub would simply be refused — which is the behaviour under test in
 * [theWalletSigningSomethingElseIsNotASignature]. The device key is [SoftwareDeviceKeys]; the
 * Keystore one is the same interface and runs on a device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class FeedAccessManagerTest {
    @get:Rule val folder = TemporaryFolder()

    private val walletKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val address = encodeBase58(publicKeyBytes(walletKey))
    private val otherKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val otherAddress = encodeBase58(publicKeyBytes(otherKey))

    private val api = FakePublisher()
    private val keys = SoftwareDeviceKeys()
    private val store by lazy { FeedAccessStore(File(folder.root, "no_backup/feed-access")) }
    private val vaultKey = softwareKey()
    private val sessions by lazy {
        CredentialVault(File(folder.root, "no_backup/feed-sessions")) { vaultKey }
    }

    private var connections = listOf(restrictedFeed())
    private var selected: SelectedWallet? = wallet(address)
    private var clock = Instant.parse("2026-09-26T12:00:00Z")

    /** What the wallet will answer, so a test can decline or answer with the wrong bytes. */
    private var walletAnswer: suspend (ByteArray) -> SignResult = { built -> signed(walletKey, built) }

    private val signings = mutableListOf<ByteArray>()
    private val connected = mutableListOf<String>()
    private val pushed = mutableListOf<Triple<String, String, String>>()

    private fun TestScope.manager() =
        FeedAccessManager(
            connections = { connections },
            store = store,
            sessions = sessions,
            keys = keys,
            api = api,
            wallet = { selected },
            sign = { message, _ ->
                signings += message.toByteArray()
                walletAnswer(message.toByteArray())
            },
            label = { "A phone" },
            pushTarget = { _, channel, session, target ->
                pushed += Triple(channel, session, target)
            },
            onConnected = { connected += it },
            now = { clock },
            io = StandardTestDispatcher(testScheduler),
            scope = backgroundScope,
        )

    @Test
    fun asksOnceWithTheWalletAndThenWaitsForTheDecision() = runTest {
        val manager = manager()
        val result = manager.requestAccess(CONNECTION)

        assertTrue(result is AccessResult.Done)
        assertEquals(State.Pending, manager.states.value[CONNECTION]?.state)
        assertEquals(address, manager.states.value[CONNECTION]?.wallet)
        assertEquals(1, signings.size)
        // Pending is not access: nothing on this phone can read the feed yet.
        assertNull(manager.sessionFor(CHANNEL))
        assertFalse(sessions.contains(CONNECTION))
        // What the publisher was told, and it is the request it asked for rather than a claim: the
        // installation is the fingerprint of a key it will have to see a signature from again.
        assertEquals(listOf("challenge", "request"), api.calls)
        assertEquals(
            FeedAccessProof.installation(keys.publicKey(DeviceKeys.aliasFor(CONNECTION))),
            manager.states.value[CONNECTION]?.installation,
        )
    }

    @Test
    fun anApprovedDeviceRedeemsItsInvitationAndConnects() = runTest {
        val manager = manager()
        manager.requestAccess(CONNECTION)
        api.state = "approved"
        api.invitation = "an-invitation"

        val result = manager.check(CONNECTION)

        assertTrue(result is AccessResult.Done)
        assertEquals(State.Connected, manager.states.value[CONNECTION]?.state)
        assertEquals(SESSION, manager.sessionFor(CHANNEL))
        assertEquals(SESSION, sessions.get(CONNECTION))
        assertEquals(api.until, manager.states.value[CONNECTION]?.grantUntil)
        // Newly readable, so the feed is read now rather than at the next foreground pass.
        assertEquals(listOf(CONNECTION), connected)
        // And the wallet was not opened a second time: waiting for a decision and taking it are
        // signed by the device key.
        assertEquals(1, signings.size)
        assertEquals(listOf("challenge", "request", "status", "redeem"), api.calls)
    }

    @Test
    fun aRestrictedFeedIsReadWithItsSessionAndAPublicOneWithNone() = runTest {
        val manager = manager()
        connect(manager)
        assertEquals(SESSION, manager.sessionFor(CHANNEL))
        assertNull(manager.sessionFor(channelFor(OTHER_SERVER)))
    }

    @Test
    fun theSessionComesBackAfterARestart() = runTest {
        connect(manager())

        val next = manager()
        assertNull(next.sessionFor(CHANNEL))
        next.load()
        assertEquals(SESSION, next.sessionFor(CHANNEL))
        assertEquals(State.Connected, next.states.value[CONNECTION]?.state)
    }

    @Test
    fun theGatewaysRefusalIsWhatRecordsARevocation() = runTest {
        val manager = manager()
        connect(manager)

        // The publisher still says approved; the gateway does not. The gateway is the authority.
        manager.denied(CHANNEL, FeedSessions.Denial.Revoked)
        advanceUntilIdle()

        assertEquals(State.Revoked, manager.states.value[CONNECTION]?.state)
        assertNull(manager.sessionFor(CHANNEL))
        assertFalse(sessions.contains(CONNECTION))
        // And it stays refused across a restart: a revocation survives the process that learned it.
        val next = manager()
        next.load()
        assertNull(next.sessionFor(CHANNEL))
        assertEquals(State.Revoked, next.states.value[CONNECTION]?.state)
    }

    @Test
    fun aGrantThatRanOutIsSaidToHaveRunOutRatherThanToHaveBeenRevoked() = runTest {
        val manager = manager()
        connect(manager)

        manager.denied(CHANNEL, FeedSessions.Denial.Expired)
        advanceUntilIdle()

        // Expiry is not a decision about this device, so the state says so and the publisher may
        // renew it. Revocation is the one that is final.
        assertEquals(State.Expired, manager.states.value[CONNECTION]?.state)
    }

    @Test
    fun aForgedChallengeIsNeverPutInFrontOfTheWallet() = runTest {
        val manager = manager()
        val forgeries =
            listOf<(FeedAccessProof.Challenge) -> FeedAccessProof.Challenge>(
                // Another publisher's origin, in an answer from this one.
                { it.copy(authOrigin = "https://elsewhere.example.com") },
                // Another feed, so a proof for one feed could be presented for another.
                { it.copy(channel = channelFor(OTHER_SERVER)) },
                // Another wallet's, so the owner would sign somebody else's request.
                { it.copy(wallet = otherAddress) },
                // Another installation, so the approval would bind a device that is not this one.
                { it.copy(installation = "00000000000000000000") },
                // Already expired, and one that claims to last far longer than a challenge may.
                { it.copy(expiresAt = clock.minusSeconds(1)) },
                { it.copy(expiresAt = it.issuedAt.plus(Duration.ofHours(8))) },
                { it.copy(issuedAt = it.expiresAt.plusSeconds(1)) },
            )
        for (forge in forgeries) {
            api.forge = forge
            assertEquals(AccessResult.BadChallenge, manager.requestAccess(CONNECTION))
            assertTrue(signings.isEmpty())
            assertTrue(manager.states.value.isEmpty())
            assertEquals(listOf("challenge"), api.calls)
            api.calls.clear()
        }
    }

    @Test
    fun aChallengeWhoseWordsAreNotTheFieldsIsNeverSignedEither() = runTest {
        val manager = manager()
        // Every field matches; the text the owner would have been shown does not. This is the
        // case the field checks alone would miss, and it is the one that matters most: the words
        // are what a person reads before they sign.
        api.message = { "Approve this transfer of 10 SOL." }

        assertEquals(AccessResult.BadChallenge, manager.requestAccess(CONNECTION))
        assertTrue(signings.isEmpty())
        assertTrue(manager.states.value.isEmpty())
    }

    @Test
    fun theWalletDecliningSendsNothing() = runTest {
        val manager = manager()
        walletAnswer = { SignResult.Declined }

        val result = manager.requestAccess(CONNECTION)

        assertEquals(AccessResult.WalletDidNotSign(SignResult.Declined), result)
        assertEquals(listOf("challenge"), api.calls)
        assertTrue(manager.states.value.isEmpty())
    }

    @Test
    fun theWalletSigningSomethingElseIsNotASignature() = runTest {
        val manager = manager()
        // A wallet that reports signing other bytes, and a wallet that returns a signature made
        // with another key. Neither is a proof of this wallet over this challenge.
        walletAnswer = { signed(walletKey, "something else".toByteArray()) }
        assertTrue(manager.requestAccess(CONNECTION) is AccessResult.WalletDidNotSign)

        walletAnswer = { built -> signed(otherKey, built).let { it.copy(address = address) } }
        assertTrue(manager.requestAccess(CONNECTION) is AccessResult.WalletDidNotSign)

        assertEquals(listOf("challenge", "challenge"), api.calls)
        assertTrue(manager.states.value.isEmpty())
    }

    @Test
    fun withNoWalletThereIsNothingToAskFor() = runTest {
        selected = null
        assertEquals(AccessResult.NoWallet, manager().requestAccess(CONNECTION))
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun aPublicFeedIsNotAskedAboutAtAll() = runTest {
        connections = listOf(restrictedFeed(access = FeedAccess.Public))
        val manager = manager()
        assertEquals(AccessResult.NotRestricted, manager.requestAccess(CONNECTION))
        assertEquals(AccessResult.NotRestricted, manager.check(CONNECTION))
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun askingAgainForAFeedThatWasAlreadyAskedAboutIsACheck() = runTest {
        val manager = manager()
        manager.requestAccess(CONNECTION)
        api.calls.clear()

        manager.requestAccess(CONNECTION)

        // The publisher would answer the first request anyway, so the owner is not sent to the
        // wallet for nothing.
        assertEquals(1, signings.size)
        assertEquals(listOf("status"), api.calls)
    }

    @Test
    fun anotherWalletDoesNotInheritThisOnesAccess() = runTest {
        val manager = manager()
        connect(manager)
        assertEquals(SESSION, manager.sessionFor(CHANNEL))

        selected = wallet(otherAddress)
        walletAnswer = { built -> signed(otherKey, built).let { it.copy(address = otherAddress) } }
        api.state = "pending"
        api.invitation = null
        api.requestId = "b2c3d4e5-f6a7-4b89-bcde-123456789abc"

        manager.requestAccess(CONNECTION)

        // A new request, for a new wallet, and the session the old one held is gone rather than
        // carried over: the publisher approved a wallet, not a phone.
        assertEquals(otherAddress, manager.states.value[CONNECTION]?.wallet)
        assertEquals(State.Pending, manager.states.value[CONNECTION]?.state)
        assertNull(manager.sessionFor(CHANNEL))
        assertFalse(sessions.contains(CONNECTION))
        assertEquals(2, signings.size)
    }

    @Test
    fun removingTheConnectionTakesItsAccessWithIt() = runTest {
        val manager = manager()
        connect(manager)
        val alias = DeviceKeys.aliasFor(CONNECTION)
        val before = FeedAccessProof.installation(keys.publicKey(alias))

        manager.forget(CONNECTION)

        assertTrue(manager.states.value.isEmpty())
        assertNull(manager.sessionFor(CHANNEL))
        assertFalse(sessions.contains(CONNECTION))
        assertNull(store.get(CONNECTION))
        // Including the key the approval was bound to: adding the feed again is a new device, and
        // needs its own approval.
        assertFalse(before == FeedAccessProof.installation(keys.publicKey(alias)))
    }

    @Test
    fun aRegistrationTellsEveryConnectedFeedWhereThisDevicesHintsGo() = runTest {
        val manager = manager()
        connect(manager)
        pushed.clear()

        manager.onRegistered("a-firebase-target")
        advanceUntilIdle()

        assertEquals(listOf(Triple(CHANNEL, SESSION, "a-firebase-target")), pushed)
    }

    @Test
    fun aFeedThatIsNotConnectedIsToldNothingAboutThisDevice() = runTest {
        val manager = manager()
        manager.requestAccess(CONNECTION)

        manager.onRegistered("a-firebase-target")
        advanceUntilIdle()

        // A pending device has no grant to route a hint under, and a restricted feed has no public
        // topic to fall back on. Silence is the correct amount of delivery.
        assertTrue(pushed.isEmpty())
    }

    @Test
    fun aPublisherThatCannotBeReachedChangesNothing() = runTest {
        val manager = manager()
        api.unreachable = true

        assertEquals(AccessResult.Unreachable, manager.requestAccess(CONNECTION))
        assertTrue(manager.states.value.isEmpty())
        assertTrue(signings.isEmpty())
    }

    @Test
    fun aRejectedDeviceIsToldSoAndHoldsNothing() = runTest {
        val manager = manager()
        manager.requestAccess(CONNECTION)
        api.state = "rejected"

        manager.check(CONNECTION)

        assertEquals(State.Rejected, manager.states.value[CONNECTION]?.state)
        assertNull(manager.sessionFor(CHANNEL))
        // And asking again is a new request rather than a check, because the first one is answered.
        api.calls.clear()
        api.state = "pending"
        manager.requestAccess(CONNECTION)
        assertEquals(listOf("challenge", "request"), api.calls)
        assertEquals(2, signings.size)
    }

    /** Takes one feed all the way to connected, which most of these start from. */
    private suspend fun connect(manager: FeedAccessManager) {
        manager.requestAccess(CONNECTION)
        api.state = "approved"
        api.invitation = "an-invitation"
        manager.check(CONNECTION)
        api.invitation = null
    }

    /**
     * A publisher's authentication endpoint that answers, and can be made to answer badly. It
     * builds the challenge from the fields it is given, exactly as the real one does, so a test
     * that forges one changes a field rather than a string.
     */
    private inner class FakePublisher : FeedAccessApi {
        val calls = mutableListOf<String>()
        var state = "pending"
        var invitation: String? = null
        var requestId = "a1b2c3d4-e5f6-4789-abcd-0123456789ab"
        var unreachable = false
        var forge: (FeedAccessProof.Challenge) -> FeedAccessProof.Challenge = { it }
        var message: (FeedAccessProof.Challenge) -> String = {
            String(FeedAccessProof.message(it), Charsets.US_ASCII)
        }
        val until: Instant = Instant.parse("2026-09-27T12:00:00Z")

        override suspend fun challenge(
            authOrigin: String,
            channel: String,
            wallet: String,
            deviceKey: ByteArray,
            label: String,
        ): ChallengeAnswer {
            calls += "challenge"
            if (unreachable) throw FeedAccessException(FeedAccessException.Kind.Unreachable)
            val challenge =
                forge(
                    FeedAccessProof.Challenge(
                        authOrigin = authOrigin,
                        channel = channel,
                        wallet = wallet,
                        installation = FeedAccessProof.installation(deviceKey),
                        attempt = "7c9e6679-7425-40de-944b-e07fc1f90ae7",
                        nonce = "AAECAwQFBgcICQoLDA0ODw",
                        issuedAt = clock,
                        expiresAt = clock.plus(Duration.ofMinutes(5)),
                    )
                )
            return ChallengeAnswer(challenge, message(challenge))
        }

        override suspend fun request(
            authOrigin: String,
            attempt: String,
            walletSignature: ByteArray,
            deviceSignature: ByteArray,
        ): RequestAnswer {
            calls += "request"
            if (unreachable) throw FeedAccessException(FeedAccessException.Kind.Unreachable)
            return RequestAnswer(requestId, state)
        }

        override suspend fun status(
            authOrigin: String,
            requestId: String,
            atMillis: Long,
            deviceSignature: ByteArray,
        ): StatusAnswer {
            calls += "status"
            if (unreachable) throw FeedAccessException(FeedAccessException.Kind.Unreachable)
            return StatusAnswer(
                requestId = requestId,
                state = state,
                connected = sessions.contains(CONNECTION),
                invitation = invitation,
                invitationExpiresAt = invitation?.let { clock.plus(Duration.ofMinutes(5)) },
            )
        }

        override suspend fun redeem(
            authOrigin: String,
            channel: String,
            invitation: String,
            atMillis: Long,
            deviceSignature: ByteArray,
        ): RedeemAnswer {
            calls += "redeem"
            if (unreachable) throw FeedAccessException(FeedAccessException.Kind.Unreachable)
            return RedeemAnswer(requestId, SESSION, until, synced = true)
        }
    }

    private fun signed(pair: KeyPair, message: ByteArray): SignResult.Signed =
        SignResult.Signed(
            message = message.toByteString(),
            address = encodeBase58(publicKeyBytes(pair)),
            signature = sign(pair, message).toByteString(),
        )

    private fun sign(pair: KeyPair, message: ByteArray): ByteArray =
        Signature.getInstance("Ed25519")
            .apply {
                initSign(pair.private)
                update(message)
            }
            .sign()

    private fun wallet(address: String) =
        SelectedWallet(
            address = address,
            network = WalletNetwork.Mainnet,
            selectedAt = Instant.parse("2026-09-26T09:00:00Z"),
        )

    private fun restrictedFeed(access: FeedAccess = FeedAccess.Restricted(ORIGIN)) =
        Connection(
            id = CONNECTION,
            label = "Copy trading",
            serverUrl = GATEWAY,
            serverId = SERVER,
            deviceName = "",
            pairedAt = Instant.parse("2026-09-26T09:00:00Z"),
            hasCredential = false,
            mode = ConnectionMode.GatewayFeed,
            server =
                ServerRecord.Known(
                    ServerManifest(
                        serverId = SERVER,
                        protocolVersion = 1,
                        settingsRevision = 1,
                        mode = ConnectionMode.GatewayFeed,
                        reference =
                            ServerReference.Feed(
                                gatewayUrl = GATEWAY,
                                channel = channelFor(SERVER),
                                access = access,
                            ),
                        environments = setOf(PluginEnvironment.Production),
                    )
                ),
        )

    private companion object {
        const val CONNECTION = "0b8e2b1c-3f4d-4e5a-9b6c-7d8e9f0a1b2c"
        const val SERVER = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
        const val OTHER_SERVER = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"
        const val GATEWAY = "https://feeds.example.com"
        const val ORIGIN = "https://auth.copytrading.example.com"
        const val SESSION = "a-session-the-gateway-holds-a-digest-of"
        val CHANNEL: String = channelFor(SERVER)

        /** The 32 bytes a Solana address is: y little-endian, with x's sign in the top bit. */
        fun publicKeyBytes(pair: KeyPair): ByteArray {
            val point = (pair.public as EdECPublicKey).point
            val bytes = point.y.toByteArray().reversedArray()
            val encoded = ByteArray(32) { if (it < bytes.size) bytes[it] else 0 }
            if (point.isXOdd) encoded[31] = (encoded[31].toInt() or 0x80).toByte()
            return encoded
        }
    }
}
