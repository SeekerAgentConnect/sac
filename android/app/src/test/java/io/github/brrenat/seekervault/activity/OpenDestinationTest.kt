package io.github.brrenat.seekervault.activity

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Where a destination actually goes (SEE-157).
 *
 * The rule is one sentence — the provider's own app first, the web only if no app took it — and it
 * is the sentence that decides whether "open this on the provider" means what it says. Both halves
 * are checked here, because the second is the one an owner without the app installed will get and
 * it must not be a dead button.
 *
 * What is asserted about the first attempt is that it *asks for an app*:
 * `FLAG_ACTIVITY_REQUIRE_NON_BROWSER` is what makes Android open a verified address in the app that
 * verified it rather than in whichever browser is default, and whether a given phone has verified a
 * given address is Android's answer to give, not this app's and not a test's.
 */
@RunWith(AndroidJUnit4::class)
class OpenDestinationTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val page = "https://jup.ag/prediction/fed-decision-in-october"

    @Before
    fun refuseWhatNothingHandles() {
        // Robolectric starts every intent happily. Told to check, it behaves as a phone does: an
        // address no installed app claims throws, which is the signal the fallback rests on.
        shadowOf(application).checkActivities(true)
    }

    @Test
    fun theProvidersOwnAppIsAskedFirst() {
        install("https")

        assertTrue(openDestination(application, deepLink = page, url = page))

        val started = shadowOf(application).nextStartedActivity
        assertEquals(page, started.dataString)
        // Asked for as an app rather than as a page, which is the whole difference: an address
        // every browser also claims would otherwise open in whichever one is default.
        assertNotEquals(0, started.flags and Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)
        // One launch and nothing behind it: the web address is not also opened.
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun aPhoneWithoutTheProvidersAppFallsBackToTheWeb() {
        // A venue with a scheme of its own, and nothing installed that claims it. This is the
        // owner who has never installed the provider.
        install("https")

        assertTrue(
            openDestination(application, deepLink = "venue://market/POLY-2589813", url = page)
        )

        // The app-only attempt found nothing and threw; what started is the ordinary launch, on
        // the web address, which a browser answers.
        val started = shadowOf(application).nextStartedActivity
        assertEquals(page, started.dataString)
        assertEquals(0, started.flags and Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun aDestinationWithNoAppLinkIsSimplyOpened() {
        install("https")

        assertTrue(openDestination(application, deepLink = null, url = page))

        val started = shadowOf(application).nextStartedActivity
        assertEquals(page, started.dataString)
        // Nothing was tried as an app, because nothing said there was one.
        assertEquals(0, started.flags and Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun aPhoneThatOpensNothingIsNotAnError() {
        // No browser and no provider. Unusual, and not a failure: the screen still says everything
        // it said, and nothing about the operation depended on the link.
        assertFalse(openDestination(application, deepLink = page, url = page))
    }

    /** Installs one app that claims every address with this scheme, as a real one would. */
    private fun install(scheme: String) {
        val component = ComponentName("com.example.$scheme", "com.example.$scheme.ViewActivity")
        val filter =
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme(scheme)
            }
        shadowOf(application.packageManager).addActivityIfNotPresent(component)
        shadowOf(application.packageManager).addIntentFilterForActivity(component, filter)
    }
}
