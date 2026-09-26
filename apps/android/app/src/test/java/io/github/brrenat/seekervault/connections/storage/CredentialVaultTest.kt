package io.github.brrenat.seekervault.connections.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.connections.newSecret
import io.github.brrenat.seekervault.connections.softwareKey
import java.io.File
import java.security.GeneralSecurityException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * The credential vault's encryption and isolation. The key is a software AES key here; the real
 * Keystore key runs in `CredentialVaultDeviceTest` on a device or emulator.
 */
@RunWith(AndroidJUnit4::class)
class CredentialVaultTest {
    @get:Rule val folder = TemporaryFolder()

    private val key = softwareKey()
    private val dir by lazy { File(folder.root, "no_backup/credentials") }
    private val vault by lazy { CredentialVault(dir) { key } }
    private val a = "0b8e2b1c-3f4d-4e5a-9b6c-7d8e9f0a1b2c"
    private val b = "5d3c8f0e-2b7a-4c1d-9e6f-0a1b2c3d4e5f"
    private val credentialA = newSecret()
    private val credentialB = newSecret()

    @Test
    fun readsBackWhatItStored() {
        vault.put(a, credentialA)
        vault.put(b, credentialB)
        assertEquals(credentialA, vault.get(a))
        assertEquals(credentialB, vault.get(b))
        assertEquals(setOf(a, b), vault.ids())
        // Another vault over the same files, as after a restart.
        assertEquals(credentialA, CredentialVault(dir) { key }.get(a))
    }

    @Test
    fun keepsNoPlaintextOnDisk() {
        vault.put(a, credentialA)
        val bytes = folder.root.walk().filter { it.isFile }.map { it.readBytes() }.toList()
        assertFalse(bytes.isEmpty())
        for (content in bytes) {
            assertFalse(String(content, Charsets.ISO_8859_1).contains(credentialA))
        }
    }

    @Test
    fun encryptsEachWriteWithAFreshIv() {
        vault.put(a, credentialA)
        val first = File(dir, a).readBytes()
        vault.put(a, credentialA)
        assertNotEquals(first.toList(), File(dir, a).readBytes().toList())
    }

    @Test
    fun deletingOneCredentialLeavesTheOther() {
        vault.put(a, credentialA)
        vault.put(b, credentialB)
        vault.delete(a)
        assertNull(vault.get(a))
        assertFalse(vault.contains(a))
        assertEquals(credentialB, vault.get(b))
    }

    @Test
    fun aCredentialCopiedToAnotherConnectionDoesNotDecrypt() {
        vault.put(a, credentialA)
        File(dir, a).copyTo(File(dir, b))
        assertNull(vault.get(b))
        assertEquals(credentialA, vault.get(a))
    }

    @Test
    fun anotherKeyCannotReadIt() {
        // As on another device, or after the Keystore lost its key.
        vault.put(a, credentialA)
        assertNull(CredentialVault(dir) { softwareKey() }.get(a))
    }

    @Test
    fun aDamagedFileReadsAsNoCredential() {
        vault.put(a, credentialA)
        val file = File(dir, a)
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size - 1))
        assertNull(vault.get(a))
        file.writeBytes(bytes.also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() })
        assertNull(vault.get(a))
        file.writeBytes(ByteArray(0))
        assertNull(vault.get(a))
    }

    @Test
    fun namesFilesOnlyByConnectionIds() {
        assertThrows(IllegalArgumentException::class.java) { vault.put("../escape", credentialA) }
        assertThrows(IllegalArgumentException::class.java) { vault.put(a.uppercase(), credentialA) }
        assertFalse(vault.contains("../escape"))
        assertEquals(emptySet<String>(), vault.ids())
    }

    @Test
    fun aKeyFailureStoresNothing() {
        val broken = CredentialVault(dir) { throw GeneralSecurityException("no key") }
        assertThrows(GeneralSecurityException::class.java) { broken.put(a, credentialA) }
        assertEquals(emptySet<String>(), vault.ids())
    }
}
