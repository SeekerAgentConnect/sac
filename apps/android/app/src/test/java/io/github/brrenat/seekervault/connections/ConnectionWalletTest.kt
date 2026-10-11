package io.github.brrenat.seekervault.connections

import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.SERVER_PROTOCOL
import io.github.brrenat.seekervault.servers.ServerManifest
import io.github.brrenat.seekervault.servers.ServerRecord
import io.github.brrenat.seekervault.servers.ServerReference
import io.github.brrenat.seekervault.servers.channelFor
import io.github.brrenat.seekervault.wallet.WalletNetwork
import io.github.brrenat.seekervault.wallet.WalletProfile
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which saved wallet profiles a connection's picker offers (SEE-174): the same filter when a
 * connection is added and when its wallet is changed. A direct server signs with the profile bound
 * here, so only its declared networks are offered; a feed's bound profile is only its access
 * identity (SEE-185), so any profile is.
 */
class ConnectionWalletTest {
    private val aMain = profile("a-main", WalletNetwork.Mainnet)
    private val aDev = profile("a-dev", WalletNetwork.Devnet)
    private val bMain = profile("b-main", WalletNetwork.Mainnet)
    private val cTest = profile("c-test", WalletNetwork.Testnet)
    private val all = listOf(aMain, aDev, bMain, cTest)

    @Test
    fun aMainnetOnlyServerIsOfferedOnlyMainnetProfiles() {
        assertEquals(
            listOf(aMain, bMain),
            compatibleProfiles(direct(setOf(WalletNetwork.Mainnet)), all),
        )
    }

    @Test
    fun aMultiNetworkServerIsOfferedExactlyTheNetworksItDeclared() {
        assertEquals(
            listOf(aDev, cTest),
            compatibleProfiles(direct(setOf(WalletNetwork.Devnet, WalletNetwork.Testnet)), all),
        )
    }

    @Test
    fun noMatchingProfileOffersNothingAndNeverAnotherNetwork() {
        assertEquals(
            emptyList<WalletProfile>(),
            compatibleProfiles(direct(setOf(WalletNetwork.Testnet)), listOf(aMain, aDev)),
        )
    }

    @Test
    fun aDirectServerThatDeclaredNoNetworkMayBeBoundToAnyProfileButSignsNothing() {
        // Binding it is allowed, but its readiness stays NetworksUnknown, so nothing is signed
        // (WalletRepositoryTest).
        assertEquals(all, compatibleProfiles(direct(emptySet()), all))
    }

    @Test
    fun aFeedIsOfferedEveryProfileWhateverNetworksItDeclares() {
        // SEE-185: a feed's profile is its access identity — an address proof no network enters —
        // and each of its operations chooses its own wallet for its signal's network. So the
        // networks it declares never narrow which profile may prove its reader.
        assertEquals(all, compatibleProfiles(feed(setOf(WalletNetwork.Mainnet)), all))
        assertEquals(all, compatibleProfiles(feed(setOf(WalletNetwork.Testnet)), all))
        assertEquals(all, compatibleProfiles(feed(emptySet()), all))
    }

    private fun profile(id: String, network: WalletNetwork) =
        WalletProfile(
            id = id,
            address = "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW",
            network = network,
            authorizationId = "auth",
            connectedAt = Instant.EPOCH,
        )

    private fun feed(networks: Set<WalletNetwork>) =
        Connection(
            id = "feed",
            label = "Feed",
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
                        supportedNetworks = networks,
                    )
                ),
        )

    private fun direct(networks: Set<WalletNetwork>) =
        Connection(
            id = "direct",
            label = "Direct",
            serverUrl = URL,
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
                        reference = ServerReference.Direct(URL),
                        supportedNetworks = networks,
                    )
                ),
        )

    private companion object {
        const val URL = "https://vault.example.com"
        const val GATEWAY = "https://gateway.example.com"
        const val SERVER = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
    }
}
