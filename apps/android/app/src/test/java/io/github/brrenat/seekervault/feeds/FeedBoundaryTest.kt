package io.github.brrenat.seekervault.feeds

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The broker stays behind one file, and the schema it is spoken with stays the one that was
 * vendored (SEE-91).
 *
 * Two different guards, both cheap and both about the same thing: a broker is an implementation
 * detail of listening, and a wire contract we do not own is a thing to pin rather than to track.
 */
class FeedBoundaryTest {
    private val repoRoot =
        File(
            checkNotNull(System.getProperty("seekervault.repoRoot")) {
                "run this test through Gradle"
            }
        )

    private val main = File(repoRoot, "apps/android/app/src/main")

    private val sources =
        File(main, "java")
            .walk()
            .filter { it.extension == "kt" }
            .toList()
            .also {
                check(it.size > 40) { "the walk found ${it.size} sources" }
            }

    /**
     * One file imports the vendored schema, and it is the adapter.
     *
     * The rest of the app deals in `FeedStream`'s own types, so which broker is behind a feed is a
     * decision that can be revisited by rewriting one file — and, more to the point, a decision
     * that cannot leak into the repositories, the lifecycle or the UI without this test saying so.
     */
    @Test
    fun onlyTheAdapterKnowsWhichBrokerIsBehindAFeed() {
        val vendored = Regex("""centrifugal\.centrifugo""")
        assertEquals(
            listOf("CentrifugoFeedStream.kt"),
            sources.filter { vendored.containsMatchIn(it.readText()) }.map { it.name }.sorted(),
        )
    }

    /**
     * The session and the seam it talks through name no broker at all — not in a type, not in a
     * string, not in a comment. A name in a comment is how the next person learns that the
     * abstraction is not one.
     */
    @Test
    fun theSeamAndTheSessionNameNoBroker() {
        val named = Regex("""(?i)\b(centrifugo|centrifuge|redis)\b""")
        val offenders =
            sources
                .filter { it.startsWith(File(main, "java/io/github/brrenat/seekervault/feeds")) }
                .filterNot { it.name == "CentrifugoFeedStream.kt" }
                .filter { named.containsMatchIn(it.readText()) }
                .map { it.name }
        assertEquals(emptyList<String>(), offenders)
    }

    /**
     * The field numbers the adapter reads, pinned against the vendored schema.
     *
     * An upgrade that moved one of these would be a wire break, and a silent one: protobuf would
     * hand the adapter a default value rather than an error, so a listener would quietly stop
     * recovering or stop noticing a disconnect. This is what makes an upgrade of
     * `packages/protocol/third_party/centrifugo` say something (see its README).
     */
    @Test
    fun theVendoredSchemaStillNumbersTheFieldsTheAdapterReads() {
        val schema =
            File(
                    repoRoot,
                    "packages/protocol/third_party/centrifugo/centrifugal/centrifugo/unistream/unistream.proto",
                )
                .readText()
        // Per message, because these names repeat across them: three messages have an `epoch` and
        // four have an `offset`, at different numbers.
        val numbered =
            Regex("""(?m)^\s*(?:repeated\s+|map<[^>]+>\s+)?[\w.<>, ]+?\s+(\w+)\s*=\s*(\d+);""")
        val messages =
            Regex("""(?s)message\s+(\w+)\s*\{(.*?)\n\}""").findAll(schema).associate { match ->
                match.groupValues[1] to
                    numbered.findAll(match.groupValues[2]).associate {
                        it.groupValues[1] to it.groupValues[2].toInt()
                    }
            }

        fun pinned(message: String, vararg fields: Pair<String, Int>) {
            assertEquals(
                message,
                fields.toMap(),
                messages[message]?.filterKeys { key ->
                    fields.any { it.first == key }
                },
            )
        }

        // What goes up: the ticket is the grant, and a cursor per channel is how recovery is asked
        // for. `subs` is not a subscribe request — the channels come from the ticket — which is why
        // the only fields the adapter fills in it are the position.
        pinned("ConnectRequest", "token" to 1, "subs" to 3, "name" to 4, "version" to 5)
        pinned("SubscribeRequest", "recover" to 3, "epoch" to 6, "offset" to 7)
        // What comes down. A push the adapter does not know becomes "alive", so a *new* number
        // appearing here is harmless; one of these moving would make a disconnect look like
        // nothing at all.
        pinned(
            "Push",
            "channel" to 2,
            "pub" to 4,
            "unsubscribe" to 7,
            "connect" to 10,
            "disconnect" to 11,
        )
        pinned("Publication", "data" to 4, "offset" to 6)
        pinned("Connect", "subs" to 4)
        // The continuity answer, which is the one thing on this transport that cannot be asked for
        // twice.
        pinned(
            "SubscribeResult",
            "recoverable" to 3,
            "epoch" to 6,
            "publications" to 7,
            "recovered" to 8,
            "offset" to 9,
            "was_recovering" to 12,
        )
        pinned("Unsubscribe", "code" to 2, "reason" to 3)
        pinned("Disconnect", "code" to 1, "reason" to 2)
        // And the schema is still the release that was vendored, digest and all: `pnpm generate`
        // refuses to run otherwise (packages/protocol/third_party/centrifugo/README.md).
        assertTrue(
            "SHA256SUMS names the schema",
            "unistream.proto" in
                File(repoRoot, "packages/protocol/third_party/centrifugo/SHA256SUMS").readText(),
        )
    }
}
