package io.github.brrenat.seekervault.live

import io.github.brrenat.seekervault.live.v1.LiveCommand
import java.time.Instant

/**
 * Whether this command has timed out at [now]. A command is live strictly before `expires_at` and
 * timed out from that instant on; one without `expires_at` is malformed and treated as timed out
 * (docs/protocol.md). Mirrors `isExpired` in the sidecar.
 */
fun LiveCommand.isExpiredAt(now: Instant): Boolean =
    !hasExpiresAt() ||
        !now.isBefore(Instant.ofEpochSecond(expiresAt.seconds, expiresAt.nanos.toLong()))
