package io.github.brrenat.seekervault.plugins

import io.github.brrenat.seekervault.plugins.actions.ActionPayload
import io.github.brrenat.seekervault.plugins.actions.PredictionPayload
import io.github.brrenat.seekervault.plugins.actions.SwapPayload
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.request.v1.actionRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Registering a bundled execution provider, and resolving one action to exactly one of them
 * (SEE-145, SEE-86).
 *
 * Every refusal is asserted separately, because each is a different thing to tell someone: this
 * build has no such provider, it has one written against another version of the boundary, one that
 * does not serve the action, not at this schema version, not on this cluster, not in this
 * environment, or not in the asset the document names. None of them is a verdict about the action,
 * and none of them is a reason to go ahead without one.
 *
 * The other half of the acceptance lives here too: a second provider registers beside the first and
 * is resolved by the same code, with nothing provider-specific anywhere in it.
 */
class ProviderRegistryTest {
    private val production = PluginEnvironment.Production
    private val sandbox = PluginEnvironment.Sandbox
    private val mainnet = Network.NETWORK_MAINNET
    private val devnet = Network.NETWORK_DEVNET
    private val connection = "6a5f0a0e-2f2f-4a07-9a1f-4f0f2b3f9a11"

    @Test
    fun aTestProviderIsRegisteredAndResolvedForTheActionItServes() {
        val provider = TestExecutionProvider()
        val registry = ProviderRegistry.of(provider)

        assertEquals(listOf(provider.capabilities), registry.capabilities)
        assertSame(provider, registry.byId(ExecutionProviderId("test")))
        val resolved = resolve(registry)
        assertSame(provider, (resolved as ProviderResolution.Supported).provider)
        assertEquals(SWAP_ACTION, resolved.capability.action)
        // Resolving is a lookup: nothing was prepared, inspected, or asked of the provider.
        assertEquals(emptyList<String>(), provider.calls)
    }

    @Test
    fun aSecondProviderRegistersBesideTheFirstAndIsResolvedByTheSameCode() {
        // The acceptance criterion, in one case: two providers of the same action, told apart only
        // by the name the document gives, and no dispatch logic anywhere that knows either of them.
        val one = TestExecutionProvider(id = "one")
        val two = TestExecutionProvider(id = "two")
        val registry = ProviderRegistry.of(one, two)

        assertSame(one, (resolve(registry, ExecutionProviderId("one")) as Supported).provider)
        assertSame(two, (resolve(registry, ExecutionProviderId("two")) as Supported).provider)
    }

    @Test
    fun aProviderTheDocumentDoesNotNameIsNeverSubstitutedForTheOneItDoes() {
        // There is no routing and no fallback. A build carrying exactly one provider for `swap`
        // still refuses a document that asked for a different one — because a venue is not an
        // implementation detail, and a market at one is not the market at another.
        val registry = ProviderRegistry.of(TestExecutionProvider(id = "one"))

        assertEquals(
            ProviderResolution.Unsupported(SWAP_ACTION, UnsupportedReason.NoProvider),
            resolve(registry, ExecutionProviderId("two")),
        )
        // And a document that named none at all resolves to nothing rather than to whatever is
        // there: null is a refusal, not a wildcard.
        assertEquals(
            ProviderResolution.Unsupported(SWAP_ACTION, UnsupportedReason.NoProvider),
            resolve(registry, provider = null),
        )
    }

    @Test
    fun anActionTheProviderDoesNotServeIsRejectedAsItselfRatherThanGuessedAt() {
        val registry =
            ProviderRegistry.of(
                TestExecutionProvider(actions = listOf(testAction(action = ActionId("other"))))
            )

        assertEquals(
            ProviderResolution.Unsupported(SWAP_ACTION, UnsupportedReason.ActionUnsupported),
            resolve(registry),
        )
    }

    @Test
    fun aProviderWrittenAgainstAnotherBoundaryIsRejectedRatherThanAdapted() {
        // It is registered, and it claims the action. What it doesn't have is a contract this build
        // knows how to call, and calling it anyway would be guessing at the interface.
        val provider = TestExecutionProvider(contract = PROVIDER_CONTRACT + 1)
        val registry = ProviderRegistry.of(provider)

        assertEquals(listOf(provider.capabilities), registry.capabilities)
        assertEquals(
            ProviderResolution.Unsupported(SWAP_ACTION, UnsupportedReason.ContractUnsupported),
            resolve(registry),
        )
        // The same provider is still there to be named; it just can't be called.
        assertSame(provider, registry.byId(provider.capabilities.id))
    }

    @Test
    fun aSchemaVersionTheProviderDoesNotReadIsItsOwnRefusal() {
        val registry = ProviderRegistry.of(TestExecutionProvider())

        assertEquals(
            ProviderResolution.Unsupported(SWAP_ACTION, UnsupportedReason.SchemaUnsupported),
            resolve(registry, schemaVersion = 2),
        )
    }

