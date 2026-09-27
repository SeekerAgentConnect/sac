package io.github.brrenat.seekervault.access

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The device keys a restricted feed binds (SEE-156): one P-256 key per feed connection, generated
 * on this phone and never exported.
 *
 * It is what makes an installation this installation. A device label, or an identifier the phone
 * reports, is a claim anyone could repeat; a signature from the key the owner's wallet bound is a
 * proof. One key per feed rather than one per phone, so two publishers cannot compare notes about
 * the same device through the gateway either.
 */
interface DeviceKeys {
    /** The key's X.509 SubjectPublicKeyInfo encoding, creating the key on first use. */
    fun publicKey(alias: String): ByteArray

    /** An ECDSA-over-SHA-256 signature, DER encoded. */
    fun sign(alias: String, message: ByteArray): ByteArray

    /** Forgets the key, when the connection it belongs to is removed. */
    fun delete(alias: String)

    companion object {
        /** The alias for one connection's key. */
        fun aliasFor(connectionId: String): String = "seekervault.feed-access.v1.$connectionId"
    }
}

/**
 * Keys in the Android Keystore: generated inside it, usable only for signing, and never readable by
 * this app or anything else. A restored backup is a different device, and has to ask again.
 */
class KeystoreDeviceKeys : DeviceKeys {
    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override fun publicKey(alias: String): ByteArray {
        val store = keyStore()
        val existing = store.getCertificate(alias)?.publicKey
        if (existing != null) return existing.encoded
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
        generator.initialize(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        return generator.generateKeyPair().public.encoded
    }

    override fun sign(alias: String, message: ByteArray): ByteArray {
        val key = keyStore().getKey(alias, null) as? PrivateKey
        checkNotNull(key) { "no device key for this feed" }
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(message)
            sign()
        }
    }

    override fun delete(alias: String) {
        val store = keyStore()
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
    }
}

/** Keys held in memory, for tests on the JVM, where there is no Keystore. */
class SoftwareDeviceKeys : DeviceKeys {
    private val keys = mutableMapOf<String, java.security.KeyPair>()

    @Synchronized
    private fun pair(alias: String): java.security.KeyPair =
        keys.getOrPut(alias) {
            KeyPairGenerator.getInstance("EC")
                .apply { initialize(ECGenParameterSpec("secp256r1")) }
                .generateKeyPair()
        }

    override fun publicKey(alias: String): ByteArray = pair(alias).public.encoded

    override fun sign(alias: String, message: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(pair(alias).private)
            update(message)
            sign()
        }

    @Synchronized
    override fun delete(alias: String) {
        keys.remove(alias)
    }
}
