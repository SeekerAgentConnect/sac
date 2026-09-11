package io.github.brrenat.seekervault.live

import com.google.protobuf.timestamp
import io.github.brrenat.seekervault.live.v1.LiveCommand
import io.github.brrenat.seekervault.live.v1.liveCommand
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same boundaries as the sidecar's `isExpired` tests. */
class LiveCommandDeadlineTest {
    private val noon = Instant.parse("2026-09-11T12:00:00Z")

    @Test
    fun deadlineInstantIsExpired() {
        val deadline = noon.plusSeconds(60)
        val command = liveCommand { expiresAt = timestamp { seconds = deadline.epochSecond } }
        assertFalse(command.isExpiredAt(deadline.minusMillis(1)))
        assertTrue(command.isExpiredAt(deadline))
        assertTrue(command.isExpiredAt(deadline.plusMillis(1)))
    }

    @Test
    fun subMillisecondDeadlineFromTheSharedFixture() {
        // 2026-09-11T12:00:00.999999999Z
        val command =
            LiveCommand.parseFrom(LiveProtocolFixtures.bytes("LiveCommand/deadline_nanos"))
        assertFalse(command.isExpiredAt(noon.minusMillis(1)))
        assertFalse(command.isExpiredAt(noon.plusMillis(999)))
        assertFalse(command.isExpiredAt(noon.plusNanos(999_999_998)))
        assertTrue(command.isExpiredAt(noon.plusNanos(999_999_999)))
        assertTrue(command.isExpiredAt(noon.plusSeconds(1)))
    }

    @Test
    fun commandWithoutDeadlineIsExpired() {
        assertTrue(LiveCommand.getDefaultInstance().isExpiredAt(noon))
    }
}
