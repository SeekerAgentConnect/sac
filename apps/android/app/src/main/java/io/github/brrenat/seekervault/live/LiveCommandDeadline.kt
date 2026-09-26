package io.github.brrenat.seekervault.live

import io.github.brrenat.seekervault.live.v1.LiveCommand
import java.time.Instant

/** The command's deadline, or null when `expires_at` is missing. */
fun LiveCommand.expiresAtInstant(): Instant? =
    if (hasExpiresAt()) Instant.ofEpochSecond(expiresAt.seconds, expiresAt.nanos.toLong()) else null

/**
 * Whether this command has timed out at [now]. A command is live strictly before `expires_at` and
 * timed out from that instant on; one without `expires_at` is malformed and treated as timed out
 * (docs/protocol.md). Mirrors `isExpired` in the sidecar.
 */
fun LiveCommand.isExpiredAt(now: Instant): Boolean =
    expiresAtInstant()?.let { !now.isBefore(it) } ?: true
