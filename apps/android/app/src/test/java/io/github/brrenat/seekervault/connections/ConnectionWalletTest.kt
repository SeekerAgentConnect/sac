package io.github.brrenat.seekervault.connections

import io.github.brrenat.seekervault.plugins.PluginEnvironment
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
 * connection is added and when its wallet is changed.
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
            compatibleProfiles(feed(setOf(WalletNetwork.Mainnet)), all),
        )
    }

    @Test
    fun aMultiNetworkServerIsOfferedExactlyTheNetworksItDeclared() {
        assertEquals(
            listOf(aDev, cTest),
            compatibleProfiles(feed(setOf(WalletNetwork.Devnet, WalletNetwork.Testnet)), all),
        )
    }

    @Test
    fun noMatchingProfileOffersNothingAndNeverAnotherNetwork() {
        assertEquals(
            emptyList<WalletProfile>(),
            compatibleProfiles(feed(setOf(WalletNetwork.Testnet)), listOf(aMain, aDev)),
        )
    }

    @Test
    fun aServerThatDeclaredNoNetworkMayBeBoundToAnyProfileForItsAccessProofOnly() {
        // Binding it is allowed — a restricted feed proves its reader's address — but its
        // readiness stays NetworksUnknown, so nothing is signed (WalletRepositoryTest).
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
                        environments = setOf(PluginEnvironment.Production),
                        supportedNetworks = networks,
                    )
                ),
        )

    private companion object {
        const val GATEWAY = "https://gateway.example.com"
        const val SERVER = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
    }
}