    @Test
    fun aClusterTheProviderDoesNotServeIsToldApartFromAnEnvironmentItDoesNot() {
        // The two are different facts and the owner would change different settings for them, so
        // they are never collapsed: a provider with no devnet is not a provider with no sandbox
        // (docs/wiki/environments.md).
        val registry =
            ProviderRegistry.of(
                TestExecutionProvider(
                    actions = listOf(testAction(networks = setOf(mainnet))),
                    environments = setOf(production),
                )
            )

        assertEquals(
            ProviderResolution.Unsupported(SWAP_ACTION, UnsupportedReason.NetworkUnsupported),
            resolve(registry, network = devnet),
        )
        assertEquals(
            ProviderResolution.Unsupported(SWAP_ACTION, UnsupportedReason.EnvironmentUnsupported),
            resolve(registry, environment = sandbox),
        )
    }

    @Test
    fun anAssetTheVenueDoesNotSettleInIsRefusedBeforeAnythingIsPrepared() {
        // The stake tokens a venue accepts are the venue's rule rather than the action's, so this
        // is answered by its capability and not by the payload reader (SEE-145). It is said when
        // the signal is read, rather than when an order comes back refused.
        val registry =
            ProviderRegistry.of(
                TestExecutionProvider(
                    actions =
                        listOf(
                            testAction(
                                action = PREDICTION_BUY_ACTION,
                                schemaVersions = 1..1,
                                depositAssets = setOf(USDC),
                            )
                        )
                )
            )

        assertEquals(
            ProviderResolution.Unsupported(
                PREDICTION_BUY_ACTION,
                UnsupportedReason.AssetUnsupported,
            ),
            resolve(
                registry,
                action = PREDICTION_BUY_ACTION,
                payload = predictionPayload(depositMint = SOL),
            ),
        )
        assertTrue(
            resolve(
                registry,
                action = PREDICTION_BUY_ACTION,
                payload = predictionPayload(depositMint = USDC),
            )
                is Supported
        )
        // With nothing read, there is nothing to check the asset against, and the other checks
        // still stand on their own.
        assertTrue(resolve(registry, action = PREDICTION_BUY_ACTION, payload = null) is Supported)
    }

    @Test
    fun theReasonNamesWhatIsMissingBeforeWhereItWouldRun() {
        // A provider that serves another action and another environment is missing the action:
        // there is no point telling the owner about an environment for something this build
        // couldn't do anywhere.
        val registry =
            ProviderRegistry.of(
                TestExecutionProvider(
                    actions = listOf(testAction(action = ActionId("other"))),
                    environments = setOf(sandbox),
                )
            )

        assertEquals(
            ProviderResolution.Unsupported(SWAP_ACTION, UnsupportedReason.ActionUnsupported),
            resolve(registry),
        )
    }

    @Test
    fun aManifestsPluginNameResolvesToTheProviderThatAnswersToIt() {
        // A server written before SEE-145 requires `jupiter.swap`, and what satisfies it is the
        // provider that declares that legacy name — matched, never parsed out of the name.
        val provider = TestExecutionProvider(id = "jupiter", legacyPlugins = setOf(JUPITER_SWAP))
        val registry = ProviderRegistry.of(provider)

        assertSame(provider, registry.byLegacyPlugin(JUPITER_SWAP))
        assertNull(registry.byLegacyPlugin(JUPITER_PREDICTION))
    }

    @Test
    fun twoProvidersWithOneIdOrOneLegacyNameAreAMistakeInTheBuild() {
        // An ID is what a document names and a legacy name is what a manifest requires (SEE-88). A
        // name that means two things means nothing, so this fails where the list is written rather
        // than when a request arrives.
        val sameId =
            assertThrows(IllegalArgumentException::class.java) {
                ProviderRegistry.of(
                    TestExecutionProvider(),
                    TestExecutionProvider(actions = listOf(testAction(action = ActionId("other")))),
                )
            }
        assertTrue(sameId.message!!.contains("test"))
        val sameLegacy =
            assertThrows(IllegalArgumentException::class.java) {
                ProviderRegistry.of(
                    TestExecutionProvider(id = "one", legacyPlugins = setOf(JUPITER_SWAP)),
                    TestExecutionProvider(id = "two", legacyPlugins = setOf(JUPITER_SWAP)),
                )
            }
        assertTrue(sameLegacy.message!!.contains("jupiter.swap"))
    }

    @Test
    fun aStatusQueryIsDeclaredBeforeItIsAnswered() {
        // The optional half of the interface, and the rule about it: a provider that does not
        // declare status queries answers `Unsupported` rather than inventing a state, and one that
        // does answers in its own words — which nothing in this app turns into a fill, a settlement
        // or a profit. **Nothing polls it** (SEE-145, docs/wiki/execution-providers.md).
        val quiet = TestExecutionProvider(id = "quiet")
        val talkative = TestExecutionProvider(id = "talkative", statusQueries = true)
        val reference = PluginReference("order_account", "3fJ")

        assertFalse(quiet.capabilities.statusQueries)
        assertEquals(
            ActionStatus.Unsupported,
            runBlocking { quiet.status(operation(), reference) },
        )
        assertEquals(
            ActionStatus.Reported("submitted"),
            runBlocking { talkative.status(operation(), reference) },
        )
    }

