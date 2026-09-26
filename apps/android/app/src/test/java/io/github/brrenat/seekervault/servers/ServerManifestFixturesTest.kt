package io.github.brrenat.seekervault.servers

import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginId
import io.github.brrenat.seekervault.server.v1.ServerManifest as WireManifest
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Kotlin half of the manifest fixtures in
 * packages/protocol/proto/fixtures/seekervault/server/v1 (SEE-88).
 *
 * `buf convert` writes the bytes and the sidecar's manifest.test.ts checks them too, so both
 * runtimes agree about the document. What this side adds is what the phone *makes* of each one: a
 * manifest is a statement by the other side, and the same bytes have to be accepted or refused the
 * same way whichever build reads them.
 */
class ServerManifestFixturesTest {
    @Test
    fun aDirectServersManifest() {
        val manifest =
            valid(
                "direct",
                ManifestExpectation(
                    serverId = SIDECAR,
                    mode = ConnectionMode.Direct,
                    origin = "https://vault.example.com",
                ),
            )

        assertEquals(SIDECAR, manifest.serverId)
        assertEquals(SERVER_PROTOCOL, manifest.protocolVersion)
        assertEquals(3L, manifest.settingsRevision)
        assertEquals(ServerReference.Direct("https://vault.example.com"), manifest.reference)
        assertEquals(emptyList<PluginRequirement>(), manifest.required)
        assertEquals(setOf(PluginEnvironment.Production), manifest.environments)
        assertEquals("", manifest.name)
    }

    @Test
    fun aPublishersManifestWithItsRequirementsAndItsOwnName() {
        val manifest = valid("feed", feed)

        assertEquals(12L, manifest.settingsRevision)
        assertEquals(
            ServerReference.Feed("https://gateway.example.com", channelFor(PUBLISHER)),
            manifest.reference,
        )
        assertEquals(
            listOf(
                PluginRequirement(PluginId("jupiter.swap"), 1..1),
                PluginRequirement(PluginId("jupiter.prediction"), 1..2),
            ),
            manifest.required,
        )
        assertEquals(
            setOf(PluginEnvironment.Production, PluginEnvironment.Sandbox),
            manifest.environments,
        )
        // A name in another script, with an emoji: shown as it is, and never as this app's word
        // for the publisher.
        assertEquals("Копи-трейдинг 📈", manifest.name)
    }

    @Test
    fun aManifestClaimingAnotherPublishersChannel() {
        assertEquals(ManifestProblem.ForeignChannel, refused("foreign_channel", feed))
    }

    @Test
    fun aRevisionAtTheUnsignedMaximum() {
        // The protocol carries a uint64, and this runtime has no unsigned long: the largest
        // revision a server could publish arrives here as a negative number. It is refused rather
        // than read as older or newer than anything — a revision this phone can't order is no
        // revision at all — and the sidecar's own test pins the same bytes as exactly 2^64 - 1.
        assertEquals(
            ManifestProblem.NoRevision,
            refused(
                "max_revision",
                ManifestExpectation(
                    serverId = SIDECAR,
                    mode = ConnectionMode.Direct,
                    origin = "https://vault.example.com",
                ),
            ),
        )
    }

    @Test
    fun coversEveryFixture() {
        val dir = File(checkNotNull(javaClass.getResource("/$PACKAGE")) { "no fixtures" }.toURI())
        val names =
            dir.walk()
                .filter { it.extension == "json" }
                .map { it.nameWithoutExtension }
                .sorted()
                .toList()
        assertEquals(
            listOf("direct", "feed", "foreign_channel", "max_revision"),
            names,
        )
    }

    private val feed =
        ManifestExpectation(
            serverId = PUBLISHER,
            mode = ConnectionMode.GatewayFeed,
            origin = "https://gateway.example.com",
        )

    private fun message(name: String): WireManifest =
        WireManifest.parseFrom(
            checkNotNull(javaClass.getResourceAsStream("/$PACKAGE/$name.binpb")) {
                    "Missing fixture $name.binpb; run pnpm generate"
                }
                .use { it.readBytes() }
        )

    private fun valid(name: String, expect: ManifestExpectation): ServerManifest =
        (manifestFrom(message(name), expect) as ManifestResult.Valid).manifest

    private fun refused(name: String, expect: ManifestExpectation): ManifestProblem =
        (manifestFrom(message(name), expect) as ManifestResult.Invalid).problem

    private companion object {
        const val PACKAGE = "seekervault/server/v1/ServerManifest"
        const val SIDECAR = "9fda5035-f3b4-4ec3-a68a-5e6caa02397a"
        const val PUBLISHER = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
    }
}
