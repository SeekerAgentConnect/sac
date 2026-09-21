package io.github.brrenat.seekervault.push

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The relayed invalidation's three sides, pinned against each other (SEE-144).
 *
 * A server that holds no Firebase credential is woken by the gateway instead, and the message the
 * gateway builds has to be the one the sidecar already sends — because this phone matches the
 * payload whole and cannot tell the two senders apart. Get the constants out of step and nothing
 * fails: the gateway sends, Firebase delivers, and this phone ignores the message. Silence is the
 * worst failure to debug, so it is this test's job to make it loud.
 *
 * It reads both other sources rather than copies of them, for the same reason
 * [FeedHintContractTest] does: a pin that quoted a value twice would be a third place to keep in
 * step.
 */
class RelayContractTest {
    private val repoRoot =
        File(
            checkNotNull(System.getProperty("seekervault.repoRoot")) {
                "run this test through Gradle"
            }
        )

    private val direct = File(repoRoot, "feed-gateway/internal/relay/direct.go").readText()
    private val sidecar = File(repoRoot, "server-sdk/src/push/invalidation.ts").readText()
    private val contract = File(repoRoot, "feed-gateway/internal/pushrelay/wire.go").readText()

    @Test
    fun theGatewayRelaysExactlyTheInvalidationTheSidecarSends() {
        assertTrue("the gateway's source was read", direct.length > 1_000)
        assertTrue("the sidecar's source was read", sidecar.length > 1_000)

        val gateway =
            Regex("""(?m)^\s*Request(Kind|Version|CollapseKey)\s*=\s*"([^"]*)"""")
                .findAll(direct)
                .associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(
            "the gateway declares the whole message",
            setOf("Kind", "Version", "CollapseKey"),
            gateway.keys,
        )

        // What the phone accepts, matched whole: a message with anything else in it is ignored on
        // arrival rather than partly trusted.
        assertEquals(
            mapOf(
                "kind" to gateway.getValue("Kind"),
                "version" to gateway.getValue("Version"),
            ),
            REQUEST_INVALIDATION_DATA,
        )

        // And the sidecar's own, so the two senders cannot drift apart from each other either.
        val fromSidecar = Regex("""kind:\s*"([^"]*)",\s*\n\s*version:\s*"([^"]*)",""").find(sidecar)
        checkNotNull(fromSidecar) { "the sidecar's payload constants moved" }
        assertEquals(
            "the gateway and the sidecar send the same kind",
            fromSidecar.groupValues[1],
            gateway.getValue("Kind"),
        )
        assertEquals(
            "the gateway and the sidecar send the same version",
            fromSidecar.groupValues[2],
            gateway.getValue("Version"),
        )
        val collapse = Regex("""FCM_INVALIDATION_COLLAPSE_KEY\s*=\s*"([^"]*)"""").find(sidecar)
        checkNotNull(collapse) { "the sidecar's collapse key moved" }
        assertEquals(
            "the two senders collapse under the same key, so one phone is woken once",
            collapse.groupValues[1],
            gateway.getValue("CollapseKey"),
        )
    }

    @Test
    fun theGatewayAddressesAnInstallationRatherThanSomethingElseWithTheSameCharacters() {
        // The phone's registration is a Firebase installation, which is what the sidecar sends to.
        // A gateway that put the same opaque string in `token` would be addressing a different
        // kind of thing with the same characters — which is exactly the confusion this stage says
        // not to make.
        assertTrue("""the gateway addresses `fid`""", """`json:"fid"`""" in direct)
        // Struct tags rather than words, so the prose above them — which says at length that none
        // of these exists — cannot make this test pass or fail by being reworded.
        for (wrong in
            listOf(
                """`json:"token"`""",
                """`json:"topic"`""",
                """`json:"condition"`""",
                """`json:"notification"`""",
            )) {
            assertFalse("the gateway's device message carries $wrong", wrong in direct)
        }
    }

    @Test
    fun thePhoneAndTheRelayAgreeOnWhereTheRelayLives() {
        assertTrue("the contract's source was read", contract.length > 1_000)
        val prefix = Regex("""(?m)^const Prefix = "([^"]*)"""").find(contract)
        checkNotNull(prefix) { "the relay's route prefix moved" }
        assertEquals(
            "the phone calls the routes the gateway serves",
            prefix.groupValues[1],
            RELAY_PREFIX,
        )
        val version = Regex("""(?m)^const Version = "([^"]*)"""").find(contract)
        checkNotNull(version) { "the relay's contract version moved" }
        assertEquals(
            "the phone speaks the version the gateway implements",
            version.groupValues[1],
            RELAY_VERSION,
        )
    }
}
