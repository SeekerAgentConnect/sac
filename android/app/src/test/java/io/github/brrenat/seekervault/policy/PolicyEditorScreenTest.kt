package io.github.brrenat.seekervault.policy

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.SeekerVaultTheme
import io.github.brrenat.seekervault.policy.storage.UnreadableReason
import io.github.brrenat.seekervault.request.v1.Network
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The policy editor on Robolectric: writing rules, and being told what they say. */
@RunWith(AndroidJUnit4::class)
class PolicyEditorScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val calls = mutableListOf<String>()
    private val saved = mutableListOf<PolicyDraft>()
    private lateinit var ui: MutableState<PolicyUiState>

    private val draft: PolicyDraft
        get() = ui.value.draft

    private fun show(state: PolicyUiState) {
        compose.setContent {
            val current = remember { mutableStateOf(state) }
            ui = current
            SeekerVaultTheme {
                PolicyEditorScreen(
                    label = "Home Mac",
                    state = current.value,
                    onEdit = { current.value = current.value.copy(draft = it) },
                    onStartOver = {
                        current.value =
                            current.value.copy(
                                unreadable = null,
                                replacing = true,
                                draft = PolicyDraft(CONNECTION),
                            )
                    },
                    onSave = { saved += current.value.draft },
                    onMessageShown = { current.value = current.value.copy(message = null) },
                    onClose = { calls += "close" },
                )
            }
        }
    }

    /** A connection whose rules have been read, with nothing configured unless said otherwise. */
    private fun open(
        draft: PolicyDraft = PolicyDraft(CONNECTION),
        stored: PolicyDraft = PolicyDraft(CONNECTION),
        storedAt: Instant? = null,
    ) =
        show(
            PolicyUiState(
                connectionId = CONNECTION,
                loaded = true,
                draft = draft,
                stored = stored,
                storedAt = storedAt,
            )
        )

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun click(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()

    private fun type(tag: String, value: String) =
        compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)

    private fun seen(id: Int, vararg args: Any) =
        compose.onNodeWithText(text(id, *args)).assertExists()

    // A dialog isn't in the scrolling column, so its buttons are clicked where they are.
    private fun addSol() {
        click(PolicyTags.ADD_ASSET)
        compose.onNodeWithTag(PolicyTags.DIALOG_ADD).performClick()
    }

    private fun addToken(mint: String = MINT) {
        click(PolicyTags.ADD_ASSET)
        compose.onNodeWithTag(PolicyTags.ASSET_TOKEN).performClick()
        compose.onNodeWithTag(PolicyTags.MINT_FIELD).performTextReplacement(mint)
        compose.onNodeWithTag(PolicyTags.DIALOG_ADD).performClick()
    }

    @Test
    fun aConnectionWithNoRulesSaysSoAndHasNothingToSave() {
        open()
        seen(R.string.policy_never_saved)
        seen(R.string.policy_summary_none)
        seen(R.string.policy_summary_manual)
        for (list in listOf("actions", "assets", RECIPIENTS, PROGRAMS)) {
            compose.onNodeWithTag(PolicyTags.restrict(list)).performScrollTo().assertIsOff()
        }
        compose.onNodeWithTag(PolicyTags.SAVE).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun aSwitchThatIsOffIsNotAnEmptyList() {
        open()
        // Off: no check at all, and the review says so.
        seen(R.string.policy_actions_off)
        click(PolicyTags.restrict("actions"))
        // On with nothing ticked: a rule that matches nothing, said in words rather than shown
        // as a blank.
        seen(R.string.policy_actions_on)
        seen(R.string.policy_actions_empty)
        seen(R.string.policy_summary_actions_empty)
        compose.onNodeWithTag(PolicyTags.restrict("actions")).assertIsOn()
        compose.onNodeWithTag(PolicyTags.SAVE).performScrollTo().assertIsEnabled()
    }

    @Test
    fun tickingAnActionNamesItInTheSummary() {
        open()
        click(PolicyTags.restrict("actions"))
        click(PolicyTags.action(PolicyAction.Transfer))
        click(PolicyTags.action(PolicyAction.Acknowledgement))
        assertEquals(setOf(PolicyAction.Transfer, PolicyAction.Acknowledgement), draft.actions)
        seen(
            R.string.policy_summary_actions,
            text(R.string.policy_action_ack_short) +
                ", " +
                text(R.string.policy_action_transfer_short),
        )
        // And unticking takes it back out.
        click(PolicyTags.action(PolicyAction.Transfer))
        assertEquals(setOf(PolicyAction.Acknowledgement), draft.actions)
    }

    @Test
    fun anAmountOfSolIsShownInTheUnitsItIsStoredIn() {
        open()
        addSol()
        compose.onNodeWithTag(PolicyTags.asset(0)).assertTextEquals("SOL on mainnet")
        compose
            .onNodeWithTag(PolicyTags.perOperation(0))
            .assertTextContains(text(R.string.policy_amount_none))
        type(PolicyTags.perOperation(0), "1.5")
        seen(R.string.policy_amount_stored, "1500000000")
        seen(R.string.policy_summary_per_operation, "1.5", "SOL on mainnet")
        compose.onNodeWithTag(PolicyTags.SAVE).performScrollTo().assertIsEnabled()
    }

    @Test
    fun anAmountThatIsntOneIsRefusedInWordsAndCantBeSaved() {
        open()
        addSol()
        type(PolicyTags.perOperation(0), "lots")
        seen(R.string.policy_amount_not_a_number)
        compose.onNodeWithTag(PolicyTags.SAVE).performScrollTo().assertIsNotEnabled()
        type(PolicyTags.perOperation(0), "0")
        seen(R.string.policy_amount_zero)
        compose.onNodeWithTag(PolicyTags.SAVE).performScrollTo().assertIsNotEnabled()
        type(PolicyTags.perOperation(0), "18446744073.709551616")
        seen(R.string.policy_amount_too_large, ULong.MAX_VALUE.toString())
        type(PolicyTags.perOperation(0), "1")
        compose.onNodeWithTag(PolicyTags.SAVE).performScrollTo().assertIsEnabled()
    }

    @Test
    fun aDailyThresholdBelowThePerRequestOneIsRefused() {
        open()
        addSol()
        type(PolicyTags.perOperation(0), "2")
        type(PolicyTags.daily(0), "1")
        seen(R.string.policy_daily_below)
        compose.onNodeWithTag(PolicyTags.SAVE).performScrollTo().assertIsNotEnabled()
        type(PolicyTags.daily(0), "2")
        compose.onNodeWithTag(PolicyTags.SAVE).performScrollTo().assertIsEnabled()
    }

    @Test
    fun aTokenIsTypedInItsOwnBaseUnitsAndSaysSo() {
        open()
        addToken()
        compose.onNodeWithTag(PolicyTags.asset(0)).assertTextEquals("$MINT on mainnet")
        seen(R.string.policy_token_units)
        seen(R.string.policy_per_operation_units)
        type(PolicyTags.perOperation(0), "1.5")
        seen(R.string.policy_amount_too_precise, "$MINT on mainnet", 0)
        type(PolicyTags.perOperation(0), "1000000")
        seen(R.string.policy_amount_stored, "1000000")
        seen(
            R.string.policy_summary_per_operation,
            text(R.string.policy_base_units, "1000000"),
            "$MINT on mainnet",
        )
    }

    @Test
    fun aMintThatIsntAnAddressIsRefusedBeforeItIsAdded() {
        open()
        click(PolicyTags.ADD_ASSET)
        compose.onNodeWithTag(PolicyTags.ASSET_TOKEN).performClick()
        compose.onNodeWithTag(PolicyTags.MINT_FIELD).performTextReplacement("not-a-mint")
        compose.onNodeWithTag(PolicyTags.DIALOG_ADD).performClick()
        compose.onNodeWithText(text(R.string.policy_address_invalid)).assertExists()
        assertEquals(emptyList<AssetDraft>(), draft.assets)
        compose.onNodeWithTag(PolicyTags.MINT_FIELD).performTextReplacement(MINT)
        compose.onNodeWithTag(PolicyTags.DIALOG_ADD).performClick()
        assertEquals(listOf(AssetDraft(Network.NETWORK_MAINNET, MINT)), draft.assets)
    }

    @Test
    fun theSameAssetIsNeverListedTwice() {
        open()
        addSol()
        click(PolicyTags.ADD_ASSET)
        compose.onNodeWithTag(PolicyTags.DIALOG_ADD).performClick()
        compose.onNodeWithText(text(R.string.policy_address_listed)).assertExists()
        assertEquals(1, draft.assets.size)
        // The same mint on another chain is another thing to spend, so it is allowed.
        compose.onNodeWithTag(PolicyTags.network(Network.NETWORK_DEVNET)).performClick()
        compose.onNodeWithTag(PolicyTags.DIALOG_ADD).performClick()
        assertEquals(listOf(SOL, SOL_ON_DEVNET), draft.assets.map { it.asset })
    }

    @Test
    fun anAssetCanBeTakenBackOut() {
        open()
        addSol()
        type(PolicyTags.perOperation(0), "1")
        click(PolicyTags.removeAsset(0))
        assertEquals(emptyList<AssetDraft>(), draft.assets)
        seen(R.string.policy_assets_none)
    }

    @Test
    fun anAddressListKeepsWholeAddressesAndSaysWhenOneIsntOne() {
        open()
        click(PolicyTags.restrict(RECIPIENTS))
        seen(R.string.policy_recipients_empty)
        type(PolicyTags.entryField(RECIPIENTS), "nonsense")
        click(PolicyTags.add(RECIPIENTS))
        seen(R.string.policy_address_invalid)
        assertEquals(emptyList<String>(), draft.recipients)
        type(PolicyTags.entryField(RECIPIENTS), RECIPIENT)
        click(PolicyTags.add(RECIPIENTS))
        assertEquals(listOf(RECIPIENT), draft.recipients)
        // The whole address, not the first few characters of it.
        compose
            .onNodeWithTag(PolicyTags.entry(RECIPIENTS, RECIPIENT))
            .performScrollTo()
            .assertTextEquals(RECIPIENT)
    }

    @Test
    fun anAddressIsNotListedTwiceAndCanBeRemovedByName() {
        open()
        click(PolicyTags.restrict(RECIPIENTS))
        type(PolicyTags.entryField(RECIPIENTS), RECIPIENT)
        click(PolicyTags.add(RECIPIENTS))
        type(PolicyTags.entryField(RECIPIENTS), RECIPIENT)
        click(PolicyTags.add(RECIPIENTS))
        seen(R.string.policy_address_listed)
        assertEquals(listOf(RECIPIENT), draft.recipients)
        // The button says which address it takes out, for a screen reader as well as on screen.
        compose
            .onNodeWithContentDescription(text(R.string.policy_remove_entry, RECIPIENT))
            .performScrollTo()
            .performClick()
        assertEquals(emptyList<String>(), draft.recipients)
    }

    @Test
    fun theProgramListIsItsOwnList() {
        open()
        click(PolicyTags.restrict(PROGRAMS))
        type(PolicyTags.entryField(PROGRAMS), SYSTEM)
        click(PolicyTags.add(PROGRAMS))
        assertEquals(listOf(SYSTEM), draft.programs)
        assertEquals(emptyList<String>(), draft.recipients)
        seen(R.string.policy_summary_programs, SYSTEM)
    }

    @Test
    fun theSummarySaysWhichChecksArentCovered() {
        open()
        click(PolicyTags.restrict("actions"))
        click(PolicyTags.action(PolicyAction.Transfer))
        seen(
            R.string.policy_summary_unchecked,
            listOf(
                    R.string.policy_check_asset,
                    R.string.policy_check_recipient,
                    R.string.policy_check_program,
                    R.string.policy_check_per_operation,
                    R.string.policy_check_daily,
                )
                .joinToString { text(it) },
        )
    }

    @Test
    fun turningEveryRuleOffSaysTheRulesWillGo() {
        val stored = PolicyDraft(CONNECTION, restrictActions = true)
        open(draft = stored, stored = stored, storedAt = Instant.parse("2026-09-12T10:00:00Z"))
        compose.onNodeWithTag(PolicyTags.SAVE).performScrollTo().assertIsNotEnabled()
        click(PolicyTags.restrict("actions"))
        seen(R.string.policy_summary_removes)
        compose.onNodeWithTag(PolicyTags.SAVE).performScrollTo().assertIsEnabled()
    }

    @Test
    fun savingHandsBackWhatWasTyped() {
        open()
        click(PolicyTags.restrict("actions"))
        click(PolicyTags.action(PolicyAction.Transfer))
        click(PolicyTags.SAVE)
        assertEquals(1, saved.size)
        assertEquals(setOf(PolicyAction.Transfer), saved.single().actions)
    }

    @Test
    fun leavingWithChangesAsksFirst() {
        open()
        click(PolicyTags.restrict("actions"))
        click(PolicyTags.CANCEL)
        compose.onNodeWithText(text(R.string.policy_discard_title)).assertExists()
        assertEquals(emptyList<String>(), calls)
        compose.onNodeWithTag(PolicyTags.KEEP_EDITING).performClick()
        compose.onNodeWithTag(PolicyTags.restrict("actions")).performScrollTo().assertIsOn()
        click(PolicyTags.CANCEL)
        compose.onNodeWithTag(PolicyTags.DISCARD).performClick()
        assertEquals(listOf("close"), calls)
    }

    @Test
    fun leavingWithNothingChangedDoesntAsk() {
        open()
        click(PolicyTags.CANCEL)
        compose.onNodeWithText(text(R.string.policy_discard_title)).assertDoesNotExist()
        assertEquals(listOf("close"), calls)
    }

    @Test
    fun rulesThatCantBeReadOfferNoFormUntilTheOwnerAsksForOne() {
        show(
            PolicyUiState(
                connectionId = CONNECTION,
                loaded = true,
                unreadable = UnreadableReason.NewerVersion,
            )
        )
        compose.onNodeWithTag(PolicyTags.UNREADABLE).assertExists()
        seen(R.string.policy_unreadable_newer)
        seen(R.string.policy_unreadable_text)
        // No form and nothing to save on top of what is stored.
        compose.onNodeWithTag(PolicyTags.SAVE).assertDoesNotExist()
        compose.onNodeWithTag(PolicyTags.restrict("actions")).assertDoesNotExist()
        click(PolicyTags.START_OVER)
        compose.onNodeWithTag(PolicyTags.restrict("actions")).performScrollTo().assertIsOff()
        // Replacing rules nobody could read is itself a change worth saving.
        compose.onNodeWithTag(PolicyTags.SAVE).performScrollTo().assertIsEnabled()
    }

    @Test
    fun nothingIsEditableUntilTheRulesHaveBeenRead() {
        show(PolicyUiState(connectionId = CONNECTION, loaded = false))
        compose.onNodeWithTag(PolicyTags.LOADING).assertExists()
        compose.onNodeWithTag(PolicyTags.SAVE).assertDoesNotExist()
    }

    @Test
    @Config(fontScale = 2.0f)
    fun everythingStaysReachableAtTwiceTheSystemTextSize() {
        // Nothing is laid out at a fixed height, so the whole form is still there — and still
        // operable — when the owner has turned the system text size up.
        assertEquals(2.0f, context.resources.configuration.fontScale, 0.0f)
        open()
        click(PolicyTags.restrict("actions"))
        click(PolicyTags.action(PolicyAction.Transfer))
        assertEquals(setOf(PolicyAction.Transfer), draft.actions)
        addSol()
        type(PolicyTags.perOperation(0), "1.5")
        seen(R.string.policy_amount_stored, "1500000000")
        compose.onNodeWithTag(PolicyTags.SAVE).performScrollTo().assertIsEnabled()
        compose.onNodeWithTag(PolicyTags.CANCEL).performScrollTo().assertIsEnabled()
    }

    @Test
    fun theScreenSaysEveryVerdictStillNeedsTheWallet() {
        open(
            draft =
                PolicyDraft(
                    CONNECTION,
                    restrictActions = true,
                    actions = setOf(PolicyAction.Transfer),
                )
        )
        seen(R.string.policy_intro)
        seen(R.string.policy_summary_manual)
    }

    private companion object {
        const val SYSTEM = "11111111111111111111111111111111"
    }
}
