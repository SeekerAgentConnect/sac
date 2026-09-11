package io.github.brrenat.seekervault.connections

import java.time.Instant

/**
 * This phone's connection to one sidecar (docs/security.md), keyed by the connection ID the sidecar
 * assigned at pairing. Its credential isn't here: it stays encrypted in the `CredentialVault`, and
 * only the repository reads it.
 */
data class Connection(
    val id: String,
    /** The owner's name for the connection. It stays on the phone. */
    val label: String,
    /** Where this connection's credential goes, and nowhere else. It never changes. */
    val serverUrl: String,
    val serverId: String,
    /** The name this phone gave the sidecar when it paired. */
    val deviceName: String,
    val pairedAt: Instant,
    /** When the sidecar stopped accepting the credential. The owner must pair again. */
    val revokedAt: Instant? = null,
    val lastCheck: Check? = null,
    /** Whether this phone still holds the credential. Read from the vault, not stored here. */
    val hasCredential: Boolean = true,
) {
    /** The last time the phone asked the sidecar for the connection's pending requests. */
    data class Check(
        val at: Instant,
        val outcome: CheckOutcome,
        val pending: Int? = null,
        /** More requests are pending than the one page the phone fetched. */
        val morePending: Boolean = false,
    )

    /** Whether the phone can still call the sidecar for this connection. */
    val usable: Boolean
        get() = revokedAt == null && hasCredential
}

enum class CheckOutcome {
    Ok,
    Unreachable,
    CertificateRejected,
    CleartextBlocked,
    Failed,
}

/** Why a new name for a connection isn't accepted. */
enum class LabelProblem {
    Blank,
    TooLong,
}

const val MAX_LABEL_LENGTH = 64

fun labelProblem(label: String): LabelProblem? {
    val trimmed = label.trim()
    return when {
        trimmed.isEmpty() -> LabelProblem.Blank
        trimmed.codePointCount(0, trimmed.length) > MAX_LABEL_LENGTH -> LabelProblem.TooLong
        else -> null
    }
}
