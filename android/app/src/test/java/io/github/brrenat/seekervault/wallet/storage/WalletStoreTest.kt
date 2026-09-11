package io.github.brrenat.seekervault.wallet.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.connections.softwareKey
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.WalletNetwork
import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * What the phone keeps about the wallet: the selection in plain JSON, and the wallet's
 * authorization encrypted. The key is a software AES key here, as in `CredentialVaultTest`.
 */
@RunWith(AndroidJUnit4::class)
class WalletStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val key = softwareKey()
    private val dir by lazy { File(folder.root, "files/wallet") }
    private val secretDir by lazy { File(folder.root, "no_backup/wallet") }
    private val store by lazy { WalletStore(dir, secretDir) { key } }

    private val wallet =
        SelectedWallet(
            address = WALLET,
            network = WalletNetwork.Devnet,
            label = "Seeker account 1",
            selectedAt = Instant.parse("2026-09-12T09:30:00Z"),
        )

    @Test
    fun readsBackWhatItStored() {
        store.put(wallet, AUTHORIZATION)
        assertEquals(wallet, store.selected())
        assertEquals(AUTHORIZATION, store.authorization())
    }

    @Test
    fun keepsTheAuthorizationOutOfThePlainFileAndOutOfItsOwn() {
        store.put(wallet, AUTHORIZATION)
        val selection = File(dir, "wallet.json").readText()
        assertTrue(selection, WALLET in selection)
        assertFalse(selection, AUTHORIZATION in selection)
        val sealed = File(secretDir, "wallet-authorization").readBytes()
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains(AUTHORIZATION))
    }

    @Test
    fun refusesAnAuthorizationSealedForSomethingElse() {
        store.put(wallet, AUTHORIZATION)
        // Another key can't open it: a phone reset, or a copy onto another device.
        val other = WalletStore(dir, secretDir) { softwareKey() }
        assertNull(other.authorization())
        // The selection is public, so it still reads: the repository drops it when its
        // authorization is gone.
        assertEquals(wallet, other.selected())
    }

    @Test
    fun readsNothingBeforeAnythingIsStored() {
        assertNull(store.selected())
        assertNull(store.authorization())
    }

    @Test
    fun forgetsBothFilesTogether() {
        store.put(wallet, AUTHORIZATION)
        store.clear()
        assertNull(store.selected())
        assertNull(store.authorization())
        assertFalse(File(dir, "wallet.json").exists())
        assertFalse(File(secretDir, "wallet-authorization").exists())
    }

    @Test
    fun skipsADamagedOrTruncatedFile() {
        store.put(wallet, AUTHORIZATION)
        File(dir, "wallet.json").writeText("{not json")
        assertNull(store.selected())
        File(secretDir, "wallet-authorization").writeBytes(byteArrayOf(1, 12))
        assertNull(store.authorization())
    }

    @Test
    fun keepsTheNetworkTheOwnerChoseAndWhetherTheWalletConfirmedIt() {
        val unconfirmed = wallet.copy(network = WalletNetwork.Mainnet, networkConfirmed = false)
        store.put(unconfirmed, AUTHORIZATION)
        assertEquals(unconfirmed, store.selected())
    }

    @Test
    fun keepsAnAccountWithoutALabel() {
        val unnamed = wallet.copy(label = null)
        store.put(unnamed, AUTHORIZATION)
        assertEquals(unnamed, store.selected())
    }

    private companion object {
        const val WALLET = "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW"
        const val AUTHORIZATION = "auth-token-from-the-wallet-0123456789"
    }
}
