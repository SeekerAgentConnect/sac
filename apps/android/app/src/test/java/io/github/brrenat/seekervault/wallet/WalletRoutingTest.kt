package io.github.brrenat.seekervault.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where an association is aimed, given what the owner connected and what this phone has installed
 * now (SEE-159). It is the whole of the decision, and it decides it without a wallet, a
 * `PackageManager` or an intent: the wallet's own association URI first, the app the owner
 * connected to narrow it, and — for an app that has gone — nothing at all.
 */
class WalletRoutingTest {
    @Test
    fun aimsAtNothingWhenNothingWasEverLearned() {
        assertEquals(WalletTarget.Wide, targetOf(null, setOf(WALLET_APP)))
        assertEquals(WalletTarget.Wide, targetOf(WalletRouting.Untargeted, setOf(WALLET_APP)))
    }

    @Test
    fun aimsAtTheWalletsOwnAssociationUri() {
        val route = WalletRouting(uriBase = URI_BASE)

        assertEquals(WalletTarget.Endpoint(URI_BASE, null), targetOf(route, setOf(WALLET_APP)))
    }

    @Test
    fun narrowsTheAssociationUriToTheAppTheOwnerConnected() {
        val route = WalletRouting(uriBase = URI_BASE, packageName = WALLET_APP)

        assertEquals(
            WalletTarget.Endpoint(URI_BASE, WALLET_APP),
            targetOf(route, setOf(WALLET_APP, OTHER_APP)),
        )
    }

    @Test
    fun aimsAtTheAppWhenTheWalletReportedNoUri() {
        val route = WalletRouting(packageName = WALLET_APP, appLabel = "Seeker Wallet")

        assertEquals(WalletTarget.App(WALLET_APP), targetOf(route, setOf(WALLET_APP, OTHER_APP)))
    }

    @Test
    fun aimsAtNothingWhenTheAppTheOwnerConnectedIsGone() {
        // The one outcome that must not become a wide association: another installed wallet would
        // inherit the approval the owner gave for this one.
        val route = WalletRouting(uriBase = URI_BASE, packageName = WALLET_APP)

        assertEquals(WalletTarget.Missing, targetOf(route, setOf(OTHER_APP)))
    }

    @Test
    fun trustsTheRouteWhenThisPhoneListedNothingAtAll() {
        // An empty list is "this phone didn't say", and launching is the authority: an intent aimed
        // at one package opens that app or fails, and can never open another.
        val route = WalletRouting(packageName = WALLET_APP)

        assertEquals(WalletTarget.App(WALLET_APP), targetOf(route, emptySet()))
    }

    @Test
    fun ignoresAnAssociationUriMobileWalletAdapterWouldRefuse() {
        // The library takes an absolute, hierarchical https prefix and throws on anything else.
        assertFalse(WalletRouting.usableUriBase(null))
        assertFalse(WalletRouting.usableUriBase("solana-wallet:/v1/associate/local"))
        assertFalse(WalletRouting.usableUriBase("http://wallet.example/ul"))
        assertFalse(WalletRouting.usableUriBase("https:///v1"))
        assertTrue(WalletRouting.usableUriBase(URI_BASE))

        val route = WalletRouting(uriBase = "http://wallet.example/ul", packageName = WALLET_APP)
        assertEquals(WalletTarget.App(WALLET_APP), targetOf(route, setOf(WALLET_APP)))
    }

    @Test
    fun takesTheUriTheWalletJustReportedAndKeepsWhatItSaidNothingAbout() {
        val route = WalletRouting(uriBase = URI_BASE, packageName = WALLET_APP)

        // A wallet that reported none has said nothing, exactly as one that handed back no
        // replacement authorization has: what this phone holds stands.
        assertEquals(route, route.withReported(null))
        assertEquals(route, route.withReported("not a usable prefix"))
        assertEquals(route.copy(uriBase = MOVED), route.withReported(MOVED))
        assertEquals(
            WalletRouting(uriBase = MOVED),
            WalletRouting.Untargeted.withReported(MOVED),
        )
    }

    @Test
    fun isTargetedOnlyWhenItNamesSomething() {
        assertFalse(WalletRouting.Untargeted.targeted)
        assertTrue(WalletRouting(uriBase = URI_BASE).targeted)
        assertTrue(WalletRouting(packageName = WALLET_APP).targeted)
        // A label alone names no wallet: it is what the system called one, for display.
        assertFalse(WalletRouting(appLabel = "Seeker Wallet").targeted)
    }

    private companion object {
        const val WALLET_APP = "com.example.seekerwallet"
        const val OTHER_APP = "com.example.otherwallet"
        const val URI_BASE = "https://wallet.example/ul"
        const val MOVED = "https://wallet.example/ul/v2"
    }
}
