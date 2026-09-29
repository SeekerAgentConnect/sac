package io.github.brrenat.seekervault.wallet

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.ConnectionRepository
import io.github.brrenat.seekervault.connections.FakeConnectionGateway
import io.github.brrenat.seekervault.connections.softwareKey
import io.github.brrenat.seekervault.connections.storage.ConnectionStore
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import io.github.brrenat.seekervault.connections.storage.ResultStore
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.servers.ALL_NETWORKS
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.SERVER_PROTOCOL
import io.github.brrenat.seekervault.servers.ServerManifest
import io.github.brrenat.seekervault.servers.ServerRecord
import io.github.brrenat.seekervault.servers.ServerReference
import io.github.brrenat.seekervault.servers.channelFor
import io.github.brrenat.seekervault.servers.directManifest
import io.github.brrenat.seekervault.wallet.storage.WalletStore
import java.io.File
import java.security.GeneralSecurityException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Saved wallet profiles and the one each connection signs with (SEE-174). The wallet is a
 * [FakeWalletAdapter] and the connections a [FakeConnectionWallets], so every test can say exactly
 * which connection, which profile and which network were used, and which server heard what. The
 * last tests run the same rules over the real connection repository and fake sidecars.
 */
@RunWith(AndroidJUnit4::class)
class WalletRepositoryTest {
    @get:Rule val folder = TemporaryFolder()

    private val key = softwareKey()
    private val adapter = FakeWalletAdapter()
    private val bindings = FakeConnectionWallets()

    /** Set while a test needs this phone's storage to be unavailable, as a locked Keystore is. */
    private var storageFails = false

    private fun store() =
        WalletStore(File(folder.root, "wallet"), File(folder.root, "no_backup/wallet")) {
            if (storageFails) throw GeneralSecurityException("the keystore went away") else key
        }

    private val store by lazy { store() }

    private var ids = 0

    private fun repository(
        connections: io.github.brrenat.seekervault.connections.ConnectionWallets = bindings
    ) =
        WalletRepository(
            store(),
            adapter,
            connections,
            io = Dispatchers.Unconfined,
            newId = { "id-${++ids}" },
        )

    private val repository by lazy { repository() }

    /** Adds [address] on [network] and returns the one profile it made. */
    private suspend fun WalletRepository.add(
        address: String,
        network: WalletNetwork,
        app: String? = null,
        token: String = "authorization-$address-$network",
    ): WalletProfile {
        adapter.answerConnected(
            address,
            authToken = token,
            route =
                app?.let { WalletRouting(packageName = it, appLabel = it) }
                    ?: WalletRouting.Untargeted,
        )
        return connectProfiles(network, app?.let { InstalledWallet(it, it) }).profiles.single()
    }

    // --- Profiles -------------------------------------------------------------------------------

    @Test
    fun keepsTheSameAddressOnTwoNetworksAndAnotherAddressApartAfterARestart() = runBlocking {
        repository.load()
        val aMain = repository.add(WALLET_A, WalletNetwork.Mainnet)
        val aDev = repository.add(WALLET_A, WalletNetwork.Devnet)
        val bMain = repository.add(WALLET_B, WalletNetwork.Mainnet)

        assertEquals(3, setOf(aMain.id, aDev.id, bMain.id).size)

        val restarted = repository()
        restarted.load()
        val profiles = restarted.profiles.value
        assertEquals(listOf(aMain.id, aDev.id, bMain.id), profiles.map { it.id })
        assertEquals(
            listOf(
                WALLET_A to WalletNetwork.Mainnet,
                WALLET_A to WalletNetwork.Devnet,
                WALLET_B to WalletNetwork.Mainnet,
            ),
            profiles.map { it.address to it.network },
        )
        // Each keeps its own authorization, sealed: the network a token was issued for is its own.
        val stored = store().profiles()
        assertEquals(
            listOf(
                "authorization-$WALLET_A-Mainnet",
                "authorization-$WALLET_A-Devnet",
                "authorization-$WALLET_B-Mainnet",
            ),
            profiles.map { stored.authorization(it.authorizationId)?.token },
        )
    }

