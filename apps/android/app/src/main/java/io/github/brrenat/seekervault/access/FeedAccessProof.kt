package io.github.brrenat.seekervault.access

import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * The texts a restricted feed's onboarding signs (SEE-156, docs/wiki/restricted-feeds.md).
 *
 * The phone builds every one of them itself, from fields, and compares the publisher's copy of the
 * challenge with its own before the wallet sees anything: a publisher cannot get the wallet to sign
 * text this app did not write. The publisher builds the same bytes from the same fields
 * (publisher-support/access/proof.go), and both sides pin one fixture
 * (fixtures/restricted-feeds/challenge.json).
 *
 * The wallet signs exactly one of these, once — the challenge, which says in its own words that it
 * is not a transaction and moves nothing. Every later step is signed by this app's device key.
 */
object FeedAccessProof {
    const val MESSAGE_VERSION = "1"

    /** A challenge, as fields. */
    data class Challenge(
        val authOrigin: String,
        val channel: String,
        val wallet: String,
        val installation: String,
        val attempt: String,
        val nonce: String,
        val issuedAt: Instant,
        val expiresAt: Instant,
    )

    /** The exact bytes the wallet signs. ASCII by construction. */
    fun message(challenge: Challenge): ByteArray = buildString {
        append("Seeker Agent Connect feed access v$MESSAGE_VERSION\n")
        append("\n")
        append(
            "${challenge.authOrigin} asks you to prove that you control this wallet, so it " +
                "can decide whether this device may read its restricted feed.\n"
        )
        append("\n")
        append("This is not a transaction. Signing it moves no funds and approves nothing.\n")
        append("\n")
        append("Wallet: ${challenge.wallet}\n")
        append("Feed: ${challenge.channel}\n")
        append("Device key: ${challenge.installation}\n")
        append("Attempt: ${challenge.attempt}\n")
        append("Nonce: ${challenge.nonce}\n")
        append("Issued: ${stamp(challenge.issuedAt)}\n")
        append("Expires: ${stamp(challenge.expiresAt)}")
    }
        .toByteArray(Charsets.US_ASCII)

    /** What the device key signs to ask for its decision. */
    fun statusStatement(requestId: String, atMillis: Long): ByteArray =
        "seekervault-feed-access-status:v1\n$requestId\n$atMillis".toByteArray(Charsets.US_ASCII)

    /** What the device key signs to redeem an invitation. */
    fun redeemStatement(channel: String, invitation: String, atMillis: Long): ByteArray =
        "seekervault-feed-access-redeem:v1\n$channel\n$invitation\n$atMillis"
            .toByteArray(Charsets.US_ASCII)

    /**
     * A device key's fingerprint, the installation a wallet's signature binds: the first 10 bytes
     * of the SHA-256 of its X.509 encoding, in hex.
     */
    fun installation(publicKey: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(publicKey).take(10).joinToString("") {
            "%02x".format(it)
        }

    /** RFC 3339 to the second, which is how both sides write a challenge's instants. */
    fun stamp(at: Instant): String = DateTimeFormatter.ISO_INSTANT.format(at)
}
