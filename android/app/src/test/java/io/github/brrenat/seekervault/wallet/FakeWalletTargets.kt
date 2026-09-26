package io.github.brrenat.seekervault.wallet

/**
 * The wallet apps a test says this phone has, without a `PackageManager` (SEE-159). It is the whole
 * of what the adapter learns about what is installed, so what it does with a route pointing at an
 * app that is there, and at one that isn't, is exercised here.
 */
class FakeWalletTargets(var wallets: List<InstalledWallet> = emptyList()) : WalletTargets {
    /** How many times the phone asked. A signing asks once, and never more than once. */
    var asked = 0
        private set

    override fun installed(): List<InstalledWallet> {
        asked += 1
        return wallets
    }
}
