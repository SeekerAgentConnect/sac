package io.github.brrenat.seekervault.wallet.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.connections.softwareKey
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.WalletNetwork
import io.github.brrenat.seekervault.wallet.WalletRouting
import java.io.File
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
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
    fun keepsTheRouteToTheWalletAppBesideTheAccount() {
        // SEE-159: an account without the app that holds it is how an approval ends up in front of
        // whichever wallet Android resolved. The two are one record, and a restart reads both.
        val route =
            WalletRouting(
                uriBase = "https://wallet.example/ul",
                packageName = "com.example.seekerwallet",
                appLabel = "Seeker Wallet",
            )

        store.put(wallet, AUTHORIZATION, route)

        assertEquals(StoredSession(wallet, AUTHORIZATION, route), store.session())
        // Reading it again with a store made afresh is what opening the app after a restart does.
        assertEquals(route, WalletStore(dir, secretDir) { key }.session()?.route)
    }

    @Test
    fun sealsTheRouteWithTheRestOfTheRecord() {
        store.put(
            wallet,
            AUTHORIZATION,
            WalletRouting(uriBase = "https://wallet.example/ul", packageName = WALLET_APP),
        )

        val sealed = String(record.readBytes(), Charsets.ISO_8859_1)
        assertFalse(sealed, WALLET_APP in sealed)
    }

    @Test
    fun keepsARecordWithNoRouteAtAll() {
        // Every account starts this way: the first association resolves as it always did, and what
        // the wallet then reports about itself is what the route is made of.
        store.put(wallet, AUTHORIZATION)

        assertEquals(WalletRouting.Untargeted, store.session()?.route)
    }

    @Test
    fun forgettingTheWalletTakesItsRouteWithIt() {
        store.put(wallet, AUTHORIZATION, WalletRouting(packageName = WALLET_APP))
        store.clear()

        // Disconnecting leaves nothing aimed at that wallet app (SEE-159).
        assertNull(store.session())
        assertFalse(record.exists())
    }

    @Test
    fun readsTheRecordAnEarlierBuildWroteWithoutARoute() {
        // A phone upgrading from SEE-84's format keeps its wallet: the record is whole, it just
        // never knew which app answered, and the next association finds out.
        store.put(wallet, AUTHORIZATION, WalletRouting(packageName = WALLET_APP))
        rewriteRecord {
            it.remove("walletPackage")
            it.put("version", 2)
        }

        assertEquals(StoredSession(wallet, AUTHORIZATION), store.session())
    }

    @Test
    fun refusesARecordFromAFormatItDoesNotKnow() {
        store.put(wallet, AUTHORIZATION)
        rewriteRecord { it.put("version", 4) }

        assertNull(store.session())
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

    /** Reseals the record with its JSON changed, as another build's would have been. */
    private fun rewriteRecord(change: (JSONObject) -> JSONObject) {
        val buffer = ByteBuffer.wrap(record.readBytes())
        buffer.get()
        val iv = ByteArray(buffer.get().toInt()).also { buffer.get(it) }
        val sealed = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val opening = Cipher.getInstance("AES/GCM/NoPadding")
        opening.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        opening.updateAAD(SESSION_DATA)
        val json = change(JSONObject(String(opening.doFinal(sealed), Charsets.UTF_8))).toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(SESSION_DATA)
        val resealed = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        record.writeBytes(
            ByteBuffer.allocate(2 + cipher.iv.size + resealed.size)
                .put(1)
                .put(cipher.iv.size.toByte())
                .put(cipher.iv)
                .put(resealed)
                .array()
        )
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

    @Test
    fun keepsEveryProfileAndTheAuthorizationsTheyShareAsOneSealedRecord() {
        // SEE-174: several profiles, two of them sharing the one authorization their wallet app
        // issued for both accounts, and the same address again on another network.
        val shared = StoredAuthorization("a1", AUTHORIZATION, WalletNetwork.Mainnet, route())
        val devnet = StoredAuthorization("a2", OTHER_AUTHORIZATION, WalletNetwork.Devnet, route())
        val profiles =
            WalletProfiles(
                profiles =
                    listOf(
                        profile("p1", WALLET, WalletNetwork.Mainnet, "a1", label = "Trading"),
                        profile("p2", OTHER_WALLET, WalletNetwork.Mainnet, "a1"),
                        profile("p3", WALLET, WalletNetwork.Devnet, "a2", authorized = false),
                    ),
                authorizations = listOf(shared, devnet),
                legacyProfileId = "p1",
            )
        store.putProfiles(profiles)

        val reopened = WalletStore(dir, secretDir) { key }.profiles()

        assertEquals(profiles.routed(), reopened)
        assertEquals(WALLET_APP, reopened.profile("p3")?.route?.packageName)
        val sealed = String(File(secretDir, "wallet-profiles").readBytes(), Charsets.ISO_8859_1)
        assertFalse(sealed, AUTHORIZATION in sealed)
        assertFalse(sealed, WALLET in sealed)
    }

    @Test
    fun migratesTheSingleSessionIntoOneProfileAndDropsItOnlyOnceCommitted() {
        val route = route()
        store.put(wallet, AUTHORIZATION, route)

        val migrated = store.profiles()

        val profile = migrated.profiles.single()
        assertEquals(WALLET to WalletNetwork.Devnet, profile.address to profile.network)
        assertEquals("Seeker account 1", profile.accountLabel)
        assertEquals(wallet.selectedAt, profile.connectedAt)
        assertEquals(route, profile.route)
        assertEquals(AUTHORIZATION, migrated.authorization(profile.authorizationId)?.token)
        assertEquals(profile.id, migrated.legacyProfileId)
        assertFalse(record.exists())
        // Read again: the committed record, not a second migration.
        assertEquals(migrated, WalletStore(dir, secretDir) { key }.profiles())
    }

    @Test
    fun aMigrationThatCannotCommitKeepsTheSessionForTheNextStart() {
        store.put(wallet, AUTHORIZATION)
        // The key opens the old record but can't seal the new one: an interrupted write.
        var reads = 0
        val failing =
            WalletStore(dir, secretDir) {
                if (++reads > 1) throw GeneralSecurityException("keystore went away") else key
            }

        val interim = failing.profiles()

        assertEquals(WALLET, interim.profiles.single().address)
        assertTrue(record.exists())
        // The next start migrates it into exactly the same IDs.
        assertEquals(interim, store.profiles())
    }

    @Test
    fun anUnreadableProfilesRecordIsNoProfiles() {
        store.putProfiles(
            WalletProfiles(
                listOf(profile("p1", WALLET, WalletNetwork.Mainnet, "a1")),
                listOf(StoredAuthorization("a1", AUTHORIZATION, WalletNetwork.Mainnet)),
            )
        )
        assertEquals(WalletProfiles.Empty, WalletStore(dir, secretDir) { softwareKey() }.profiles())
    }

    private fun route() =
        WalletRouting(uriBase = null, packageName = WALLET_APP, appLabel = "Seeker Wallet")

    private fun profile(
        id: String,
        address: String,
        network: WalletNetwork,
        authorization: String,
        label: String? = null,
        authorized: Boolean = true,
    ) =
        io.github.brrenat.seekervault.wallet.WalletProfile(
            id = id,
            address = address,
            network = network,
            label = label,
            accountLabel = "Account",
            authorizationId = authorization,
            connectedAt = Instant.parse("2026-09-12T09:30:00Z"),
            authorized = authorized,
        )

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
        const val WALLET_APP = "com.example.seekerwallet"
        val SESSION_DATA = "seekervault/wallet-session/v2".toByteArray(Charsets.UTF_8)
    }
}
