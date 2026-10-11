package io.github.brrenat.seekervault.config

import io.github.brrenat.seekervault.plugins.ServiceFeeTerms
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.server.v1.SolanaNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What this phone makes of the trusted gateway's client configuration (SEE-185,
 * docs/wiki/client-config.md).
 *
 * An answer is validated whole: one that breaks any rule is refused entirely — never partly used,
 * never repaired — so an invalid nonzero fee is never read as no fee, and a bad endpoint never
 * leaves the rest of the endpoints standing.
 */
class ClientConfigTest {
    private val mainnetUrl = "https://mainnet.rpc.example.com/?api-key=public"
    private val devnetUrl = "https://devnet.rpc.example.com"

    private fun valid(
        answer: io.github.brrenat.seekervault.gateway.v1.ClientConfig,
        allowCleartext: Boolean = false,
    ): ClientConfig =
        when (val read = clientConfigFrom(answer, allowCleartext)) {
            is ConfigRead.Valid -> read.config
            is ConfigRead.Invalid -> throw AssertionError("refused: ${read.problem}")
        }

    private fun refused(
        answer: io.github.brrenat.seekervault.gateway.v1.ClientConfig,
        allowCleartext: Boolean = false,
    ): ConfigInvalid =
        when (val read = clientConfigFrom(answer, allowCleartext)) {
            is ConfigRead.Invalid -> read.problem
            is ConfigRead.Valid -> throw AssertionError("accepted: ${read.config}")
        }

    @Test
    fun aCompleteConfigurationIsReadWithAnEndpointPerNetworkAndItsFee() {
        val config =
            valid(
                wireConfig(
                    revision = 7,
                    rpc =
                        listOf(
                            SolanaNetwork.SOLANA_NETWORK_MAINNET to mainnetUrl,
                            SolanaNetwork.SOLANA_NETWORK_DEVNET to " $devnetUrl ",
                        ),
                    fee =
                        wireFee(
                            bps = 25,
                            accounts = listOf(FEE_MINT to FEE_TOKEN_ACCOUNT, USDC to USDC_ACCOUNT),
                        ),
                )
            )

        assertEquals(7L, config.revision)
        assertEquals(
            mapOf(Network.NETWORK_MAINNET to mainnetUrl, Network.NETWORK_DEVNET to devnetUrl),
            config.rpc,
        )
        assertEquals(
            SwapFeeConfig(
                25,
                Network.NETWORK_MAINNET,
                FEE_OWNER_ADDRESS,
                mapOf(FEE_MINT to FEE_TOKEN_ACCOUNT, USDC to USDC_ACCOUNT),
            ),
            config.swapFee,
        )
    }

    @Test
    fun aGatewayNobodyConfiguredAnswersRevisionZeroWhichIsAConfigurationWithNoFee() {
        // The gateway's own "never configured": no endpoints and no fee. That is an answer, and it
        // is kept apart from having none at all (ClientConfigRepositoryTest).
        val config = valid(wireConfig(revision = 0))

        assertEquals(ClientConfig(0, emptyMap(), SwapFeeConfig.Zero), config)
        // A zero rate with its fields left in is no fee too, and carries nothing.
        assertEquals(SwapFeeConfig.Zero, valid(wireConfig(fee = wireFee(bps = 0))).swapFee)
    }

    @Test
    fun anEndpointForAnUnspecifiedOrRepeatedNetworkRefusesTheWholeAnswer() {
        assertEquals(
            ConfigInvalid.BadNetwork,
            refused(
                wireConfig(
                    rpc =
                        listOf(
                            SolanaNetwork.SOLANA_NETWORK_MAINNET to mainnetUrl,
                            SolanaNetwork.SOLANA_NETWORK_UNSPECIFIED to devnetUrl,
                        )
                )
            ),
        )
        assertEquals(
            ConfigInvalid.BadNetwork,
            refused(
                wireConfig(
                    rpc =
                        listOf(
                            SolanaNetwork.SOLANA_NETWORK_DEVNET to devnetUrl,
                            SolanaNetwork.SOLANA_NETWORK_DEVNET to mainnetUrl,
                        )
                )
            ),
        )
        // A network value this build has never heard of is no network either.
        val unknown =
            wireConfig()
                .toBuilder()
                .addRpcEndpoints(
                    io.github.brrenat.seekervault.gateway.v1.RpcEndpoint.newBuilder()
                        .setNetworkValue(42)
                        .setUrl(devnetUrl)
                )
                .build()
        assertEquals(ConfigInvalid.BadNetwork, refused(unknown))
    }

