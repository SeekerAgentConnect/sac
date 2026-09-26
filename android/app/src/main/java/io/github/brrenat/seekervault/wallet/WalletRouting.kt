package io.github.brrenat.seekervault.wallet

/**
 * How this phone reaches the wallet *app* the owner connected (SEE-159), beside [SelectedWallet],
 * which is the account inside it. The two are stored together and read together: an account without
 * the app that holds it is how an approval ended up in front of whichever wallet Android happened to
 * resolve.
 *
 * Nothing here is invented. [uriBase] is the wallet's own association URI, exactly as Mobile Wallet
 * Adapter reported it in `AuthorizationResult.walletUriBase`, and [packageName] and [appLabel] come
 * from `PackageManager`, which is the only thing on this phone that knows what is installed. The app
 * never writes down a wallet's package name of its own accord.
 */
data class WalletRouting(
    /**
     * The wallet-specific association URI the wallet reported, or null when it reported none. Mobile
     * Wallet Adapter only accepts an absolute, hierarchical `https` one, so that is all that is kept
     * (`LocalAssociationIntentCreator.createAssociationIntent` throws on anything else).
     */
    val uriBase: String? = null,
    /** The wallet app's package, as `PackageManager` reported it, or null when it isn't known. */
    val packageName: String? = null,
    /** The wallet app's name, as `PackageManager` reported it, for display only. */
    val appLabel: String? = null,
) {
    /** Whether this route names a wallet app at all, rather than leaving the choice to Android. */
    val targeted: Boolean
        get() = uriBase != null || packageName != null

    /**
     * This route with whatever the wallet last reported folded in. A wallet that reported no
     * association URI has said nothing about one — exactly as it says nothing by handing back no
     * replacement authorization — so what this phone already holds stands.
     */
    fun withReported(reported: String?): WalletRouting =
        if (usableUriBase(reported) && reported != uriBase) copy(uriBase = reported) else this

    companion object {
        /** A route that names nothing: the association resolves the way any other one does. */
        val Untargeted = WalletRouting()

        /** Whether [uri] is one Mobile Wallet Adapter will take as an association URI prefix. */
        fun usableUriBase(uri: String?): Boolean =
            uri != null && uri.startsWith("https://") && !uri.startsWith("https:///")
    }
}

/** A wallet app installed on this phone, as `PackageManager` reported it. */
data class InstalledWallet(
    val packageName: String,
    /** What the system says the app is called. Never a name this app made up. */
    val label: String,
)

/** What this phone knows about the wallet apps installed on it. */
interface WalletTargets {
    /**
     * The installed apps that answer Mobile Wallet Adapter's local association, in the order the
     * system listed them. Empty means no wallet app is installed at all.
     */
    fun installed(): List<InstalledWallet>
}

/** Where one association is aimed. */
sealed interface WalletTarget {
    /**
     * At the wallet's own association URI, which is what Mobile Wallet Adapter's routing is for.
     * [packageName] narrows it further when this phone also knows the app is installed; a URI that
     * another app could claim is then still aimed at the one the owner connected.
     */
    data class Endpoint(val uriBase: String, val packageName: String?) : WalletTarget

    /** At one installed app, by package. The association URI is the ordinary `solana-wallet:` one. */
    data class App(val packageName: String) : WalletTarget

    /** At no app in particular: Android resolves it, and may ask the owner. */
    data object Wide : WalletTarget

    /**
     * At the app the owner connected, which isn't installed any more. Nothing is opened: falling
     * back to [Wide] here would put an approval in front of a *different* wallet, which is the
     * silent switch this app must never make.
     */
    data object Missing : WalletTarget
}

/**
 * Where an association for [route] should be aimed, given the wallet apps [installed] on this phone.
 *
 * The wallet's own association URI comes first, because that is the mechanism Mobile Wallet Adapter
 * defines for reaching one wallet, and the package narrows it when this phone knows it. A route that
 * has only a package uses it. A route that names a package this phone no longer has is [
 * WalletTarget.Missing], whatever else it holds: the app it pointed at is gone, and no other app
 * inherits an approval.
 */
fun targetOf(route: WalletRouting?, installed: Set<String>): WalletTarget {
    if (route == null || !route.targeted) return WalletTarget.Wide
    // An empty list is "this phone didn't say", not "nothing is installed", so it doesn't condemn a
    // route on its own. Launching is the authority either way: an intent aimed at one package opens
    // that app or fails, and it can never open another.
    val listed = installed.isNotEmpty()
    val app = route.packageName?.takeIf { !listed || it in installed }
    if (route.packageName != null && app == null) return WalletTarget.Missing
    if (WalletRouting.usableUriBase(route.uriBase)) {
        return WalletTarget.Endpoint(checkNotNull(route.uriBase), app)
    }
    return app?.let(WalletTarget::App) ?: WalletTarget.Wide
}
