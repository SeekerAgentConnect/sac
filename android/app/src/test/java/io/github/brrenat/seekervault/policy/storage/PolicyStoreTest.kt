package io.github.brrenat.seekervault.policy.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.policy.Allowlist
import io.github.brrenat.seekervault.policy.AssetLimits
import io.github.brrenat.seekervault.policy.ConnectionPolicy
import io.github.brrenat.seekervault.policy.PolicyAction
import io.github.brrenat.seekervault.policy.PolicyAsset
import io.github.brrenat.seekervault.request.v1.Network
import java.io.File
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Storing the owner's rules: one file per connection, read back exactly, or not read at all. */
@RunWith(AndroidJUnit4::class)
class PolicyStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val dir by lazy { File(folder.root, "files/policies").apply { mkdirs() } }
    private val store by lazy { PolicyStore(dir) }

    @Test
    fun aConnectionWithNoRulesHasNone() {
        assertEquals(StoredPolicy.None, store.get(CONNECTION))
        assertEquals(emptySet<String>(), store.connectionIds())
    }

    @Test
    fun aFullPolicyComesBackTheWayItWasWritten() {
        val policy =
            ConnectionPolicy(
                connectionId = CONNECTION,
                actions = Allowlist.of(PolicyAction.Transfer, PolicyAction.MessageSignature),
                assets = Allowlist.of(SOL, USDC),
                recipients = Allowlist.of(RECIPIENT),
                programs = Allowlist.of(SYSTEM),
                limits =
                    mapOf(
                        SOL to AssetLimits(perOperation = 1_000_000UL, daily = 5_000_000UL),
                        USDC to AssetLimits(daily = ULong.MAX_VALUE),
                    ),
                updatedAt = NOW,
            )

        store.put(policy)

        assertEquals(StoredPolicy.Policy(policy), store.get(CONNECTION))
        assertEquals(setOf(CONNECTION), store.connectionIds())
    }

    @Test
    fun theDefaultPolicySurvivesAWriteWithEveryCheckStillUnconfigured() {
        val policy = ConnectionPolicy.default(CONNECTION, NOW)

        store.put(policy)

        assertEquals(StoredPolicy.Policy(policy), store.get(CONNECTION))
    }

    @Test
    fun anEmptyListIsStoredAsOneAndAnAbsentListIsStoredAsNothing() {
        val policy =
            ConnectionPolicy.default(CONNECTION, NOW).copy(recipients = Allowlist.nothing())
        store.put(policy)

        val json = JSONObject(file(CONNECTION).readText())
        assertEquals(0, json.getJSONArray("recipients").length())
        assertEquals(false, json.has("programs"))

        val read = store.get(CONNECTION) as StoredPolicy.Policy
        assertEquals(Allowlist.nothing<String>(), read.policy.recipients)
        assertEquals(null, read.policy.programs)
    }

    @Test
    fun oneConnectionsRulesAreNeverAnothersAndRemovingOneLeavesTheRest() {
        val mine =
            ConnectionPolicy.default(CONNECTION, NOW).copy(recipients = Allowlist.of(RECIPIENT))
        val theirs = ConnectionPolicy.default(OTHER, NOW).copy(recipients = Allowlist.nothing())
        store.put(mine)
        store.put(theirs)

        assertEquals(StoredPolicy.Policy(mine), store.get(CONNECTION))
        assertEquals(StoredPolicy.Policy(theirs), store.get(OTHER))
        assertEquals(setOf(CONNECTION, OTHER), store.connectionIds())

        store.delete(CONNECTION)

        assertEquals(StoredPolicy.None, store.get(CONNECTION))
        assertEquals(StoredPolicy.Policy(theirs), store.get(OTHER))
    }

    @Test
    fun aFileThatNamesAnotherConnectionIsNotThisConnectionsPolicy() {
        store.put(
            ConnectionPolicy.default(CONNECTION, NOW).copy(recipients = Allowlist.of(RECIPIENT))
        )
        val moved = file(CONNECTION).readText()
        file(OTHER).writeText(moved)

        assertEquals(StoredPolicy.Unreadable(UnreadableReason.Damaged), store.get(OTHER))
    }

    @Test
    fun aDocumentFromALaterVersionIsRefusedRatherThanReadAsFewerRules() {
        write(CONNECTION, base().put("version", 2).put("recipients", JSONArray(listOf(RECIPIENT))))

        assertEquals(StoredPolicy.Unreadable(UnreadableReason.NewerVersion), store.get(CONNECTION))
    }

    @Test
    fun aDocumentWithNoVersionIsDamagedBecauseThereIsNoOlderFormat() {
        val json = base()
        json.remove("version")
        write(CONNECTION, json)

        assertEquals(StoredPolicy.Unreadable(UnreadableReason.Damaged), store.get(CONNECTION))
    }

    @Test
    fun aRuleThisBuildHasNoNameForMakesTheWholeDocumentUnreadable() {
        write(CONNECTION, base().put("actions", JSONArray(listOf("transfer", "stake"))))
        assertEquals(StoredPolicy.Unreadable(UnreadableReason.UnknownRule), store.get(CONNECTION))

        write(
            CONNECTION,
            base()
                .put(
                    "assets",
                    JSONArray(
                        listOf(JSONObject().put("network", "NETWORK_SOLARIS").put("mint", MINT))
                    ),
                ),
        )
        assertEquals(StoredPolicy.Unreadable(UnreadableReason.UnknownRule), store.get(CONNECTION))
    }

    @Test
    fun rubbishOnDiskIsUnreadableAndNotAnEmptyPolicy() {
        file(CONNECTION).writeText("{ not json")
        assertEquals(StoredPolicy.Unreadable(UnreadableReason.Damaged), store.get(CONNECTION))

        write(CONNECTION, base().put("updatedAt", "the other day"))
        assertEquals(StoredPolicy.Unreadable(UnreadableReason.Damaged), store.get(CONNECTION))

        write(CONNECTION, base().put("recipients", "not a list"))
        assertEquals(StoredPolicy.Unreadable(UnreadableReason.Damaged), store.get(CONNECTION))
    }

    @Test
    fun aLimitThatIsNotAWholeNumberOfBaseUnitsIsDamaged() {
        write(
            CONNECTION,
            base()
                .put(
                    "limits",
                    JSONArray(
                        listOf(
                            JSONObject()
                                .put("asset", JSONObject().put("network", "NETWORK_MAINNET"))
                                .put("perOperation", "1.5")
                        )
                    ),
                ),
        )

        assertEquals(StoredPolicy.Unreadable(UnreadableReason.Damaged), store.get(CONNECTION))
    }

    @Test
    fun aStoredPolicyThisAppWouldRefuseToWriteIsRefusedOnTheWayBackToo() {
        write(CONNECTION, base().put("recipients", JSONArray(listOf("not an address"))))

        assertEquals(StoredPolicy.Unreadable(UnreadableReason.Damaged), store.get(CONNECTION))
    }

    @Test
    fun aPolicyWithAProblemIsNeverWritten() {
        val policy =
            ConnectionPolicy.default(CONNECTION, NOW).copy(recipients = Allowlist.of("nope!"))

        assertThrows(IllegalArgumentException::class.java) { store.put(policy) }
        assertEquals(StoredPolicy.None, store.get(CONNECTION))
    }

    @Test
    fun nothingIsStoredOrReadUnderAnythingButAConnectionId() {
        assertEquals(StoredPolicy.None, store.get("../elsewhere"))
        assertThrows(IllegalArgumentException::class.java) {
            store.put(ConnectionPolicy.default("../elsewhere", NOW))
        }
    }

    private fun file(connectionId: String) = File(dir, "$connectionId.json")

    private fun write(connectionId: String, json: JSONObject) {
        file(connectionId).writeText(json.toString())
    }

    /** A valid version 1 document, for a test to spoil one field of. */
    private fun base() =
        JSONObject()
            .put("version", 1)
            .put("connectionId", CONNECTION)
            .put("updatedAt", NOW.toString())

    private companion object {
        const val CONNECTION = "6a5f0a0e-2f2f-4a07-9a1f-4f0f2b3f9a11"
        const val OTHER = "0f1e2d3c-4b5a-4c7d-8e9f-0a1b2c3d4e5f"
        const val RECIPIENT = "7LCE7pWnYuYKtGMWt2Q4aXKQrqTmGf4c1Kt1PTmAGwSt"
        const val MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        const val SYSTEM = "11111111111111111111111111111111"
        val SOL = PolicyAsset.sol(Network.NETWORK_MAINNET)
        val USDC = PolicyAsset.token(Network.NETWORK_MAINNET, MINT)
        val NOW: Instant = Instant.parse("2026-09-12T10:00:00Z")
    }
}
