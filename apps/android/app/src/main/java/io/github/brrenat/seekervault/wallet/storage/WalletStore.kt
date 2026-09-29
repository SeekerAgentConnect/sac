package io.github.brrenat.seekervault.wallet.storage

import android.util.AtomicFile
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.WalletNetwork
import io.github.brrenat.seekervault.wallet.WalletProfile
import io.github.brrenat.seekervault.wallet.WalletRouting
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The owner's wallet as this phone holds it: the selection they made, the authorization the wallet
 * issued for exactly that account, and the way back to the wallet app that issued it. All three
 * belong together and are never read apart — an account without the app that holds it is how an
 * approval ends up in front of whichever wallet Android resolved (SEE-159).
 */
data class StoredSession(
    val wallet: SelectedWallet,
    val authToken: String,
    val route: WalletRouting = WalletRouting.Untargeted,
) {
    override fun toString() = "StoredSession(wallet=$wallet, authToken=<redacted>, route=$route)"
}

/**
 * One authorization a wallet app issued this app (SEE-174): the token, the network it was asked
 * for, and the wallet app that issued it. Mobile Wallet Adapter scopes a token to the wallet that
 * issued it and to the chain it was authorized on, so a token is never offered to another app or
 * used for another network; [WalletProfile]s reference it by [id], and several may share one when
 * the wallet authorized several accounts at once.
 */
data class StoredAuthorization(
    val id: String,
    val token: String,
    val network: WalletNetwork,
    val route: WalletRouting = WalletRouting.Untargeted,
) {
    override fun toString() =
        "StoredAuthorization(id=$id, token=<redacted>, network=$network, route=$route)"
}

/**
 * Every wallet profile this phone holds, with the authorizations they use (SEE-174). It is one
 * record, sealed as one, for the same reason SEE-84 made the single session one: a profile and the
 * authorization it signs with are one fact, and an interrupted write must leave either the old
 * record or the new one, never a profile naming an authorization that isn't there.
 *
 * [legacyProfileId] is the profile the single-session format became, when it did. It stays so that
 * a connection stored before SEE-174 — which named no profile, because there was one wallet — is
 * bound to the wallet it was using, however many restarts the migration took.
 */
data class WalletProfiles(
    val profiles: List<WalletProfile> = emptyList(),
    val authorizations: List<StoredAuthorization> = emptyList(),
    val legacyProfileId: String? = null,
) {
    fun profile(id: String?): WalletProfile? = id?.let { wanted ->
        profiles.firstOrNull { it.id == wanted }
    }

    fun authorization(id: String): StoredAuthorization? = authorizations.firstOrNull { it.id == id }

    /** Profiles with their wallet app filled in from the authorization that holds it. */
    fun routed(): WalletProfiles =
        copy(
            profiles =
                profiles.map { profile ->
                    authorization(profile.authorizationId)?.let { profile.copy(route = it.route) }
                        ?: profile
                }
        )

    /** Authorizations no profile uses any more. */
    fun unreferenced(): List<StoredAuthorization> = authorizations.filter { held ->
        profiles.none { it.authorizationId == held.id }
    }

    override fun toString() =
        "WalletProfiles(profiles=$profiles, authorizations=${authorizations.size}, " +
            "legacyProfileId=$legacyProfileId)"

    companion object {
        val Empty = WalletProfiles()
    }
}

/**
 * What the phone keeps about the owner's wallet (docs/security.md#local-storage-and-recovery): one
 * record holding the selection — a public address, its network, and when it was chosen — together
 * with the wallet's authorization token for that account, encrypted with AES-256-GCM under the key
 * [key] returns, in [secretDir], which the app keeps in `noBackupFilesDir`.
 *
 * It is one record because its parts are one fact (SEE-84). The selection and the authorization
 * used to be two files, each written atomically on its own but not as a pair, so an interruption
 * between them could leave this phone holding a token that belonged to another account. A single
 * sealed record can't: an interrupted replacement leaves the record that was there, whole, and a
 * half-written legacy pair is refused rather than used. SEE-159 puts the route to the wallet app in
 * the same record, for the same reason.
 *
 * There is never a seed phrase or a private key here: the wallet app owns those, and this app never
 * asks for them.
 *
 * The sealed file's format is the
 * [io.github.brrenat.seekervault.connections.storage.CredentialVault]'s: version (1 byte), IV
 * length (1 byte), IV, then the ciphertext with its 16-byte tag.
 */