    @Test
    fun aRegistryWithNothingInItIsReportedAsEmpty() {
        // A build that carries no provider for an action says so, and never reports one that turned
        // out not to need one. Which providers a build *does* carry is the composition root's
        // business rather than this package's, and `BundledProvidersTest` holds the real list.
        val empty = ProviderRegistry.of()

        assertEquals(emptyList<ProviderCapabilities>(), empty.capabilities)
        assertNull(empty.byId(JUPITER_PROVIDER))
        assertNull(empty.byLegacyPlugin(JUPITER_SWAP))
        assertEquals(
            ProviderResolution.Unsupported(SWAP_ACTION, UnsupportedReason.NoProvider),
            resolve(empty),
        )
    }

    @Test
    fun aProviderDescribesItsFieldsAndCoreReadsTheChoiceBackByKey() {
        // Parameter collection is a description, not a screen: the app owns its own presentation
        // (AGENTS.md#ui), and the words live in resources rather than in code. Amounts are base
        // units throughout, which is what a transaction carries and what a rule is written in —
        // nothing here rounds anything.
        val provider = TestExecutionProvider(form = TestExecutionProvider.form())

        val form = provider.inputs(operation())

        assertEquals(listOf("inputs"), provider.calls)
        assertEquals(
            listOf("input_amount", "outcome", "slippage_bps"),
            form.fields.map { it.key.value },
        )
        form.fields.forEach { assertNotEquals(0, it.label) }
        val amount = form.fields.first().kind as ParameterKind.Amount
        assertNull("native SOL names no mint", amount.mint)
        assertEquals(9, amount.decimals)
        // What the owner picked is read back by key, so it survives however it was shown.
        val choice =
            ParameterChoice(
                mapOf(
                    ParameterKey("input_amount") to ParameterValue.Amount(250uL),
                    ParameterKey("outcome") to ParameterValue.Selected(ParameterKey("yes")),
                    ParameterKey("slippage_bps") to ParameterValue.Count(50u),
                )
            )
        assertEquals(ParameterValue.Amount(250uL), choice[ParameterKey("input_amount")])
        assertNull(choice[ParameterKey("not_a_field")])
        assertTrue(ParameterForm().isEmpty)
    }

    @Test
    fun aNameIsANameAndNothingLoadable() {
        // Nothing here is a URL, a class, or a path: a document names something this build either
        // has or hasn't, and there is no third case in which one is fetched.
        assertThrows(IllegalArgumentException::class.java) {
            PluginId("https://example.com/plugin.jar")
        }
        assertThrows(IllegalArgumentException::class.java) { PluginId("io.github.Plugin") }
        assertThrows(IllegalArgumentException::class.java) { PluginId("../plugin") }
        // A legacy plugin ID names a vendor and a capability, so it has at least two segments.
        assertThrows(IllegalArgumentException::class.java) { PluginId("swap") }
        assertEquals("jupiter.swap", PluginId("jupiter.swap").value)
        // An execution provider is a name of its own, and needs no dot.
        assertEquals("jupiter", ExecutionProviderId("jupiter").value)
        assertThrows(IllegalArgumentException::class.java) { ExecutionProviderId("Jupiter") }
        // An action is the protocol's own name for what was asked, and needs no vendor.
        assertEquals("swap", ActionId("swap").value)
        assertEquals("prediction.buy", PREDICTION_BUY_ACTION.value)
        assertThrows(IllegalArgumentException::class.java) { ActionId("Swap") }
    }

    private fun resolve(
        registry: ProviderRegistry,
        provider: ExecutionProviderId? = ExecutionProviderId("test"),
        action: ActionId = SWAP_ACTION,
        schemaVersion: Int = SWAP_SCHEMA_VERSION,
        network: Network = mainnet,
        environment: PluginEnvironment = production,
        payload: ActionPayload? = null,
    ): ProviderResolution =
        registry.resolve(provider, action, schemaVersion, network, environment, payload)

    private fun operation() =
        ActionOperation(
            connectionId = connection,
            action = SWAP_ACTION,
            schemaVersion = SWAP_SCHEMA_VERSION,
            provider = ExecutionProviderId("test"),
            environment = production,
            network = mainnet,
            payload =
                ActionPayload.Swap(
                    SwapPayload(
                        inputMint = USDC,
                        inputDecimals = 6,
                        outputMint = SOL,
                        outputDecimals = 9,
                        maxSlippageBps = 100,
                    )
                ),
            request = actionRequest {},
            wallet = null,
        )

    private fun predictionPayload(depositMint: String) =
        ActionPayload.PredictionBuy(
            PredictionPayload(
                marketId = "market-1",
                depositMint = depositMint,
                depositDecimals = 6,
            )
        )

    private companion object {
        const val USDC = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        const val SOL = "So11111111111111111111111111111111111111112"
    }
}

private typealias Supported = ProviderResolution.Supported