    @Test
    fun addingAlwaysAsksTheWalletAfreshSoASecondAccountCanBeChosen() = runBlocking {
        repository.load()
        repository.add(WALLET_A, WalletNetwork.Mainnet)
        repository.add(WALLET_B, WalletNetwork.Mainnet)

        // No stored token was offered either time, so the wallet asked which account to authorize
        // rather than handing back the one it authorized before.
        assertEquals(listOf(null, null), adapter.connects.map { it.second })
        assertEquals(2, repository.profiles.value.size)
    }

    @Test
    fun reconnectingTheSameAccountRefreshesItsProfileInsteadOfDuplicatingIt() = runBlocking {
        repository.load()
        val first = repository.add(WALLET_A, WalletNetwork.Mainnet, app = SEED_VAULT)
        bindings.flow.value = listOf(feed(FEED_1, first.id))

        val again =
            repository.add(WALLET_A, WalletNetwork.Mainnet, app = SEED_VAULT, token = REFRESHED)

        assertEquals(first.id, again.id)
        assertEquals(1, repository.profiles.value.size)
        assertEquals(REFRESHED, store().profiles().authorization(again.authorizationId)?.token)
        // The connection naming it names the same profile, and nothing was unbound.
        assertEquals(first.id, bindings.connection(FEED_1)?.walletProfileId)
        // The old authorization nothing uses any more is not kept.
        assertEquals(1, store().profiles().authorizations.size)
    }

    @Test
    fun theSameAccountInAnotherWalletAppIsAnotherProfile() = runBlocking {
        repository.load()
        val seedVault = repository.add(WALLET_A, WalletNetwork.Mainnet, app = SEED_VAULT)
        val other = repository.add(WALLET_A, WalletNetwork.Mainnet, app = OTHER_APP)

        assertNotEquals(seedVault.id, other.id)
        assertEquals(SEED_VAULT, repository.profile(seedVault.id)?.walletApp)
        assertEquals(OTHER_APP, repository.profile(other.id)?.walletApp)
    }

    @Test
    fun everyAccountOneAuthorizationNamesIsAProfileSharingIt() = runBlocking {
        repository.load()
        adapter.answerAccounts(WALLET_A, WALLET_B, authToken = SECRET)

        val saved = repository.connectProfiles(WalletNetwork.Mainnet).profiles

        assertEquals(listOf(WALLET_A, WALLET_B), saved.map { it.address })
        assertEquals(1, saved.map { it.authorizationId }.toSet().size)
        assertEquals(1, store().profiles().authorizations.size)
    }

    @Test
    fun cancellingInTheWalletChangesNothing() = runBlocking {
        repository.load()
        val kept = repository.add(WALLET_A, WalletNetwork.Mainnet)
        bindings.flow.value = listOf(feed(FEED_1, kept.id))
        adapter.answer(WalletResult.Declined)

        val outcome = repository.connectProfiles(WalletNetwork.Devnet)

        assertEquals(WalletResult.Declined, outcome.result)
        assertEquals(listOf(kept), repository.profiles.value)
        assertEquals(kept.id, bindings.connection(FEED_1)?.walletProfileId)
    }

    @Test
    fun aProfileThatCannotBeStoredIsNotSaved() = runBlocking {
        repository.load()
        adapter.answerConnected(WALLET_A)
        storageFails = true
        try {
            repository.connectProfiles(WalletNetwork.Mainnet)
            error("storing should have failed")
        } catch (e: WalletStorageException) {
            // Reported, and nothing held.
        }
        storageFails = false
        assertEquals(emptyList<WalletProfile>(), repository.profiles.value)
    }

    @Test
    fun renamingKeepsTheAccountLabelApartAndChangesNothingElse() = runBlocking {
        repository.load()
        adapter.answerConnected(WALLET_A, label = "Account 1")
        val profile = repository.connectProfiles(WalletNetwork.Mainnet).profiles.single()
        val direct = direct(DIRECT_X, profile.id)
        bindings.flow.value = listOf(direct)
        repository.publish()
        val heard = bindings.published.getValue(DIRECT_X).size

        repository.rename(profile.id, "  Trading  ")

        val renamed = checkNotNull(repository.profile(profile.id))
        assertEquals("Trading", renamed.displayLabel)
        assertEquals("Account 1", renamed.accountLabel)
        // A name is local: no server is told anything again.
        repository.publish()
        assertEquals(heard, bindings.published.getValue(DIRECT_X).size)
        repository.rename(profile.id, " ")
        assertEquals("Account 1", repository.profile(profile.id)?.displayLabel)
    }

