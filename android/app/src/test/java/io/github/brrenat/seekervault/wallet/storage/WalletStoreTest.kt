package io.github.brrenat.seekervault.wallet.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.connections.softwareKey
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.WalletNetwork
import java.io.File
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.SecretKey
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * What the phone keeps about the wallet: one encrypted, versioned record holding the selection and
 * the authorization the wallet issued for it, which are never read apart (SEE-84). The key is a
 * software AES key here, as in `CredentialVaultTest`.
 */
@RunWith(AndroidJUnit4::class)
class WalletStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val key = softwareKey()
    private val dir by lazy { File(folder.root, "files/wallet") }
    private val secretDir by lazy { File(folder.root, "no_backup/wallet") }
    private var sealing: () -> SecretKey = { key }
    private val store by lazy { WalletStore(dir, secretDir) { sealing() } }
    private val record by lazy { File(secretDir, "wallet-session") }

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
        assertEquals(StoredSession(wallet, AUTHORIZATION), store.session())
        assertEquals(wallet, store.selected())
        assertEquals(AUTHORIZATION, store.authorization())
    }

    @Test
    fun keepsAllOfItSealedAndOutOfTheBackedUpDirectory() {
        store.put(wallet, AUTHORIZATION)
        val sealed = String(record.readBytes(), Charsets.ISO_8859_1)
        assertFalse(sealed, AUTHORIZATION in sealed)
        // The selection is public, but it is the token's own record: it is sealed with it, in the
        // directory the app excludes from backups, rather than beside it in plain sight.
        assertFalse(sealed, WALLET in sealed)
        assertFalse(dir.exists() && dir.list()?.isNotEmpty() == true)
    }

    @Test
    fun refusesARecordSealedForSomethingElse() {
        store.put(wallet, AUTHORIZATION)
        // Another key can't open it: a phone reset, or a copy onto another device. The selection
        // goes with the authorization now, so there is no half of it left to read.
        val other = WalletStore(dir, secretDir) { softwareKey() }
        assertNull(other.session())
        assertNull(other.selected())
        assertNull(other.authorization())
    }

    @Test
    fun readsNothingBeforeAnythingIsStored() {
        assertNull(store.session())
        assertNull(store.selected())
        assertNull(store.authorization())
    }

    @Test
    fun forgetsTheWholeSession() {
        store.put(wallet, AUTHORIZATION)
        store.clear()
        assertNull(store.session())
        assertFalse(record.exists())
    }

    @Test
    fun skipsADamagedOrTruncatedRecord() {
        store.put(wallet, AUTHORIZATION)
        record.writeBytes(byteArrayOf(1, 12))
        assertNull(store.session())
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

    @Test
    fun readsWhatTheOlderBuildWroteAsTwoFilesAndStoresItAsOne() {
        writeLegacySelection(wallet)
        writeLegacyAuthorization(AUTHORIZATION)

        assertEquals(StoredSession(wallet, AUTHORIZATION), store.session())

        // It is one record from now on, and the two files it came from are gone rather than left
        // to be found again by a build that reads them.
        assertTrue(record.exists())
        assertFalse(File(dir, "wallet.json").exists())
        assertFalse(File(secretDir, "wallet-authorization").exists())
        assertEquals(StoredSession(wallet, AUTHORIZATION), store.session())
    }

    @Test
    fun refusesHalfOfWhatTheOlderBuildWrote() {
        // A selection whose authorization is gone is not a session, and neither is an
        // authorization with no selection: one half of a pair is never restored as a whole.
        writeLegacySelection(wallet)
        assertNull(store.session())
        assertFalse(record.exists())

        File(dir, "wallet.json").delete()
        writeLegacyAuthorization(AUTHORIZATION)
        assertNull(store.session())
        assertFalse(record.exists())
    }

    @Test
    fun anInterruptedReplacementLeavesTheRecordThatWasThere() {
        store.put(wallet, AUTHORIZATION)
        val replacement = wallet.copy(address = OTHER_WALLET, label = "Seeker account 2")

        // The phone dies while the wallet is being replaced: sealing the new record never
        // finishes, so nothing of it is written.
        sealing = { throw GeneralSecurityException("the keystore went away") }
        val failed = runCatching { store.put(replacement, OTHER_AUTHORIZATION) }
        assertTrue(failed.isFailure)

        sealing = { key }
        // What was there is still there, whole: the account and the token that belong together.
        assertEquals(StoredSession(wallet, AUTHORIZATION), store.session())
    }

    @Test
    fun aHalfWrittenReplacementIsNeverTheRecord() {
        store.put(wallet, AUTHORIZATION)
        // An interrupted write leaves its temporary file behind; it is not the record, and the
        // record is what is read.
        File(secretDir, "wallet-session.new").writeBytes(ByteArray(24) { 9 })

        assertEquals(StoredSession(wallet, AUTHORIZATION), store.session())
    }

    /** The selection exactly as the build before SEE-84 wrote it. */
    private fun writeLegacySelection(wallet: SelectedWallet) {
        dir.mkdirs()
        File(dir, "wallet.json")
            .writeText(
                JSONObject()
                    .put("version", 1)
                    .put("address", wallet.address)
                    .put("network", wallet.network.name)
                    .putOpt("label", wallet.label)
                    .put("selectedAt", wallet.selectedAt.toString())
                    .put("networkConfirmed", wallet.networkConfirmed)
                    .toString()
            )
    }

    /** The authorization exactly as the build before SEE-84 sealed it. */
    private fun writeLegacyAuthorization(authToken: String) {
        secretDir.mkdirs()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD("seekervault/wallet-authorization/v1".toByteArray(Charsets.UTF_8))
        val sealed = cipher.doFinal(authToken.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        File(secretDir, "wallet-authorization")
            .writeBytes(
                ByteBuffer.allocate(2 + iv.size + sealed.size)
                    .put(1)
                    .put(iv.size.toByte())
                    .put(iv)
                    .put(sealed)
                    .array()
            )
    }

    private companion object {
        const val WALLET = "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW"
        const val OTHER_WALLET = "3YKUMU99pedShDEe76HuSAHo3dt9CXjBwjN8w8NUo9Wh"
        const val AUTHORIZATION = "auth-token-from-the-wallet-0123456789"
        const val OTHER_AUTHORIZATION = "auth-token-from-the-wallet-9876543210"
    }
}
