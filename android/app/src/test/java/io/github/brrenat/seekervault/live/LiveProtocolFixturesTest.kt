package io.github.brrenat.seekervault.live

import com.google.protobuf.MessageLite
import com.google.protobuf.timestamp
import io.github.brrenat.seekervault.live.v1.AcknowledgeCommandRequest
import io.github.brrenat.seekervault.live.v1.AcknowledgementResult
import io.github.brrenat.seekervault.live.v1.CommandAcknowledgement
import io.github.brrenat.seekervault.live.v1.LiveCommand
import io.github.brrenat.seekervault.live.v1.WatchCommandsResponse
import io.github.brrenat.seekervault.live.v1.acknowledgeCommandRequest
import io.github.brrenat.seekervault.live.v1.commandAcknowledgement
import io.github.brrenat.seekervault.live.v1.liveCommand
import io.github.brrenat.seekervault.live.v1.watchCommandsResponse
import java.time.Instant
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Kotlin half of the cross-runtime fixture check; see LiveProtocolFixtures. */
class LiveProtocolFixturesTest {
    @Test fun liveCommandAscii() = check("LiveCommand/ascii", LiveCommand::parseFrom, helloSeeker)

    @Test
    fun liveCommandUnicode() =
        check(
            "LiveCommand/unicode",
            LiveCommand::parseFrom,
            liveCommand {
                id = "0b7e2a44-9c31-4f8d-8a55-6d2e1f3c9b10"
                text = "Привет 👋🏽 你好 مرحبا é 👩‍💻\nSecond line"
                expiresAt = timestamp {
                    seconds = NOON
                    nanos = 123_000_000
                }
            },
        )

    @Test
    fun liveCommandMaxText() =
        check(
            "LiveCommand/max_text",
            LiveCommand::parseFrom,
            liveCommand {
                id = "9d8c7b6a-5f4e-4d3c-8b2a-1f0e9d8c7b6a"
                text = "€".repeat(1365) + "!"
                expiresAt = timestamp { seconds = NOON }
            },
        )

    @Test
    fun liveCommandDeadlineNanos() =
        check(
            "LiveCommand/deadline_nanos",
            LiveCommand::parseFrom,
            liveCommand {
                id = "2f4e6a8c-1b3d-4f5a-9c7e-0a2b4c6d8e1f"
                text = "Deadline boundary"
                expiresAt = timestamp {
                    seconds = NOON
                    nanos = 999_999_999
                }
            },
        )

    @Test
    fun liveCommandMaxTimestamp() =
        check(
            "LiveCommand/max_timestamp",
            LiveCommand::parseFrom,
            liveCommand {
                id = "6a1d3c5e-7f9b-4b2d-8e4f-1a3c5e7f9b2d"
                text = "Latest representable deadline"
                expiresAt = timestamp {
                    seconds = Instant.parse("9999-12-31T23:59:59Z").epochSecond
                    nanos = 999_999_999
                }
            },
        )

    @Test
    fun liveCommandEmpty() =
        check("LiveCommand/empty", LiveCommand::parseFrom, LiveCommand.getDefaultInstance())

    @Test
    fun commandAcknowledgementOk() =
        check("CommandAcknowledgement/ok", CommandAcknowledgement::parseFrom, acknowledgedOk)

    @Test
    fun watchCommandsResponseReady() {
        check(
            "WatchCommandsResponse/ready",
            WatchCommandsResponse::parseFrom,
            watchCommandsResponse { ready = WatchCommandsResponse.Ready.getDefaultInstance() },
        )
        val parsed =
            WatchCommandsResponse.parseFrom(
                LiveProtocolFixtures.bytes("WatchCommandsResponse/ready")
            )
        assertEquals(WatchCommandsResponse.EventCase.READY, parsed.eventCase)
    }

    @Test
    fun watchCommandsResponseCommand() =
        check(
            "WatchCommandsResponse/command",
            WatchCommandsResponse::parseFrom,
            watchCommandsResponse { command = helloSeeker },
        )

    @Test
    fun acknowledgeCommandRequestOk() =
        check(
            "AcknowledgeCommandRequest/ok",
            AcknowledgeCommandRequest::parseFrom,
            acknowledgeCommandRequest { acknowledgement = acknowledgedOk },
        )

    private fun <T : MessageLite> check(name: String, parse: (ByteArray) -> T, expected: T) {
        val bytes = LiveProtocolFixtures.bytes(name)
        assertEquals(expected, parse(bytes))
        assertArrayEquals(bytes, expected.toByteArray())
    }

    private companion object {
        val NOON = Instant.parse("2026-09-11T12:00:00Z").epochSecond

        val helloSeeker = liveCommand {
            id = "4c1f3f8e-5a2b-4d6e-9f10-2b3c4d5e6f70"
            text = "Hello Seeker"
            expiresAt = timestamp { seconds = NOON }
        }

        val acknowledgedOk = commandAcknowledgement {
            id = "4c1f3f8e-5a2b-4d6e-9f10-2b3c4d5e6f70"
            result = AcknowledgementResult.ACKNOWLEDGEMENT_RESULT_OK
        }
    }
}