    // --- Readiness ------------------------------------------------------------------------------

    @Test
    fun aConnectionIsReadyOnlyWithItsOwnProfileOnADeclaredNetwork() = runBlocking {
        repository.load()
        val main = repository.add(WALLET_A, WalletNetwork.Mainnet)
        val dev = repository.add(WALLET_A, WalletNetwork.Devnet)
        bindings.flow.value =
            listOf(
                feed(FEED_1, null),
                feed(FEED_2, main.id, networks = emptySet()),
                feed(FEED_3, dev.id, networks = setOf(WalletNetwork.Mainnet)),
                feed(FEED_4, "gone"),
                feed(FEED_5, main.id, networks = setOf(WalletNetwork.Mainnet)),
            )

        assertEquals(WalletReadiness.NoProfile, repository.readiness(FEED_1))
        // A server that declared no networks is never read as Mainnet.
        assertEquals(WalletReadiness.NetworksUnknown(main), repository.readiness(FEED_2))
        assertEquals(
            WalletReadiness.NetworkUnsupported(dev, setOf(WalletNetwork.Mainnet)),
            repository.readiness(FEED_3),
        )
        assertEquals(WalletReadiness.ProfileMissing, repository.readiness(FEED_4))
        assertEquals(WalletReadiness.Ready(main), repository.readiness(FEED_5))
        assertNull(repository.walletFor(FEED_2))
        assertNull(repository.walletFor(FEED_3))
        assertEquals(main.id, repository.walletFor(FEED_5)?.profileId)
        // The access proof is address-only, so a feed that declared nothing can still prove its
        // reader with its own wallet — and never with another's.
        assertEquals(WALLET_A, repository.accessWalletFor(FEED_2)?.address)
        assertNull(repository.accessWalletFor(FEED_1))
    }

    @Test
    fun onlyProfilesOnADeclaredNetworkCanBeBound() = runBlocking {
        repository.load()
        val main = repository.add(WALLET_A, WalletNetwork.Mainnet)
        val dev = repository.add(WALLET_A, WalletNetwork.Devnet)
        bindings.flow.value = listOf(feed(FEED_1, null, networks = setOf(WalletNetwork.Mainnet)))

        assertEquals(BindOutcome.Incompatible, repository.bind(FEED_1, dev.id))
        assertNull(bindings.connection(FEED_1)?.walletProfileId)
        assertEquals(BindOutcome.Bound, repository.bind(FEED_1, main.id))
        assertEquals(main.id, bindings.connection(FEED_1)?.walletProfileId)
        assertEquals(BindOutcome.Gone, repository.bind(FEED_1, "no-such-profile"))
    }

    // --- Direct publication ---------------------------------------------------------------------

    @Test
    fun twoDirectServersEachHearOnlyTheirOwnBinding() = runBlocking {
        repository.load()
        val aDev = repository.add(WALLET_A, WalletNetwork.Devnet)
        val bMain = repository.add(WALLET_B, WalletNetwork.Mainnet)
        bindings.flow.value = listOf(direct(DIRECT_X, null), direct(DIRECT_Y, null))

        assertEquals(BindOutcome.Published(0), repository.bind(DIRECT_X, aDev.id))
        assertEquals(BindOutcome.Published(0), repository.bind(DIRECT_Y, bMain.id))

        assertEquals(
            listOf(WALLET_A to Network.NETWORK_DEVNET),
            bindings.published.getValue(DIRECT_X).map { it!!.wallet to it.network },
        )
        assertEquals(
            listOf(WALLET_B to Network.NETWORK_MAINNET),
            bindings.published.getValue(DIRECT_Y).map { it!!.wallet to it.network },
        )
        assertEquals(aDev.id, repository.walletFor(DIRECT_X)?.profileId)
        assertEquals(bMain.id, repository.walletFor(DIRECT_Y)?.profileId)
    }

