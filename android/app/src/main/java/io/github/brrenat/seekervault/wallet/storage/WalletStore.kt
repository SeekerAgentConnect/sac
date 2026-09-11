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
 * What the phone keeps about the owner's wallet (docs/security.md#local-storage-and-recovery):
 * - the selection, as JSON in [dir]: a public address, its network, and when it was chosen. It is
 *   published to every paired sidecar, so it is not a secret.
 * - the wallet's authorization token, encrypted with AES-256-GCM under the key [key] returns, in
 *   [secretDir], which the app keeps in `noBackupFilesDir`. It is a secret: it never reaches a
 *   sidecar, a log, or a backup.
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
    /** The wallet the owner selected, or null when none is stored or the file is damaged. */
    fun selected(): SelectedWallet? {
        val file = AtomicFile(File(dir, SELECTION))
        return try {
            decode(String(file.readFully(), Charsets.UTF_8))
        } catch (e: IOException) {
            null
        } catch (e: JSONException) {
            null
        } catch (e: IllegalArgumentException) {
            null // an unknown network or a malformed timestamp
        }
    }

    /**
     * Stores the selection and its authorization together; neither is written without the other.
     */
    fun put(wallet: SelectedWallet, authToken: String) {
        write(secretDir, AUTHORIZATION, seal(authToken))
        write(dir, SELECTION, encode(wallet).toByteArray(Charsets.UTF_8))
    }

    /** The stored authorization, or null when there is none or it can't be decrypted. */
    fun authorization(): String? {
        val bytes =
            try {
                AtomicFile(File(secretDir, AUTHORIZATION)).readFully()
            } catch (e: IOException) {
                return null
            }
        return try {
            val buffer = ByteBuffer.wrap(bytes)
            if (buffer.get() != VERSION) return null
            val iv = ByteArray(buffer.get().toInt()).also { buffer.get(it) }
            val sealed = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(ASSOCIATED_DATA)
            String(cipher.doFinal(sealed), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: RuntimeException) {
            null // a truncated file: BufferUnderflowException, NegativeArraySizeException
        }
    }

    /** Forgets the wallet: the authorization first, then the selection. */
    fun clear() {
        AtomicFile(File(secretDir, AUTHORIZATION)).delete()
        AtomicFile(File(dir, SELECTION)).delete()
    }

    private fun seal(secret: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The Keystore picks the IV; a caller-chosen IV isn't allowed for its GCM keys.
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(ASSOCIATED_DATA)
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
        const val SELECTION = "wallet.json"
        const val AUTHORIZATION = "wallet-authorization"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val VERSION: Byte = 1
        const val FORMAT = 1
        val ASSOCIATED_DATA = "seekervault/wallet-authorization/v1".toByteArray(Charsets.UTF_8)

        fun encode(wallet: SelectedWallet): String =
            JSONObject()
                .put("version", FORMAT)
                .put("address", wallet.address)
                .put("network", wallet.network.name)
                .putOpt("label", wallet.label)
                .put("selectedAt", wallet.selectedAt.toString())
                .put("networkConfirmed", wallet.networkConfirmed)
                .toString()

        fun decode(text: String): SelectedWallet? {
            val json = JSONObject(text)
            if (json.getInt("version") != FORMAT) return null
            return SelectedWallet(
                address = json.getString("address"),
                network = WalletNetwork.valueOf(json.getString("network")),
                label = json.optString("label").takeIf { it.isNotEmpty() },
                selectedAt = Instant.parse(json.getString("selectedAt")),
                networkConfirmed = json.optBoolean("networkConfirmed", true),
            )
        }
    }
}
