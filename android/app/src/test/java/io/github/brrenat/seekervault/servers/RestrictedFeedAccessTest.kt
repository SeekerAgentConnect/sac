package io.github.brrenat.seekervault.servers

import io.github.brrenat.seekervault.server.v1.FeedAccessPolicy
import io.github.brrenat.seekervault.server.v1.ServerManifest as WireManifest
import io.github.brrenat.seekervault.server.v1.feedAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where a feed's access policy comes from, and what a reference is allowed to say about it
 * (SEE-156, docs/wiki/restricted-feeds.md#public-and-restricted-and-who-decides-which).
 *
 * One rule runs through all of it: the policy is the gateway operator's registration, stamped into
 * the manifest the gateway serves. A link can say a feed is restricted — that is a warning the
 * owner sees before anything is stored, and a floor the manifest is then held to — but it can never
 * say a feed is public, and it can never name the address the phone proves a wallet to. That
 * address is the one thing in this flow a publisher could use to point the phone at somebody else's
 * endpoint, so it is only ever read off the manifest the gateway vouches for.
 */
class RestrictedFeedAccessTest {
    private val expect =
        ManifestExpectation(serverId = SERVER_B, mode = ConnectionMode.GatewayFeed, origin = GATEWAY)

    private fun valid(message: WireManifest, expect: ManifestExpectation = this.expect) =
        (manifestFrom(message, expect) as ManifestResult.Valid).manifest

    private fun refused(message: WireManifest, expect: ManifestExpectation = this.expect) =
        (manifestFrom(message, expect) as ManifestResult.Invalid).problem

    private fun restricted(origin: String = ORIGIN) = feedAccess {
        policy = FeedAccessPolicy.FEED_ACCESS_POLICY_RESTRICTED
        authOrigin = origin
    }

    @Test
    fun aManifestWithoutTheFieldIsThePublicFeedItAlwaysWas() {
        // Every manifest published before SEE-156. Reading it as anything else would have made a
        // feed unreadable the day the app updated.
        assertEquals(FeedAccess.Public, valid(feedManifest()).feedAccess)
    }

    @Test
    fun aRestrictedManifestNamesTheOneOriginThePhoneMayProveAWalletTo() {
        val manifest = valid(feedManifest(access = restricted()))

        assertEquals(FeedAccess.Restricted(ORIGIN), manifest.feedAccess)
        assertEquals(
            ServerReference.Feed(GATEWAY, channelFor(SERVER_B), FeedAccess.Restricted(ORIGIN)),
            manifest.reference,
        )
    }

    @Test
    fun aPolicyThisBuildDoesNotKnowIsRefusedRatherThanReadAsPublic() {
        // The failure that matters: an older build meeting a policy added later must not conclude
        // that anyone may read the feed. Unspecified is the same case — it is never published.
        assertEquals(
            ManifestProblem.BadAccess,
            refused(feedManifest(access = feedAccess { policyValue = 99 })),
        )
        assertEquals(
            ManifestProblem.BadAccess,
            refused(
                feedManifest(
                    access =
                        feedAccess { policy = FeedAccessPolicy.FEED_ACCESS_POLICY_UNSPECIFIED }
                )
            ),
        )
    }

    @Test
    fun anAuthenticationOriginThatIsNotOneIsRefused() {
        // It is held to the gateway's own address rules, because it is the same kind of thing: an
        // origin, and nothing else. A path, a query, or a host this phone would send a wallet
        // proof to over cleartext is not one.
        val bad =
            listOf(
                "",
                "https://auth.example.com/onboarding",
                "https://auth.example.com?wallet=mine",
                "https://user:secret@auth.example.com",
                "http://auth.example.com",
                "ftp://auth.example.com",
                "auth.example.com",
            )
        for (origin in bad) {
            assertEquals(
                origin,
                ManifestProblem.BadAccess,
                refused(feedManifest(access = restricted(origin))),
            )
        }
    }

    @Test
    fun aPublicPolicyCannotSmuggleAnOriginWithIt() {
        // Nothing would read it, but a manifest that carries an address for a feed that needs
        // none is not a manifest this phone acts on.
        assertEquals(
            ManifestProblem.BadAccess,
            refused(
                feedManifest(
                    access =
                        feedAccess {
                            policy = FeedAccessPolicy.FEED_ACCESS_POLICY_PUBLIC
                            authOrigin = ORIGIN
                        }
                )
            ),
        )
        assertEquals(
            FeedAccess.Public,
            valid(
                    feedManifest(
                        access = feedAccess { policy = FeedAccessPolicy.FEED_ACCESS_POLICY_PUBLIC }
                    )
                )
                .feedAccess,
        )
    }

    @Test
    fun aFeedThatWasRestrictedIsNeverServedAsPublic() {
        // The downgrade, from either direction: the reference the owner added said restricted, or
        // the manifest this phone already holds does. Access tightens on its own and loosens only
        // by the owner removing the feed.
        val known = expect.copy(restricted = true)
        assertEquals(ManifestProblem.AccessDowngraded, refused(feedManifest(), known))
        assertEquals(
            ManifestProblem.AccessDowngraded,
            refused(
                feedManifest(
                    access = feedAccess { policy = FeedAccessPolicy.FEED_ACCESS_POLICY_PUBLIC }
                ),
                known,
            ),
        )
        // And the manifest that keeps its word is read as usual.
        assertEquals(
            FeedAccess.Restricted(ORIGIN),
            valid(feedManifest(access = restricted()), known).feedAccess,
        )
    }

    @Test
    fun aDirectServerHasNoFeedAccessAtAll() {
        val direct =
            ManifestExpectation(serverId = SERVER_A, mode = ConnectionMode.Direct, origin = URL_A)
        assertNull(valid(directManifest(), direct).feedAccess)
    }

    @Test
    fun aReferenceMaySayRestrictedAndMayCarryAnInvitation() {
        val reference = parsed("$BASE&access=restricted")
        assertEquals(true, reference.restricted)
        assertNull(reference.invitation)

        val invited = parsed("$BASE&access=restricted&invitation=$INVITATION")
        assertEquals(INVITATION, invited.invitation)
        assertEquals(true, invited.restricted)

        // An invitation is only ever for a restricted feed, so one implies the other rather than
        // being quietly ignored on a reference that forgot to say so.
        assertEquals(true, parsed("$BASE&invitation=$INVITATION").restricted)
    }

    @Test
    fun aReferenceSaysNothingAboutWhereToProveAWallet() {
        // The whole of what a reference can say about access is one word and one invitation. There
        // is no parameter here that could name an endpoint, which is what keeps a scanned code
        // from pointing this phone's wallet proof at an address of somebody's choosing.
        val reference = parsed("$BASE&access=restricted&auth=https://evil.example.com")
        assertEquals(true, reference.restricted)
        assertEquals(GATEWAY, reference.gatewayUrl)
        assertEquals(SERVER_B, reference.serverId)
    }

    @Test
    fun anAccessWordOrAnInvitationThisBuildCannotReadIsRefused() {
        assertEquals(FeedReferenceProblem.BadAccess, problem("$BASE&access=public"))
        assertEquals(FeedReferenceProblem.BadAccess, problem("$BASE&access=Restricted"))
        assertEquals(FeedReferenceProblem.BadAccess, problem("$BASE&access=members-only"))
        assertEquals(FeedReferenceProblem.BadInvitation, problem("$BASE&invitation=short"))
        assertEquals(
            FeedReferenceProblem.BadInvitation,
            problem("$BASE&invitation=${INVITATION.dropLast(1)}+"),
        )
    }

    @Test
    fun anOrdinaryPublicReferenceIsUnchanged() {
        val reference = parsed(BASE)
        assertEquals(false, reference.restricted)
        assertNull(reference.invitation)
    }

    private fun parsed(text: String): FeedReference =
        (FeedReferences.parse(text) { false } as FeedReferenceResult.Valid).reference

    private fun problem(text: String): FeedReferenceProblem =
        (FeedReferences.parse(text) { false } as FeedReferenceResult.Invalid).problem

    private companion object {
        const val ORIGIN = "https://auth.copytrading.example.com"
        const val BASE = "seekervault://feed?v=1&gateway=$GATEWAY&server=$SERVER_B"
        /** 32 bytes, base64url without padding, which is what the publisher issues. */
        const val INVITATION = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    }
}
