package io.github.brrenat.seekervault.servers

import io.github.brrenat.seekervault.connections.PairingCodes
import io.github.brrenat.seekervault.connections.isConnectionId
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginId
import io.github.brrenat.seekervault.plugins.isPluginId
import io.github.brrenat.seekervault.server.v1.ConnectionMode as WireMode
import io.github.brrenat.seekervault.server.v1.FeedAccessPolicy as WireAccessPolicy
import io.github.brrenat.seekervault.server.v1.GatewayFeed as WireFeed
import io.github.brrenat.seekervault.server.v1.ServerEnvironment as WireEnvironment
import io.github.brrenat.seekervault.server.v1.ServerManifest as WireManifest

/**
 * Reading a manifest a server published (SEE-88, docs/wiki/server-manifests.md).
 *
 * Every rule here exists because a manifest is a statement by the other side, and the phone has to
 * be able to refuse one without being confused by it. They fall into three groups:
 *
 * - **Identity.** The manifest must be about the server the connection already trusts, at the
 *   origin it already goes to, in the mode the connection is already in. Nothing here can move a
 *   connection: a manifest confirms where the phone is talking, and can never redirect it
 *   (`docs/security.md`).
 * - **Ownership.** A publisher may name only its own channel, so a manifest cannot claim another
 *   publisher's audience.
 * - **Boundedness.** A revision that moves forward, at most [MAX_REQUIRED_PLUGINS] well-formed
 *   plugin names, environments named explicitly, and a name short enough to show.
 *
 * A protocol version this build doesn't speak is *not* a refusal: it is a valid statement about a
 * contract this app can't act on, and it is reported as [ServerSupport.ProtocolUnsupported] so the
 * owner is told the app needs an update rather than that the server is broken. Only version zero,
 * which is never published, is malformed.
 */
data class ManifestExpectation(
    /** The identity the phone already trusts: the paired server's ID, or a feed reference's. */
    val serverId: String,
    /** The mode the connection is in. A manifest that changes it is refused, never followed. */
    val mode: ConnectionMode,
    /**
     * The origin the phone already uses for this connection: the paired server URL, or the gateway
     * the feed was added through.
     */
    val origin: String,
    /**
     * The revision the phone already holds, if any. A manifest may repeat it or exceed it; one
     * below it is refused, because a revision never goes backwards and a replayed older manifest
     * would otherwise restore settings the server has moved on from.
     */
    val heldRevision: Long? = null,
    /**
     * The phone already knows this feed is restricted — the reference it is being added from said
     * so, or the manifest it holds does (SEE-156). A manifest that now describes the feed as public
     * is refused ([ManifestProblem.AccessDowngraded]) rather than acted on: access only ever
     * tightens on its own.
     */
    val restricted: Boolean = false,
)

sealed interface ManifestResult {
    data class Valid(val manifest: ServerManifest) : ManifestResult

    data class Invalid(val problem: ManifestProblem) : ManifestResult
}

/** Which rule a manifest broke. Each is a separate fact, and none of them is a guess. */
enum class ManifestProblem(val code: String) {
    /** Version zero, which is never published: the server said nothing about its contract. */
    NoProtocol("no_protocol"),
    /** The identity isn't a lowercase UUID. */
    BadServerId("bad_server_id"),
    /** A well-formed identity, but not the one this connection trusts. */
    OtherServer("other_server"),
    /** No explicit mode. A missing mode is never read as a feed. */
    NoMode("no_mode"),
    /** A mode other than the one the connection is in: a manifest cannot switch transports. */
    OtherMode("other_mode"),
    /** No reference, or one that doesn't match the mode it declares. */
    BadReference("bad_reference"),
    /** The endpoint is malformed, or not a scheme this build will use. */
    BadEndpoint("bad_endpoint"),
    /** A usable endpoint, but not the one this connection already goes to. */
    OtherEndpoint("other_endpoint"),
    /** A channel this manifest's own server doesn't own. */
    ForeignChannel("foreign_channel"),
    /** Revision zero: a manifest with no revision can't be cached or compared. */
    NoRevision("no_revision"),
    /** A revision below the one the phone already holds. */
    StaleRevision("stale_revision"),
    /**
     * The content changed while the revision stood still. The revision is the server's promise
     * about the content, so the two disagreeing is a contradiction rather than an update: the phone
     * keeps neither, because it has no way to tell which one the server meant.
     */
    ChangedWithoutRevision("changed_without_revision"),
    /** A required plugin's name or contract range is malformed. */
    BadPlugin("bad_plugin"),
    /** The same plugin required twice, which says two things about one name. */
    DuplicatePlugin("duplicate_plugin"),
    /** More required plugins than a manifest may name. */
    TooManyPlugins("too_many_plugins"),
    /** An environment left unspecified, repeated, or none named at all. */
    BadEnvironment("bad_environment"),
    /** A name too long, or one that isn't printable text. */
    BadName("bad_name"),
    /**
     * An access policy this build does not know, or a restricted feed whose authentication origin
     * is not an origin this phone may send a wallet proof to (SEE-156). Never read as public.
     */
    BadAccess("bad_access"),
    /**
     * A feed that was restricted — by the reference it was added from, or by the manifest this
     * phone already holds — now described as public. A link or a gateway cannot open a feed to this
     * phone by saying less (SEE-156).
     */
    AccessDowngraded("access_downgraded"),
}

