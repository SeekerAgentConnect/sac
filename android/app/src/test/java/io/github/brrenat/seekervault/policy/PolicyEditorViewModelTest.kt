package io.github.brrenat.seekervault.policy

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.policy.storage.PolicyStore
import io.github.brrenat.seekervault.policy.storage.StoredPolicy
import io.github.brrenat.seekervault.policy.storage.UnreadableReason
import io.github.brrenat.seekervault.request.v1.Network
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** The editor's state: reading one connection's rules, editing them, and writing them back. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PolicyEditorViewModelTest {
    @get:Rule val folder = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = UnconfinedTestDispatcher(scheduler)
    private val at = Instant.parse("2026-09-12T10:00:00Z")
    private val dir by lazy { File(folder.root, "files/policies") }
    private val store by lazy { PolicyStore(dir) }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(policies: PolicyStore = store) =
        PolicyEditorViewModel(policies, { at }, dispatcher)

    private fun opened(connectionId: String = CONNECTION) =
        viewModel().also { it.open(connectionId) }

    @Test
    fun aConnectionWithNoRulesOpensOnAnEmptyForm() {
        val state = opened().state.value
        assertTrue(state.loaded)
        assertNull(state.unreadable)
        assertNull(state.storedAt)
        assertEquals(PolicyDraft(CONNECTION), state.draft)
        assertFalse(state.changed)
    }

    @Test
    fun whatIsSavedIsWhatIsOnDisk() {
        val editor = opened()
        editor.edit(
            editor.state.value.draft.copy(
                restrictActions = true,
                actions = setOf(PolicyAction.Transfer),
                assets = listOf(AssetDraft(Network.NETWORK_MAINNET, null, perOperation = "1.5")),
            )
        )
        assertTrue(editor.state.value.changed)
        editor.save()
        assertEquals(PolicyMessage.Saved, editor.state.value.message)
        assertFalse(editor.state.value.changed)
        val stored = store.get(CONNECTION) as StoredPolicy.Policy
        assertEquals(Allowlist.of(PolicyAction.Transfer), stored.policy.actions)
        assertEquals(1_500_000_000UL, stored.policy.limitsFor(SOL).perOperation)
        assertEquals(at, stored.policy.updatedAt)
    }

    @Test
    fun rulesSurviveARestart() {
        val editor = opened()
        editor.edit(
            editor.state.value.draft.copy(
                restrictRecipients = true,
                recipients = listOf(RECIPIENT),
            )
        )
        editor.save()
        // A new store and a new ViewModel is what the next start of the app is.
        val again = PolicyEditorViewModel(PolicyStore(dir), { at }, dispatcher)
        again.open(CONNECTION)
        assertEquals(listOf(RECIPIENT), again.state.value.draft.recipients)
        assertTrue(again.state.value.draft.restrictRecipients)
        assertFalse(again.state.value.changed)
    }

    @Test
    fun oneConnectionsRulesNeverShowUnderAnother() {
        val editor = opened()
        editor.edit(
            editor.state.value.draft.copy(
                restrictPrograms = true,
                programs = listOf(SYSTEM),
            )
        )
        editor.save()
        editor.close()
        editor.open(OTHER_CONNECTION)
        assertEquals(PolicyDraft(OTHER_CONNECTION), editor.state.value.draft)
        assertEquals(StoredPolicy.None, store.get(OTHER_CONNECTION))
        // And the first connection's rules are still its own.
        editor.close()
        editor.open(CONNECTION)
        assertEquals(listOf(SYSTEM), editor.state.value.draft.programs)
    }

    @Test
    fun switchingConnectionsWithoutSavingDropsWhatWasTyped() {
        val editor = opened()
        editor.edit(editor.state.value.draft.copy(restrictActions = true))
        editor.close()
        editor.open(CONNECTION)
        assertFalse(editor.state.value.draft.restrictActions)
        assertEquals(StoredPolicy.None, store.get(CONNECTION))
    }

    @Test
    fun openingTheConnectionThatIsAlreadyOpenKeepsWhatWasTyped() {
        val editor = opened()
        editor.edit(editor.state.value.draft.copy(restrictActions = true))
        // A rotation runs the screen's effect again with the same connection.
        editor.open(CONNECTION)
        assertTrue(editor.state.value.draft.restrictActions)
        assertTrue(editor.state.value.changed)
    }

    @Test
    fun turningEveryRuleOffRemovesTheConnectionsRules() {
        val editor = opened()
        editor.edit(
            editor.state.value.draft.copy(
                restrictActions = true,
                actions = setOf(PolicyAction.Transfer),
            )
        )
        editor.save()
        editor.edit(editor.state.value.draft.copy(restrictActions = false))
        editor.save()
        assertEquals(PolicyMessage.Removed, editor.state.value.message)
        assertEquals(StoredPolicy.None, store.get(CONNECTION))
        assertNull(editor.state.value.storedAt)
    }

    @Test
    fun rulesThisBuildCantReadAreNeverSilentlyReplaced() {
        dir.mkdirs()
        File(dir, "$CONNECTION.json").writeText("""{"version":99,"connectionId":"$CONNECTION"}""")
        val editor = opened()
        assertEquals(UnreadableReason.NewerVersion, editor.state.value.unreadable)
        // Nothing is editable, so nothing can be saved on top of them by accident.
        editor.edit(editor.state.value.draft.copy(restrictActions = true))
        assertFalse(editor.state.value.draft.restrictActions)
        editor.save()
        assertEquals(
            UnreadableReason.NewerVersion,
            (store.get(CONNECTION) as StoredPolicy.Unreadable).why,
        )
    }

    @Test
    fun startingOverReplacesRulesThatCouldNotBeRead() {
        dir.mkdirs()
        File(dir, "$CONNECTION.json").writeText("not a policy")
        val editor = opened()
        assertEquals(UnreadableReason.Damaged, editor.state.value.unreadable)
        editor.startOver()
        assertNull(editor.state.value.unreadable)
        // An empty form over an unreadable file is still a change: saving it takes the file away.
        assertTrue(editor.state.value.changed)
        editor.save()
        assertEquals(PolicyMessage.Removed, editor.state.value.message)
        assertEquals(StoredPolicy.None, store.get(CONNECTION))
        assertFalse(editor.state.value.changed)
    }

    @Test
    fun aDraftThatIsntFitToStoreIsNotWritten() {
        val editor = opened()
        editor.edit(
            editor.state.value.draft.copy(
                assets = listOf(AssetDraft(Network.NETWORK_MAINNET, null, perOperation = "lots"))
            )
        )
        editor.save()
        assertNull(editor.state.value.message)
        assertEquals(StoredPolicy.None, store.get(CONNECTION))
    }

    @Test
    fun aSaveThatFailsLeavesTheStoredRulesAlone() {
        val editor = opened()
        editor.edit(editor.state.value.draft.copy(restrictActions = true))
        // A file where the directory has to go: the store can't write, and says so.
        File(folder.root, "files").mkdirs()
        dir.writeText("in the way")
        editor.save()
        assertEquals(PolicyMessage.SaveFailed, editor.state.value.message)
        // The draft is kept, so the owner doesn't lose what they typed.
        assertTrue(editor.state.value.draft.restrictActions)
        assertTrue(editor.state.value.changed)
    }

    @Test
    fun openingRulesReadsThemRatherThanTheOnesInHand() {
        val editor = opened()
        editor.edit(editor.state.value.draft.copy(restrictActions = true))
        editor.save()
        // Something else wrote the file since — the connection was removed and paired again.
        store.delete(CONNECTION)
        editor.close()
        editor.open(CONNECTION)
        assertFalse(editor.state.value.draft.restrictActions)
    }

    private companion object {
        const val SYSTEM = "11111111111111111111111111111111"
    }
}
