package io.github.brrenat.seekervault

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.ConnectionsTags
import io.github.brrenat.seekervault.connections.FakeConnectionGateway
import io.github.brrenat.seekervault.connections.softwareKey
import io.github.brrenat.seekervault.policy.Allowlist
import io.github.brrenat.seekervault.policy.GlobalPolicy
import io.github.brrenat.seekervault.policy.PolicyAction
import io.github.brrenat.seekervault.policy.PolicyAsset
import io.github.brrenat.seekervault.policy.PolicyTags
import io.github.brrenat.seekervault.policy.RECIPIENTS
import io.github.brrenat.seekervault.policy.SpendScope
import io.github.brrenat.seekervault.policy.storage.StoredGlobalPolicy
import io.github.brrenat.seekervault.policy.storage.StoredPolicy
import io.github.brrenat.seekervault.request.v1.Network
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The policy editor in the real activity, with the app's own storage: rules written on the phone,
 * kept on the phone, kept apart per connection, and still there after a restart.
 */
@RunWith(AndroidJUnit4::class)
class PolicyActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SeekerVaultApplication>()
    private val gateway = FakeConnectionGateway()
    private val server = gateway.serve(URL)
    private val key = softwareKey()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun useFakes() {
        app.connectionGateway = { gateway }
        app.credentialKey = { key }
        app.connectionIo = Dispatchers.Unconfined
    }

    @After fun close() = scenario?.close() ?: Unit

    private fun launch() = ActivityScenario.launch(MainActivity::class.java).also { scenario = it }

    private fun pair(): Connection = runBlocking {
        app.connectionRepository.pair(server.issue(URL))
    }

    private fun openRules(connection: Connection) {
        val connectionIndex =
            app.connectionRepository.connections.value.indexOfFirst { it.id == connection.id }
        compose.onNodeWithTag(ConnectionsTags.LIST).performScrollToIndex(5 + connectionIndex)
        compose.onNodeWithTag(ConnectionsTags.item(connection.id)).performClick()
        compose.onNodeWithTag(PolicyTags.RULES).performScrollTo().performClick()
    }

    private fun stored(connection: Connection) = app.policyStore.get(connection.id)

    /** Saves, and lets the snackbar go so it doesn't cover the buttons under it. */
    private fun save() {
        compose.onNodeWithTag(PolicyTags.SAVE).performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(10_000)
    }

    @Test
    fun rulesWrittenHereAreStoredForThatConnectionAndSurviveARestart() {
        val connection = pair()
        val scenario = launch()
        openRules(connection)
        compose.onNodeWithTag(PolicyTags.override("actions")).performScrollTo().performClick()
        compose.onNodeWithTag(PolicyTags.restrict("actions")).performScrollTo().performClick()
        compose
            .onNodeWithTag(PolicyTags.action(PolicyAction.Transfer))
            .performScrollTo()
            .performClick()
        compose.onNodeWithTag(PolicyTags.ADD_LIMIT_ASSET).performScrollTo().performClick()
        compose.onNodeWithTag(PolicyTags.DIALOG_ADD).performClick()
        compose
            .onNodeWithTag(
                PolicyTags.overridePerOperation(PolicyAsset.sol(Network.NETWORK_MAINNET))
            )
            .performScrollTo()
            .performClick()
        compose
            .onNodeWithTag(
                PolicyTags.connectionPerOperation(PolicyAsset.sol(Network.NETWORK_MAINNET))
            )
            .performScrollTo()
            .performTextReplacement("1.5")
        compose.onNodeWithTag(PolicyTags.SAVE).performScrollTo().performClick()
        compose.onNodeWithText(app.getString(R.string.policy_saved)).assertExists()

        // On the phone, in this connection's own file, and nowhere else.
        assertTrue(File(app.filesDir, "policies/${connection.id}.json").isFile)
        val policy = (stored(connection) as StoredPolicy.Policy).policy
        assertEquals(Allowlist.of(PolicyAction.Transfer), policy.actions)
        assertEquals(
            1_500_000_000UL,
            policy.limitsFor(PolicyAsset.sol(Network.NETWORK_MAINNET)).perOperation,
        )

        // Still there when the app starts again, and shown as what was written.
        scenario.recreate()
        compose.onNodeWithTag(PolicyTags.override("actions")).performScrollTo().assertExists()
        compose.onNodeWithTag(PolicyTags.SAVE).performScrollTo().assertExists()
        compose
            .onNodeWithTag(PolicyTags.connectionAsset(PolicyAsset.sol(Network.NETWORK_MAINNET)))
            .performScrollTo()
            .assertExists()
    }

    @Test
    fun oneConnectionsRulesNeverShowUnderAnother() {
        val first = pair()
        val second = pair()
        launch()
        openRules(first)
        compose.onNodeWithTag(PolicyTags.override(RECIPIENTS)).performScrollTo().performClick()
        compose.onNodeWithTag(PolicyTags.restrict(RECIPIENTS)).performScrollTo().performClick()
        compose
            .onNodeWithTag(PolicyTags.entryField(RECIPIENTS))
            .performScrollTo()
            .performTextReplacement(RECIPIENT)
        compose.onNodeWithTag(PolicyTags.add(RECIPIENTS)).performScrollTo().performClick()
        save()
        compose.onNodeWithTag(PolicyTags.CANCEL).performScrollTo().performClick()
        compose.onNodeWithTag(ConnectionsTags.CLOSE).performClick()
        compose.mainClock.advanceTimeBy(240)

        openRules(second)
        compose.onNodeWithTag(PolicyTags.inherit(RECIPIENTS)).performScrollTo().assertExists()
        compose
            .onNodeWithText(
                app.getString(
                    R.string.policy_effective_line,
                    app.getString(R.string.policy_section_recipients),
                    app.getString(R.string.policy_effective_not_checked),
                    app.getString(R.string.policy_source_none),
                )
            )
            .assertExists()
        compose.onNodeWithTag(PolicyTags.entry(RECIPIENTS, RECIPIENT)).assertDoesNotExist()
        assertEquals(StoredPolicy.None, stored(second))
        assertEquals(setOf(first.id), app.policyStore.connectionIds())
    }

    @Test
    fun aConnectionsRulesGoWhenTheConnectionDoes() {
        val connection = pair()
        val global =
            GlobalPolicy.default(Instant.parse("2026-09-13T12:00:00Z"))
                .copy(actions = Allowlist.of(PolicyAction.Transfer))
        app.policyStore.putGlobal(global)
        launch()
        openRules(connection)
        compose.onNodeWithTag(PolicyTags.override("actions")).performScrollTo().performClick()
        save()
        assertTrue(File(app.filesDir, "policies/${connection.id}.json").isFile)
        compose.onNodeWithTag(PolicyTags.CANCEL).performScrollTo().performClick()
        compose.onNodeWithTag(ConnectionsTags.DISCONNECT).performScrollTo().performClick()
        compose.onNodeWithTag(ConnectionsTags.DIALOG_CONFIRM).performClick()
        compose.onNodeWithTag(ConnectionsTags.EMPTY).assertExists()
        assertFalse(File(app.filesDir, "policies/${connection.id}.json").exists())
        assertEquals(emptySet<String>(), app.policyStore.connectionIds())
        assertEquals(StoredGlobalPolicy.Policy(global), app.policyStore.getGlobal())
    }

    @Test
    fun leavingWithUnsavedRulesAsksAndKeepsTheStoredOnesAsTheyWere() {
        val connection = pair()
        launch()
        openRules(connection)
        compose.onNodeWithTag(PolicyTags.override("actions")).performScrollTo().performClick()
        compose.onNodeWithTag(PolicyTags.CANCEL).performScrollTo().performClick()
        compose.onNodeWithTag(PolicyTags.DISCARD).performClick()
        assertEquals(StoredPolicy.None, stored(connection))
        // Back on the connection, and the editor opens again on what is stored: nothing.
        compose.onNodeWithTag(PolicyTags.RULES).performScrollTo().performClick()
        compose.onNodeWithTag(PolicyTags.inherit("actions")).performScrollTo().assertExists()
    }

    @Test
    fun aGlobalEditRefreshesInheritanceWithoutLosingTheLocalDraftUnderIt() {
        val connection = pair()
        launch()
        openRules(connection)
        compose.onNodeWithTag(PolicyTags.override(RECIPIENTS)).performScrollTo().performClick()
        compose.onNodeWithTag(PolicyTags.restrict(RECIPIENTS)).performScrollTo().performClick()
        compose
            .onNodeWithTag(PolicyTags.entryField(RECIPIENTS))
            .performScrollTo()
            .performTextReplacement(RECIPIENT)
        compose.onNodeWithTag(PolicyTags.add(RECIPIENTS)).performScrollTo().performClick()

        compose.onNodeWithTag(PolicyTags.OPEN_GLOBAL).performScrollTo().performClick()
        compose.onNodeWithTag(PolicyTags.restrict("actions")).performScrollTo().performClick()
        compose
            .onNodeWithTag(PolicyTags.action(PolicyAction.Transfer))
            .performScrollTo()
            .performClick()
        compose.onNodeWithTag(PolicyTags.SAVE).performScrollTo().performClick()
        compose.onNodeWithTag(PolicyTags.CONFIRM_GLOBAL_SAVE).performClick()
        compose.mainClock.advanceTimeBy(10_000)
        // System Back must use the same close path as the app-bar button: the global editor has
        // no dirty draft after saving, but the connection underneath still needs refreshed context.
        checkNotNull(scenario).onActivity { it.onBackPressedDispatcher.onBackPressed() }

        // The local recipient is still an unsaved connection override, while the inherited action
        // summary has refreshed from the global document that was just saved.
        compose
            .onNodeWithTag(PolicyTags.entry(RECIPIENTS, RECIPIENT))
            .performScrollTo()
            .assertExists()
        compose
            .onNodeWithText(
                app.getString(
                    R.string.policy_effective_line,
                    app.getString(R.string.policy_section_actions),
                    app.getString(R.string.policy_action_transfer_short),
                    app.getString(R.string.policy_source_global),
                )
            )
            .assertExists()
        assertEquals(StoredPolicy.None, stored(connection))
    }

    @Test
    fun theAppsOwnEvaluatorTreatsAnUnreadHistoryAsUnknownRatherThanAsNothingSpent() {
        // The history is read off the disk asynchronously and a read can fail, so the evaluator
        // the app wires up has to tell "nothing here" from "nobody has looked". Wired the other
        // way, every daily threshold would pass on a cold start (docs/policy.md#counters).
        val connection = pair()
        val scope =
            SpendScope(
                connection.id,
                "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW",
                PolicyAsset.sol(Network.NETWORK_MAINNET),
            )
        assertFalse(app.activityLog.loaded.value)

        assertNull(app.policyEvaluator.spentToday(scope))

        app.activityLog.load()
        assertEquals(0UL, checkNotNull(app.policyEvaluator.spentToday(scope)).confirmed)

        val activity = File(app.filesDir, "activity/${connection.id}").apply { mkdirs() }
        File(activity, "f7e6d5c4-b3a2-4918-8a7f-6e5d4c3b2a19.json").writeText("{ not json")
        app.activityLog.load()
        val partial = checkNotNull(app.policyEvaluator.spentToday(scope))
        assertFalse(partial.known)
        assertEquals(1, partial.unreadable)
    }

    private companion object {
        const val URL = "https://mac.tailnet.ts.net"
        const val RECIPIENT = "7LCE7pWnYuYKtGMWt2Q4aXKQrqTmGf4c1Kt1PTmAGwSt"
    }
}