class WalletStore(
    private val dir: File,
    private val secretDir: File,
    private val key: () -> SecretKey,
) {
    /**
     * Every wallet profile this phone holds (SEE-174), migrating the single session the app kept
     * before it when that is all there is.
     *
     * The migration is restartable. The profile and authorization it makes have IDs derived from
     * the session itself, so doing it twice — because the process died before the new record was
     * written, or before the old one was deleted — makes the same record twice. The session is
     * deleted only after the new record is committed; until then it is what the next read migrates
     * again. A record that can't be decrypted is no profiles, exactly as an unreadable session was
     * no wallet.
     */
    fun profiles(): WalletProfiles {
        storedProfiles()?.let { held ->
            // Committed: whatever the older format left is now only a copy of it.
            if (File(secretDir, SESSION).exists() || File(dir, LEGACY_SELECTION).exists()) {
                AtomicFile(File(secretDir, SESSION)).delete()
                forgetLegacy()
            }
            return held.routed()
        }
        val session = session() ?: return WalletProfiles.Empty
        val migrated = fromSession(session)
        try {
            putProfiles(migrated)
            AtomicFile(File(secretDir, SESSION)).delete()
            forgetLegacy()
        } catch (e: GeneralSecurityException) {
            // Not committed; the session is still there, and the next read migrates it again into
            // the same IDs.
        } catch (e: IOException) {
            // Kept as it was; see above.
        }
        return migrated.routed()
    }

    /** Stores every profile and authorization as one sealed record, replacing the one there was. */
    fun putProfiles(profiles: WalletProfiles) {
        write(secretDir, PROFILES, seal(encodeProfiles(profiles), PROFILES_DATA))
    }

    /** Forgets every profile, and anything the older formats left behind. */
    fun clearProfiles() {
        AtomicFile(File(secretDir, PROFILES)).delete()
        clear()
    }

    private fun storedProfiles(): WalletProfiles? {
        val bytes =
            try {
                AtomicFile(File(secretDir, PROFILES)).readFully()
            } catch (e: IOException) {
                return null
            }
        val text = open(bytes, PROFILES_DATA) ?: return null
        return try {
            decodeProfiles(text)
        } catch (e: JSONException) {
            null
        } catch (e: IllegalArgumentException) {
            null // an unknown network or a malformed timestamp
        }
    }

    /**
     * The wallet session this phone holds in the single-session format that SEE-174 replaced, or
     * null when there is none, when it can't be decrypted, or when all that is left of one is half
     * of the storage this app used before (SEE-84). It is read only to migrate it into [profiles].
     */
    fun session(): StoredSession? = stored() ?: migrated()

    /** The wallet the owner selected, or null when no whole session is stored. */
    fun selected(): SelectedWallet? = session()?.wallet

    /** The stored authorization, or null when no whole session is stored. */
    fun authorization(): String? = session()?.authToken

    /**
     * Stores the selection, its authorization and the route to the wallet app as one record, so
     * nothing can ever read one without the others. Anything the older format left behind goes with
     * it.
     */
    fun put(
        wallet: SelectedWallet,
        authToken: String,
        route: WalletRouting = WalletRouting.Untargeted,
    ) {
        write(secretDir, SESSION, seal(encode(wallet, authToken, route)))
        forgetLegacy()
    }

    /**
     * Forgets the wallet: the record — the selection, the authorization and the route to the wallet
     * app alike — and anything the older format left behind.
     */
    fun clear() {
        AtomicFile(File(secretDir, SESSION)).delete()
        forgetLegacy()
    }

    private fun stored(): StoredSession? {
        val bytes =
            try {
                AtomicFile(File(secretDir, SESSION)).readFully()
            } catch (e: IOException) {
                return null
            }
        val text = open(bytes, SESSION_DATA) ?: return null
        return try {
            decode(text)
        } catch (e: JSONException) {
            null
        } catch (e: IllegalArgumentException) {
            null // an unknown network or a malformed timestamp
        }
    }

    /**
     * The session as the two files this app used before held it, written back as one record. Both
     * halves have to be there and readable: a selection whose authorization is gone, or an
     * authorization sealed under a key this phone no longer has, is not a session, and it is
     * refused rather than half-restored.
     */
    private fun migrated(): StoredSession? {
        val wallet = legacySelection() ?: return null
        val authToken = legacyAuthorization() ?: return null
        // Nothing in the older format ever knew which wallet app answered, so the migrated record
        // has no route, and the next association learns one.
        val session = StoredSession(wallet, authToken)
        try {
            put(wallet, authToken)
        } catch (e: GeneralSecurityException) {
            // The record couldn't be written; the files it came from are still there, so the next
            // read migrates it again rather than losing the owner's wallet.
        } catch (e: IOException) {
            // Kept as it was; see above.
        }
        return session
    }

    private fun legacySelection(): SelectedWallet? {
        val file = AtomicFile(File(dir, LEGACY_SELECTION))
        return try {
            decodeLegacy(String(file.readFully(), Charsets.UTF_8))
        } catch (e: IOException) {
            null
        } catch (e: JSONException) {
            null
        } catch (e: IllegalArgumentException) {
            null // an unknown network or a malformed timestamp
        }
    }

    private fun legacyAuthorization(): String? {
        val bytes =
            try {
                AtomicFile(File(secretDir, LEGACY_AUTHORIZATION)).readFully()
            } catch (e: IOException) {
                return null
            }
        return open(bytes, LEGACY_AUTHORIZATION_DATA)
    }

    private fun forgetLegacy() {
        AtomicFile(File(secretDir, LEGACY_AUTHORIZATION)).delete()
        AtomicFile(File(dir, LEGACY_SELECTION)).delete()
    }

    /** What a sealed file holds, or null when it can't be opened with the key this phone has. */
    private fun open(bytes: ByteArray, associated: ByteArray): String? =
        try {
            val buffer = ByteBuffer.wrap(bytes)
            if (buffer.get() != VERSION) return null
            val iv = ByteArray(buffer.get().toInt()).also { buffer.get(it) }
            val sealed = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(associated)
            String(cipher.doFinal(sealed), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: RuntimeException) {
            null // a truncated file: BufferUnderflowException, NegativeArraySizeException
        }

    private fun seal(secret: String, associated: ByteArray = SESSION_DATA): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The Keystore picks the IV; a caller-chosen IV isn't allowed for its GCM keys.
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(associated)
        val sealed = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        return ByteBuffer.allocate(2 + iv.size + sealed.size)
            .put(VERSION)
            .put(iv.size.toByte())
            .put(iv)
            .put(sealed)
            .array()
    }

    private fun write(directory: File, name: String, bytes: ByteArray) {
        val file = AtomicFile(File(directory, name))
        directory.mkdirs()
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (e: IOException) {
            file.failWrite(stream)
            throw e
        }
    }

    private companion object {
        const val PROFILES = "wallet-profiles"
        /** SEE-174's record: every profile and authorization. */
        const val PROFILES_FORMAT = 4
        val PROFILES_DATA = "seekervault/wallet-profiles/v4".toByteArray(Charsets.UTF_8)
        const val SESSION = "wallet-session"
        const val LEGACY_SELECTION = "wallet.json"
        const val LEGACY_AUTHORIZATION = "wallet-authorization"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val VERSION: Byte = 1
        /**
         * The record's own format: 2 was SEE-84's single sealed session, and 3 adds SEE-159's route
         * to the wallet app. A 2 is still a whole session and is read as one, with no route; the
         * next association resolves the way it used to and learns the route from the wallet's
         * answer, so an upgrade keeps the owner's wallet rather than asking for it again.
         */
        const val FORMAT = 3
        const val ROUTELESS_FORMAT = 2
        const val LEGACY_FORMAT = 1
        val SESSION_DATA = "seekervault/wallet-session/v2".toByteArray(Charsets.UTF_8)
        val LEGACY_AUTHORIZATION_DATA =
            "seekervault/wallet-authorization/v1".toByteArray(Charsets.UTF_8)

        fun encode(wallet: SelectedWallet, authToken: String, route: WalletRouting): String =
            JSONObject()
                .put("version", FORMAT)
                .put("address", wallet.address)
                .put("network", wallet.network.name)
                .putOpt("label", wallet.label)
                .put("selectedAt", wallet.selectedAt.toString())
                .put("networkConfirmed", wallet.networkConfirmed)
                .put("authToken", authToken)
                .putOpt("walletUriBase", route.uriBase)
                .putOpt("walletPackage", route.packageName)
                .putOpt("walletApp", route.appLabel)
                .toString()

        fun decode(text: String): StoredSession? {
            val json = JSONObject(text)
            val version = json.getInt("version")
            if (version != FORMAT && version != ROUTELESS_FORMAT) return null
            val authToken = json.optString("authToken").takeIf { it.isNotEmpty() } ?: return null
            return StoredSession(wallet(json), authToken, route(json))
        }

        /**
         * The route the record holds. A format-2 record holds none, and so does a format-3 one
         * written before the wallet said anything about where it lives; either way the association
         * resolves as it always did, and learns.
         */
        fun route(json: JSONObject): WalletRouting =
            WalletRouting(
                uriBase = json.optString("walletUriBase").takeIf { it.isNotEmpty() },
                packageName = json.optString("walletPackage").takeIf { it.isNotEmpty() },
                appLabel = json.optString("walletApp").takeIf { it.isNotEmpty() },
            )

        /**
         * The profile and authorization a single session becomes. Their IDs are derived from the
         * session, so a migration that is repeated after an interruption produces the same ones,
         * and a connection bound to the migrated profile on the first attempt still is after the
         * second.
         */
        fun fromSession(session: StoredSession): WalletProfiles {
            val wallet = session.wallet
            val seed =
                "${wallet.address}|${wallet.network.name}|${session.route.packageName.orEmpty()}"
            val authorization =
                StoredAuthorization(
                    id = "wa_" + digest("legacy-authorization|$seed"),
                    token = session.authToken,
                    network = wallet.network,
                    route = session.route,
                )
            val profile =
                WalletProfile(
                    id = "wp_" + digest("legacy-profile|$seed"),
                    address = wallet.address,
                    network = wallet.network,
                    accountLabel = wallet.label,
                    route = session.route,
                    authorizationId = authorization.id,
                    connectedAt = wallet.selectedAt,
                    networkConfirmed = wallet.networkConfirmed,
                )
            return WalletProfiles(listOf(profile), listOf(authorization), profile.id)
        }

        fun digest(text: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .take(12)
                .joinToString("") { "%02x".format(it) }

        fun encodeProfiles(state: WalletProfiles): String =
            JSONObject()
                .put("version", PROFILES_FORMAT)
                .putOpt("legacyProfileId", state.legacyProfileId)
                .put(
                    "profiles",
                    JSONArray().apply {
                        state.profiles.forEach { profile ->
                            put(
                                JSONObject()
                                    .put("id", profile.id)
                                    .put("address", profile.address)
                                    .put("network", profile.network.name)
                                    .putOpt("label", profile.label)
                                    .putOpt("accountLabel", profile.accountLabel)
                                    .put("authorizationId", profile.authorizationId)
                                    .put("connectedAt", profile.connectedAt.toString())
                                    .put("networkConfirmed", profile.networkConfirmed)
                                    .put("authorized", profile.authorized)
                            )
                        }
                    },
                )
                .put(
                    "authorizations",
                    JSONArray().apply {
                        state.authorizations.forEach { held ->
                            put(
                                JSONObject()
                                    .put("id", held.id)
                                    .put("authToken", held.token)
                                    .put("network", held.network.name)
                                    .putOpt("walletUriBase", held.route.uriBase)
                                    .putOpt("walletPackage", held.route.packageName)
                                    .putOpt("walletApp", held.route.appLabel)
                            )
                        }
                    },
                )
                .toString()

        fun decodeProfiles(text: String): WalletProfiles? {
            val json = JSONObject(text)
            if (json.getInt("version") != PROFILES_FORMAT) return null
            val authorizations =
                json.getJSONArray("authorizations").objects().mapNotNull { held ->
                    val token = held.optString("authToken").takeIf { it.isNotEmpty() }
                    token?.let {
                        StoredAuthorization(
                            id = held.getString("id"),
                            token = it,
                            network = WalletNetwork.valueOf(held.getString("network")),
                            route = route(held),
                        )
                    }
                }
            val ids = authorizations.map { it.id }.toSet()
            val profiles =
                json.getJSONArray("profiles").objects().mapNotNull { profile ->
                    val authorizationId = profile.getString("authorizationId")
                    // A profile whose authorization is gone is not one this phone can sign with,
                    // and it can't be: the two are written together.
                    if (authorizationId !in ids) return@mapNotNull null
                    WalletProfile(
                        id = profile.getString("id"),
                        address = profile.getString("address"),
                        network = WalletNetwork.valueOf(profile.getString("network")),
                        label = profile.optText("label"),
                        accountLabel = profile.optText("accountLabel"),
                        authorizationId = authorizationId,
                        connectedAt = Instant.parse(profile.getString("connectedAt")),
                        networkConfirmed = profile.optBoolean("networkConfirmed", true),
                        authorized = profile.optBoolean("authorized", true),
                    )
                }
            return WalletProfiles(profiles, authorizations, json.optText("legacyProfileId"))
        }

        fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

        fun JSONObject.optText(name: String): String? =
            if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

        fun decodeLegacy(text: String): SelectedWallet? {
            val json = JSONObject(text)
            if (json.getInt("version") != LEGACY_FORMAT) return null
            return wallet(json)
        }

        fun wallet(json: JSONObject): SelectedWallet =
            SelectedWallet(
                address = json.getString("address"),
                network = WalletNetwork.valueOf(json.getString("network")),
                label = json.optString("label").takeIf { it.isNotEmpty() },
                selectedAt = Instant.parse(json.getString("selectedAt")),
                networkConfirmed = json.optBoolean("networkConfirmed", true),
            )
    }
}
