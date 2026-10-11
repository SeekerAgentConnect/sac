package io.github.brrenat.seekervault.servers

import io.github.brrenat.seekervault.plugins.PROVIDER_CONTRACT
import io.github.brrenat.seekervault.plugins.PluginId
import io.github.brrenat.seekervault.plugins.ProviderRegistry
import io.github.brrenat.seekervault.plugins.TestExecutionProvider
import io.github.brrenat.seekervault.plugins.jupiterLike
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether this build supports a server, matched against the plugins compiled into it (SEE-88).
 *
 * The states are kept apart because they are different things to tell someone, and the acceptance
 * for this ticket is exactly that: a build that carries Swap but not Prediction says which one is
 * missing, and one that carries a plugin at the wrong version says that instead.
 *
 * Nothing here is stored. That is the reason these are all pure calls: the same manifest on disk
 * gets a different answer from a build that carries more plugins, and it has to, or installing an
 * update would leave yesterday's verdict in a file.
 */
class ServerSupportTest {
    private val swap = PluginId(SWAP_PLUGIN)
    private val prediction = PluginId(PREDICTION_PLUGIN)

    private fun registry(vararg plugins: TestExecutionProvider) = ProviderRegistry.of(*plugins)

    private fun manifest(
        required: List<Pair<String, IntRange>> = emptyList(),
        protocol: Int = SERVER_PROTOCOL,
    ): ServerRecord =
        ServerRecord.Known(
            (manifestFrom(
                    feedManifest(
                        required = required,
                        protocol = protocol,
                    ),
                    ManifestExpectation(
                        serverId = SERVER_B,
                        mode = ConnectionMode.GatewayFeed,
                        origin = GATEWAY,
                    ),
                )
                    as ManifestResult.Valid)
                .manifest
        )

    private fun support(
        record: ServerRecord,
        plugins: ProviderRegistry = registry(),
    ) = serverSupport(record, plugins)

    @Test
    fun aServerWhosePluginsAreAllHereIsSupportedAndExecutable() {
        val state =
            support(
                manifest(required = listOf(SWAP_PLUGIN to 1..1)),
                registry(jupiterLike()),
            )

        assertEquals(ServerSupport.Supported, state)
        assertTrue(state.executable)
    }

    @Test
    fun aServerThatNeedsNothingIsSupportedByAnyBuild() {
        assertEquals(ServerSupport.Supported, support(manifest()))
    }

    @Test
    fun aBuildWithSwapButNotPredictionSaysWhichOneIsMissing() {
        // The acceptance case: one of the two Stage 7.1 plugins is installed and the other isn't,
        // and the owner is told what is missing rather than that the server is broken.
        val state =
            support(
                manifest(required = listOf(SWAP_PLUGIN to 1..1, PREDICTION_PLUGIN to 1..1)),
                registry(jupiterLike()),
            )

        assertEquals(ServerSupport.PluginMissing(listOf(prediction)), state)
        assertFalse(state.executable)
    }

    @Test
    fun aPluginThisBuildCarriesAtTheWrongVersionIsIncompatibleAndNotMissing() {
        // The two are different facts: one says this build has nothing for the job, the other says
        // it has something the server doesn't work with. Neither is a reason to call it anyway.
        val state =
            support(
                manifest(required = listOf(SWAP_PLUGIN to 3..4)),
                registry(jupiterLike(contract = PROVIDER_CONTRACT)),
            )

        assertEquals(ServerSupport.PluginIncompatible(listOf(swap)), state)
        assertFalse(state.executable)
    }

    @Test
    fun aPluginWrittenAgainstABoundaryThisBuildNoLongerCallsIsIncompatibleToo() {
        // The server's range covers it, but this build doesn't call that boundary any more, so
        // there is still no agreed contract to call it through (SEE-86).
        val state =
            support(
                manifest(required = listOf(SWAP_PLUGIN to 1..9)),
                registry(jupiterLike(contract = 9)),
            )

        assertEquals(ServerSupport.PluginIncompatible(listOf(swap)), state)
    }

    @Test
    fun aMissingPluginIsReportedBeforeOneAtTheWrongVersion() {
        val state =
            support(
                manifest(required = listOf(SWAP_PLUGIN to 3..4, PREDICTION_PLUGIN to 1..1)),
                registry(jupiterLike()),
            )

        assertEquals(ServerSupport.PluginMissing(listOf(prediction)), state)
    }

    @Test
    fun aProtocolVersionThisBuildDoesNotSpeakIsReportedAheadOfAnythingItAsksFor() {
        // A contract this app can't speak makes the rest of the manifest unactionable: there is no
        // point telling the owner about a plugin when the app couldn't talk to the server anyway.
        val state = support(manifest(required = listOf(SWAP_PLUGIN to 1..1), protocol = 9))

        assertEquals(ServerSupport.ProtocolUnsupported(9), state)
        assertFalse(state.executable)
    }

    @Test
    fun aFeedHeldFromAGatewayThisBuildDoesNotTrustIsReadableAndNeverExecutable() {
        // SEE-185: one trusted gateway. A feed added from another one before is held as it stands,
        // and nothing from it is executable however supported its manifest is.
        val record = manifest(required = listOf(SWAP_PLUGIN to 1..1))
        val plugins = registry(jupiterLike())

        val other = feedSupport(GATEWAY, record, plugins, "https://other.example.com")
        assertEquals(ServerSupport.OtherGateway, other)
        assertFalse(other.executable)
        // The trusted one, compared as an origin: a trailing slash is the same gateway.
        assertEquals(ServerSupport.Supported, feedSupport(GATEWAY, record, plugins, "$GATEWAY/"))
        // No trusted gateway to compare against is refused too, never read as "any gateway".
        assertEquals(ServerSupport.OtherGateway, feedSupport(GATEWAY, record, plugins, ""))
        // Null skips the comparison, which only a test of something else asks for.
        assertEquals(ServerSupport.Supported, feedSupport(GATEWAY, record, plugins, null))
    }

    @Test
    fun aLegacyServerRequiresNothingAndKeepsWorking() {
        // The documented path for a direct server that publishes no manifest: it asked for
        // nothing, so there is nothing it can be found wanting for.
        val state = support(ServerRecord.Legacy)

        assertEquals(ServerSupport.LegacyDirect, state)
        assertTrue(state.executable)
    }

    @Test
    fun aServerNotYetAskedIsExecutableBecauseItHasClaimedNothing() {
        // Only a direct connection the owner paired can be in this state, and they could always
        // act on it. An operation a plugin would serve is still resolved per request (SEE-86), so
        // nothing is executed on the strength of not having asked.
        val state = support(ServerRecord.Unknown)

        assertEquals(ServerSupport.Unknown, state)
        assertTrue(state.executable)
    }

    @Test
    fun aRefusedManifestIsNotTheSameAsNoManifestAtAll() {
        // A server that published nothing made no claim. One whose claim was refused did, and
        // this phone wouldn't accept it, so nothing from it is acted on.
        val state = support(ServerRecord.Refused(ManifestProblem.ForeignChannel))

        assertEquals(ServerSupport.ManifestRefused(ManifestProblem.ForeignChannel), state)
        assertFalse(state.executable)
    }

    @Test
    fun theSameManifestBecomesSupportedWhenABuildCarriesWhatItNeeds() {
        // Why support is derived and never stored: this is one manifest and two builds.
        val record = manifest(required = listOf(SWAP_PLUGIN to 1..1))

        assertEquals(ServerSupport.PluginMissing(listOf(swap)), support(record))
        assertEquals(
            ServerSupport.Supported,
            support(record, registry(jupiterLike())),
        )
    }
}
