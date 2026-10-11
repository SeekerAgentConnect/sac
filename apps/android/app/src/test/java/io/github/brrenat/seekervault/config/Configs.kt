package io.github.brrenat.seekervault.config

import io.github.brrenat.seekervault.gateway.v1.ClientConfig as WireClientConfig
import io.github.brrenat.seekervault.gateway.v1.SwapFee as WireSwapFee
import io.github.brrenat.seekervault.gateway.v1.clientConfig
import io.github.brrenat.seekervault.gateway.v1.rpcEndpoint
import io.github.brrenat.seekervault.gateway.v1.swapFee
import io.github.brrenat.seekervault.gateway.v1.swapFeeAccount
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.server.v1.SolanaNetwork
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Client configurations as the trusted gateway answers them (SEE-185), for the tests that read one.
 *
 * These build the protocol message, not the validated model, for the same reason the manifest and
 * proposal builders do: what the phone makes of an answer is the thing under test, so a test that
 * wants a bad one has to be able to write a bad one.
 */
fun wireConfig(
    revision: Long = 1,
    rpc: List<Pair<SolanaNetwork, String>> = emptyList(),
    fee: WireSwapFee? = null,
): WireClientConfig = clientConfig {
    this.revision = revision
    rpcEndpoints += rpc.map { (network, url) ->
        rpcEndpoint {
            this.network = network
            this.url = url
        }
    }
    fee?.let { swapFee = it }
}

fun wireFee(
    bps: Int = 20,
    network: SolanaNetwork = SolanaNetwork.SOLANA_NETWORK_MAINNET,
    owner: String = FEE_OWNER_ADDRESS,
    accounts: List<Pair<String, String>> = listOf(FEE_MINT to FEE_TOKEN_ACCOUNT),
): WireSwapFee = swapFee {
    this.bps = bps
    this.network = network
    this.owner = owner
    this.accounts += accounts.map { (mint, account) ->
        swapFeeAccount {
            this.mint = mint
            tokenAccount = account
        }
    }
}

/** A fee of [bps] on [network], as validated, for the tests that hand one to an operation. */
fun feeConfig(bps: Int = 20, network: Network = Network.NETWORK_MAINNET): SwapFeeConfig =
    SwapFeeConfig(bps, network, FEE_OWNER_ADDRESS, mapOf(FEE_MINT to FEE_TOKEN_ACCOUNT))

/**
 * An [OperationConfig] a test sets by hand: what a preparation is answered ([answer]) and what an
 * approval re-reads ([holding]). Both default to a usable configuration with no fee.
 */
class FakeOperationConfig(
    var answer: ConfigAvailability = ConfigAvailability.Usable(ZERO_CONFIG, cached = false),
    var holding: ConfigAvailability = answer,
) : OperationConfig {
    private val _state = MutableStateFlow(stateOf(answer))
    override val state: StateFlow<ClientConfigState> = _state

    /** How many times an operation asked the gateway before preparing. */
    var asked = 0

    override suspend fun forOperation(): ConfigAvailability {
        asked++
        return answer
    }

    override fun held(): ConfigAvailability = holding

    /** Both answers become [availability], and the state moves so a watcher sees it. */
    fun set(availability: ConfigAvailability) {
        answer = availability
        holding = availability
        _state.value = stateOf(availability)
    }

    /** The configuration with [fee] in place, usable now. */
    fun withFee(fee: SwapFeeConfig) =
        set(
            ConfigAvailability.Usable(ZERO_CONFIG.copy(revision = 2, swapFee = fee), cached = false)
        )

    private fun stateOf(availability: ConfigAvailability) =
        when (availability) {
            is ConfigAvailability.Usable ->
                ClientConfigState(availability.config, Instant.EPOCH, problem = null)
            is ConfigAvailability.Unavailable -> ClientConfigState(problem = availability.problem)
        }
}

val ZERO_CONFIG = ClientConfig(revision = 0, rpc = emptyMap(), swapFee = SwapFeeConfig.Zero)

/** Real base58 public keys, spelled here so this file depends on no provider's package. */
const val FEE_OWNER_ADDRESS = "6xJ8QGkQ6Qx1e8YpQ2CqZ9bJ7jY7N3tFh5T9Jr2vQ4dM"

const val FEE_MINT = "So11111111111111111111111111111111111111112"

const val FEE_TOKEN_ACCOUNT = "8YqPZcJgTkVV1w7M6kC4t2u8D1n3a5F7h9L2s4B6v8Nq"

const val USDC = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