    @Test
    fun anEndpointThatIsNotAnHttpsUrlWithAHostAndNoSecretsRefusesTheWholeAnswer() {
        fun endpoint(url: String) =
            wireConfig(
                rpc =
                    listOf(
                        SolanaNetwork.SOLANA_NETWORK_MAINNET to mainnetUrl,
                        SolanaNetwork.SOLANA_NETWORK_DEVNET to url,
                    )
            )
        val cases =
            listOf(
                "plain HTTP in a release build" to "http://devnet.rpc.example.com",
                "user info" to "https://user:secret@devnet.rpc.example.com",
                "a fragment" to "https://devnet.rpc.example.com/#key",
                "no host" to "https:///path",
                "not a URL" to "devnet",
                "empty" to "",
                "another scheme" to "wss://devnet.rpc.example.com",
                "too long" to "https://devnet.rpc.example.com/" + "a".repeat(600),
            )
        for ((what, url) in cases) {
            assertEquals(what, ConfigInvalid.BadEndpoint, refused(endpoint(url)))
        }
        // Plain HTTP is a debug build's local validator, and only that build's.
        val local = endpoint("http://127.0.0.1:8899")
        assertEquals(ConfigInvalid.BadEndpoint, refused(local))
        assertEquals("http://127.0.0.1:8899", valid(local, allowCleartext = true).rpc[DEVNET])
        // Even then, never with credentials in it.
        assertEquals(
            ConfigInvalid.BadEndpoint,
            refused(endpoint("http://user:pw@127.0.0.1:8899"), allowCleartext = true),
        )
    }

    @Test
    fun aRateAboveOnePercentRefusesTheWholeAnswer() {
        assertEquals(ConfigInvalid.BadRate, refused(wireConfig(fee = wireFee(bps = 101))))
        assertEquals(100, valid(wireConfig(fee = wireFee(bps = 100))).swapFee.bps)
    }

    @Test
    fun aNonzeroRateWithoutItsNetworkOwnerOrAccountsIsRefusedNeverReadAsNoFee() {
        val cases =
            listOf(
                "no network" to wireFee(network = SolanaNetwork.SOLANA_NETWORK_UNSPECIFIED),
                "no owner" to wireFee(owner = ""),
                "no receiving account" to wireFee(accounts = emptyList()),
            )
        for ((what, fee) in cases) {
            assertEquals(what, ConfigInvalid.IncompleteFee, refused(wireConfig(fee = fee)))
        }
    }

    @Test
    fun anAddressThatIsNotAPublicKeyOrAnAccountThatIsNotAnAccountRefusesTheWholeAnswer() {
        val cases =
            listOf(
                "an owner that is not base58" to wireFee(owner = "not-an-address"),
                "a mint that is not base58" to
                    wireFee(accounts = listOf("0OIl" to FEE_TOKEN_ACCOUNT)),
                "an account that is not base58" to wireFee(accounts = listOf(FEE_MINT to "0OIl")),
                "the same mint twice" to
                    wireFee(
                        accounts = listOf(FEE_MINT to FEE_TOKEN_ACCOUNT, FEE_MINT to USDC_ACCOUNT)
                    ),
                // A fee is received by a token account for the fee's mint, never by a wallet
                // address or by the mint itself.
                "the owner as the account" to
                    wireFee(accounts = listOf(FEE_MINT to FEE_OWNER_ADDRESS)),
                "the mint as its own account" to wireFee(accounts = listOf(FEE_MINT to FEE_MINT)),
            )
        for ((what, fee) in cases) {
            assertEquals(what, ConfigInvalid.BadFeeAccount, refused(wireConfig(fee = fee)))
        }
    }

    @Test
    fun aFeeConfiguredForOneNetworkIsNoFeeOnAnother() {
        val fee = feeConfig(bps = 20, network = Network.NETWORK_MAINNET)

        assertEquals(
            ServiceFeeTerms(20, FEE_OWNER_ADDRESS, mapOf(FEE_MINT to FEE_TOKEN_ACCOUNT)),
            fee.termsFor(Network.NETWORK_MAINNET),
        )
        assertTrue(fee.termsFor(Network.NETWORK_MAINNET).charges)
        // The same mint on another cluster is another asset, and its account another account.
        assertEquals(ServiceFeeTerms.None, fee.termsFor(Network.NETWORK_DEVNET))
        assertEquals(ServiceFeeTerms.None, fee.termsFor(Network.NETWORK_TESTNET))
        assertFalse(SwapFeeConfig.Zero.termsFor(Network.NETWORK_MAINNET).charges)
    }

    private companion object {
        val DEVNET = Network.NETWORK_DEVNET
        /** A second receiving account, for the configurations that list two mints. */
        const val USDC_ACCOUNT = "3YKUMU99pedShDEe76HuSAHo3dt9CXjBwjN8w8NUo9Wh"
    }
}
