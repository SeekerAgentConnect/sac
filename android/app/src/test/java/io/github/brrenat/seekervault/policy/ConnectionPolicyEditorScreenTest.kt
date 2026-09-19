package io.github.brrenat.seekervault.policy

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
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
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import io.github.brrenat.seekervault.policy.storage.UnreadableReason
import io.github.brrenat.seekervault.request.v1.Network
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The connection editor makes inheritance, replacement, and both daily scopes explicit. */
@RunWith(AndroidJUnit4::class)
class ConnectionPolicyEditorScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val actions = mutableListOf<String>()
    private lateinit var ui: MutableState<PolicyUiState>

    private sealed interface Editor {
        data class Asset(
            val asset: PolicyAsset?,
            val kind: PolicyAssetEditorKind,
        ) : Editor

        data class Address(val kind: PolicyAddressKind) : Editor
    }

    private val draft: ConnectionPolicyDraft
        get() = (ui.value.draft as PolicyEditorDraft.Connection).rules

    private fun show(
        draft: ConnectionPolicyDraft = ConnectionPolicyDraft(CONNECTION),
        stored: ConnectionPolicyDraft = draft,
        global: GlobalPolicy? = null,
        globalUnreadable: UnreadableReason? = null,
    ) {
        compose.setContent {
            val current = remember {
                mutableStateOf(
                    PolicyUiState(
                        scope = PolicyEditorScope.Connection,
                        connectionId = CONNECTION,
                        loaded = true,
                        global = global,
                        globalUnreadable = globalUnreadable,
                        draft = PolicyEditorDraft.Connection(draft),
                        stored = PolicyEditorDraft.Connection(stored),
                    )
                )
            }
            ui = current
            val editor = remember { mutableStateOf<Editor?>(null) }
            SeekerTheme {
                when (val route = editor.value) {
                    null ->
                        PolicyEditorScreen(
                            label = "Home Mac",
                            state = current.value,
                            onEdit = { current.value = current.value.copy(draft = it) },
                            onStartOver = {},
                            onResetConnection = {
                                current.value =
                                    current.value.copy(
                                        draft =
                                            PolicyEditorDraft.Connection(
                                                ConnectionPolicyDraft(CONNECTION)
                                            )
                                    )
                            },
                            onOpenGlobal = { actions += "global" },
                            onSave = { actions += "save" },
                            onMessageShown = {},
                            onClose = { actions += "close" },
                            onOpenAsset = { asset, kind ->
                                editor.value = Editor.Asset(asset, kind)
                            },
                            onOpenAddress = { editor.value = Editor.Address(it) },
                        )
                    is Editor.Asset ->
                        PolicyAssetEditorScreen(
                            state = current.value,
                            asset = route.asset,
                            kind = route.kind,
                            onEdit = { current.value = current.value.copy(draft = it) },
                            onBack = { editor.value = null },
                        )
                    is Editor.Address ->
                        PolicyAddressEditorScreen(
                            state = current.value,
                            kind = route.kind,
                            onEdit = { current.value = current.value.copy(draft = it) },
                            onBack = { editor.value = null },
                        )
                }
            }
        }
    }

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun click(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()

    private fun type(tag: String, value: String) =
        compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)

    @Test
    fun globalProgramsAndALocalRecipientAppearWithTheirSources() {
        show(
            draft =
                ConnectionPolicyDraft(
                    CONNECTION,
                    overrideRecipients = true,
                    restrictRecipients = true,
                    recipients = listOf(RECIPIENT),
                ),
            global = GlobalPolicy(programs = Allowlist.of(SYSTEM), updatedAt = AT),
        )
        compose
            .onNodeWithText(
                text(
                    R.string.policy_effective_line,
                    text(R.string.policy_section_programs),
                    SYSTEM,
                    text(R.string.policy_source_global),
                )
            )
            .assertExists()
        compose
            .onNodeWithText(
                text(
                    R.string.policy_effective_line,
                    text(R.string.policy_section_recipients),
                    RECIPIENT,
                    text(R.string.policy_source_connection),
                )
            )
            .assertExists()
    }

    @Test
    fun aProgramOverrideReplacesRatherThanAddsToTheGlobalList() {
        show(global = GlobalPolicy(programs = Allowlist.of(SYSTEM), updatedAt = AT))
        click(PolicyTags.override(PROGRAMS))
        click(PolicyTags.restrict(PROGRAMS))
        click(PolicyTags.add(PROGRAMS))
        type(PolicyTags.entryField(PROGRAMS), STRANGER)
        compose.onNodeWithTag(PolicyTags.add(PROGRAMS)).performClick()
        assertEquals(listOf(STRANGER), draft.programs)
        compose
            .onNodeWithText(
                text(
                    R.string.policy_effective_line,
                    text(R.string.policy_section_programs),
                    STRANGER,
                    text(R.string.policy_source_connection),
                )
            )
            .assertExists()
        compose.onNodeWithText(text(R.string.policy_override_replaces)).assertExists()
    }

    @Test
    fun inheritedNoCheckAndExplicitEmptyAreThreeVisibleStates() {
        show(global = GlobalPolicy(actions = Allowlist.of(PolicyAction.Transfer), updatedAt = AT))
        compose
            .onNodeWithText(
                text(
                    R.string.policy_effective_line,
                    text(R.string.policy_section_actions),
                    text(R.string.policy_action_transfer_short),
                    text(R.string.policy_source_global),
                )
            )
            .assertExists()

        click(PolicyTags.override("actions"))
        compose
            .onNodeWithText(
                text(
                    R.string.policy_effective_line,
                    text(R.string.policy_section_actions),
                    text(R.string.policy_effective_not_checked),
                    text(R.string.policy_source_connection),
                )
            )
            .assertExists()

        click(PolicyTags.restrict("actions"))
        compose.onNodeWithText(text(R.string.policy_actions_empty)).assertExists()
        compose
            .onNodeWithText(
                text(
                    R.string.policy_effective_line,
                    text(R.string.policy_section_actions),
                    text(R.string.policy_effective_nothing),
                    text(R.string.policy_source_connection),
                )
            )
            .assertExists()
    }

    @Test
    fun resetReturnsToInheritanceWithoutRemovingGlobalDailyContext() {
        show(
            draft =
                ConnectionPolicyDraft(
                    CONNECTION,
                    limits = listOf(ConnectionAssetDraft(Network.NETWORK_MAINNET, daily = "3")),
                ),
            stored = ConnectionPolicyDraft(CONNECTION),
            global =
                GlobalPolicy(
                    limits = mapOf(SOL to AssetLimits(daily = 10_000_000_000UL)),
                    updatedAt = AT,
                ),
        )
        click(PolicyTags.connectionAsset(SOL))
        compose
            .onNodeWithTag(PolicyTags.globalDaily(SOL))
            .performScrollTo()
            .assertTextContains("10", substring = true)
            .assertTextContains(text(R.string.policy_source_global), substring = true)
        compose
            .onNodeWithTag(PolicyTags.connectionDaily(SOL))
            .performScrollTo()
            .assertTextContains("3", substring = true)
            .assertTextContains("3000000000", substring = true)
        compose.onNodeWithTag(PolicyTags.DIALOG_ADD).performClick()

        click(PolicyTags.RESET_OVERRIDES)
        assertEquals(ConnectionPolicyDraft(CONNECTION), draft)
        click(PolicyTags.connectionAsset(SOL))
        compose
            .onNodeWithTag(PolicyTags.globalDaily(SOL))
            .performScrollTo()
            .assertTextContains("10", substring = true)
        compose
            .onNodeWithTag(PolicyTags.connectionDaily(SOL))
            .performScrollTo()
            .assertTextContains(text(R.string.policy_amount_none))
    }

    @Test
    fun perRequestThresholdCanOverrideWhileTheAssetListStillInherits() {
        show(
            global =
                GlobalPolicy(
                    assets = Allowlist.of(SOL),
                    limits = mapOf(SOL to AssetLimits(perOperation = 2_000_000_000UL)),
                    updatedAt = AT,
                )
        )
        click(PolicyTags.connectionAsset(SOL))
        click(PolicyTags.overridePerOperation(SOL))
        type(PolicyTags.connectionPerOperation(SOL), "1")
        compose.onNodeWithTag(PolicyTags.DIALOG_ADD).performClick()
        assertEquals(false, draft.overrideAssets)
        assertEquals(true, draft.limits.single().overridePerOperation)
        compose
            .onNodeWithTag(PolicyTags.connectionAsset(SOL))
            .performScrollTo()
            .assertTextContains("1", substring = true)
    }

    @Test
    fun globalRulesNavigationAndConnectionSaveAreSeparateActions() {
        show(
            draft = ConnectionPolicyDraft(CONNECTION, overrideActions = true),
            stored = ConnectionPolicyDraft(CONNECTION),
        )
        click(PolicyTags.OPEN_GLOBAL)
        click(PolicyTags.SAVE)
        assertEquals(listOf("global", "save"), actions)
    }

    @Test
    fun anUnreadableGlobalDocumentIsNotPresentedAsNoRuleConfigured() {
        show(globalUnreadable = UnreadableReason.NewerVersion)
        compose.onNodeWithTag(PolicyTags.GLOBAL_UNREADABLE).assertExists()
        compose.onNodeWithTag(PolicyTags.SUMMARY).assertDoesNotExist()
        compose.onNodeWithTag(PolicyTags.override("actions")).performScrollTo().assertIsEnabled()
    }

    @Test
    fun anUnreadableGlobalDocumentWithholdsInheritedThresholdContext() {
        show(
            draft =
                ConnectionPolicyDraft(
                    CONNECTION,
                    limits = listOf(ConnectionAssetDraft(Network.NETWORK_MAINNET, daily = "1")),
                ),
            globalUnreadable = UnreadableReason.NewerVersion,
        )

        compose.onNodeWithTag(PolicyTags.GLOBAL_UNREADABLE).assertExists()
        compose
            .onNodeWithText(
                text(
                    R.string.policy_effective_per_request,
                    text(R.string.policy_effective_not_checked),
                    text(R.string.policy_source_none),
                )
            )
            .assertDoesNotExist()
        click(PolicyTags.connectionAsset(SOL))
        compose.onNodeWithTag(PolicyTags.globalDaily(SOL)).assertDoesNotExist()
        compose.onNodeWithTag(PolicyTags.connectionDaily(SOL)).performScrollTo().assertExists()
    }

    @Test
    fun addAssetOpensItsEditorAndSavesBackIntoTheConnectionDraft() {
        show(
            draft =
                ConnectionPolicyDraft(
                    CONNECTION,
                    overrideAssets = true,
                    restrictAssets = true,
                )
        )

        click(PolicyTags.ADD_ALLOWED_ASSET)
        click(PolicyTags.network(Network.NETWORK_DEVNET))
        compose.onNodeWithTag(PolicyTags.DIALOG_ADD).performClick()

        val devnetSol = PolicyAsset(Network.NETWORK_DEVNET)
        assertEquals(listOf(devnetSol), draft.assets)
    }

    @Test
    fun addSpendingLimitDoesNotAlsoChangeTheAssetAllowlist() {
        show(
            draft =
                ConnectionPolicyDraft(
                    CONNECTION,
                    overrideAssets = true,
                    restrictAssets = true,
                )
        )

        click(PolicyTags.ADD_LIMIT_ASSET)
        click(PolicyTags.network(Network.NETWORK_DEVNET))
        type(PolicyTags.connectionDaily(PolicyAsset(Network.NETWORK_DEVNET)), "1")
        compose.onNodeWithTag(PolicyTags.DIALOG_ADD).performClick()

        assertTrue(draft.assets.isEmpty())
        assertEquals(
            listOf(ConnectionAssetDraft(Network.NETWORK_DEVNET, daily = "1")),
            draft.limits,
        )
    }

    @Test
    fun aLocalAssetRemovalNamesTheWholeAssetForAccessibility() {
        show(
            draft =
                ConnectionPolicyDraft(
                    CONNECTION,
                    overrideAssets = true,
                    restrictAssets = true,
                    assets = listOf(SOL),
                )
        )
        compose
            .onNodeWithContentDescription(text(R.string.policy_asset_remove, "SOL on mainnet"))
            .performScrollTo()
            .performClick()
        assertTrue(draft.assets.isEmpty())
    }

    @Test
    @Config(fontScale = 2.0f)
    fun overrideControlsRemainReachableAtLargeText() {
        show(global = GlobalPolicy(actions = Allowlist.of(PolicyAction.Transfer), updatedAt = AT))
        click(PolicyTags.override("actions"))
        click(PolicyTags.restrict("actions"))
        click(PolicyTags.action(PolicyAction.Transfer))
        compose.onNodeWithTag(PolicyTags.SAVE).performScrollTo().assertIsEnabled()
        compose.onNodeWithTag(PolicyTags.CANCEL).performScrollTo().assertIsEnabled()
    }

    @Test
    fun savingStateDisablesBothSaveAndCancel() {
        val changed = ConnectionPolicyDraft(CONNECTION, overrideActions = true)
        show(changed, ConnectionPolicyDraft(CONNECTION))
        ui.value = ui.value.copy(saving = true)
        compose.onNodeWithTag(PolicyTags.SAVE).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(PolicyTags.CANCEL).performScrollTo().assertIsNotEnabled()
    }

    private companion object {
        val AT = java.time.Instant.parse("2026-09-13T10:00:00Z")
        const val SYSTEM = "11111111111111111111111111111111"
    }
}
