package io.github.brrenat.seekervault.connections.storage

import android.util.AtomicFile
import io.github.brrenat.seekervault.connections.isConnectionId
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The phone credentials (docs/security.md#local-storage-and-recovery), encrypted with AES-256-GCM
 * under the key that [key] returns. In the app that is [AndroidKeystoreKey], which never leaves the
 * Keystore. There's one file per connection, `<dir>/<connection ID>`, and the app keeps [dir] in
 * `noBackupFilesDir`. The connection ID is the cipher's associated data, so a file copied under
 * another connection's name doesn't decrypt: one connection's credential can't become another's.
 *
 * File format: version (1 byte), IV length (1 byte), IV, then the ciphertext with its 16-byte tag.
 */
class CredentialVault(private val dir: File, private val key: () -> SecretKey) {
    fun put(connectionId: String, credential: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The Keystore picks the IV; a caller-chosen IV isn't allowed for its GCM keys.
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(associatedData(connectionId))
        val sealed = cipher.doFinal(credential.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val bytes =
            ByteBuffer.allocate(2 + iv.size + sealed.size)
                .put(VERSION)
                .put(iv.size.toByte())
                .put(iv)
                .put(sealed)
                .array()
        val file = atomicFile(connectionId)
        dir.mkdirs()
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (e: IOException) {
            file.failWrite(stream)
            throw e
        }
    }

    /**
     * The credential, or null when there's none, or when it can't be decrypted: a damaged file,
     * another connection's file, or a key the Keystore no longer has (a reset, or another device).
     */
    fun get(connectionId: String): String? {
        val bytes =
            try {
                atomicFile(connectionId).readFully()
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
            cipher.updateAAD(associatedData(connectionId))
            String(cipher.doFinal(sealed), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: RuntimeException) {
            null // a truncated file: BufferUnderflowException, NegativeArraySizeException
        }
    }

    fun contains(connectionId: String): Boolean =
        isConnectionId(connectionId) && File(dir, connectionId).isFile

    fun delete(connectionId: String) {
        atomicFile(connectionId).delete()
    }

    /** The connection IDs that have a credential file. */
    fun ids(): Set<String> = dir.list().orEmpty().filter { isConnectionId(it) }.toSet()

    // The ID names the file, so it must be the UUID the sidecar assigned, never a path.
    private fun atomicFile(connectionId: String): AtomicFile {
        require(isConnectionId(connectionId)) { "not a connection ID" }
        return AtomicFile(File(dir, connectionId))
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val VERSION: Byte = 1

        fun associatedData(connectionId: String) =
            "seekervault/credential/v1/$connectionId".toByteArray(Charsets.UTF_8)
    }
}
