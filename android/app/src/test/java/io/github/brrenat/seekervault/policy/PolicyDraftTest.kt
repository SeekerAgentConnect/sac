package io.github.brrenat.seekervault.policy

import io.github.brrenat.seekervault.request.v1.Network
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The editor's form: what the owner types, and the policy it turns into. */
class PolicyDraftTest {
    private val at = Instant.parse("2026-09-12T10:00:00Z")

    private fun draft(
        restrictActions: Boolean = false,
        actions: Set<PolicyAction> = emptySet(),
        restrictAssets: Boolean = false,
        assets: List<AssetDraft> = emptyList(),
        restrictRecipients: Boolean = false,
        recipients: List<String> = emptyList(),
        restrictPrograms: Boolean = false,
        programs: List<String> = emptyList(),
    ) =
        PolicyDraft(
            connectionId = CONNECTION,
            restrictActions = restrictActions,
            actions = actions,
            restrictAssets = restrictAssets,
            assets = assets,
            restrictRecipients = restrictRecipients,
            recipients = recipients,
            restrictPrograms = restrictPrograms,
            programs = programs,
        )

    private fun sol(perOperation: String = "", daily: String = "") =
        AssetDraft(Network.NETWORK_MAINNET, null, perOperation, daily)

    private fun token(perOperation: String = "", daily: String = "") =
        AssetDraft(Network.NETWORK_MAINNET, MINT, perOperation, daily)

    private fun ready(draft: PolicyDraft): ConnectionPolicy {
        val review = draft.review(at)
        assertTrue(review.toString(), review is DraftReview.Ready)
        return (review as DraftReview.Ready).policy
    }

    @Test
    fun aTypedAmountOfSolIsShiftedIntoLamports() {
        assertEquals(AmountEntry.Amount(1_000_000_000UL), readAmount("1", 9))
        assertEquals(AmountEntry.Amount(1_500_000_000UL), readAmount("1.5", 9))
        assertEquals(AmountEntry.Amount(1UL), readAmount("0.000000001", 9))
        assertEquals(AmountEntry.Amount(500_000_000UL), readAmount(".5", 9))
        assertEquals(AmountEntry.Amount(2_000_000_000UL), readAmount("2.", 9))
        // Leading zeros and the space around a pasted number are not a problem with the number.
        assertEquals(AmountEntry.Amount(1_000_000_000UL), readAmount("  01.0  ", 9))
    }

    @Test
    fun aTokenIsTypedInItsOwnBaseUnits() {
        assertEquals(AmountEntry.Amount(1_000_000UL), readAmount("1000000", 0))
        // No decimal place at all, because the app doesn't know how many the mint has.
        assertEquals(AmountEntry.Problem(AmountProblem.TooPrecise), readAmount("1.5", 0))
        assertEquals(0, token().decimals)
        assertEquals(9, sol().decimals)
    }

    @Test
    fun anAmountThatIsntOneIsSaidToBeWhatItIs() {
        assertEquals(AmountEntry.None, readAmount("", 9))
        assertEquals(AmountEntry.None, readAmount("   ", 9))
        assertEquals(AmountEntry.Problem(AmountProblem.NotANumber), readAmount("one", 9))
        assertEquals(AmountEntry.Problem(AmountProblem.NotANumber), readAmount("-1", 9))
        assertEquals(AmountEntry.Problem(AmountProblem.NotANumber), readAmount("1e9", 9))
        assertEquals(AmountEntry.Problem(AmountProblem.NotANumber), readAmount("1,5", 9))
        assertEquals(AmountEntry.Problem(AmountProblem.NotANumber), readAmount("1 000", 9))
        assertEquals(AmountEntry.Problem(AmountProblem.NotANumber), readAmount(".", 9))
        assertEquals(AmountEntry.Problem(AmountProblem.TooPrecise), readAmount("0.0000000001", 9))
    }

    @Test
    fun theLargestAmountATransferCanCarryFits() {
        // 18446744073709551615 lamports, typed in SOL.
        assertEquals(AmountEntry.Amount(ULong.MAX_VALUE), readAmount("18446744073.709551615", 9))
        assertEquals(
            AmountEntry.Problem(AmountProblem.TooLarge),
            readAmount("18446744073.709551616", 9),
        )
        assertEquals(AmountEntry.Amount(ULong.MAX_VALUE), readAmount(ULong.MAX_VALUE.toString(), 0))
    }

    @Test
    fun aThresholdOfZeroIsNotARule() {
        assertEquals(AmountEntry.Problem(AmountProblem.Zero), readAmount("0", 9))
        assertEquals(AmountEntry.Problem(AmountProblem.Zero), readAmount("0.0", 9))
        assertEquals(AmountEntry.Problem(AmountProblem.Zero), readAmount("0", 0))
        // And the draft refuses to be saved with one in it, rather than storing a rule the store
        // would reject.
        val review = draft(assets = listOf(sol(perOperation = "0"))).review(at)
        assertEquals(
            mapOf(0 to AssetProblems(perOperation = AmountProblem.Zero)),
            (review as DraftReview.Problems).assets,
        )
    }

    @Test
    fun aDailyThresholdBelowThePerRequestOneIsRefused() {
        val review = draft(assets = listOf(sol(perOperation = "2", daily = "1"))).review(at)
        assertEquals(
            mapOf(0 to AssetProblems(dailyBelowPerOperation = true)),
            (review as DraftReview.Problems).assets,
        )
        // Equal is fine: one request may use the whole day.
        assertTrue(
            draft(assets = listOf(sol(perOperation = "2", daily = "2"))).review(at)
                is DraftReview.Ready
        )
    }

