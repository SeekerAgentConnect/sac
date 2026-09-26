package io.github.brrenat.seekervault.push.storage

import android.util.AtomicFile
import io.github.brrenat.seekervault.push.RelayEnrollment
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * What this phone remembers about the gateway push relay (SEE-144): which installation it is, which
 * authorization belongs to which direct connection, and which revocations it still owes.
 *
 * # Why it is sealed, and why it is not the credential vault
 *
 * The installation secret is what proves this phone is this installation: everything that can
 * change where its wake-ups go needs it. So it is encrypted with the same Keystore key and the same
 * AES-256-GCM shape a phone credential is, rather than left in a file beside the state.
 *
 * It is a second small sealed file rather than a row in [CredentialVault] because that one is keyed
 * by connection ID *by design* — the ID is both the filename and the cipher's associated data,
 * which is exactly what stops one connection's credential from becoming another's. An installation
 * is not a connection: it is one per app installation, it outlives every pairing, and giving it a
 * connection's shape to get into that file would be making it look like the thing it is
 * deliberately not.
 *
 * # What is in it, and what is not
 *
 * No FCM registration. The phone does not store its own target — Firebase holds it and hands it
 * back on every callback — and neither does this. No push handle either: a handle belongs to the
 * server it was issued for and is given to it over the authenticated direct connection; keeping a
 * second copy here would be a second place to leak one from, and re-authorizing is one call.
 *
 * File format: version (1 byte), IV length (1 byte), IV, then the ciphertext with its 16-byte tag.
 */
class RelayStore(private val dir: File, private val key: () -> SecretKey) {

    /** Everything this phone remembers, as one value that is written and read whole. */
    data class State(
        val enrollment: RelayEnrollment?,
        /** connection ID to the binding ID authorized for it at the gateway. */
        val bindings: Map<String, String>,
        /**
         * Revocations this phone owes the gateway and has not managed to make.
         *
         * They are kept because a disconnection must be final from the owner's point of view even
         * when it happened on a train: the connection is gone locally at once, and the gateway is
         * told the next time this phone can reach it. Without this, a phone that was offline when
         * its owner disconnected would leave a server able to wake it until the binding expired.
         */
        val owed: Set<String>,
    ) {
        companion object {
            val EMPTY = State(enrollment = null, bindings = emptyMap(), owed = emptySet())
        }
    }

    /** What is stored, or the empty state when nothing is, or when it cannot be decrypted. */
    fun read(): State {
        val bytes =
            try {
                file().readFully()
            } catch (e: IOException) {
                return State.EMPTY
            }
        val json =
            try {
                val buffer = ByteBuffer.wrap(bytes)
                if (buffer.get() != VERSION) return State.EMPTY
                val iv = ByteArray(buffer.get().toInt()).also { buffer.get(it) }
                val sealed = ByteArray(buffer.remaining()).also { buffer.get(it) }
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
                cipher.updateAAD(ASSOCIATED_DATA)
                JSONObject(String(cipher.doFinal(sealed), Charsets.UTF_8))
            } catch (e: GeneralSecurityException) {
                // A key the Keystore no longer has — a reset, or a restored backup on another
                // device. The enrollment is unreadable, so this phone enrolls again, which is the
                // same path a lost gateway database takes.
                return State.EMPTY
            } catch (e: RuntimeException) {
                return State.EMPTY // a truncated or malformed file
            }
        val installation = json.optString(INSTALLATION)
        val secret = json.optString(SECRET)
        val bindings = buildMap {
            val held = json.optJSONObject(BINDINGS) ?: JSONObject()
            for (connectionId in held.keys()) put(connectionId, held.optString(connectionId))
        }
        val owed = buildSet {
            val held = json.optJSONArray(OWED)
            if (held != null) for (index in 0 until held.length()) add(held.optString(index))
        }
        return State(
            enrollment =
                if (installation.isEmpty() || secret.isEmpty()) null
                else RelayEnrollment(installation, secret),
            bindings = bindings,
            owed = owed,
        )
    }

    /** Replaces everything. The whole state is one value, so a partial write is not a state. */
    fun write(state: State) {
        // Every value put here is one org.json will serialize as itself. A raw Kotlin list is not:
        // Android's JSONObject writes an unrecognized value as its toString(), so a set of owed
        // revocations stored that way comes back as the *string* "[binding-1]" and reads as
        // nothing at all — a revocation this phone owed, silently forgotten on the next launch.
        val owed = JSONArray()
        for (binding in state.owed) owed.put(binding)
        val json =
            JSONObject()
                .put(INSTALLATION, state.enrollment?.installation.orEmpty())
                .put(SECRET, state.enrollment?.secret.orEmpty())
                .put(BINDINGS, JSONObject(state.bindings.toMap()))
                .put(OWED, owed)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The Keystore picks the IV; a caller-chosen IV isn't allowed for its GCM keys.
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(ASSOCIATED_DATA)
        val sealed = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val bytes =
            ByteBuffer.allocate(2 + iv.size + sealed.size)
                .put(VERSION)
                .put(iv.size.toByte())
                .put(iv)
                .put(sealed)
                .array()
        dir.mkdirs()
        val file = file()
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (e: IOException) {
            file.failWrite(stream)
            throw e
        }
    }

    /** Forgets the enrollment entirely. What it authorized is the gateway's to expire. */
    fun clear() {
        file().delete()
    }

    private fun file() = AtomicFile(File(dir, NAME))

    private companion object {
        const val NAME = "relay"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val VERSION: Byte = 1
        const val INSTALLATION = "installation"
        const val SECRET = "secret"
        const val BINDINGS = "bindings"
        const val OWED = "owed"
        val ASSOCIATED_DATA = "seekervault/relay/v1".toByteArray(Charsets.UTF_8)
    }
}
