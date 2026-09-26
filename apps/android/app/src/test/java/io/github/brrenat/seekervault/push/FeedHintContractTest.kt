package io.github.brrenat.seekervault.push

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hint's two sides, pinned against each other (SEE-92).
 *
 * A feed hint is not in the protocol: it carries no document, so there is no `.proto` to generate
 * it from and no cross-runtime fixture to compare bytes with. What it has instead is two literals —
 * a kind and a version — written in two languages, and a phone that matches the payload whole. Get
 * them out of step and nothing fails: the relay sends, Firebase delivers, and this phone ignores
 * the message. Silence is the worst failure to debug, so it is this test's job to make it loud.
 *
 * It reads the relay's own Go source rather than a copy of it, for the same reason
 * `FeedBoundaryTest` reads the vendored schema: a pin that quoted the value twice would be a third
 * place to keep in step.
 */
class FeedHintContractTest {
    private val repoRoot =
        File(
            checkNotNull(System.getProperty("seekervault.repoRoot")) {
                "run this test through Gradle"
            }
        )

    private val relay = File(repoRoot, "feed-gateway/internal/relay/relay.go").readText()

    @Test
    fun thePhoneAndTheRelayAgreeOnWhatAHintIs() {
        assertTrue("the relay's source was read", relay.length > 1_000)
        val constants =
            Regex("""(?m)^\s*(Kind|Version)\s*=\s*"([^"]*)"""").findAll(relay).associate {
                it.groupValues[1] to it.groupValues[2]
            }
        assertEquals(
            "the relay declares both halves of the payload",
            setOf("Kind", "Version"),
            constants.keys,
        )
        assertEquals(
            mapOf("kind" to constants.getValue("Kind"), "version" to constants.getValue("Version")),
            FEED_INVALIDATION_DATA,
        )
    }

    /**
     * And they agree on how a topic is spelled. The gateway states the name, so the phone does not
     * derive one — but it does validate what it is given before handing it to Firebase, and a
     * prefix the relay changed would be a name this phone quietly refused.
     */
    @Test
    fun thePhoneAcceptsTheShapeOfTopicTheRelayProduces() {
        val shape =
            Regex("""return\s+"(feed\.)"\s*\+\s*r\.environment\s*\+\s*"(\.)"\s*\+\s*serverID""")
                .find(relay)
        assertTrue(
            "the relay still builds a topic out of its environment and a server ID",
            shape != null,
        )
        val prefix = checkNotNull(shape).groupValues[1]
        for (environment in listOf("production", "sandbox")) {
            assertTrue(isFeedTopic("$prefix$environment.3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"))
        }
    }

    /**
     * The collapse key is one for every feed, deliberately: a phone that was offline wakes once and
     * reads every feed it holds, so two hints waiting for it are one thing to do. This pins the
     * *reason* rather than the string — there is no second key to be out of step with — by failing
     * if the relay grows a second one.
     */
    @Test
    fun thereIsOneCollapseKeyForEveryFeed() {
        val keys = Regex("""CollapseKey\s*=\s*"([^"]*)"""").findAll(relay).map { it.groupValues[1] }
        assertEquals(1, keys.count())
    }
}