    @Test
    fun addingOrReconnectingAProfileTellsNoServerAnything() = runBlocking {
        repository.load()
        val bound = repository.add(WALLET_A, WalletNetwork.Mainnet)
        bindings.flow.value = listOf(direct(DIRECT_X, bound.id), direct(DIRECT_Y, null))
        repository.publish()
        val before = bindings.published.mapValues { it.value.toList() }

        repository.add(WALLET_B, WalletNetwork.Mainnet)
        adapter.answerConnected(WALLET_A, authToken = REFRESHED)
        repository.reconnect(bound.id)
        repository.publish()

        assertEquals(before, bindings.published.mapValues { it.value.toList() })
    }

    @Test
    fun rebindingOneDirectServerTellsOnlyThatServerAndReportsWhatItCancelled() = runBlocking {
        repository.load()
        val a = repository.add(WALLET_A, WalletNetwork.Mainnet)
        val b = repository.add(WALLET_B, WalletNetwork.Mainnet)
        bindings.flow.value = listOf(direct(DIRECT_X, a.id), direct(DIRECT_Y, a.id))
        repository.publish()
        bindings.cancels = { id, _ -> if (id == DIRECT_X) 2 else 0 }

        assertEquals(BindOutcome.Published(2), repository.bind(DIRECT_X, b.id))

        assertEquals(WALLET_B, bindings.published.getValue(DIRECT_X).last()?.wallet)
        assertEquals(1, bindings.published.getValue(DIRECT_Y).size)
        assertEquals(a.id, repository.walletFor(DIRECT_Y)?.profileId)
    }

    @Test
    fun aServerThatMissedItsBindingSignsNothingUntilItHearsIt() = runBlocking {
        repository.load()
        val a = repository.add(WALLET_A, WalletNetwork.Mainnet)
        val b = repository.add(WALLET_B, WalletNetwork.Mainnet)
        bindings.flow.value = listOf(direct(DIRECT_X, a.id))
        repository.publish()
        val reviewed = checkNotNull(repository.walletFor(DIRECT_X))

        bindings.reachable[DIRECT_X] = false
        assertEquals(BindOutcome.PublicationFailed, repository.bind(DIRECT_X, b.id))

        assertEquals(WalletReadiness.PublicationPending(b), repository.readiness(DIRECT_X))
        assertNull(repository.walletFor(DIRECT_X))
        // Neither the old wallet the server still holds, nor the new one it hasn't heard of.
        assertEquals(SignResult.Changed, repository.sign(MESSAGE, reviewed, DIRECT_X))
        assertEquals(
            SignResult.Changed,
            repository.sign(MESSAGE, b.selected(), DIRECT_X),
        )
        assertEquals(emptyList<Any>(), adapter.signings)

        // Retried, it lands, and only then is the new wallet ready.
        bindings.reachable[DIRECT_X] = true
        assertEquals(emptyList<String>(), repository.publish())
        assertEquals(b.id, repository.walletFor(DIRECT_X)?.profileId)
    }

    // --- Signing --------------------------------------------------------------------------------

    @Test
    fun signsWithTheConnectionsOwnProfileAuthorizationAndApp() = runBlocking {
        repository.load()
        val a = repository.add(WALLET_A, WalletNetwork.Mainnet, app = SEED_VAULT, token = "token-a")
        val b = repository.add(WALLET_B, WalletNetwork.Mainnet, app = OTHER_APP, token = "token-b")
        bindings.flow.value = listOf(feed(FEED_1, a.id), feed(FEED_2, b.id))
        adapter.installed =
            listOf(InstalledWallet(SEED_VAULT, "S"), InstalledWallet(OTHER_APP, "O"))
        adapter.sendWith(SIGNATURE)
        adapter.routes.clear()

        repository.signAndSend(TRANSACTION, checkNotNull(repository.walletFor(FEED_2)), FEED_2)

        val (_, wallet, token) = adapter.sendings.single()
        assertEquals(WALLET_B, wallet.address)
        assertEquals("token-b", token)
        assertEquals(OTHER_APP, adapter.routes.single()?.packageName)
    }

    @Test
    fun aReviewForAConnectionThatWasReboundNeverReachesTheWallet() = runBlocking {
        repository.load()
        val a = repository.add(WALLET_A, WalletNetwork.Mainnet)
        val b = repository.add(WALLET_B, WalletNetwork.Mainnet)
        bindings.flow.value = listOf(feed(FEED_1, a.id))
        val reviewed = checkNotNull(repository.walletFor(FEED_1))

        repository.bind(FEED_1, b.id)

        assertEquals(SendResult.Changed, repository.signAndSend(TRANSACTION, reviewed, FEED_1))
        assertEquals(emptyList<Any>(), adapter.sendings)
    }