/**
 * Validates [message] against what the phone already trusts about the connection ([expect]), and
 * returns the manifest or the first rule it broke.
 *
 * The checks run in the order they are written, so the problem reported is the most fundamental
 * one: what the server claims to be comes before where it claims to be, which comes before what it
 * asks of this build.
 */
fun manifestFrom(message: WireManifest, expect: ManifestExpectation): ManifestResult {
    if (message.protocolVersion <= 0) return invalid(ManifestProblem.NoProtocol)
    if (!isConnectionId(message.serverId)) return invalid(ManifestProblem.BadServerId)
    if (message.serverId != expect.serverId) return invalid(ManifestProblem.OtherServer)
    val mode =
        when (message.mode) {
            WireMode.CONNECTION_MODE_DIRECT -> ConnectionMode.Direct
            WireMode.CONNECTION_MODE_GATEWAY_FEED -> ConnectionMode.GatewayFeed
            // Unspecified, or a mode from a later version of the format. Either way this phone
            // doesn't know how it would reach the server, and it doesn't pick one for it.
            else -> return invalid(ManifestProblem.NoMode)
        }
    if (mode != expect.mode) return invalid(ManifestProblem.OtherMode)
    // A uint64 above Long.MAX_VALUE arrives here as a negative number, which is refused with
    // everything else that can't be compared: a revision the phone can't order is no revision.
    if (message.settingsRevision <= 0L) return invalid(ManifestProblem.NoRevision)
    if (expect.heldRevision != null && message.settingsRevision < expect.heldRevision) {
        return invalid(ManifestProblem.StaleRevision)
    }
    referenceProblem(message, mode, expect)?.let {
        return invalid(it)
    }
    val reference = reference(message, mode)
    val required = mutableListOf<PluginRequirement>()
    if (message.requiredPluginsCount > MAX_REQUIRED_PLUGINS) {
        return invalid(ManifestProblem.TooManyPlugins)
    }
    for (requirement in message.requiredPluginsList) {
        if (!isPluginId(requirement.pluginId)) return invalid(ManifestProblem.BadPlugin)
        val least = requirement.minContract
        val most = requirement.maxContract
        if (least < 1 || most < least) return invalid(ManifestProblem.BadPlugin)
        val id = PluginId(requirement.pluginId)
        if (required.any { it.id == id }) return invalid(ManifestProblem.DuplicatePlugin)
        required += PluginRequirement(id, least..most)
    }
    val environments = mutableSetOf<PluginEnvironment>()
    for (environment in message.environmentsList) {
        val named =
            when (environment) {
                WireEnvironment.SERVER_ENVIRONMENT_PRODUCTION -> PluginEnvironment.Production
                WireEnvironment.SERVER_ENVIRONMENT_SANDBOX -> PluginEnvironment.Sandbox
                else -> return invalid(ManifestProblem.BadEnvironment)
            }
        if (!environments.add(named)) return invalid(ManifestProblem.BadEnvironment)
    }
    if (environments.isEmpty()) return invalid(ManifestProblem.BadEnvironment)
    if (!printableName(message.displayName)) return invalid(ManifestProblem.BadName)
    return ManifestResult.Valid(
        ServerManifest(
            serverId = message.serverId,
            protocolVersion = message.protocolVersion,
            settingsRevision = message.settingsRevision,
            mode = mode,
            reference = reference,
            required = required.toList(),
            environments = environments.toSet(),
            name = message.displayName,
        )
    )
}

