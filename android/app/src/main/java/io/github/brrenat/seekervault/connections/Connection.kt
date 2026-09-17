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
 * all. One phone holds any mixture of the two, and neither affects the other.
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
     * The name this phone gave the sidecar when it paired; empty for a feed, which pairs with
     * nothing.
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
    val mode: ConnectionMode = ConnectionMode.Direct,
    /**
     * What this phone knows about the server's own manifest. [ServerRecord.Unknown] until it has
     * been asked, and [ServerRecord.Legacy] for a server that publishes none — which is a direct
     * server from before Stage 7.1, behaving exactly as it always has.
     */
    val server: ServerRecord = ServerRecord.Unknown,
) {
    init {
        // A feed exists only because a manifest was read for it: the gateway, the channel and the
        // plugins it needs all come from that manifest, and there is no other way to reach one.
        require(mode == ConnectionMode.Direct || server is ServerRecord.Known) {
            "a feed connection needs its manifest"
        }
        // And a manifest a connection holds is always a manifest about that connection's mode,
        // which manifestFrom is what guarantees.
        require(server.manifest?.mode?.equals(mode) != false) {
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
