package io.github.brrenat.seekervault.config

import io.github.brrenat.seekervault.gateway.v1.ClientConfig as WireClientConfig
import io.github.brrenat.seekervault.plugins.MOST_SERVICE_FEE_BPS
import io.github.brrenat.seekervault.plugins.ServiceFeeTerms
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.server.v1.SolanaNetwork
import io.github.brrenat.seekervault.wallet.isSolanaAddress
import java.net.URI

/** The Solana networks the gateway may configure an endpoint for, in the order they are kept. */
val CONFIG_NETWORKS: List<Network> =
    listOf(Network.NETWORK_MAINNET, Network.NETWORK_DEVNET, Network.NETWORK_TESTNET)

/**
 * The trusted gateway's client configuration, as this phone validated it (SEE-185,
 * docs/wiki/client-config.md).
 *
 * The build names exactly one gateway, and this is the only thing that decides which endpoint each
 * Solana network is read through and what SAC service fee a feed swap carries. No publisher,
 * manifest, signal, link or direct server can set or override any of it.
 */
data class ClientConfig(
    /** The operator's revision; it only ever goes up at the gateway. */
    val revision: Long,
    /** One endpoint per configured network. A network absent here has none. */
    val rpc: Map<Network, String>,
    val swapFee: SwapFeeConfig,
)

/** The SAC swap service fee, as configured: zero unless the operator set a rate. */
data class SwapFeeConfig(
    val bps: Int,
    /** The network [owner] and [accounts] exist on; null when no fee is configured. */
    val network: Network?,
    val owner: String?,
    val accounts: Map<String, String>,
) {
    /**
     * The terms an operation on [network] carries. A fee configured for another network is not this
     * network's fee: the same mint on another cluster is another asset, and its receiving account
     * another account.
     */
    fun termsFor(network: Network): ServiceFeeTerms =
        if (bps > 0 && this.network == network && owner != null && accounts.isNotEmpty()) {
            ServiceFeeTerms(bps, owner, accounts)
        } else {
            ServiceFeeTerms.None
        }

    companion object {
        val Zero: SwapFeeConfig = SwapFeeConfig(0, null, null, emptyMap())
    }
}

/** Why an answer from the gateway is not a configuration this phone will use. */
enum class ConfigInvalid(val code: String) {
    /** An endpoint for an unspecified or unknown network, or the same network twice. */
    BadNetwork("bad_network"),
    /** Not an absolute https URL with a host, or one carrying user info or a fragment. */
    BadEndpoint("bad_endpoint"),
    /** A rate outside 0..[MOST_SERVICE_FEE_BPS]. */
    BadRate("bad_rate"),
    /** A nonzero rate without a known network, an owner, or any receiving account. */
    IncompleteFee("incomplete_fee"),
    /** An owner, mint or account that is not a base58 public key, or a mint listed twice. */
    BadFeeAccount("bad_fee_account"),
}

sealed interface ConfigRead {
    data class Valid(val config: ClientConfig) : ConfigRead

    data class Invalid(val problem: ConfigInvalid) : ConfigRead
}

/**
 * Validates the gateway's answer whole (SEE-185).
 *
 * It applies the gateway's own rules again rather than trusting that they were applied: a
 * configuration that breaks any of them is refused entirely, never partly used and never repaired —
 * an invalid nonzero fee is not read as a zero one, and an endpoint that is not one is not skipped
 * while the rest is kept. [allowCleartext] admits `http:` endpoints, which only a debug build does,
 * for a local test validator.
 */
fun clientConfigFrom(wire: WireClientConfig, allowCleartext: Boolean): ConfigRead {
    val rpc = mutableMapOf<Network, String>()
    for (endpoint in wire.rpcEndpointsList) {
        val network = networkOf(endpoint.network) ?: return invalid(ConfigInvalid.BadNetwork)
        if (network in rpc) return invalid(ConfigInvalid.BadNetwork)
        val url = endpoint.url.trim()
        if (!isEndpointUrl(url, allowCleartext)) return invalid(ConfigInvalid.BadEndpoint)
        rpc[network] = url
    }
    val fee = if (wire.hasSwapFee()) wire.swapFee else null
    val bps = fee?.bps ?: 0
    if (bps < 0 || bps > MOST_SERVICE_FEE_BPS) return invalid(ConfigInvalid.BadRate)
    val owner = fee?.owner?.trim().orEmpty()
    if (owner.isNotEmpty() && !isSolanaAddress(owner)) return invalid(ConfigInvalid.BadFeeAccount)
    val accounts = mutableMapOf<String, String>()
    for (entry in fee?.accountsList.orEmpty()) {
        val mint = entry.mint.trim()
        val account = entry.tokenAccount.trim()
        if (
            !isSolanaAddress(mint) ||
                !isSolanaAddress(account) ||
                mint in accounts ||
                account == mint ||
                account == owner
        ) {
            return invalid(ConfigInvalid.BadFeeAccount)
        }
        accounts[mint] = account
    }
    val feeNetwork =
        fee?.network
            ?.takeIf { it != SolanaNetwork.SOLANA_NETWORK_UNSPECIFIED }
            ?.let { networkOf(it) ?: return invalid(ConfigInvalid.BadNetwork) }
    if (bps > 0 && (feeNetwork == null || owner.isEmpty() || accounts.isEmpty())) {
        return invalid(ConfigInvalid.IncompleteFee)
    }
    return ConfigRead.Valid(
        ClientConfig(
            revision = wire.revision,
            rpc = rpc,
            swapFee =
                if (bps == 0) SwapFeeConfig.Zero
                else SwapFeeConfig(bps, feeNetwork, owner, accounts.toSortedMap()),
        )
    )
}

/**
 * Whether [url] may be an endpoint: absolute, https (or http where allowed), a host, no secrets.
 */
fun isEndpointUrl(url: String, allowCleartext: Boolean): Boolean {
    if (url.isEmpty() || url.length > MOST_ENDPOINT_BYTES) return false
    val uri =
        try {
            URI(url)
        } catch (_: Exception) {
            return false
        }
    val scheme = uri.scheme?.lowercase()
    if (scheme != "https" && !(scheme == "http" && allowCleartext)) return false
    return !uri.host.isNullOrEmpty() && uri.rawUserInfo == null && uri.rawFragment == null
}

/** A wire network as the request contract's, by number; null for unspecified or unknown. */
fun networkOf(network: SolanaNetwork): Network? =
    when (network) {
        SolanaNetwork.SOLANA_NETWORK_MAINNET -> Network.NETWORK_MAINNET
        SolanaNetwork.SOLANA_NETWORK_DEVNET -> Network.NETWORK_DEVNET
        SolanaNetwork.SOLANA_NETWORK_TESTNET -> Network.NETWORK_TESTNET
        else -> null
    }

private fun invalid(problem: ConfigInvalid) = ConfigRead.Invalid(problem)

/** The gateway's own bound on an endpoint URL. */
private const val MOST_ENDPOINT_BYTES = 512
