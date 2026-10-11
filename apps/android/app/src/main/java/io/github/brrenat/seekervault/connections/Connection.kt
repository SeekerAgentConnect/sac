package io.github.brrenat.seekervault.connections

import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.ServerRecord
import io.github.brrenat.seekervault.servers.manifest
import java.time.Instant

/**
 * This phone's connection to one server (docs/security.md), keyed by the connection ID the sidecar
 * assigned at pairing, or the one this phone minted for a feed. Its credential isn't here: it stays
 * encrypted in the `CredentialVault`, and only the repository reads it.
 *
 * SEE-88 made the kind of server part of the record. A [ConnectionMode.Direct] connection is the
 * one that has always been here: the owner's own server, which this phone paired with and calls
 * with the credential it holds. A [ConnectionMode.GatewayFeed] connection is a publisher's
 * broadcast, read through the shared gateway, with no credential and no call to the publisher at
 * all. A retired gateway-private record has no active mode: it remains only so the owner can see
 * what stopped working and remove or replace it with a fresh direct pairing.
 */
data class Connection(
    val id: String,
    /** The owner's name for the connection. It stays on the phone. */
    val label: String,
    /**
     * Where this connection's credential goes, and nowhere else. It never changes. For a feed,
     * which has no credential, it is the gateway origin the feed is read from.
     */
    val serverUrl: String,
    val serverId: String,
    /**
     * The name this phone gave the private server when it paired; empty for a feed, which pairs
     * with nothing.
     */
    val deviceName: String,
    val pairedAt: Instant,
    /** When the sidecar stopped accepting the credential. The owner must pair again. */
    val revokedAt: Instant? = null,
    val lastCheck: Check? = null,
    /** Whether this phone still holds the credential. Read from the vault, not stored here. */
    val hasCredential: Boolean = true,
    /**
     * Which kind of server this is (SEE-88). It is stored rather than inferred, and it is set when
     * the connection is created: a manifest read later can confirm it but never change it, so no
     * server can move a connection onto another transport.
     */
    val mode: ConnectionMode? = ConnectionMode.Direct,
    /** Why this historical connection is inert. Mutually exclusive with an active [mode]. */
    val retirement: ConnectionRetirement? = null,
    /**
     * What this phone knows about the server's own manifest. [ServerRecord.Unknown] until it has
     * been asked, and [ServerRecord.Legacy] for a server that publishes none — which is a direct
     * server from before Stage 7.1, behaving exactly as it always has.
     */
    val server: ServerRecord = ServerRecord.Unknown,
    /**
     * The colour this connection is marked with everywhere it appears (SEE-83). Chosen by the owner
     * in the connection sheet, auto-assigned at pairing from the next unused palette entry, and
     * purely local: cosmetic, so a record whose stored colour is missing or unreadable stays
     * readable and simply gets an assignment again.
     */
    val colour: ServerColour? = null,
    /**
     * The saved wallet profile bound to this connection (SEE-174,
     * docs/guides/wallet-setup.md#one-wallet-per-connection), or null when the owner hasn't chosen
     * one. It is the owner's choice and nobody else's: a server's manifest can't set it, and
     * removing a profile clears it rather than picking another. What it is *for* depends on the
     * mode:
     *
     * - **Direct**: the wallet this server's requests are reviewed and signed with, published to
     *   the server as the connection's binding. Every review, preparation and signing for the
     *   connection resolves the wallet from it and from nothing global.
     * - **Gateway feed**: only the feed's *access identity* (SEE-185) — the address a restricted
     *   feed's publisher approved — and nothing else. A public feed needs none. A feed operation's
     *   wallet is chosen per operation, for the network its signal names
     *   ([io.github.brrenat.seekervault.wallet.WalletRepository.feedWallet]), so changing the
     *   execution wallet never touches access, and changing this never moves an operation.
     *
     * [LEGACY_WALLET_PROFILE] marks a record stored before profiles existed, when every connection
     * used the one wallet. It resolves to nothing until [ConnectionRepository.adoptLegacyWallet]
     * replaces it with the profile that wallet became.
     */
    val walletProfileId: String? = null,
) {
    init {
        // A gateway connection exists only because a manifest was read for it: its gateway
        // reference and the plugins it needs all come from that manifest, and there is no other
        // way to reach one.
        require((mode == null) != (retirement == null)) {
            "a connection is either active or retired"
        }
        require(retirement == null || server == ServerRecord.Unknown) {
            "a retired connection carries no active server manifest"
        }
        require(mode == null || mode == ConnectionMode.Direct || server is ServerRecord.Known) {
            "a gateway connection needs its manifest"
        }
        // And a manifest a connection holds is always a manifest about that connection's mode,
        // which manifestFrom is what guarantees.
        require(mode == null || server.manifest?.mode?.equals(mode) != false) {
            "a $mode connection cannot hold a ${server.manifest?.mode} manifest"
        }
    }

    /** The last time the phone asked the sidecar for the connection's pending requests. */
    data class Check(
        val at: Instant,
        val outcome: CheckOutcome,
        val pending: Int? = null,
        /** More requests are pending than the one page the phone fetched. */
        val morePending: Boolean = false,
    )

    /**
     * Whether the phone can still call this connection's sidecar: it has to be the kind of server
     * the phone calls at all, it must not have been revoked, and this phone must still hold its
     * credential.
     *
     * The mode belongs in this one condition on purpose. Refresh, synchronization, push
     * registration, wallet publication and every approval path are already gated on it, so a feed —
     * which the phone never calls, and holds no credential for — is excluded from all of them here
     * rather than in thirty places that could each be forgotten. What a feed's own reachability
     * means is the gateway's question, and SEE-90 answers it.
     */
    val usable: Boolean
        get() = mode == ConnectionMode.Direct && revokedAt == null && hasCredential

    companion object {
        /**
         * What a connection stored before SEE-174 names as its wallet: "the one wallet this phone
         * had". Never a real profile ID, and never written by anything but the store's upgrade.
         */
        const val LEGACY_WALLET_PROFILE = "legacy:single-wallet"
    }
}

/** Historical reason a stored connection is visible but cannot perform any action. */
enum class ConnectionRetirement(val code: String) {
    GatewayPrivateRemoved("gateway_private_removed")
}

enum class CheckOutcome {
    Ok,
    Unreachable,
    CertificateRejected,
    CleartextBlocked,
    Failed,
}

/** Why a new name for a connection isn't accepted. */
enum class LabelProblem {
    Blank,
    TooLong,
}

const val MAX_LABEL_LENGTH = 64

fun labelProblem(label: String): LabelProblem? {
    val trimmed = label.trim()
    return when {
        trimmed.isEmpty() -> LabelProblem.Blank
        trimmed.codePointCount(0, trimmed.length) > MAX_LABEL_LENGTH -> LabelProblem.TooLong
        else -> null
    }
}