    @Test
    fun aReviewOfARemovedProfileNeverReachesTheWallet() = runBlocking {
        repository.load()
        val a = repository.add(WALLET_A, WalletNetwork.Mainnet)
        bindings.flow.value = listOf(feed(FEED_1, a.id))
        val reviewed = checkNotNull(repository.walletFor(FEED_1))

        repository.remove(a.id)

        assertEquals(SignResult.Changed, repository.sign(MESSAGE, reviewed, FEED_1))
        assertEquals(SignResult.Changed, repository.sign(MESSAGE, reviewed))
        assertEquals(emptyList<Any>(), adapter.signings)
    }

    @Test
    fun aRequestForAnotherNetworkIsNotSignedWithAFallbackProfile() = runBlocking {
        repository.load()
        repository.add(WALLET_A, WalletNetwork.Mainnet)
        val dev = repository.add(WALLET_A, WalletNetwork.Devnet)
        bindings.flow.value = listOf(feed(FEED_1, dev.id))
        // What a screen would have captured had it been about Mainnet: the same address, another
        // network. The connection's own profile is Devnet, and nothing else stands in.
        val mainnet = SelectedWallet(WALLET_A, WalletNetwork.Mainnet, selectedAt = Instant.EPOCH)

        assertEquals(SignResult.Changed, repository.sign(MESSAGE, mainnet, FEED_1))
        assertEquals(emptyList<Any>(), adapter.signings)
    }

    @Test
    fun aRebindingQueuedBehindTheWalletLockIsCheckedAgainBeforeTheHandoff() = runBlocking {
        repository.load()
        val a = repository.add(WALLET_A, WalletNetwork.Mainnet)
        val b = repository.add(WALLET_B, WalletNetwork.Mainnet)
        bindings.flow.value = listOf(feed(FEED_1, a.id), feed(FEED_2, b.id))
        val reviewedOne = checkNotNull(repository.walletFor(FEED_1))
        val reviewedTwo = checkNotNull(repository.walletFor(FEED_2))
        adapter.sendWith(SIGNATURE)
        val release = CompletableDeferred<Unit>()
        coroutineScope {
            // Another review holds the wallet — the owner is in the wallet app for it.
            val busy = launch { repository.withWallet { release.await() } }
            yield()
            // Meanwhile FEED_1 is rebound, and both reviews wait for the lock behind it.
            val rebinding = launch { repository.bind(FEED_1, b.id) }
            val first = launch {
                assertEquals(
                    SendResult.Changed,
                    repository.signAndSend(TRANSACTION, reviewedOne, FEED_1),
                )
            }
            val second = launch {
                assertEquals(
                    SendResult.Sent(SIGNATURE),
                    repository.signAndSend(TRANSACTION, reviewedTwo, FEED_2),
                )
            }
            yield()
            release.complete(Unit)
            listOf(busy, rebinding, first, second).forEach { it.join() }
        }
        // Only the review whose connection still names its profile reached the wallet.
        assertEquals(listOf(WALLET_B), adapter.sendings.map { it.second.address })
    }

    @Test
    fun aPositionFollowUpUsesItsOwnerNotTheFeedsNewWallet() = runBlocking {
        repository.load()
        val owner = repository.add(WALLET_A, WalletNetwork.Mainnet)
        val newer = repository.add(WALLET_B, WalletNetwork.Mainnet)
        bindings.flow.value = listOf(feed(FEED_1, newer.id))

        assertEquals(owner.id, repository.ownerProfile(WALLET_A, WalletNetwork.Mainnet)?.profileId)
        // No profile for the owner on another network: the follow-up is blocked, not rerouted.
        assertNull(repository.ownerProfile(WALLET_A, WalletNetwork.Devnet))
        repository.remove(owner.id)
        assertNull(repository.ownerProfile(WALLET_A, WalletNetwork.Mainnet))
    }

    // --- Authorizations -------------------------------------------------------------------------

