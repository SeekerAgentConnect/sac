package io.github.brrenat.seekervault

import android.content.Context
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.connections.storage.AndroidKeystoreKey
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKeyFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The credential vault with the real Android Keystore key, which the JVM tests can't have. Runs on
 * a device or emulator, through `pnpm test:hello --device` or `connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class CredentialVaultDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dir = File(context.noBackupFilesDir, "credential-vault-device-test")

    @After fun clean() = check(dir.deleteRecursively())

    @Test
    fun encryptsCredentialsUnderANonExportableKeystoreKey() {
        val vault = CredentialVault(dir, AndroidKeystoreKey::get)
        val id = UUID.randomUUID().toString()
        val credential =
            Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        vault.put(id, credential)
        assertEquals(credential, vault.get(id))
        assertEquals(credential, CredentialVault(dir, AndroidKeystoreKey::get).get(id))
        val file = File(dir, id)
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains(credential))

        val key = AndroidKeystoreKey.get()
        assertNull("the key material must stay in the Keystore", key.encoded)
        val info =
            SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
                .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        assertEquals(256, info.keySize)
        assertEquals(
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            info.purposes,
        )

        // A credential copied under another connection's name doesn't decrypt.
        val other = UUID.randomUUID().toString()
        file.copyTo(File(dir, other))
        assertNull(vault.get(other))
    }
}
