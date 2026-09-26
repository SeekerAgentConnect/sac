package io.github.brrenat.seekervault.policy.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.policy.Allowlist
import io.github.brrenat.seekervault.policy.AssetLimits
import io.github.brrenat.seekervault.policy.ConnectionAssetLimits
import io.github.brrenat.seekervault.policy.ConnectionPolicy
import io.github.brrenat.seekervault.policy.ConnectionPolicyOverrides
import io.github.brrenat.seekervault.policy.GlobalPolicy
import io.github.brrenat.seekervault.policy.PolicyAction
import io.github.brrenat.seekervault.policy.PolicyAsset
import io.github.brrenat.seekervault.policy.RuleOverride
import io.github.brrenat.seekervault.request.v1.Network
import java.io.File
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Version 2 stores global rules and connection overrides separately and migrates Stage 5 files. */
@RunWith(AndroidJUnit4::class)
class PolicyStoreV2Test {
    @get:Rule val folder = TemporaryFolder()

    private val dir by lazy { File(folder.root, "files/policies").apply { mkdirs() } }
    private val store by lazy { PolicyStore(dir) }

    @Test
    fun globalRulesAndEveryConnectionOverrideStateRoundTripSeparately() {
        val global =
            GlobalPolicy.default(NOW)
                .copy(
                    actions = Allowlist.of(PolicyAction.Transfer),
                    limits = mapOf(SOL to AssetLimits(perOperation = 10UL, daily = 100UL)),
                )
        val overrides =
            ConnectionPolicyOverrides.inheritAll(CONNECTION, LATER)
                .copy(
                    actions = RuleOverride.NoCheck,
                    assets = RuleOverride.Replace(Allowlist.nothing()),
                    recipients = RuleOverride.Replace(Allowlist.of(RECIPIENT)),
                )
        store.putGlobal(global)
        store.putOverrides(overrides)

        assertEquals(StoredGlobalPolicy.Policy(global), store.getGlobal())
        assertEquals(StoredConnectionOverrides.Policy(overrides), store.getOverrides(CONNECTION))

        val json = JSONObject(file(CONNECTION).readText())
        assertEquals(2, json.getInt("version"))
        assertEquals("connection", json.getString("scope"))
        assertEquals("no_check", json.getJSONObject("actions").getString("mode"))
        assertEquals(0, json.getJSONObject("assets").getJSONArray("values").length())
        assertFalse(json.has("programs"))
    }

    @Test
    fun thresholdOverrideStatesAndBothDailyScopesSurviveARestart() {
        val global =
            GlobalPolicy.default(NOW)
                .copy(limits = mapOf(SOL to AssetLimits(perOperation = 10UL, daily = 100UL)))
        val replaced =
            ConnectionPolicyOverrides.inheritAll(CONNECTION, LATER)
                .copy(
                    limits =
                        mapOf(
                            SOL to
                                ConnectionAssetLimits(
                                    perOperation = RuleOverride.Replace(20UL),
                                    daily = 50UL,
                                ),
                            USDC to ConnectionAssetLimits(perOperation = RuleOverride.NoCheck),
                        )
                )
        store.putGlobal(global)
        store.putOverrides(replaced)

        val restarted = PolicyStore(dir)

        assertEquals(StoredGlobalPolicy.Policy(global), restarted.getGlobal())
        assertEquals(StoredConnectionOverrides.Policy(replaced), restarted.getOverrides(CONNECTION))
    }

    @Test
    fun deletingOneScopeNeverDeletesAnother() {
        val global = GlobalPolicy.default(NOW).copy(programs = Allowlist.of(SYSTEM))
        val mine =
            ConnectionPolicyOverrides.inheritAll(CONNECTION, LATER)
                .copy(recipients = RuleOverride.Replace(Allowlist.of(RECIPIENT)))
        val theirs =
            ConnectionPolicyOverrides.inheritAll(OTHER, LATER)
                .copy(recipients = RuleOverride.NoCheck)
        store.putGlobal(global)
        store.putOverrides(mine)
        store.putOverrides(theirs)

        store.delete(CONNECTION)

        assertEquals(StoredConnectionOverrides.None, store.getOverrides(CONNECTION))
        assertEquals(StoredConnectionOverrides.Policy(theirs), store.getOverrides(OTHER))
        assertEquals(StoredGlobalPolicy.Policy(global), store.getGlobal())
        assertEquals(setOf(OTHER), store.connectionIds())

        store.deleteGlobal()

        assertEquals(StoredGlobalPolicy.None, store.getGlobal())
        assertEquals(StoredConnectionOverrides.Policy(theirs), store.getOverrides(OTHER))
    }