    @Test
    fun aRotatedTokenIsKeptForEveryProfileSharingIt() = runBlocking {
        repository.load()
        adapter.answerAccounts(WALLET_A, WALLET_B, authToken = SECRET)
        val (a, b) = repository.connectProfiles(WalletNetwork.Mainnet).profiles
        bindings.flow.value = listOf(feed(FEED_1, a.id), feed(FEED_2, b.id))
        adapter.refreshedAuthorization = REFRESHED
        adapter.answerSigning(SignResult.Declined)

        repository.sign(MESSAGE, a.selected(), FEED_1)
        adapter.refreshedAuthorization = null
        repository.sign(MESSAGE, b.selected(), FEED_2)

        assertEquals(listOf(SECRET, REFRESHED), adapter.signings.map { it.third })
    }

    @Test
    fun anExpiredAuthorizationNeedsReconnectingForItsOwnProfilesAndNoOthers() = runBlocking {
        repository.load()
        val a = repository.add(WALLET_A, WalletNetwork.Mainnet)
        val b = repository.add(WALLET_B, WalletNetwork.Mainnet)
        bindings.flow.value = listOf(feed(FEED_1, a.id), feed(FEED_2, b.id))
        adapter.answerSigning(SignResult.AuthorizationExpired)

        assertEquals(
            SignResult.AuthorizationExpired,
            repository.sign(MESSAGE, a.selected(), FEED_1),
        )

        assertEquals(
            WalletReadiness.NeedsReconnect(a.copy(authorized = false)),
            repository.readiness(FEED_1),
        )
        // The connection still names it: an expiry reconnects a wallet, it doesn't rebind one.
        assertEquals(a.id, bindings.connection(FEED_1)?.walletProfileId)
        assertEquals(b.id, repository.walletFor(FEED_2)?.profileId)

        // Reconnecting asks afresh, since the token was refused, and makes it ready again.
        adapter.answerConnected(WALLET_A, authToken = REFRESHED)
        repository.reconnect(a.id)
        assertEquals(null, adapter.connects.last().second)
        assertEquals(a.id, repository.walletFor(FEED_1)?.profileId)
    }

    @Test
    fun removingOneProfileLeavesTheAuthorizationAnotherStillUses() = runBlocking {
        repository.load()
        adapter.answerAccounts(WALLET_A, WALLET_B, authToken = SECRET)
        val (a, b) = repository.connectProfiles(WalletNetwork.Mainnet).profiles
        bindings.flow.value = listOf(feed(FEED_2, b.id))

        repository.remove(a.id)
        // The wallet isn't told to forget a grant another profile signs with.
        assertEquals(emptyList<String>(), adapter.disconnects)
        adapter.answerSigning(SignResult.Declined)
        repository.sign(MESSAGE, b.selected(), FEED_2)
        assertEquals(SECRET, adapter.signings.single().third)

        repository.remove(b.id)
        assertEquals(listOf(SECRET), adapter.disconnects)
        assertEquals(emptyList<Any>(), store().profiles().authorizations)
    }

    @Test
    fun removingAUsedProfileLeavesItsConnectionsWithoutAWalletAndTellsOnlyTheirServers() =
        runBlocking {
            repository.load()
            val a = repository.add(WALLET_A, WalletNetwork.Mainnet)
            val b = repository.add(WALLET_B, WalletNetwork.Mainnet)
            bindings.flow.value =
                listOf(direct(DIRECT_X, a.id), direct(DIRECT_Y, b.id), feed(FEED_1, a.id))
            repository.publish()

            assertEquals(listOf(DIRECT_X, FEED_1), repository.usersOf(a.id).map { it.id })
            val affected = repository.remove(a.id)

            assertEquals(listOf(DIRECT_X, FEED_1), affected.map { it.id })
            assertNull(bindings.connection(DIRECT_X)?.walletProfileId)
            assertNull(bindings.connection(FEED_1)?.walletProfileId)
            // Nothing was chosen in its place.
            assertEquals(WalletReadiness.NoProfile, repository.readiness(FEED_1))
            assertEquals(null, bindings.published.getValue(DIRECT_X).last())
            assertEquals(1, bindings.published.getValue(DIRECT_Y).size)
            assertEquals(b.id, repository.walletFor(DIRECT_Y)?.profileId)
        }

    // --- Migration ------------------------------------------------------------------------------

