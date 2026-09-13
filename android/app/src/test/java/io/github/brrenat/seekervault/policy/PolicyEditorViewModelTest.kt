package io.github.brrenat.seekervault.policy

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.policy.storage.PolicyStore
import io.github.brrenat.seekervault.policy.storage.StoredConnectionOverrides
import io.github.brrenat.seekervault.policy.storage.StoredGlobalPolicy
import io.github.brrenat.seekervault.policy.storage.UnreadableReason
import io.github.brrenat.seekervault.request.v1.Network
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** The two editors retain every inheritance distinction while writing separate documents. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PolicyEditorViewModelTest {
    @get:Rule val folder = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = UnconfinedTestDispatcher(scheduler)
    private val at = Instant.parse("2026-09-13T10:00:00Z")
    private val dir by lazy { File(folder.root, "files/policies") }
    private val store by lazy { PolicyStore(dir) }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(io: kotlinx.coroutines.CoroutineDispatcher = dispatcher) =
        PolicyEditorViewModel(store, { at }, io)

    private fun opened() = viewModel().also { it.open(CONNECTION) }

    private fun PolicyEditorViewModel.connectionDraft(): ConnectionPolicyDraft =
        (state.value.draft as PolicyEditorDraft.Connection).rules

    private fun PolicyEditorViewModel.globalDraft(): PolicyDraft =
        (state.value.draft as PolicyEditorDraft.Global).rules

    private fun PolicyEditorViewModel.editConnection(
        change: (ConnectionPolicyDraft) -> ConnectionPolicyDraft
    ) = edit(PolicyEditorDraft.Connection(change(connectionDraft())))

    private fun PolicyEditorViewModel.editGlobal(change: (PolicyDraft) -> PolicyDraft) =
        edit(PolicyEditorDraft.Global(change(globalDraft())))

    @Test
    fun aConnectionWithoutADocumentOpensInheritingEverySection() {
        val editor = opened()
        assertTrue(editor.state.value.loaded)
        assertEquals(ConnectionPolicyDraft(CONNECTION), editor.connectionDraft())
        assertFalse(editor.state.value.changed)
        assertEquals(ConnectionDraftReview.InheritAll, editor.connectionDraft().review(at))
    }

    @Test
    fun globalProgramsAndALocalRecipientRemainSeparateAndResolveTogether() {
        store.putGlobal(GlobalPolicy(programs = Allowlist.of(SYSTEM), updatedAt = at))
        val editor = opened()
        editor.editConnection {
            it.copy(
                overrideRecipients = true,
                restrictRecipients = true,
                recipients = listOf(RECIPIENT),
            )
        }
        editor.save()

        val local = (store.getOverrides(CONNECTION) as StoredConnectionOverrides.Policy).overrides
        val effective = resolveEffectivePolicy(CONNECTION, global(), local)
        assertEquals(Allowlist.of(SYSTEM), effective.programs.value)
        assertEquals(RuleSource.Global, effective.programs.source)
        assertEquals(Allowlist.of(RECIPIENT), effective.recipients.value)
        assertEquals(RuleSource.ConnectionOverride, effective.recipients.source)
    }

    @Test
    fun aProgramOverrideReplacesTheWholeGlobalList() {
        store.putGlobal(GlobalPolicy(programs = Allowlist.of(SYSTEM, RECIPIENT), updatedAt = at))
        val editor = opened()
        editor.editConnection {
            it.copy(
                overridePrograms = true,
                restrictPrograms = true,
                programs = listOf(STRANGER),
            )
        }
        editor.save()
        val local = (store.getOverrides(CONNECTION) as StoredConnectionOverrides.Policy).overrides
        assertEquals(
            Allowlist.of(STRANGER),
            resolveEffectivePolicy(CONNECTION, global(), local).programs.value,
        )
    }

    @Test
    fun inheritNoCheckAndEmptyReplacementSurviveSaveAndRestart() {
        val editor = opened()
        editor.editConnection {
            it.copy(
                overrideActions = true,
                restrictActions = false,
                overridePrograms = true,
                restrictPrograms = true,
                programs = emptyList(),
            )
        }
        editor.save()
        val again = viewModel().also { it.open(CONNECTION) }.connectionDraft()
        assertTrue(again.overrideActions)
        assertFalse(again.restrictActions)
        assertFalse(again.overrideRecipients)
        assertTrue(again.overridePrograms)
        assertTrue(again.restrictPrograms)
        assertTrue(again.programs.isEmpty())
    }

    @Test
    fun perRequestInheritanceAndOverrideAreIndependentOfTheAssetList() {
        store.putGlobal(
            GlobalPolicy(
                assets = Allowlist.of(SOL),
                limits = mapOf(SOL to AssetLimits(perOperation = 2_000_000_000UL)),
                updatedAt = at,
            )
        )
        val editor = opened()
        editor.editConnection {
            it.copy(
                limits =
                    listOf(
                        ConnectionAssetDraft(
                            Network.NETWORK_MAINNET,
                            overridePerOperation = true,
                            perOperation = "1",
                        )
                    )
            )
        }
        editor.save()
        val local = (store.getOverrides(CONNECTION) as StoredConnectionOverrides.Policy).overrides
        val effective = resolveEffectivePolicy(CONNECTION, global(), local)
        assertEquals(Allowlist.of(SOL), effective.assets.value)
        assertEquals(RuleSource.Global, effective.assets.source)
        assertEquals(1_000_000_000UL, effective.limitsFor(SOL).perOperation.value)
        assertEquals(RuleSource.ConnectionOverride, effective.limitsFor(SOL).perOperation.source)
    }

    @Test
    fun bothDailyThresholdsRemainAfterLocalReset() {
        store.putGlobal(
            GlobalPolicy(
                limits = mapOf(SOL to AssetLimits(daily = 10_000_000_000UL)),
                updatedAt = at,
            )
        )
        val editor = opened()
        editor.editConnection {
            it.copy(limits = listOf(ConnectionAssetDraft(Network.NETWORK_MAINNET, daily = "3")))
        }
        editor.save()
        var local = (store.getOverrides(CONNECTION) as StoredConnectionOverrides.Policy).overrides
        var limits = resolveEffectivePolicy(CONNECTION, global(), local).limitsFor(SOL)
        assertEquals(10_000_000_000UL, limits.globalDaily.value)
        assertEquals(3_000_000_000UL, limits.connectionDaily.value)

        editor.resetConnectionOverrides()
        editor.save()
        assertEquals(StoredConnectionOverrides.None, store.getOverrides(CONNECTION))
        local = ConnectionPolicyOverrides.inheritAll(CONNECTION, at)
        limits = resolveEffectivePolicy(CONNECTION, global(), local).limitsFor(SOL)
        assertEquals(10_000_000_000UL, limits.globalDaily.value)
        assertNull(limits.connectionDaily.value)
    }

    @Test
    fun aGlobalEditorWritesAndRemovesOnlyTheGlobalDocument() {
        val editor = viewModel().also { it.openGlobal() }
        editor.editGlobal {
            it.copy(
                restrictActions = true,
                actions = setOf(PolicyAction.Transfer),
                assets = listOf(AssetDraft(Network.NETWORK_MAINNET, daily = "10")),
            )
        }
        editor.save()
        val saved = (store.getGlobal() as StoredGlobalPolicy.Policy).policy
        assertEquals(Allowlist.of(PolicyAction.Transfer), saved.actions)
        assertEquals(10_000_000_000UL, saved.limitsFor(SOL).daily)

        editor.editGlobal {
            it.copy(restrictActions = false, actions = emptySet(), assets = emptyList())
        }
        editor.save()
        assertEquals(StoredGlobalPolicy.None, store.getGlobal())
    }

    @Test
    fun resettingConnectionOverridesNeverDeletesGlobalRules() {
        store.putGlobal(GlobalPolicy(actions = Allowlist.of(PolicyAction.Transfer), updatedAt = at))
        val editor = opened()
        editor.editConnection {
            it.copy(overrideActions = true, restrictActions = true)
        }
        editor.save()
        editor.resetConnectionOverrides()
        editor.save()
        assertTrue(store.getGlobal() is StoredGlobalPolicy.Policy)
        assertEquals(StoredConnectionOverrides.None, store.getOverrides(CONNECTION))
    }

    @Test
    fun refreshingGlobalContextKeepsAnUnsavedLocalDraft() {
        store.putGlobal(GlobalPolicy(programs = Allowlist.of(SYSTEM), updatedAt = at))
        val editor = opened()
        editor.editConnection {
            it.copy(
                overrideRecipients = true,
                restrictRecipients = true,
                recipients = listOf(RECIPIENT),
            )
        }
        store.putGlobal(
            GlobalPolicy(programs = Allowlist.of(STRANGER), updatedAt = at.plusSeconds(1))
        )
        editor.refreshGlobal()
        assertEquals(listOf(RECIPIENT), editor.connectionDraft().recipients)
        assertEquals(Allowlist.of(STRANGER), editor.state.value.global?.programs)
        assertTrue(editor.state.value.changed)
    }

    @Test
    fun globalEditsChangeInheritedSectionsButNotOverriddenOnes() {
        store.putGlobal(GlobalPolicy(programs = Allowlist.of(SYSTEM), updatedAt = at))
        val editor = opened()
        editor.editConnection {
            it.copy(
                overrideRecipients = true,
                restrictRecipients = true,
                recipients = listOf(RECIPIENT),
            )
        }
        store.putGlobal(
            GlobalPolicy(
                programs = Allowlist.of(STRANGER),
                recipients = Allowlist.of(STRANGER),
                updatedAt = at.plusSeconds(1),
            )
        )
        editor.refreshGlobal()
        val local = (editor.connectionDraft().review(at) as ConnectionDraftReview.Ready).overrides
        val effective = resolveEffectivePolicy(CONNECTION, editor.state.value.global, local)
        assertEquals(Allowlist.of(STRANGER), effective.programs.value)
        assertEquals(Allowlist.of(RECIPIENT), effective.recipients.value)
    }

    @Test
    fun reopeningTheSameScopeForRotationKeepsUnsavedEdits() {
        val editor = opened()
        editor.editConnection { it.copy(overrideActions = true) }
        editor.open(CONNECTION)
        assertTrue(editor.connectionDraft().overrideActions)
        assertTrue(editor.state.value.changed)
    }

    @Test
    fun editsTypedWhileSavingAreNotMarkedAsSaved() {
        val slow = StandardTestDispatcher(scheduler)
        val editor = viewModel(slow)
        editor.open(CONNECTION)
        scheduler.advanceUntilIdle()
        editor.editConnection {
            it.copy(overrideActions = true, restrictActions = true)
        }
        editor.save()
        editor.editConnection {
            it.copy(overrideRecipients = true, restrictRecipients = true)
        }
        scheduler.advanceUntilIdle()
        val stored = (editor.state.value.stored as PolicyEditorDraft.Connection).rules
        assertFalse(stored.overrideRecipients)
        assertTrue(editor.connectionDraft().overrideRecipients)
        assertTrue(editor.state.value.changed)
    }

    @Test
    fun unreadableDocumentsAreNeverOpenedAsBlankForms() {
        dir.mkdirs()
        File(dir, "$CONNECTION.json").writeText("{\"version\":99}")
        val editor = opened()
        assertEquals(UnreadableReason.NewerVersion, editor.state.value.unreadable)
        editor.edit(
            PolicyEditorDraft.Connection(ConnectionPolicyDraft(CONNECTION, overrideActions = true))
        )
        editor.save()
        assertTrue(store.getOverrides(CONNECTION) is StoredConnectionOverrides.Unreadable)
        editor.startOver()
        assertTrue(editor.state.value.changed)
        editor.save()
        assertEquals(StoredConnectionOverrides.None, store.getOverrides(CONNECTION))
    }

    @Test
    fun anUnreadableGlobalDocumentIsNotReportedAsNoGlobalRules() {
        dir.mkdirs()
        File(dir, "global.json").writeText("{\"version\":99}")
        val editor = opened()
        assertEquals(UnreadableReason.NewerVersion, editor.state.value.globalUnreadable)
        assertNull(editor.state.value.global)
        assertNull(editor.state.value.unreadable)
    }

    @Test
    fun theGlobalEditorNeverReplacesAnUnreadableDocumentUntilStartOver() {
        dir.mkdirs()
        File(dir, "global.json").writeText("{\"version\":99}")
        val editor = viewModel().also { it.openGlobal() }
        assertEquals(UnreadableReason.NewerVersion, editor.state.value.unreadable)
        editor.edit(PolicyEditorDraft.Global(PolicyDraft(GLOBAL_DRAFT_ID, restrictActions = true)))
        editor.save()
        assertTrue(store.getGlobal() is StoredGlobalPolicy.Unreadable)

        editor.startOver()
        editor.save()
        assertEquals(StoredGlobalPolicy.None, store.getGlobal())
    }

    @Test
    fun aFailedSaveKeepsTheDraftAndTheStoredDocumentAlone() {
        val editor = opened()
        editor.editConnection {
            it.copy(overrideActions = true, restrictActions = true)
        }
        File(folder.root, "files").mkdirs()
        dir.writeText("in the way")
        editor.save()
        assertEquals(PolicyMessage.SaveFailed, editor.state.value.message)
        assertTrue(editor.connectionDraft().overrideActions)
        assertTrue(editor.state.value.changed)
    }

    private fun global() = (store.getGlobal() as StoredGlobalPolicy.Policy).policy

    private companion object {
        const val SYSTEM = "11111111111111111111111111111111"
    }
}