    @Test
    fun version1MigratesOnceAndKeepsExistingBehaviorWithoutCreatingGlobalRules() {
        val old =
            ConnectionPolicy(
                connectionId = CONNECTION,
                actions = Allowlist.of(PolicyAction.Transfer),
                recipients = Allowlist.nothing(),
                limits = mapOf(SOL to AssetLimits(perOperation = 10UL, daily = 20UL)),
                updatedAt = NOW,
            )
        file(CONNECTION).writeText(version1().toString())

        val first = store.getOverrides(CONNECTION)

        val expected =
            ConnectionPolicyOverrides(
                connectionId = CONNECTION,
                actions = RuleOverride.Replace(checkNotNull(old.actions)),
                recipients = RuleOverride.Replace(checkNotNull(old.recipients)),
                limits =
                    mapOf(
                        SOL to
                            ConnectionAssetLimits(
                                perOperation = RuleOverride.Replace(10UL),
                                daily = 20UL,
                            )
                    ),
                updatedAt = NOW,
            )
        assertEquals(StoredConnectionOverrides.Policy(expected), first)
        assertEquals(StoredPolicy.Policy(old), store.get(CONNECTION))
        assertEquals(StoredGlobalPolicy.None, store.getGlobal())

        val migrated = file(CONNECTION).readText()
        val json = JSONObject(migrated)
        assertEquals(2, json.getInt("version"))
        assertEquals("replace", json.getJSONObject("actions").getString("mode"))
        assertFalse(json.has("assets"))
        assertFalse(json.has("programs"))
        assertEquals(
            "20",
            json.getJSONArray("limits").getJSONObject(0).getString("daily"),
        )

        val restarted = PolicyStore(dir)
        assertEquals(StoredConnectionOverrides.Policy(expected), restarted.getOverrides(CONNECTION))
        assertEquals(migrated, file(CONNECTION).readText())
        assertEquals(StoredGlobalPolicy.None, restarted.getGlobal())
    }

    @Test
    fun anInterruptedAtomicWriteDoesNotHideTheLastCompleteVersion1Document() {
        file(CONNECTION).writeText(version1().toString())
        File(dir, "$CONNECTION.json.new").writeText("{ interrupted")

        val stored = PolicyStore(dir).getOverrides(CONNECTION)

        assertTrue(stored is StoredConnectionOverrides.Policy)
        assertEquals(2, JSONObject(file(CONNECTION).readText()).getInt("version"))
        assertFalse(File(dir, "$CONNECTION.json.new").exists())
    }

    @Test
    fun missingAndUnreadableGlobalAndConnectionDocumentsNeverCollapseTogether() {
        globalFile().writeText(JSONObject().put("version", 3).put("scope", "global").toString())
        file(CONNECTION)
            .writeText(
                JSONObject()
                    .put("version", 2)
                    .put("scope", "connection")
                    .put("connectionId", CONNECTION)
                    .put("updatedAt", NOW.toString())
                    .put("actions", JSONObject().put("mode", "merge"))
                    .toString()
            )
        val globalBytes = globalFile().readText()
        val localBytes = file(CONNECTION).readText()

        assertEquals(
            StoredGlobalPolicy.Unreadable(UnreadableReason.NewerVersion),
            store.getGlobal(),
        )
        assertEquals(
            StoredConnectionOverrides.Unreadable(UnreadableReason.UnknownRule),
            store.getOverrides(CONNECTION),
        )
        assertEquals(StoredConnectionOverrides.None, store.getOverrides(OTHER))
        assertEquals(globalBytes, globalFile().readText())
        assertEquals(localBytes, file(CONNECTION).readText())
    }

    @Test
    fun invalidGlobalOrConnectionRulesAreNeverWritten() {
        val badGlobal = GlobalPolicy.default(NOW).copy(recipients = Allowlist.of("not an address"))
        val badLocal =
            ConnectionPolicyOverrides.inheritAll(CONNECTION, NOW)
                .copy(
                    limits =
                        mapOf(
                            SOL to
                                ConnectionAssetLimits(
                                    perOperation = RuleOverride.Replace(10UL),
                                    daily = 5UL,
                                )
                        )
                )

        assertThrows(IllegalArgumentException::class.java) { store.putGlobal(badGlobal) }
        assertThrows(IllegalArgumentException::class.java) { store.putOverrides(badLocal) }
        assertEquals(StoredGlobalPolicy.None, store.getGlobal())
        assertEquals(StoredConnectionOverrides.None, store.getOverrides(CONNECTION))
    }

    private fun file(connectionId: String) = File(dir, "$connectionId.json")

    private fun globalFile() = File(dir, "global.json")

    private fun version1() =
        JSONObject()
            .put("version", 1)
            .put("connectionId", CONNECTION)
            .put("updatedAt", NOW.toString())
            .put("actions", JSONArray(listOf("transfer")))
            .put("recipients", JSONArray())
            .put(
                "limits",
                JSONArray(
                    listOf(
                        JSONObject()
                            .put("asset", JSONObject().put("network", "NETWORK_MAINNET"))
                            .put("perOperation", "10")
                            .put("daily", "20")
                    )
                ),
            )

    private companion object {
        const val CONNECTION = "6a5f0a0e-2f2f-4a07-9a1f-4f0f2b3f9a11"
        const val OTHER = "0f1e2d3c-4b5a-4c7d-8e9f-0a1b2c3d4e5f"
        const val RECIPIENT = "7LCE7pWnYuYKtGMWt2Q4aXKQrqTmGf4c1Kt1PTmAGwSt"
        const val MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        const val SYSTEM = "11111111111111111111111111111111"
        val SOL = PolicyAsset.sol(Network.NETWORK_MAINNET)
        val USDC = PolicyAsset.token(Network.NETWORK_MAINNET, MINT)
        val NOW: Instant = Instant.parse("2026-09-13T10:00:00Z")
        val LATER: Instant = Instant.parse("2026-09-13T11:00:00Z")
    }
}
