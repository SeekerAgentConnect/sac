package io.github.brrenat.seekervault.servers

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reading the reference a feed is added from (SEE-88).
 *
 * It is read by the pairing code's own rules — one version, an HTTPS URL unless the platform
 * permits cleartext to a loopback host, a lowercase-UUID server ID — because a difference between
 * the two parsers would be a difference nobody intended.
 */
class FeedReferenceTest {
    private val nothingCleartext: (String) -> Boolean = { false }
    private val loopbackCleartext: (String) -> Boolean = { it == "127.0.0.1" }

    private fun parse(
        text: String,
        cleartext: (String) -> Boolean = nothingCleartext,
        trusted: String = GATEWAY,
    ) = FeedReferences.parse(text, trusted, cleartext)

    private fun problem(
        text: String,
        cleartext: (String) -> Boolean = nothingCleartext,
        trusted: String = GATEWAY,
    ) = (parse(text, cleartext, trusted) as FeedReferenceResult.Invalid).problem

    @Test
    fun aReferenceNamesTheGatewayAndTheServerAndNothingElse() {
        val result = parse("seekervault://feed?v=1&gateway=$GATEWAY&server=$SERVER_B")

        assertEquals(
            FeedReferenceResult.Valid(FeedReference(GATEWAY, SERVER_B)),
            result,
        )
        // The channel is not in the reference: it is what the server owns, worked out from its ID,
        // so a reference cannot point at another publisher's audience.
        assertEquals(
            "server/$SERVER_B",
            (result as FeedReferenceResult.Valid).reference.channel,
        )
    }

    @Test
    fun aReferenceCarriesNoSecretSoThereIsNothingToRedact() {
        // Unlike a pairing code: holding this grants nothing, which is why it can be printed in a
        // README. A token here would be a secret in a public string.
        val reference = FeedReference(GATEWAY, SERVER_B)

        assertEquals(
            "FeedReference(gatewayUrl=$GATEWAY, serverId=$SERVER_B, restricted=false, invitation=null)",
            reference.toString(),
        )
    }

    @Test
    fun aPairingCodeIsNotAFeedReferenceAndTheOtherWayAround() {
        assertEquals(
            FeedReferenceProblem.NotSeekerVault,
            problem("seekervault://pair?v=1&url=$URL_A&server=$SERVER_B&token=${"a".repeat(43)}"),
        )
        assertEquals(FeedReferenceProblem.NotSeekerVault, problem("https://example.com/feed"))
        assertEquals(FeedReferenceProblem.NotAReference, problem("not a URI at all"))
    }

    @Test
    fun anotherVersionOfTheFormatIsRefusedRatherThanRead() {
        assertEquals(
            FeedReferenceProblem.OtherVersion,
            problem("seekervault://feed?v=2&gateway=$GATEWAY&server=$SERVER_B"),
        )
        assertEquals(
            FeedReferenceProblem.OtherVersion,
            problem("seekervault://feed?gateway=$GATEWAY&server=$SERVER_B"),
        )
    }

    @Test
    fun theGatewayIsAnOriginOverTlsAndNothingMore() {
        assertEquals(
            FeedReferenceProblem.InsecureGatewayUrl,
            problem("seekervault://feed?v=1&gateway=http://gateway.example.com&server=$SERVER_B"),
        )
        // A path, a query or a fragment would make one gateway several.
        assertEquals(
            FeedReferenceProblem.BadGatewayUrl,
            problem("seekervault://feed?v=1&gateway=$GATEWAY/feeds&server=$SERVER_B"),
        )
        assertEquals(
            FeedReferenceProblem.BadGatewayUrl,
            problem("seekervault://feed?v=1&gateway=&server=$SERVER_B"),
        )
    }

    @Test
    fun aDevelopmentGatewayOnLoopbackIsReachedOnlyWhereCleartextIsPermitted() {
        val text = "seekervault://feed?v=1&gateway=http%3A%2F%2F127.0.0.1%3A8080&server=$SERVER_B"

        assertEquals(
            FeedReferenceResult.Valid(FeedReference("http://127.0.0.1:8080", SERVER_B)),
            parse(text, loopbackCleartext, trusted = "http://127.0.0.1:8080"),
        )
        assertEquals(
            FeedReferenceProblem.InsecureGatewayUrl,
            problem(text, trusted = "http://127.0.0.1:8080"),
        )
    }

    @Test
    fun theServerIdIsTheSameShapeEverythingElseUses() {
        assertEquals(
            FeedReferenceProblem.BadServerId,
            problem("seekervault://feed?v=1&gateway=$GATEWAY&server=A-PUBLISHER"),
        )
        assertEquals(
            FeedReferenceProblem.BadServerId,
            problem("seekervault://feed?v=1&gateway=$GATEWAY"),
        )
    }

    @Test
    fun aRepeatedParameterMeansWhatItMeansInAPairingCode() {
        // The first value wins, as URLSearchParams.get does, because the two parsers share one.
        assertEquals(
            FeedReferenceResult.Valid(FeedReference(GATEWAY, SERVER_B)),
            parse("seekervault://feed?v=1&gateway=$GATEWAY&server=$SERVER_B&gateway=$URL_A"),
        )
    }

    @Test
    fun aReferenceToAnotherGatewayIsRefusedRatherThanFollowed() {
        // SEE-185: the build names one gateway, and a link can never point the app at another.
        val other = "https://other-gateway.example.com"
        assertEquals(
            FeedReferenceProblem.ForeignGateway,
            problem("seekervault://feed?v=1&gateway=$other&server=$SERVER_B"),
        )
        // The same gateway, written with a trailing slash or another case, is the same one.
        assertEquals(
            FeedReferenceResult.Valid(FeedReference(GATEWAY, SERVER_B)),
            parse(
                "seekervault://feed?v=1&gateway=$GATEWAY&server=$SERVER_B",
                trusted = "$GATEWAY/",
            ),
        )
        assertEquals(
            FeedReferenceResult.Valid(FeedReference(GATEWAY, SERVER_B)),
            parse(
                "seekervault://feed?v=1&gateway=$GATEWAY&server=$SERVER_B",
                trusted = "https://GATEWAY.example.com",
            ),
        )
        // A build that names no gateway adds no feed at all.
        assertEquals(
            FeedReferenceProblem.NoGateway,
            problem("seekervault://feed?v=1&gateway=$GATEWAY&server=$SERVER_B", trusted = ""),
        )
        assertEquals(null, FeedReferences.trustedGatewayProblem(GATEWAY, GATEWAY))
        assertEquals(
            FeedReferenceProblem.ForeignGateway,
            FeedReferences.trustedGatewayProblem(other, GATEWAY),
        )
    }
}