/** Why this manifest's reference can't be used, or null if it can. */
private fun referenceProblem(
    message: WireManifest,
    mode: ConnectionMode,
    expect: ManifestExpectation,
): ManifestProblem? =
    when (mode) {
        ConnectionMode.Direct -> {
            if (!message.hasDirect()) ManifestProblem.BadReference
            else if (
                PairingCodes.serverUrlProblem(message.direct.url, CLEARTEXT_ALREADY_DECIDED) != null
            )
                ManifestProblem.BadEndpoint
            // The origin the phone already goes to, character for character. That is the whole of
            // the check: a manifest can name where the credential already goes, and nothing else.
            else if (message.direct.url != expect.origin) ManifestProblem.OtherEndpoint else null
        }
        ConnectionMode.GatewayFeed -> {
            if (!message.hasFeed()) ManifestProblem.BadReference
            else if (
                FeedReferences.gatewayUrlProblem(
                    message.feed.gatewayUrl,
                    CLEARTEXT_ALREADY_DECIDED,
                ) != null
            )
                ManifestProblem.BadEndpoint
            else if (message.feed.gatewayUrl != expect.origin) ManifestProblem.OtherEndpoint
            else if (message.feed.channel != channelFor(message.serverId))
                ManifestProblem.ForeignChannel
            else if (accessOf(message.feed) == null) ManifestProblem.BadAccess
            else if (expect.restricted && accessOf(message.feed) !is FeedAccess.Restricted)
                ManifestProblem.AccessDowngraded
            else null
        }
    }

/**
 * Whether plain HTTP may reach a host is decided where a URL enters the phone — a pairing code or a
 * feed reference, against the platform's own network security policy — and a manifest's endpoint
 * has to *equal* the URL that already passed it. So the rule applied here is the shape of a URL and
 * not the permission: asking the platform again would let a development build and a release build
 * disagree about a connection that already exists, and the answer would change nothing, because an
 * origin the platform refuses could never be the one this connection is using.
 */
private val CLEARTEXT_ALREADY_DECIDED: (host: String) -> Boolean = { true }

private fun reference(message: WireManifest, mode: ConnectionMode): ServerReference =
    when (mode) {
        ConnectionMode.Direct -> ServerReference.Direct(message.direct.url)
        ConnectionMode.GatewayFeed ->
            ServerReference.Feed(
                message.feed.gatewayUrl,
                message.feed.channel,
                checkNotNull(accessOf(message.feed)),
            )
    }

/**
 * A feed's access policy (SEE-156), or null when it is not one this phone will act on.
 *
 * No field is public, which is every manifest before restricted feeds existed. A restricted feed's
 * authentication origin is the one address this phone will send a wallet proof to, so it is held to
 * a gateway's own rules — an origin, with no path — and to the gateway's scheme: HTTPS, or plain
 * HTTP only when the gateway itself is plain HTTP, which only a development build's cleartext
 * policy ever admitted. An unspecified or unknown policy is null, never public.
 */
private fun accessOf(feed: WireFeed): FeedAccess? {
    if (!feed.hasAccess()) return FeedAccess.Public
    val access = feed.access
    return when (access.policy) {
        WireAccessPolicy.FEED_ACCESS_POLICY_PUBLIC ->
            FeedAccess.Public.takeIf { access.authOrigin.isEmpty() }
        WireAccessPolicy.FEED_ACCESS_POLICY_RESTRICTED -> {
            val origin = access.authOrigin
            val scheme = origin.substringBefore("://", "").lowercase()
            val gatewayScheme = feed.gatewayUrl.substringBefore("://", "").lowercase()
            when {
                origin.isEmpty() -> null
                FeedReferences.gatewayUrlProblem(origin, CLEARTEXT_ALREADY_DECIDED) != null -> null
                scheme != "https" && !(scheme == "http" && gatewayScheme == "http") -> null
                else -> FeedAccess.Restricted(PairingCodes.normalizeServerUrl(origin))
            }
        }
        else -> null
    }
}

/**
 * Whether [name] is something the app can show: short enough, and text rather than control
 * characters, which a server could otherwise use to make its own name read as something else.
 */
private fun printableName(name: String): Boolean =
    name.toByteArray(Charsets.UTF_8).size <= MAX_SERVER_NAME_BYTES &&
        name.none { it.isISOControl() } &&
        name == name.trim()

private fun invalid(problem: ManifestProblem) = ManifestResult.Invalid(problem)
