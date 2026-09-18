package io.github.brrenat.seekervault.servers

import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginId
import io.github.brrenat.seekervault.server.v1.ConnectionMode as WireMode
import io.github.brrenat.seekervault.server.v1.ServerEnvironment
import io.github.brrenat.seekervault.server.v1.ServerManifest as WireManifest
import io.github.brrenat.seekervault.server.v1.copy
import io.github.brrenat.seekervault.server.v1.gatewayFeed
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reading a manifest a server published (SEE-88).
 *
 * Everything here is about refusing one without being confused by it. A manifest is a statement by
 * the other side, and the rules it has to pass are the ones that keep it from being able to say
 * anything about *where* the phone is talking: the identity, the origin and the mode are all things
 * the phone already knows, and a manifest can only confirm them.
 */
class ServerManifestTest {
    private val direct =
        ManifestExpectation(serverId = SERVER_A, mode = ConnectionMode.Direct, origin = URL_A)
    private val feed =
        ManifestExpectation(
            serverId = SERVER_B,
            mode = ConnectionMode.GatewayFeed,
            origin = GATEWAY,
        )
    private val private =
        ManifestExpectation(
            serverId = SERVER_B,
            mode = ConnectionMode.GatewayPrivate,
            origin = GATEWAY,
        )

    private fun valid(message: WireManifest, expect: ManifestExpectation = direct): ServerManifest {
        val result = manifestFrom(message, expect)
        return (result as? ManifestResult.Valid)?.manifest
            ?: error("refused: ${(result as ManifestResult.Invalid).problem}")
    }

    private fun refused(
        message: WireManifest,
        expect: ManifestExpectation = direct,
    ): ManifestProblem = (manifestFrom(message, expect) as ManifestResult.Invalid).problem

    @Test
    fun aDirectServersManifestIsReadAsWhatTheConnectionAlreadyIs() {
        val manifest = valid(directManifest(revision = 4, name = "Home server"))

        assertEquals(SERVER_A, manifest.serverId)
        assertEquals(SERVER_PROTOCOL, manifest.protocolVersion)
        assertEquals(4L, manifest.settingsRevision)
        assertEquals(ConnectionMode.Direct, manifest.mode)
        assertEquals(ServerReference.Direct(URL_A), manifest.reference)
        assertEquals(emptyList<PluginRequirement>(), manifest.required)
        assertEquals(setOf(PluginEnvironment.Production), manifest.environments)
        assertEquals("Home server", manifest.name)
    }

    @Test
    fun aFeedsManifestNamesItsGatewayAndTheChannelItsOwnServerOwns() {
        val manifest = valid(feedManifest(required = listOf(SWAP_PLUGIN to 1..2)), feed)

        assertEquals(ConnectionMode.GatewayFeed, manifest.mode)
        assertEquals(ServerReference.Feed(GATEWAY, "server/$SERVER_B"), manifest.reference)
        assertEquals(listOf(PluginRequirement(PluginId(SWAP_PLUGIN), 1..2)), manifest.required)
    }

    @Test
    fun aPrivateManifestCanOnlyConfirmTheInvitationGateway() {
        val manifest = valid(privateManifest(name = "Trading agent"), private)

        assertEquals(ConnectionMode.GatewayPrivate, manifest.mode)
        assertEquals(ServerReference.GatewayPrivate(GATEWAY), manifest.reference)
        assertEquals("Trading agent", manifest.name)
        assertEquals(
            ManifestProblem.OtherEndpoint,
            refused(privateManifest(gateway = "https://elsewhere.example"), private),
        )
    }

    @Test
    fun aManifestAboutAnotherServerIsRefusedRatherThanFollowed() {
        // The identity is the one thing the phone already trusts for the connection — the pairing
        // code's server ID, or the reference's. Accepting another would move the connection.
        assertEquals(
            ManifestProblem.OtherServer,
            refused(directManifest(serverId = SERVER_B)),
        )
        assertEquals(ManifestProblem.BadServerId, refused(directManifest(serverId = "server-one")))
    }

    @Test
    fun aManifestThatChangesTheEndpointCannotRedirectTheCredential() {
        // The credential goes where the owner paired it and nowhere else, so a manifest naming
        // another origin is refused outright. It is not a failure of the connection: the phone
        // keeps calling the URL it always did (ConnectionRepositoryTest covers what it records).
        assertEquals(
            ManifestProblem.OtherEndpoint,
            refused(directManifest(url = "https://elsewhere.example.com")),
        )
        // A cleartext endpoint isn't one this build will use, whoever names it.
        assertEquals(
            ManifestProblem.BadEndpoint,
            refused(directManifest(url = "http://vault.example.com")),
        )
    }

    @Test
    fun aManifestCannotSwitchTheTransportItsConnectionUses() {
        // Never guess a mode: a paired direct connection stays direct, and a manifest claiming to
        // be a feed is refused instead of quietly becoming one.
        assertEquals(
            ManifestProblem.OtherMode,
            refused(feedManifest(serverId = SERVER_A, required = emptyList())),
        )
        // And a manifest with no mode at all says nothing this phone can act on.
        assertEquals(
            ManifestProblem.NoMode,
            refused(
                directManifest().copy {
                    mode = WireMode.CONNECTION_MODE_UNSPECIFIED
                }
            ),
        )
    }

