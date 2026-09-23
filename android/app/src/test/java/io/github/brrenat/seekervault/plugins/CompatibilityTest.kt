package io.github.brrenat.seekervault.plugins

import io.github.brrenat.seekervault.proposals.PREDICTION
import io.github.brrenat.seekervault.proposals.ProposalExpectation
import io.github.brrenat.seekervault.proposals.ProposalResult
import io.github.brrenat.seekervault.proposals.SWAP
import io.github.brrenat.seekervault.proposals.proposal
import io.github.brrenat.seekervault.proposals.proposalFrom
import io.github.brrenat.seekervault.proposals.wireProposal
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.request.v2.request
import io.github.brrenat.seekervault.requests.commonEnvelope
import io.github.brrenat.seekervault.servers.SERVER_B
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The names published before SEE-145, and what they mean now.
 *
 * SEE-145 split one name into two things — the action, and who executes it — and every document,
 * manifest and stored row written before it says the old thing. The rule for all of them is the
 * same and is asserted here: a legacy name is **looked up**, never parsed. `jupiter.swap` is not
 * read as "the provider `jupiter` doing the action `swap`", because a name that happens to contain
 * a dot is not a structure; it means what [LEGACY_CAPABILITIES] says it means, and a name that
 * table does not carry means nothing at all unless a registered provider answers to it.
 */
class CompatibilityTest {

    @Test
    fun theTableIsTheWholeOfIt() {
        assertEquals(
            listOf(JUPITER_SWAP, JUPITER_PREDICTION),
            LEGACY_CAPABILITIES.map { it.plugin },
        )
        LEGACY_CAPABILITIES.forEach { assertEquals(JUPITER_PROVIDER, it.provider) }
        assertEquals(SWAP_ACTION, legacyCapabilityOf(JUPITER_SWAP)?.action)
        assertEquals(PREDICTION_BUY_ACTION, legacyCapabilityOf(JUPITER_PREDICTION)?.action)
        // The published contract of those names, which SEE-145 did not move: it is a statement
        // about the agreement with a server, not about the code inside the APK.
        assertEquals(1, legacyCapabilityOf(JUPITER_SWAP)?.contract)
        assertEquals(2, PROVIDER_CONTRACT)
        // And a name nobody published means nothing here.
        assertNull(legacyCapabilityOf(PluginId("somebody.else")))
        assertNull(providerOf(named = null, plugin = PluginId("somebody.else")))
    }

    @Test
    fun bothSpellingsOfAnActionAreTheSameAction() {
        // A publisher that says `prediction` and one that says `prediction.buy` are asking for the
        // same order, and the phone prepares the same one from either.
        assertEquals(PREDICTION_BUY_ACTION, actionOf(PREDICTION))
        assertEquals(PREDICTION_BUY_ACTION, actionOf(PREDICTION_BUY_ACTION.value))
        assertEquals(SWAP_ACTION, actionOf(SWAP))
        // Something that is neither is still an action — one nothing in this build serves, which
        // is shown as unsupported rather than mistaken for one that is.
        assertEquals(ActionId("bridge"), actionOf("bridge"))
        assertNull(actionOf("Bridge"))
    }

    @Test
    fun aLegacyDocumentAndANewFormatOneResolveToTheSameThing() {
        val legacy =
            proposal(wireProposal(operation = PREDICTION, plugin = JUPITER_PREDICTION.value))
        // The same document, said the new way: the action named provider-neutrally and versioned.
        val modern =
            (proposalFrom(
                    legacy
                        .commonEnvelope()
                        .toBuilder()
                        .setAction(
                            legacy
                                .commonEnvelope()
                                .action
                                .toBuilder()
                                .setCapabilityId(PREDICTION_BUY_ACTION.value)
                        )
                        .build(),
                    ProposalExpectation(serverId = SERVER_B),
                )
                    as ProposalResult.Valid)
                .proposal

        assertEquals(legacy.action, modern.action)
        assertEquals(legacy.provider, modern.provider)
        assertEquals(legacy.capabilityVersion, modern.capabilityVersion)
        assertEquals(legacy.values, modern.values)
        // Both resolve to the one bundled provider, for the same action, at the same version.
        val registry = ProviderRegistry.of(jupiterLike())
        for (read in listOf(legacy, modern)) {
            val resolution =
                registry.resolve(
                    provider = read.provider,
                    action = read.action,
                    schemaVersion = read.capabilityVersion,
                    network = Network.NETWORK_MAINNET,
                    environment = PluginEnvironment.Production,
                )
            assertTrue(resolution is ProviderResolution.Supported)
            assertEquals(
                JUPITER_PROVIDER,
                (resolution as ProviderResolution.Supported).provider.capabilities.id,
            )
        }
    }

    @Test
    fun aDocumentThisPhoneReEmitsStillReadsToAnOlderClient() {
        // The phone re-emits the common envelope, and it writes the *legacy* spelling: a client
        // written before SEE-145 reads what it always read (`prediction`, not `prediction.buy`).
        val legacy =
            proposal(wireProposal(operation = PREDICTION, plugin = JUPITER_PREDICTION.value))

        val envelope = legacy.commonEnvelope()

        assertEquals(PREDICTION, envelope.action.capabilityId)
        assertEquals(JUPITER_PREDICTION.value, envelope.action.pluginId)
        assertEquals(1, envelope.action.capabilityVersion)
        assertEquals(PREDICTION, legacyOperationOf(PREDICTION_BUY_ACTION))
        assertEquals(SWAP, legacyOperationOf(SWAP_ACTION))
        // An action that never existed before SEE-145 is written as itself: there is nothing to
        // preserve for it, and writing it under an old name would be inventing compatibility.
        assertEquals("bridge", legacyOperationOf(ActionId("bridge")))
    }

    @Test
    fun anActivityRecordKeepsTheNameTheOwnerAlreadyKnows() {
        // What a history row says about "which plugin" must read the same before and after the
        // upgrade, so it is the bundled name the provider answers to rather than the provider's.
        assertEquals(JUPITER_SWAP, legacyPluginOf(JUPITER_PROVIDER, SWAP_ACTION))
        assertEquals(JUPITER_PREDICTION, legacyPluginOf(JUPITER_PROVIDER, PREDICTION_BUY_ACTION))
        assertNull(legacyPluginOf(ExecutionProviderId("example"), SWAP_ACTION))
    }

    @Test
    fun aRequestWithNoEnvelopeIsStillARequest() {
        // The v2 envelope is the only place an action is versioned, and a document that carries
        // none of it is not silently read as version 1 of something.
        assertNotNull(request {})
        assertTrue(
            proposalFrom(request {}, ProposalExpectation(serverId = SERVER_B))
                is ProposalResult.Invalid
        )
    }
}