    @Test
    fun aSwitchThatIsOffIsNoListAndNotAnEmptyOne() {
        val off = draft(restrictActions = false, actions = setOf(PolicyAction.Transfer))
        // Nothing is configured at all, so there is nothing to save.
        assertEquals(DraftReview.NoRules, off.review(at))
        val empty = ready(draft(restrictActions = true))
        assertEquals(Allowlist.nothing<PolicyAction>(), empty.actions)
        assertTrue(empty.actions!!.allowsNothing)
    }

    @Test
    fun everyListHasItsOwnSwitch() {
        val policy =
            ready(
                draft(
                    restrictActions = true,
                    actions = setOf(PolicyAction.Transfer),
                    restrictRecipients = true,
                    recipients = listOf(RECIPIENT),
                )
            )
        assertEquals(Allowlist.of(PolicyAction.Transfer), policy.actions)
        assertEquals(Allowlist.of(RECIPIENT), policy.recipients)
        // The two nobody switched on aren't empty lists: they aren't there.
        assertNull(policy.assets)
        assertNull(policy.programs)
    }

    @Test
    fun aThresholdCanBeSetWithoutRestrictingWhichAssetsMove() {
        val policy = ready(draft(assets = listOf(sol(perOperation = "1.5"))))
        assertNull(policy.assets)
        assertEquals(mapOf(SOL to AssetLimits(perOperation = 1_500_000_000UL)), policy.limits)
    }

    @Test
    fun anAssetListedWithNoThresholdRestrictsWhatMovesAndSaysNothingAboutHowMuch() {
        val policy = ready(draft(restrictAssets = true, assets = listOf(sol())))
        assertEquals(Allowlist.of(SOL), policy.assets)
        assertEquals(emptyMap<PolicyAsset, AssetLimits>(), policy.limits)
    }

    @Test
    fun aThresholdBelongsToTheAssetItIsAbout() {
        val policy =
            ready(
                draft(
                    restrictAssets = true,
                    assets = listOf(sol(daily = "10"), token(perOperation = "1000000")),
                )
            )
        // Every threshold is for an asset the list allows, so the store's LimitForUnlistedAsset
        // can't be written here at all.
        assertEquals(emptyList<PolicyProblem>(), policyProblems(policy))
        assertEquals(AssetLimits(daily = 10_000_000_000UL), policy.limitsFor(SOL))
        assertEquals(AssetLimits(perOperation = 1_000_000UL), policy.limitsFor(USDC))
    }

    @Test
    fun whatWasSavedIsWhatOpensAgain() {
        val before =
            draft(
                restrictActions = true,
                actions = setOf(PolicyAction.Transfer, PolicyAction.Acknowledgement),
                restrictAssets = true,
                assets = listOf(sol(perOperation = "1.5", daily = "10"), token(daily = "2000000")),
                restrictRecipients = true,
                recipients = listOf(RECIPIENT, STRANGER),
                restrictPrograms = true,
                programs = listOf(SYSTEM),
            )
        val policy = ready(before)
        assertEquals(before, draftOf(CONNECTION, policy))
        // And saving what was reopened writes the same document back.
        assertEquals(policy, ready(draftOf(CONNECTION, policy)))
    }

    @Test
    fun anAssetThatOnlyCarriesAThresholdComesBackToo() {
        val policy = ready(draft(assets = listOf(token(perOperation = "5"))))
        val reopened = draftOf(CONNECTION, policy)
        assertEquals(listOf(AssetDraft(Network.NETWORK_MAINNET, MINT, "5", "")), reopened.assets)
        assertEquals(false, reopened.restrictAssets)
    }

    @Test
    fun aConnectionWithNoRulesOpensOnAnEmptyForm() {
        val draft = draftOf(CONNECTION, null)
        assertEquals(PolicyDraft(CONNECTION), draft)
        assertEquals(DraftReview.NoRules, draft.review(at))
    }

    @Test
    fun aDraftThatConfiguresNothingRemovesTheRulesRatherThanStoringSilence() {
        // Every switch off, with everything the owner typed still in the form.
        val draft =
            draft(
                actions = setOf(PolicyAction.Transfer),
                recipients = listOf(RECIPIENT),
                programs = listOf(SYSTEM),
                assets = listOf(sol()),
            )
        assertEquals(DraftReview.NoRules, draft.review(at))
    }

    @Test
    fun everyDraftTheEditorCanProduceIsFitForTheStore() {
        val drafts =
            listOf(
                draft(restrictActions = true),
                draft(restrictActions = true, actions = PolicyAction.entries.toSet()),
                draft(restrictAssets = true),
                draft(restrictAssets = true, assets = listOf(sol(), token())),
                draft(restrictRecipients = true, recipients = listOf(RECIPIENT)),
                draft(restrictPrograms = true, programs = listOf(SYSTEM)),
                draft(assets = listOf(sol(perOperation = "1", daily = "1"))),
                draft(assets = listOf(AssetDraft(Network.NETWORK_DEVNET, null, "1", "2"))),
            )
        for (draft in drafts) {
            assertEquals(draft.toString(), emptyList<PolicyProblem>(), policyProblems(ready(draft)))
        }
    }

    @Test
    fun theChainsARuleCanNameAreTheThreeThatExist() {
        assertEquals(
            listOf(Network.NETWORK_MAINNET, Network.NETWORK_DEVNET, Network.NETWORK_TESTNET),
            POLICY_NETWORKS,
        )
        // An asset with no chain names nothing to spend, so it isn't on the list to pick from.
        assertTrue(Network.NETWORK_UNSPECIFIED !in POLICY_NETWORKS)
    }

    private companion object {
        /** The system program, which every SOL transfer calls. */
        const val SYSTEM = "11111111111111111111111111111111"
    }
}
