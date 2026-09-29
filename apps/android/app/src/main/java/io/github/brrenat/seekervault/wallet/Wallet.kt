package io.github.brrenat.seekervault.wallet

import io.github.brrenat.seekervault.request.v1.Network
import java.time.Instant

/**
 * The Solana network a wallet is selected for. `chain` is the Mobile Wallet Adapter chain
 * identifier the wallet app knows it by, and [network] the value the protocol carries
 * (docs/protocol.md#the-wallet-binding).
 */
enum class WalletNetwork(val chain: String, val network: Network) {
    Mainnet("solana:mainnet", Network.NETWORK_MAINNET),
    Devnet("solana:devnet", Network.NETWORK_DEVNET),
    Testnet("solana:testnet", Network.NETWORK_TESTNET),
}

/**
 * The wallet the owner selected and the network they selected it for, as this phone holds it. It is
 * a public address and nothing else: no seed phrase, no private key, and not the wallet's
 * authorization token, which stays in the [storage.WalletStore] and never leaves the phone.
 */
data class SelectedWallet(
    /** The wallet's base58 address, an Ed25519 public key. */
    val address: String,
    val network: WalletNetwork,
    /** The wallet app's own name for the account, for display only. Null when it gave none. */
    val label: String? = null,
    /** When the owner connected it on this phone. */
    val selectedAt: Instant,
    /**
     * False when the wallet didn't list [network]'s chain for the account. The owner can still use
     * it, and the app says the wallet didn't confirm the network.
     */
    val networkConfirmed: Boolean = true,
    /**
     * The saved [WalletProfile] this is (SEE-174), or null for a value that was never one — a test
     * fixture, or a reviewed wallet from before profiles existed. A signing checks it against the
     * profile it would use: two profiles with the same address on the same network in two wallet
     * apps are two different things to approve with.
     */
    val profileId: String? = null,
    /**
     * The name of the wallet app the profile lives in, as the system gave it, for display only: a
     * review names the app the approval will open (SEE-159, SEE-174). It never decides anything.
     */
    val walletApp: String? = null,
)

/**
 * One saved Solana wallet profile (SEE-174, docs/guides/wallet-setup.md#wallet-profiles): an
 * account the owner connected in one wallet app, for one network. The phone keeps any number of
 * them, and every connection names the one it uses; there is no global "active" wallet any more.
 *
 * The address and network never change. Reconnecting the same account in the same app on the same
 * network refreshes this profile ([WalletProfile.sameAccount]) — which is what keeps every
 * connection that names it pointing at the same thing — and any other account or network is another
 * profile, so nothing can move a connection by editing the profile it names.
 *
 * It holds no secret. The wallet's authorization is referenced by [authorizationId] and sealed in
 * [storage.WalletStore]; several profiles may share one authorization when the wallet authorized
 * several accounts at once.
 */
data class WalletProfile(
    /** A stable local ID, never shown and never sent to a server. */
    val id: String,
    /** The base58 address. */
    val address: String,
    val network: WalletNetwork,
    /** The owner's own name for the profile, or null to show the wallet's. */
    val label: String? = null,
    /** The wallet app's own name for the account, as it reported it. */
    val accountLabel: String? = null,
    /** The wallet app this account lives in, and how to reach it (SEE-159). */
    val route: WalletRouting = WalletRouting.Untargeted,
    /** Which stored authorization signs for it. */
    val authorizationId: String,
    /** When the owner connected it, or last reconnected it. */
    val connectedAt: Instant,
    /** False when the wallet didn't list [network]'s chain for the account. */
    val networkConfirmed: Boolean = true,
    /**
     * False once the wallet refused the authorization this profile uses. The profile, and every
     * connection naming it, stays exactly as it was: the owner reconnects it, and nothing else is
     * touched (SEE-174).
     */
    val authorized: Boolean = true,
) {
    /** What the screens call it: the owner's name, then the wallet's, then nothing. */
    val displayLabel: String?
        get() = label?.takeIf(String::isNotBlank) ?: accountLabel?.takeIf(String::isNotBlank)

    /** The wallet app's name, for display only. */
    val walletApp: String?
        get() = route.appLabel?.takeIf(String::isNotBlank)

    /**
     * Whether [other] is the same account in the same wallet app on the same network — the
     * deduplication rule. The wallet app is compared by package: a profile whose app was never
     * learned only matches another that never learned one either.
     */
    fun sameAccount(address: String, network: WalletNetwork, packageName: String?): Boolean =
        this.address == address && this.network == network && route.packageName == packageName

    /** This profile as the value a review captures and a signing checks. */
    fun selected(): SelectedWallet =
        SelectedWallet(
            address = address,
            network = network,
            label = displayLabel,
            selectedAt = connectedAt,
            networkConfirmed = networkConfirmed,
            profileId = id,
            walletApp = walletApp,
        )

    override fun toString() =
        "WalletProfile(id=$id, address=$address, network=$network, app=${route.packageName}, " +
            "authorized=$authorized)"
}

/** The [WalletNetwork] a protocol network names, or null for one this build doesn't know. */
fun walletNetworkOf(network: Network): WalletNetwork? =
    WalletNetwork.entries.firstOrNull { it.network == network }