    @Test
    fun aPublisherMayNameOnlyItsOwnChannel() {
        // The channel is derived from the manifest's own identity, so claiming another
        // publisher's channel is claiming their audience.
        assertEquals(
            ManifestProblem.ForeignChannel,
            refused(feedManifest(channel = channelFor(SERVER_A)), feed),
        )
        assertEquals(
            ManifestProblem.ForeignChannel,
            refused(feedManifest(channel = "server/$SERVER_B/extra"), feed),
        )
        // A feed's gateway is addressed by origin: a path would make one gateway two.
        assertEquals(
            ManifestProblem.BadEndpoint,
            refused(feedManifest(gateway = "$GATEWAY/feeds"), feed),
        )
    }

    @Test
    fun aModeAndAReferenceThatDisagreeSayNothing() {
        assertEquals(
            ManifestProblem.BadReference,
            refused(
                directManifest().copy {
                    clearDirect()
                    feed = gatewayFeed {
                        gatewayUrl = GATEWAY
                        channel = channelFor(SERVER_A)
                    }
                }
            ),
        )
    }

    @Test
    fun aRevisionHasToMoveForwardToBeCacheableAtAll() {
        assertEquals(ManifestProblem.NoRevision, refused(directManifest(revision = 0)))
        // A replayed older manifest would otherwise restore settings the server has moved past.
        assertEquals(
            ManifestProblem.StaleRevision,
            refused(directManifest(revision = 2), direct.copy(heldRevision = 3)),
        )
        // The one it already holds is not stale: a server that hasn't changed republishes it.
        assertEquals(
            3L,
            valid(directManifest(revision = 3), direct.copy(heldRevision = 3)).settingsRevision,
        )
    }

    @Test
    fun aProtocolVersionThisBuildDoesNotSpeakIsReadButNotSupported() {
        // Deliberately not a refusal: the server made a perfectly good statement about a contract
        // this app can't act on, which is something to tell the owner rather than a fault to
        // report against the server (ServerSupportTest).
        assertEquals(9, valid(directManifest(protocol = 9)).protocolVersion)
        // Version zero is never published, so it is malformed rather than newer.
        assertEquals(ManifestProblem.NoProtocol, refused(directManifest(protocol = 0)))
    }

    @Test
    fun requiredPluginsAreBoundedWellFormedNamesAndNothingLoadable() {
        assertEquals(
            ManifestProblem.BadPlugin,
            refused(directManifest(required = listOf("https://plugins.example.com/swap" to 1..1))),
        )
        // A name with no dot isn't a plugin ID, and a range that requires nothing callable isn't
        // a requirement.
        assertEquals(
            ManifestProblem.BadPlugin,
            refused(directManifest(required = listOf("swap" to 1..1))),
        )
        assertEquals(
            ManifestProblem.BadPlugin,
            refused(directManifest(required = listOf(SWAP_PLUGIN to 0..0))),
        )
        assertEquals(
            ManifestProblem.BadPlugin,
            refused(directManifest(required = listOf(SWAP_PLUGIN to 3..2))),
        )
        assertEquals(
            ManifestProblem.DuplicatePlugin,
            refused(directManifest(required = listOf(SWAP_PLUGIN to 1..1, SWAP_PLUGIN to 2..2))),
        )
        assertEquals(
            ManifestProblem.TooManyPlugins,
            refused(
                directManifest(
                    required = (1..MAX_REQUIRED_PLUGINS + 1).map { "plugin.p$it" to 1..1 }
                )
            ),
        )
    }

    @Test
    fun anEnvironmentHasToBeNamedExplicitly() {
        assertEquals(
            ManifestProblem.BadEnvironment,
            refused(directManifest(environments = emptyList())),
        )
        assertEquals(
            ManifestProblem.BadEnvironment,
            refused(
                directManifest(
                    environments = listOf(ServerEnvironment.SERVER_ENVIRONMENT_UNSPECIFIED)
                )
            ),
        )
        assertEquals(
            ManifestProblem.BadEnvironment,
            refused(
                directManifest(
                    environments =
                        listOf(
                            ServerEnvironment.SERVER_ENVIRONMENT_PRODUCTION,
                            ServerEnvironment.SERVER_ENVIRONMENT_PRODUCTION,
                        )
                )
            ),
        )
        assertEquals(
            setOf(PluginEnvironment.Production, PluginEnvironment.Sandbox),
            valid(
                    directManifest(
                        environments =
                            listOf(
                                ServerEnvironment.SERVER_ENVIRONMENT_PRODUCTION,
                                ServerEnvironment.SERVER_ENVIRONMENT_SANDBOX,
                            )
                    )
                )
                .environments,
        )
    }

    @Test
    fun aNameIsShortPrintableTextOrItIsNotShownAtAll() {
        assertEquals(ManifestProblem.BadName, refused(directManifest(name = "a".repeat(65))))
        // Control characters would let a server's own name read as something else on screen.
        assertEquals(ManifestProblem.BadName, refused(directManifest(name = "Home‍\nserver")))
        assertEquals(ManifestProblem.BadName, refused(directManifest(name = " Home ")))
    }

    @Test
    fun aManifestAndItsModeAreHeldTogetherByTheModelItself() {
        // The invariant is in the type, so nothing downstream has to check it: a manifest that
        // says one thing and references another cannot be constructed at all.
        val thrown = runCatching {
            ServerManifest(
                serverId = SERVER_A,
                protocolVersion = SERVER_PROTOCOL,
                settingsRevision = 1,
                mode = ConnectionMode.GatewayFeed,
                reference = ServerReference.Direct(URL_A),
            )
        }
            .exceptionOrNull()
        assertEquals(IllegalArgumentException::class.java, thrown?.javaClass)
    }
}
