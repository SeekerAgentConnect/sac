package io.github.brrenat.seekervault.access

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.wallet.decodeBase58
import io.github.brrenat.seekervault.wallet.verifiesSignature
import java.time.Instant
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The texts a restricted feed's onboarding signs, against the fixture the publisher pins too
 * (`fixtures/restricted-feeds/challenge.json`, SEE-156).
 *
 * The point of a shared fixture is that neither side can drift alone. `publisher-support` builds
 * these bytes in Go and checks them against the same file (`access/access_test.go`); this checks
 * the Kotlin. A change to either builder that the other does not make turns one of the two red,
 * which is the only warning available before a phone signs something a publisher cannot verify.
 *
 * The wallet signature in the fixture verifies here as well, so the file pins the whole statement
 * and not only its spelling: these are the exact bytes an owner's wallet is asked for.
 */
@RunWith(AndroidJUnit4::class)
class AccessProofTest {
    private val fixture =
        JSONObject(
            checkNotNull(AccessProofTest::class.java.getResourceAsStream(FIXTURE)) {
                    "fixtures/restricted-feeds/challenge.json is missing"
                }
                .use { it.readBytes().decodeToString() }
        )

    private val challenge =
        FeedAccessProof.Challenge(
            authOrigin = fixture.getString("auth_origin"),
            channel = fixture.getString("channel"),
            wallet = fixture.getString("wallet"),
            installation = fixture.getString("installation"),
            attempt = fixture.getString("attempt"),
            nonce = fixture.getString("nonce"),
            issuedAt = Instant.parse(fixture.getString("issued_at")),
            expiresAt = Instant.parse(fixture.getString("expires_at")),
        )

    @Test
    fun theChallengeIsTheBytesBothSidesPinned() {
        assertEquals(
            fixture.getString("message"),
            String(FeedAccessProof.message(challenge), Charsets.US_ASCII),
        )
    }

    @Test
    fun theChallengeSaysItIsNotATransaction() {
        val text = String(FeedAccessProof.message(challenge), Charsets.US_ASCII)
        assertTrue(text.contains("This is not a transaction."))
        assertTrue(text.contains("moves no funds and approves nothing"))
        // Everything a signature is bound to is in the words the owner reads, not only in fields
        // the publisher can compare afterwards.
        assertTrue(text.contains(challenge.authOrigin))
        assertTrue(text.contains(challenge.wallet))
        assertTrue(text.contains(challenge.channel))
        assertTrue(text.contains(challenge.installation))
        assertTrue(text.contains(challenge.attempt))
        assertTrue(text.contains(challenge.nonce))
    }

    @Test
    fun theWalletSignatureInTheFixtureVerifiesOverThoseBytes() {
        val key = checkNotNull(decodeBase58(fixture.getString("wallet")))
        val signature = Base64.getDecoder().decode(fixture.getString("wallet_signature"))
        assertTrue(verifiesSignature(key, FeedAccessProof.message(challenge), signature))
        // And over nothing else: a single flipped field is a different statement.
        assertFalse(
            verifiesSignature(
                key,
                FeedAccessProof.message(challenge.copy(installation = "00000000000000000000")),
                signature,
            )
        )
    }

    @Test
    fun changingAnyFieldChangesTheBytes() {
        val original = FeedAccessProof.message(challenge)
        val changed =
            listOf(
                challenge.copy(authOrigin = "https://elsewhere.example.com"),
                challenge.copy(channel = "server/00000000-0000-4000-8000-000000000000"),
                challenge.copy(wallet = "3YKUMU99pedShDEe76HuSAHo3dt9CXjBwjN8w8NUo9Wh"),
                challenge.copy(installation = "00000000000000000000"),
                challenge.copy(attempt = "00000000-0000-4000-8000-000000000000"),
                challenge.copy(nonce = "AAAAAAAAAAAAAAAAAAAAAA"),
                challenge.copy(issuedAt = challenge.issuedAt.plusSeconds(1)),
                challenge.copy(expiresAt = challenge.expiresAt.plusSeconds(1)),
            )
        for (one in changed) {
            assertFalse(FeedAccessProof.message(one).contentEquals(original))
        }
    }

    @Test
    fun theStatusAndRedeemStatementsArePinnedToo() {
        assertEquals(
            fixture.getString("status_statement"),
            String(
                FeedAccessProof.statusStatement(
                    fixture.getString("status_request_id"),
                    fixture.getLong("status_at"),
                ),
                Charsets.US_ASCII,
            ),
        )
        assertEquals(
            fixture.getString("redeem_statement"),
            String(
                FeedAccessProof.redeemStatement(
                    fixture.getString("channel"),
                    fixture.getString("redeem_invitation"),
                    fixture.getLong("redeem_at"),
                ),
                Charsets.US_ASCII,
            ),
        )
    }

    @Test
    fun aStatementIsBoundToItsMomentAndItsSubject() {
        val at = fixture.getLong("status_at")
        val id = fixture.getString("status_request_id")
        assertFalse(
            FeedAccessProof.statusStatement(id, at)
                .contentEquals(FeedAccessProof.statusStatement(id, at + 1))
        )
        assertFalse(
            FeedAccessProof.statusStatement(id, at)
                .contentEquals(FeedAccessProof.statusStatement("$id ", at))
        )
        // A status statement is never a redeem statement, whatever the fields: a signature taken
        // from one step cannot be replayed at the other.
        assertFalse(
            FeedAccessProof.statusStatement(id, at)
                .contentEquals(FeedAccessProof.redeemStatement(id, id, at))
        )
    }

    @Test
    fun anInstallationIsTheFingerprintOfTheDeviceKeyAndNothingElse() {
        val keys = SoftwareDeviceKeys()
        val one = FeedAccessProof.installation(keys.publicKey("a"))
        val other = FeedAccessProof.installation(keys.publicKey("b"))
        assertEquals(20, one.length)
        assertTrue(one.all { it in "0123456789abcdef" })
        assertEquals(one, FeedAccessProof.installation(keys.publicKey("a")))
        // One key per feed is the point: two feeds' keys cannot be compared into one device.
        assertFalse(one == other)
    }

    private companion object {
        const val FIXTURE = "/restricted-feeds/challenge.json"
    }
}
