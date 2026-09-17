package io.github.brrenat.seekervault.connections.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.connections.CheckOutcome
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.plugins.PluginEnvironment
import io.github.brrenat.seekervault.plugins.PluginId
import io.github.brrenat.seekervault.servers.ConnectionMode
import io.github.brrenat.seekervault.servers.ManifestProblem
import io.github.brrenat.seekervault.servers.PluginRequirement
import io.github.brrenat.seekervault.servers.SERVER_PROTOCOL
import io.github.brrenat.seekervault.servers.ServerManifest
import io.github.brrenat.seekervault.servers.ServerRecord
import io.github.brrenat.seekervault.servers.ServerReference
import io.github.brrenat.seekervault.servers.channelFor
import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val dir by lazy { File(folder.root, "files/connections") }
    private val store by lazy { ConnectionStore(dir) }
    private val a =
        Connection(
            id = "0b8e2b1c-3f4d-4e5a-9b6c-7d8e9f0a1b2c",
            label = "Home Mac ✓",
            serverUrl = "https://mac.tailnet.ts.net",
            serverId = "9fda5035-f3b4-4ec3-a68a-5e6caa02397a",
            deviceName = "Seeker",
            pairedAt = Instant.parse("2026-09-11T12:00:00.123Z"),
        )
    private val b =
        a.copy(
            id = "5d3c8f0e-2b7a-4c1d-9e6f-0a1b2c3d4e5f",
            label = "VPS",
            serverUrl = "https://vps.example.com",
            pairedAt = Instant.parse("2026-09-11T13:00:00Z"),
        )

    @Test
    fun keepsEveryFieldAcrossARestart() {
        val full =
            a.copy(
                revokedAt = Instant.parse("2026-09-12T08:00:00Z"),
                lastCheck =
                    Connection.Check(
                        Instant.parse("2026-09-11T12:05:00Z"),
                        CheckOutcome.Ok,
                        pending = 100,
                        morePending = true,
                    ),
            )
        store.put(full)
        store.put(b.copy(lastCheck = Connection.Check(b.pairedAt, CheckOutcome.Unreachable)))
        val reopened = ConnectionStore(dir)
        assertEquals(full, reopened.get(a.id))
        assertEquals(CheckOutcome.Unreachable, reopened.get(b.id)?.lastCheck?.outcome)
        assertNull(reopened.get(b.id)?.lastCheck?.pending)
    }

    @Test
    fun listsOldestFirstAndRemovesOneAtATime() {
        store.put(b)
        store.put(a)
        assertEquals(listOf(a.id, b.id), store.list().map { it.id })
        store.delete(a.id)
        assertEquals(listOf(b), store.list())
        store.put(b.copy(label = "Renamed"))
        assertEquals(listOf("Renamed"), store.list().map { it.label })
    }

    @Test
    fun skipsDamagedFilesAndFilesUnderAnotherName() {
        store.put(a)
        File(dir, "${b.id}.json").writeText("{not json")
        File(dir, "notes.json").writeText("{}")
        // a's metadata under another connection's name must not become that connection.
        val c = "7c6b5a49-3827-4615-a0b9-c8d7e6f5a4b3"
        File(dir, "$a.id.json".replace("$a.id", c)).writeText(File(dir, "${a.id}.json").readText())
        assertEquals(listOf(a.id), store.list().map { it.id })
        assertNull(store.get(c))
    }

    @Test
    fun namesFilesOnlyByConnectionIds() {
        assertThrows(IllegalArgumentException::class.java) {
            store.put(a.copy(id = "../../shared_prefs/x"))
        }
        assertNull(store.get("../../shared_prefs/x"))
    }

    @Test
    fun readsAConnectionPairedBeforeManifestsExistedAsWhatItIs() {
        // A version 1 file: a direct connection whose server has not been asked for a manifest.
        // The owner paired it and nothing about it changed, so it keeps working exactly as it did,
        // and the next refresh finds out whether its server publishes one (SEE-88).
        dir.mkdirs()
        File(dir, "${a.id}.json")
            .writeText(
                "{\"version\":1,\"id\":\"${a.id}\",\"label\":\"${a.label}\"," +
                    "\"serverUrl\":\"${a.serverUrl}\",\"serverId\":\"${a.serverId}\"," +
                    "\"deviceName\":\"Seeker\",\"pairedAt\":\"2026-09-11T12:00:00.123Z\"}"
            )

        val restored = checkNotNull(store.get(a.id))

        assertEquals(a, restored)
        assertEquals(ConnectionMode.Direct, restored.mode)
        assertEquals(ServerRecord.Unknown, restored.server)
        assertTrue(restored.usable)
    }

    @Test
    fun keepsAValidatedManifestAndTheModeItSelects() {
        val manifest =
            ServerManifest(
                serverId = a.serverId,
                protocolVersion = SERVER_PROTOCOL,
                settingsRevision = 7,
                mode = ConnectionMode.Direct,
                reference = ServerReference.Direct(a.serverUrl),
                required = listOf(PluginRequirement(PluginId("jupiter.swap"), 1..2)),
                environments = setOf(PluginEnvironment.Production, PluginEnvironment.Sandbox),
                name = "Home server",
            )
        val third = "7c6b5a49-3827-4615-a0b9-c8d7e6f5a4b3"
        store.put(a.copy(server = ServerRecord.Known(manifest)))
        store.put(b.copy(server = ServerRecord.Legacy))
        store.put(a.copy(id = third, server = ServerRecord.Refused(ManifestProblem.ForeignChannel)))

        val reopened = ConnectionStore(dir)

        assertEquals(ServerRecord.Known(manifest), reopened.get(a.id)?.server)
        assertEquals(ServerRecord.Legacy, reopened.get(b.id)?.server)
        assertEquals(
            ServerRecord.Refused(ManifestProblem.ForeignChannel),
            reopened.get(third)?.server,
        )
    }

    @Test
    fun keepsAFeedWithItsGatewayAndChannelAndNoCredential() {
        val feed =
            a.copy(
                serverUrl = GATEWAY,
                deviceName = "",
                hasCredential = false,
                mode = ConnectionMode.GatewayFeed,
                server = ServerRecord.Known(feedManifest()),
            )
        store.put(feed)

        val restored = checkNotNull(ConnectionStore(dir).get(a.id))

        assertEquals(feed, restored)
        // A feed is not a server this phone calls, so it is not "usable" in the sense that every
        // sidecar path means by that word (SEE-88).
        assertFalse(restored.usable)
    }

    @Test
    fun dropsAManifestItCanNoLongerReadWithoutLosingAPairedConnection() {
        // A direct connection is the owner's pairing; a cached manifest is only what its server
        // last said. One this phone can't read any more goes back to unasked, and the connection
        // stays exactly as it was.
        store.put(a.copy(server = ServerRecord.Legacy))
        val file = File(dir, "${a.id}.json")
        file.writeText(file.readText().replace("\"legacy\"", "\"something else\""))

        assertEquals(ServerRecord.Unknown, store.get(a.id)?.server)
        assertTrue(checkNotNull(store.get(a.id)).usable)

        // A feed has nothing left without its manifest — no gateway, no channel, no requirements —
        // and it must never quietly become a direct connection, so the whole record goes.
        store.put(
            b.copy(
                serverUrl = GATEWAY,
                hasCredential = false,
                mode = ConnectionMode.GatewayFeed,
                server = ServerRecord.Known(feedManifest()),
            )
        )
        val feedFile = File(dir, "${b.id}.json")
        feedFile.writeText(feedFile.readText().replace("\"channel\"", "\"chanel\""))

        assertNull(store.get(b.id))
    }

    private companion object {
        /** A feed's manifest, as the phone validated one through the gateway (SEE-88). */
        fun feedManifest() =
            ServerManifest(
                serverId = SERVER_B,
                protocolVersion = SERVER_PROTOCOL,
                settingsRevision = 1,
                mode = ConnectionMode.GatewayFeed,
                reference = ServerReference.Feed(GATEWAY, channelFor(SERVER_B)),
                required = listOf(PluginRequirement(PluginId("jupiter.prediction"), 1..1)),
                environments = setOf(PluginEnvironment.Production),
            )

        const val GATEWAY = "https://gateway.example.com"
        const val SERVER_B = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"
    }
}
