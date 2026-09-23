package io.github.brrenat.seekervault.servers

import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginId

/**
 * What a server says about itself, as this phone validated it (SEE-88,
 * docs/wiki/server-manifests.md).
 *
 * Stage 7.1 has two kinds of server. The owner's own private server is one this phone paired with
 * and calls directly, holding a credential for it. A developer's publisher broadcasts proposals to
 * everyone subscribed, and the phone reads them through the shared gateway without ever contacting
 * the publisher. A manifest is how the phone learns which one it has, what contract that server
 * speaks, and which bundled client plugins its operations need.
 *
 * This is the validated form, and nothing reaches it unchecked: [manifestFrom] turns the protocol
 * message into one of these or says which rule it broke. A field this phone doesn't understand is
 * an unknown field and is dropped, not guessed at.
 *
 * What a manifest cannot say is the point of it being data. There is nothing here that installs
 * code, asks for a permission, carries a policy, relaxes one, or names a wallet endpoint: what the
 * phone will do with a server is decided by the build it is running and by the owner
 * (docs/security.md).
 */
data class ServerManifest(
    /**
     * The server's lasting ID, a lowercase UUID. It is the identity the connection already trusts —
     * for a direct server the one in its pairing code — and a manifest naming any other is refused,
     * so a manifest can never move a connection to a different server.
     */
    val serverId: String,
    /**
     * Which phone–server contract the server speaks. [SUPPORTED_SERVER_PROTOCOLS] is what this
     * build speaks; a version outside it is a valid statement this app can't act on, which is
     * reported as [ServerSupport.ProtocolUnsupported] rather than treated as the nearest one it
     * knows.
     */
    val protocolVersion: Int,
    /**
     * The revision this content was published as. The phone caches by it: an unchanged revision
     * means what it holds is current, a higher one means the server's settings moved, and a lower
     * one is refused because a revision never goes backwards.
     */
    val settingsRevision: Long,
    /** How the phone reaches this server. It always agrees with [reference]. */
    val mode: ConnectionMode,
    val reference: ServerReference,
    /**
     * The bundled client plugins this server's operations need (SEE-86). Matched against the
     * plugins compiled into this build by [serverSupport]; one this build doesn't carry is reported
     * as missing and never fetched.
     */
    val required: List<PluginRequirement> = emptyList(),
    /**
     * The environments the server serves. A server is not supported in an environment it doesn't
     * name: sandbox and production are different promises about what happens when the owner
     * approves (SEE-97).
     */
    val environments: Set<PluginEnvironment> = emptySet(),
    /**
     * The name the server calls itself, or empty. It is the server's own claim and is never
     * verified: it is a default the owner can rename, and their name is the one the app shows.
     */
    val name: String = "",
) {
    init {
        require(mode == reference.mode) {
            "a $mode manifest cannot carry a ${reference.mode} reference"
        }
    }
}

/**
 * Which transport a connection uses. It is stored per connection, so one phone can hold a direct
 * connection to its own server and several gateway feeds at once, each unaffected by the others.
 */
enum class ConnectionMode(val code: String) {
    /**
     * The phone calls the server itself with the credential it holds from pairing: the existing
     * private workflow. A connection whose server publishes no manifest at all is this by
     * definition, and a missing mode is never read as the other one.
     */
    Direct("direct"),

    /**
     * The phone reads the server's proposals from the shared gateway and never calls the server. It
     * holds no credential for it, and the server learns nothing about the phone.
     */
    GatewayFeed("gateway_feed"),
}

/** Where a server is reached, which is one thing for each mode and never both. */
sealed interface ServerReference {
    val mode: ConnectionMode

    /**
     * The origin this reference addresses. The phone accepts a manifest only when this is the
     * origin it already trusts for the connection, so a reference confirms where it goes and can
     * never redirect it.
     */
    val origin: String

    /** A direct server, at the URL the phone paired with. */
    data class Direct(val url: String) : ServerReference {
        override val mode: ConnectionMode
            get() = ConnectionMode.Direct

        override val origin: String
            get() = url
    }

    /**
     * A publisher's feed, read from [gatewayUrl] on [channel]. The publisher's own address is
     * deliberately absent: there is nothing here for the phone to contact.
     */
    data class Feed(val gatewayUrl: String, val channel: String) : ServerReference {
        override val mode: ConnectionMode
            get() = ConnectionMode.GatewayFeed

        override val origin: String
            get() = gatewayUrl
    }
}

/**
 * One bundled plugin a server's operations need, named by the stable ID the plugin declares
 * ([io.github.brrenat.seekervault.plugins.ProviderCapabilities.id]).
 *
 * [contracts] is the range of plugin-boundary versions the server works with. A plugin in this
 * build whose contract falls outside it is reported as incompatible rather than called through a
 * contract neither side agreed on.
 */
data class PluginRequirement(val id: PluginId, val contracts: IntRange) {
    init {
        require(!contracts.isEmpty()) { "an empty contract range requires nothing callable" }
        require(contracts.first >= 1) { "contract versions start at 1" }
    }
}

/**
 * What this phone knows about one connection's server manifest.
 *
 * The four cases are kept apart because they are different things to tell the owner, and only one
 * of them is a fault: a server that publishes nothing is the documented legacy path, a server that
 * hasn't been asked yet is simply unread, and a server whose manifest was refused made a claim this
 * phone would not accept.
 */
sealed interface ServerRecord {
    /**
     * Not asked yet, or the answer hasn't arrived. Nothing is assumed from it — in particular the
     * connection is not a feed, because a mode is never guessed.
     */
    data object Unknown : ServerRecord

    /**
     * The server has no manifest: a direct server from before Stage 7.1, which is a documented path
     * and not a defect (docs/wiki/server-manifests.md#legacy-direct). It keeps working exactly as
     * it always has.
     */
    data object Legacy : ServerRecord

    /** The manifest, as this phone validated it. */
    data class Known(val manifest: ServerManifest) : ServerRecord

    /**
     * The server published something this phone refused, and which rule it broke. The connection is
     * not revoked by this and its credential still goes only where it always did, but nothing from
     * it is executable: a server whose own description can't be read is not one to act for.
     */
    data class Refused(val problem: ManifestProblem) : ServerRecord
}

/** The manifest when there is one, for the reads that don't care why there isn't. */
val ServerRecord.manifest: ServerManifest?
    get() = (this as? ServerRecord.Known)?.manifest

/**
 * The phone–server contract this build speaks, and the versions it accepts. It goes up when a
 * change would make an older server wrong to talk to; a server outside the range is reported as
 * unsupported, never adapted to.
 */
const val SERVER_PROTOCOL: Int = 1

val SUPPORTED_SERVER_PROTOCOLS: IntRange = SERVER_PROTOCOL..SERVER_PROTOCOL

/** A manifest is bounded data: this much of it, and no more. */
const val MAX_REQUIRED_PLUGINS: Int = 16

const val MAX_SERVER_NAME_BYTES: Int = 64