    @Test
    fun migratesTheSingleWalletIntoAProfileAndBindsTheConnectionsThatUsedIt() = runBlocking {
        val route = WalletRouting(packageName = SEED_VAULT, appLabel = "Seed Vault Wallet")
        val selected =
            SelectedWallet(WALLET_A, WalletNetwork.Devnet, "Account 1", Instant.parse(AT))
        store.put(selected, SECRET, route)
        bindings.flow.value =
            listOf(
                direct(DIRECT_X, Connection.LEGACY_WALLET_PROFILE),
                feed(FEED_1, Connection.LEGACY_WALLET_PROFILE),
            )

        repository.load()

        val profile = repository.profiles.value.single()
        assertEquals(WALLET_A to WalletNetwork.Devnet, profile.address to profile.network)
        assertEquals(SEED_VAULT, profile.route.packageName)
        assertEquals(SECRET, store().profiles().authorization(profile.authorizationId)?.token)
        assertEquals(profile.id, bindings.connection(DIRECT_X)?.walletProfileId)
        assertEquals(profile.id, bindings.connection(FEED_1)?.walletProfileId)
        // The single-session record is gone only now that the profiles are committed.
        assertNull(store().session())

        // The same binding the server already held, so publishing it cancels nothing new.
        repository.publish()
        assertEquals(
            listOf(WALLET_A to Network.NETWORK_DEVNET),
            bindings.published.getValue(DIRECT_X).map { it!!.wallet to it.network },
        )
    }

    @Test
    fun aMigrationInterruptedAtAnyStepEndsInTheSameProfileAndBinding() = runBlocking {
        val selected = SelectedWallet(WALLET_A, WalletNetwork.Mainnet, selectedAt = Instant.EPOCH)
        store.put(selected, SECRET)
        // The process died after the profiles were committed and before the old session was
        // deleted, and before any connection was bound: the next start finds both.
        val first = store().profiles()
        store.put(selected, SECRET)
        assertNotNull(store.session())
        val second = store().profiles()
        assertEquals(first.profiles.map { it.id }, second.profiles.map { it.id })
        assertEquals(first.legacyProfileId, second.legacyProfileId)
        assertNull(store.session())
        // A fresh phone migrating the same session derives the same IDs, so a connection bound
        // on an earlier attempt still names its profile.
        store().clearProfiles()
        store.put(selected, SECRET)
        assertEquals(first.legacyProfileId, store().profiles().legacyProfileId)

        bindings.flow.value = listOf(feed(FEED_1, Connection.LEGACY_WALLET_PROFILE))
        repository.load()
        repository.load()
        assertEquals(first.legacyProfileId, bindings.connection(FEED_1)?.walletProfileId)
        assertEquals(1, repository.profiles.value.size)
    }

    @Test
    fun withNoUsableWalletLegacyConnectionsWaitForOneAndNothingIsInvented() = runBlocking {
        bindings.flow.value = listOf(direct(DIRECT_X, Connection.LEGACY_WALLET_PROFILE))

        repository.load()

        assertEquals(emptyList<WalletProfile>(), repository.profiles.value)
        assertEquals(WalletReadiness.NoProfile, repository.readiness(DIRECT_X))
        // The connection itself — and its credential — is untouched.
        assertTrue(checkNotNull(bindings.connection(DIRECT_X)).usable)
    }

    // --- Over the real connection repository ----------------------------------------------------

