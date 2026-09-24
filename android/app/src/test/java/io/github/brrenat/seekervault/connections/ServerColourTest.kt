package io.github.brrenat.seekervault.connections

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServerColourTest {
    @Test
    fun theNextColourIsTheFirstFreeEntryAndThenWrapsInOrder() {
        val held =
            ServerColour.entries.mapIndexed { index, colour ->
                connection(index.toString(), colour)
            }
        assertEquals(ServerColour.Tangerine, nextServerColour(held))
        val wrapped = held + connection("extra", ServerColour.Tangerine)
        assertEquals(ServerColour.Sky, nextServerColour(wrapped))
        assertEquals(
            ServerColour.Sky,
            nextServerColour(listOf(connection("one", ServerColour.Tangerine))),
        )
    }

    private fun connection(id: String, colour: ServerColour) =
        Connection(
            id = id,
            label = id,
            serverUrl = "https://$id.example",
            serverId = "9fda5035-f3b4-4ec3-a68a-5e6caa02397a",
            deviceName = "Seeker",
            pairedAt = Instant.parse("2026-09-11T12:00:00Z"),
            colour = colour,
        )
}
