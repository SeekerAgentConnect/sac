package io.github.brrenat.seekervault.plugins.actions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a publisher may name as somewhere to continue, and — the half that matters — what it may not
 * (SEE-157).
 *
 * This is the rule that decides what this app is willing to hand to another app at all. Everything
 * refused below is refused because it is not a destination: a page without the guarantee, code,
 * inline content, this phone's own storage, another app's, or an Android intent, which is an
 * arbitrary component with arbitrary extras wearing a link's clothes.
 */
class ProviderLinkTest {

    @Test
    fun acceptsAnAddressSomethingCouldOpen() {
        for (allowed in
            listOf(
                "https://jup.ag/prediction/fed-decision-in-october",
                "https://jup.ag/prediction/portfolio?tab=open",
                "https://sub.example.com/",
                // A venue with a scheme of its own is opened the same way. Nothing here knows or
                // cares which venue that is.
                "venue://market/POLY-2589813",
                "venue:market/POLY-2589813",
            )) {
            assertTrue(allowed, isProviderLink(allowed))
        }
    }

    @Test
    fun refusesWhatIsNotADestination() {
        for (refused in
            listOf(
                "",
                "   ",
                // No scheme, so nothing knows what to do with it.
                "jup.ag/prediction/x",
                // The same page without the guarantee.
                "http://jup.ag/prediction/x",
                "javascript:alert(1)",
                "data:text/html,<b>hi</b>",
                "file:///data/data/app/x",
                "content://media/external/1",
                "intent://scan/#Intent;scheme=zxing;end",
                "android-app://com.example",
                // A credential in a URL is either a leak or a lure.
                "https://owner:secret@jup.ag/x",
                // Nothing to tell anybody's property from.
                "https:///prediction/x",
                // Assembled rather than written, and a reader that trimmed it would be a reader
                // two programs disagree about.
                "https://jup.ag/a b",
                "https://jup.ag/a\nb",
                // A scheme is a scheme, not a sentence.
                "1https://jup.ag/x",
            )) {
            assertFalse(refused, isProviderLink(refused))
        }
        assertFalse(isProviderLink("https://jup.ag/" + "x".repeat(MOST_LINK_LENGTH)))
    }

    @Test
    fun aProvidersOwnPropertyIsTheHostAndNotTheEndOfTheString() {
        assertTrue(isSecureLinkTo("https://jup.ag/prediction/x", "jup.ag"))
        // A subdomain is the same property.
        assertTrue(isSecureLinkTo("https://www.jup.ag/prediction/x", "jup.ag"))
        assertTrue(isSecureLinkTo("https://JUP.AG/prediction/x", "jup.ag"))
        for (impostor in
            listOf(
                // The whole of the attack: a name that ends the same way, a name that contains it,
                // and the real name somewhere it is not the host.
                "https://notjup.ag/prediction/x",
                "https://jup.ag.example.com/prediction/x",
                "https://evil.example.com/?next=https://jup.ag/prediction/x",
                "https://evil.example.com/jup.ag/prediction/x",
                // And the same host without the guarantee, which is not the same address.
                "http://jup.ag/prediction/x",
            )) {
            assertFalse(impostor, isSecureLinkTo(impostor, "jup.ag"))
        }
    }
}
