package io.github.brrenat.seekervault.connections.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The key that protects phone credentials: AES-256 for GCM, created in the Android Keystore on
 * first use. Its material never leaves the Keystore, so it can't be exported, backed up, or moved
 * to another device. It protects credentials; it is not a wallet key.
 */
object AndroidKeystoreKey {
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "seekervault.credentials.v1"

    @Synchronized
    fun get(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let {
            return it
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
