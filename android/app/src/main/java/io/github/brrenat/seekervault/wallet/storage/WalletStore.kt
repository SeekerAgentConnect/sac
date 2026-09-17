package io.github.brrenat.seekervault.wallet.storage

import android.util.AtomicFile
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.WalletNetwork
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONException
import org.json.JSONObject

/**
 * The owner's wallet as this phone holds it: the selection they made and the authorization the
 * wallet issued for exactly that account, which belong together and are never read apart.
 */
data class StoredSession(val wallet: SelectedWallet, val authToken: String) {
    override fun toString() = "StoredSession(wallet=$wallet, authToken=<redacted>)"
}

/**
 * What the phone keeps about the owner's wallet (docs/security.md#local-storage-and-recovery): one
 * record holding the selection — a public address, its network, and when it was chosen — together
 * with the wallet's authorization token for that account, encrypted with AES-256-GCM under the key
 * [key] returns, in [secretDir], which the app keeps in `noBackupFilesDir`.
 *
 * It is one record because the two halves are one fact (SEE-84). They used to be two files, each
 * written atomically on its own but not as a pair, so an interruption between them could leave this
 * phone holding a token that belonged to another account. A single sealed record can't: an
 * interrupted replacement leaves the record that was there, whole, and a half-written legacy pair
 * is refused rather than used.
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
     * The wallet session this phone holds, or null when there is none, when it can't be decrypted,
     * or when all that is left of one is half of the storage this app used before (SEE-84).
     */
    fun session(): StoredSession? = stored() ?: migrated()

    /** The wallet the owner selected, or null when no whole session is stored. */
    fun selected(): SelectedWallet? = session()?.wallet

    /** The stored authorization, or null when no whole session is stored. */
    fun authorization(): String? = session()?.authToken

    /**
     * Stores the selection and its authorization as one record, so nothing can ever read one
     * without the other. Anything the older format left behind goes with it.
     */
    fun put(wallet: SelectedWallet, authToken: String) {
        write(secretDir, SESSION, seal(encode(wallet, authToken)))
        forgetLegacy()
    }

    /** Forgets the wallet: the record, and anything the older format left behind. */
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

    private fun seal(secret: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The Keystore picks the IV; a caller-chosen IV isn't allowed for its GCM keys.
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(SESSION_DATA)
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
        const val SESSION = "wallet-session"
        const val LEGACY_SELECTION = "wallet.json"
        const val LEGACY_AUTHORIZATION = "wallet-authorization"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val VERSION: Byte = 1
        /** The record's own format, which is 2 from SEE-84's single sealed session. */
        const val FORMAT = 2
        const val LEGACY_FORMAT = 1
        val SESSION_DATA = "seekervault/wallet-session/v2".toByteArray(Charsets.UTF_8)
        val LEGACY_AUTHORIZATION_DATA =
            "seekervault/wallet-authorization/v1".toByteArray(Charsets.UTF_8)

        fun encode(wallet: SelectedWallet, authToken: String): String =
            JSONObject()
                .put("version", FORMAT)
                .put("address", wallet.address)
                .put("network", wallet.network.name)
                .putOpt("label", wallet.label)
                .put("selectedAt", wallet.selectedAt.toString())
                .put("networkConfirmed", wallet.networkConfirmed)
                .put("authToken", authToken)
                .toString()

        fun decode(text: String): StoredSession? {
            val json = JSONObject(text)
            if (json.getInt("version") != FORMAT) return null
            val authToken = json.optString("authToken").takeIf { it.isNotEmpty() } ?: return null
            return StoredSession(wallet(json), authToken)
        }

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
