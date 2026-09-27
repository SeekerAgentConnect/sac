package io.github.brrenat.seekervault.servers

import io.github.brrenat.seekervault.connections.PairingCodeProblem
import io.github.brrenat.seekervault.connections.PairingCodes
import io.github.brrenat.seekervault.connections.isConnectionId
import java.net.URI
import java.net.URISyntaxException

/**
 * How a publisher's feed is added: the shared gateway it publishes through, and which server it is
 * (SEE-88, docs/wiki/server-manifests.md#adding-a-feed).
 *
 * Unlike a pairing code this carries no secret, because there is nothing to authenticate. A feed is
 * a broadcast: the phone subscribes through the gateway, the publisher is never contacted, and it
 * learns nothing about the phone — so a reference can be printed in a README, and holding one
 * grants nothing.
 *
 * The publisher's own address is deliberately absent. Everything else about the server — its mode,
 * the contract it speaks, the plugins its operations need — comes from the manifest the gateway
 * holds, so a reference cannot say anything about the server that the server didn't publish.
 */
data class FeedReference(
    val gatewayUrl: String,
    val serverId: String,
    /**
     * The reference says the feed is restricted (`&access=restricted`, SEE-156). It is a hint the
     * owner is shown before anything is stored, and a floor: a feed added from a restricted
     * reference whose manifest says public is refused ([ManifestProblem.AccessDowngraded]). It
     * never supplies the authentication origin — that comes only from the manifest the gateway
     * serves.
     */
    val restricted: Boolean = false,
    /**
     * A single-use invitation this reference carries (`&invitation=…`), for a device that already
     * asked for access and is redeeming its approval by link or QR code. It is bound to that
     * device's key, so on any other phone it does nothing.
     */
    val invitation: String? = null,
) {
    /** The channel this server publishes on, which is the only one its manifest may name. */
    val channel: String
        get() = channelFor(serverId)
}

/**
 * The channel a server owns. A publisher may name only its own, so a manifest that claims another
 * channel is claiming another publisher's audience and is refused; the gateway holds the same rule
 * when it accepts a publication (SEE-90).
 */
fun channelFor(serverId: String): String = "server/$serverId"

sealed interface FeedReferenceResult {
    data class Valid(val reference: FeedReference) : FeedReferenceResult

    data class Invalid(val problem: FeedReferenceProblem) : FeedReferenceResult
}

/** Why a scanned or entered text can't be used to add a feed. */
enum class FeedReferenceProblem {
    /** Not a URI at all. */
    NotAReference,
    /** A URI, but not a `seekervault://feed` one. */
    NotSeekerVault,
    /** A feed reference for another version of the format. */
    OtherVersion,
    /** The gateway URL is missing or malformed, or has a path, user name, query, or fragment. */
    BadGatewayUrl,
    /** The gateway URL isn't HTTPS, and this build doesn't allow plain HTTP to it. */
    InsecureGatewayUrl,
    BadServerId,
    /** An `access` parameter other than `restricted` (SEE-156). */
    BadAccess,
    /** An `invitation` parameter that is not an invitation's shape (SEE-156). */
    BadInvitation,
}

/**
 * Reads feed references, by the same rules a pairing code is read by: one version, a URL that must
 * be HTTPS unless the platform permits cleartext to a loopback host, and a lowercase-UUID server
 * ID. The query is parsed by the pairing code's own parser, so a repeated parameter means the same
 * thing in both.
 */
object FeedReferences {
    const val VERSION = "1"

    /**
     * Parses [text], a `seekervault://feed?v=1&gateway=…&server=…` URI. [cleartextPermitted] says
     * whether plain HTTP may reach a host, which is how a development gateway on loopback is
     * reached from a debug build.
     */
    fun parse(text: String, cleartextPermitted: (host: String) -> Boolean): FeedReferenceResult {
        val uri =
            try {
                URI(text.trim())
            } catch (e: URISyntaxException) {
                return invalid(FeedReferenceProblem.NotAReference)
            }
        if (uri.scheme == null) return invalid(FeedReferenceProblem.NotAReference)
        if (!uri.scheme.equals("seekervault", ignoreCase = true) || uri.rawAuthority != "feed") {
            return invalid(FeedReferenceProblem.NotSeekerVault)
        }
        val query = PairingCodes.queryOf(uri.rawQuery.orEmpty())
        if (query["v"] != VERSION) return invalid(FeedReferenceProblem.OtherVersion)
        val gateway = query["gateway"].orEmpty()
        val serverId = query["server"].orEmpty()
        val access = query["access"]
        val invitation = query["invitation"]
        val problem =
            gatewayUrlProblem(gateway, cleartextPermitted)
                ?: FeedReferenceProblem.BadServerId.takeUnless { isConnectionId(serverId) }
                ?: FeedReferenceProblem.BadAccess.takeUnless {
                    access == null || access == "restricted"
                }
                ?: FeedReferenceProblem.BadInvitation.takeUnless {
                    invitation == null || isInvitation(invitation)
                }
        if (problem != null) return invalid(problem)
        return FeedReferenceResult.Valid(
            FeedReference(
                PairingCodes.normalizeServerUrl(gateway),
                serverId,
                // An invitation is only ever for a restricted feed.
                restricted = access == "restricted" || invitation != null,
                invitation = invitation,
            )
        )
    }

    /**
     * Why [url] can't be a gateway origin, or null if it can. A gateway is addressed by origin
     * only: a path would make one gateway two, and the phone compares a manifest's gateway against
     * this exact string.
     */
    fun gatewayUrlProblem(
        url: String,
        cleartextPermitted: (host: String) -> Boolean,
    ): FeedReferenceProblem? {
        val path =
            try {
                URI(url).rawPath.orEmpty()
            } catch (e: URISyntaxException) {
                return FeedReferenceProblem.BadGatewayUrl
            }
        if (path.trimEnd('/').isNotEmpty()) return FeedReferenceProblem.BadGatewayUrl
        return when (PairingCodes.serverUrlProblem(url, cleartextPermitted)) {
            null -> null
            PairingCodeProblem.InsecureServerUrl -> FeedReferenceProblem.InsecureGatewayUrl
            else -> FeedReferenceProblem.BadGatewayUrl
        }
    }

    private fun invalid(problem: FeedReferenceProblem) = FeedReferenceResult.Invalid(problem)

    /** An invitation's shape: 32 random bytes, base64url without padding. */
    private fun isInvitation(text: String): Boolean =
        text.length == 43 && text.all { it.isLetterOrDigit() || it == '-' || it == '_' }
}