    @Test
    fun eachPairedServersAddressQueryAnswersItsOwnBinding() = runBlocking {
        val gateway = FakeConnectionGateway()
        val x =
            gateway.serve(URL).also {
                it.manifest = directManifest(it.serverId, URL, networks = ALL_NETWORKS)
            }
        val y =
            gateway.serve(OTHER_URL).also {
                it.manifest = directManifest(it.serverId, OTHER_URL, networks = ALL_NETWORKS)
            }
        val connections =
            ConnectionRepository(
                store = ConnectionStore(File(folder.root, "connections")),
                vault = CredentialVault(File(folder.root, "credentials")) { key },
                results = ResultStore(File(folder.root, "results")),
                gateway = gateway,
                deviceName = "Seeker",
                io = Dispatchers.Unconfined,
            )
        connections.load()
        val first = connections.pair(x.issue(URL))
        val second = connections.pair(y.issue(OTHER_URL))
        connections.resolveManifest(first.id)
        connections.resolveManifest(second.id)
        val wallet = repository(connections)
        wallet.load()
        val aDev = wallet.add(WALLET_A, WalletNetwork.Devnet)
        val bMain = wallet.add(WALLET_B, WalletNetwork.Mainnet)
        x.addPendingTransfer(first.id, WALLET_B, Network.NETWORK_MAINNET)

        wallet.bind(first.id, aDev.id)
        wallet.bind(second.id, bMain.id)

        assertEquals(WALLET_A to Network.NETWORK_DEVNET, x.wallet!!.wallet to x.wallet!!.network)
        assertEquals(WALLET_B to Network.NETWORK_MAINNET, y.wallet!!.wallet to y.wallet!!.network)
        // The binding survives a restart, stored with the connection.
        val stored = ConnectionStore(File(folder.root, "connections")).get(first.id)
        assertEquals(aDev.id, stored?.walletProfileId)
        // And the first server cancelled its own request for another wallet, the other nothing.
        assertEquals(emptyList<Any>(), x.pending[first.id].orEmpty())
        assertFalse(gateway.published.any { (_, binding) -> binding.toString().contains("token") })
        assertEquals(
            aDev.id,
            wallet.readinessByConnection().first()[first.id]?.profile?.id,
        )
    }

    private fun direct(
        id: String,
        profileId: String?,
        networks: Set<WalletNetwork> = WalletNetwork.entries.toSet(),
    ) =
        Connection(
            id = id,
            label = id,
            serverUrl = "https://$id.example",
            serverId = SERVER,
            deviceName = "Seeker",
            pairedAt = Instant.EPOCH,
            mode = ConnectionMode.Direct,
            server =
                ServerRecord.Known(
                    ServerManifest(
                        serverId = SERVER,
                        protocolVersion = SERVER_PROTOCOL,
                        settingsRevision = 1,
                        mode = ConnectionMode.Direct,
                        reference = ServerReference.Direct("https://$id.example"),
                        environments = setOf(PluginEnvironment.Production),
                        supportedNetworks = networks,
                    )
                ),
            walletProfileId = profileId,
        )

    private fun feed(
        id: String,
        profileId: String?,
        networks: Set<WalletNetwork> = WalletNetwork.entries.toSet(),
    ) =
        Connection(
            id = id,
            label = id,
            serverUrl = GATEWAY,
            serverId = SERVER,
            deviceName = "",
            pairedAt = Instant.EPOCH,
            hasCredential = false,
            mode = ConnectionMode.GatewayFeed,
            server =
                ServerRecord.Known(
                    ServerManifest(
                        serverId = SERVER,
                        protocolVersion = SERVER_PROTOCOL,
                        settingsRevision = 1,
                        mode = ConnectionMode.GatewayFeed,
                        reference = ServerReference.Feed(GATEWAY, channelFor(SERVER)),
                        environments = setOf(PluginEnvironment.Production),
                        supportedNetworks = networks,
                    )
                ),
            walletProfileId = profileId,
        )

    private companion object {
        const val URL = "http://127.0.0.1:8080"
        const val OTHER_URL = "http://127.0.0.1:8081"
        const val GATEWAY = "https://gateway.example.com"
        const val SERVER = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
        const val WALLET_A = "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW"
        const val WALLET_B = "3YKUMU99pedShDEe76HuSAHo3dt9CXjBwjN8w8NUo9Wh"
        const val SECRET = "authorization-the-wallet-issued-0123456789"
        const val REFRESHED = "authorization-the-wallet-issued-later-9876543210"
        const val SEED_VAULT = "com.solanamobile.seedvault"
        const val OTHER_APP = "app.other.wallet"
        const val AT = "2026-09-01T10:00:00Z"
        const val DIRECT_X = "direct-x"
        const val DIRECT_Y = "direct-y"
        const val FEED_1 = "feed-1"
        const val FEED_2 = "feed-2"
        const val FEED_3 = "feed-3"
        const val FEED_4 = "feed-4"
        const val FEED_5 = "feed-5"
        val MESSAGE: ByteString = ByteString.copyFromUtf8("Sign in to Example")
        val TRANSACTION: ByteString = ByteString.copyFrom(ByteArray(64) { it.toByte() })
        val SIGNATURE: ByteString = ByteString.copyFrom(ByteArray(64) { 7 })
    }
}
