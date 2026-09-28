package io.github.brrenat.seekervault

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.plugins.JUPITER_PREDICTION
import io.github.brrenat.seekervault.plugins.JUPITER_PROVIDER
import io.github.brrenat.seekervault.plugins.JUPITER_SWAP
import io.github.brrenat.seekervault.plugins.PREDICTION_BUY_ACTION
import io.github.brrenat.seekervault.plugins.PREDICTION_SELL_ACTION
import io.github.brrenat.seekervault.plugins.PROVIDER_CONTRACT
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.SWAP_ACTION
import io.github.brrenat.seekervault.request.v1.Network
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

/**
 * What this build actually ships (SEE-145).
 *
 * The acceptance for a test-only alternate provider is that it **cannot be selected in a production
 * build**, and the way that is kept true here is not a flag: the list of providers is the one in
 * `SeekerVaultApplication`, and `TestExecutionProvider` lives in `src/test`, so it is compiled into
 * no APK at all. This holds the shipped list to exactly one provider, so adding a second one to it
 * has to be a deliberate edit that fails here first.
 */
@RunWith(AndroidJUnit4::class)
class BundledProvidersTest {

    private val registry =
        (RuntimeEnvironment.getApplication() as SeekerVaultApplication).providerRegistry

    @Test
    fun theBuildCarriesJupiterAndNothingElse() {
        val carried = registry.capabilities

        assertEquals(listOf(JUPITER_PROVIDER), carried.map { it.id })
        assertNotNull(registry.byId(JUPITER_PROVIDER))
        // And it answers to both of the bundled-plugin names published before SEE-145, so every
        // manifest already out there still resolves.
        assertNotNull(registry.byLegacyPlugin(JUPITER_SWAP))
        assertNotNull(registry.byLegacyPlugin(JUPITER_PREDICTION))
        assertNull(
            registry.byLegacyPlugin(io.github.brrenat.seekervault.plugins.PluginId("example.swap"))
        )
    }

    @Test
    fun itServesTwoActionsOnOneClusterAndPromisesNoStatusQueries() {
        val jupiter = registry.capabilities.single()

        assertEquals(PROVIDER_CONTRACT, jupiter.contract)
        assertEquals(
            listOf(SWAP_ACTION, PREDICTION_BUY_ACTION),
            jupiter.actions.map { it.action },
        )
        jupiter.actions.forEach {
            assertEquals(setOf(Network.NETWORK_MAINNET), it.networks)
            assertEquals(1..1, it.schemaVersions)
        }
        assertEquals(
            setOf(PluginEnvironment.Production, PluginEnvironment.Sandbox),
            jupiter.environments,
        )
        // Since SEE-172 Jupiter reports a prediction order's fills by the order's own account; a
        // chain confirmation alone still never becomes "filled".
        assertTrue(jupiter.statusQueries)
        // Selling a held position is not a publishable action: it is reached only through the
        // provider's position management, never from a signal.
        assertEquals(
            PREDICTION_SELL_ACTION,
            checkNotNull(registry.byId(JUPITER_PROVIDER)?.positions).sale.action,
        )
    }

    @Test
    fun aDocumentNamingAProviderThisBuildDoesNotCarryResolvesToNothing() {
        val resolution =
            registry.resolve(
                provider = io.github.brrenat.seekervault.plugins.ExecutionProviderId("example"),
                action = SWAP_ACTION,
                schemaVersion = 1,
                network = Network.NETWORK_MAINNET,
                environment = PluginEnvironment.Production,
            )

        assertEquals(
            io.github.brrenat.seekervault.plugins.ProviderResolution.Unsupported(
                SWAP_ACTION,
                io.github.brrenat.seekervault.plugins.UnsupportedReason.NoProvider,
            ),
            resolution,
        )
    }
}
