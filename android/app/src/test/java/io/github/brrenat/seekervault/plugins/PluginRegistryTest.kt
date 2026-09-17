package io.github.brrenat.seekervault.plugins

import io.github.brrenat.seekervault.request.v1.actionRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Registering a bundled client plugin and resolving one operation to it (SEE-86).
 *
 * The three refusals are asserted separately, because they are three different things to tell
 * someone: this build carries nothing for the operation, it carries something written against
 * another version of the boundary, or it carries one that doesn't serve this environment. None of
 * them is a verdict about the operation, and none of them is a reason to go ahead without one.
 */
class PluginRegistryTest {
    private val production = PluginEnvironment.Production
    private val sandbox = PluginEnvironment.Sandbox
    private val connection = "6a5f0a0e-2f2f-4a07-9a1f-4f0f2b3f9a11"

    @Test
    fun aTestPluginIsRegisteredAndResolvedForTheOperationItServes() {
        val plugin = TestPlugin()
        val registry = PluginRegistry.of(plugin)

        assertEquals(listOf(plugin.descriptor), registry.descriptors)
        assertSame(plugin, registry.byId(PluginId("test.operation")))
        val resolved = registry.resolve(SWAP_OPERATION, production)
        assertSame(plugin, (resolved as PluginResolution.Supported).plugin)
        // Resolving is a lookup: nothing was prepared, inspected, or asked of the plugin.
        assertEquals(emptyList<String>(), plugin.calls)
    }

    @Test
    fun anOperationNoPluginServesIsRejectedAsMissingRatherThanGuessedAt() {
        val registry = PluginRegistry.of(TestPlugin(operations = setOf(OperationId("other"))))

        assertEquals(
            PluginResolution.Unsupported(SWAP_OPERATION, UnsupportedReason.NoPlugin),
            registry.resolve(SWAP_OPERATION, production),
        )
    }

    @Test
    fun aPluginWrittenAgainstAnotherBoundaryIsRejectedRatherThanAdapted() {
        // It is registered, and it claims the operation. What it doesn't have is a contract this
        // build knows how to call, and calling it anyway would be guessing at the interface.
        val plugin = TestPlugin(contract = PLUGIN_CONTRACT + 1)
        val registry = PluginRegistry.of(plugin)

        assertEquals(listOf(plugin.descriptor), registry.descriptors)
        assertEquals(
            PluginResolution.Unsupported(SWAP_OPERATION, UnsupportedReason.ContractUnsupported),
            registry.resolve(SWAP_OPERATION, production),
        )
        // The same plugin is still there to be named; it just can't be called.
        assertSame(plugin, registry.byId(plugin.descriptor.id))
    }

    @Test
    fun aPluginThatDoesNotServeThisEnvironmentIsRejectedForThatReason() {
        val registry = PluginRegistry.of(TestPlugin(environments = setOf(sandbox)))

        assertEquals(
            PluginResolution.Unsupported(
                SWAP_OPERATION,
                UnsupportedReason.EnvironmentUnsupported,
            ),
            registry.resolve(SWAP_OPERATION, production),
        )
        assertTrue(registry.resolve(SWAP_OPERATION, sandbox) is PluginResolution.Supported)
    }

    @Test
    fun theReasonNamesWhatIsMissingBeforeWhereItWouldRun() {
        // A plugin for another operation and another environment is missing for the operation:
        // there is no point telling the owner about an environment for something this build
        // couldn't do anywhere.
        val registry =
            PluginRegistry.of(
                TestPlugin(operations = setOf(OperationId("other")), environments = setOf(sandbox))
            )

        assertEquals(
            PluginResolution.Unsupported(SWAP_OPERATION, UnsupportedReason.NoPlugin),
            registry.resolve(SWAP_OPERATION, production),
        )
    }

    @Test
    fun oneOperationCanBeServedByTheOnlyPluginThatRunsHere() {
        val elsewhere = TestPlugin(id = "test.elsewhere", environments = setOf(sandbox))
        val here = TestPlugin(id = "test.here", environments = setOf(production))
        val registry = PluginRegistry.of(elsewhere, here)

        assertSame(
            here,
            (registry.resolve(SWAP_OPERATION, production) as PluginResolution.Supported).plugin,
        )
        assertSame(
            elsewhere,
            (registry.resolve(SWAP_OPERATION, sandbox) as PluginResolution.Supported).plugin,
        )
    }

    @Test
    fun twoPluginsWithOneIdAreAMistakeInTheBuildAndNotAStateToReport() {
        // An ID is what a server manifest names (SEE-88). A name that means two things means
        // nothing, so this fails where the list is written rather than when a request arrives.
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                PluginRegistry.of(
                    TestPlugin(),
                    TestPlugin(operations = setOf(OperationId("other"))),
                )
            }
        assertTrue(failure.message!!.contains("test.operation"))
    }

    @Test
    fun aRegistryWithNothingInItIsReportedAsEmpty() {
        // A build that carries no plugin for an operation says so, and never reports one that
        // turned out not to need a plugin. Which plugins a build *does* carry is the composition
        // root's business rather than this package's (SEE-93), and `StageBoundaryTest` is what
        // holds the real list to naming `jupiter.swap`.
        val empty = PluginRegistry.of()

        assertEquals(emptyList<PluginDescriptor>(), empty.descriptors)
        assertNull(empty.byId(PluginId("jupiter.swap")))
        assertEquals(
            PluginResolution.Unsupported(SWAP_OPERATION, UnsupportedReason.NoPlugin),
            empty.resolve(SWAP_OPERATION, production),
        )
    }

    @Test
    fun aPluginDescribesItsFieldsAndCoreReadsTheChoiceBackByKey() {
        // Parameter collection is a description, not a screen: the app owns its own presentation
        // (AGENTS.md#ui), and the plugin's words live in its resources rather than in its code.
        // Amounts are base units throughout, which is what a transaction carries and what a rule is
        // written in — nothing here rounds anything.
        val plugin = TestPlugin(form = TestPlugin.form())
        val subject =
            ActionSubject(
                connectionId = connection,
                operation = SWAP_OPERATION,
                environment = production,
                request = actionRequest {},
                wallet = null,
            )

        val form = plugin.parameters(subject)

        assertEquals(listOf("parameters"), plugin.calls)
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
    fun anIdIsANameAndNothingLoadable() {
        // Nothing here is a URL, a class, or a path: a manifest names a plugin this build either
        // has or hasn't, and there is no third case in which one is fetched.
        assertThrows(IllegalArgumentException::class.java) {
            PluginId("https://example.com/plugin.jar")
        }
        assertThrows(IllegalArgumentException::class.java) { PluginId("io.github.Plugin") }
        assertThrows(IllegalArgumentException::class.java) { PluginId("../plugin") }
        // A plugin ID names a vendor and a capability, so it has at least two segments.
        assertThrows(IllegalArgumentException::class.java) { PluginId("swap") }
        assertEquals("jupiter.swap", PluginId("jupiter.swap").value)
        // An operation is the protocol's own name for what was asked, and needs no vendor.
        assertEquals("swap", OperationId("swap").value)
        assertThrows(IllegalArgumentException::class.java) { OperationId("Swap") }
    }
}
