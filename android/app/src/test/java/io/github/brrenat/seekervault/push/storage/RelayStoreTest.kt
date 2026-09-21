package io.github.brrenat.seekervault.push.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.push.RelayEnrollment
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the relay's enrollment survives (SEE-144), and what it does not.
 *
 * The whole state is written and read as one value, so the thing worth pinning is that every part
 * of it comes back — including the set of revocations this phone owes a gateway it could not reach,
 * which is the part with a deadline the owner cares about. A revocation that did not survive a
 * restart would leave a server able to wake a phone its owner disconnected, and nothing would say
 * so.
 */
@RunWith(AndroidJUnit4::class)
class RelayStoreTest {

    @Test
    fun everyPartOfTheStateSurvivesARestart() {
        val store = store()
        val state =
            RelayStore.State(
                enrollment = RelayEnrollment("installation-1", "secret-1"),
                bindings = mapOf(A to "binding-a", B to "binding-b"),
                owed = setOf("binding-c", "binding-d"),
            )
        store.write(state)

        val back = store().read()
        assertEquals("installation-1", back.enrollment?.installation)
        assertEquals("secret-1", back.enrollment?.secret)
        assertEquals(mapOf(A to "binding-a", B to "binding-b"), back.bindings)
        // The one this file's own format got wrong once: a raw list is written by org.json as its
        // toString(), so it reads back as nothing and a revocation this phone owed is forgotten.
        assertEquals(setOf("binding-c", "binding-d"), back.owed)
    }

    @Test
    fun anEmptyStateIsAStateRatherThanAFailure() {
        assertEquals(RelayStore.State.EMPTY, store().read())
        val store = store()
        store.write(RelayStore.State.EMPTY)
        val back = store.read()
        assertNull(back.enrollment)
        assertTrue(back.bindings.isEmpty())
        assertTrue(back.owed.isEmpty())
    }

    @Test
    fun aStateSealedUnderAnotherKeyIsNotReadable() {
        val dir = Files.createTempDirectory("relay").toFile()
        RelayStore(dir) { KEY }
            .write(
                RelayStore.State(
                    enrollment = RelayEnrollment("installation-1", "secret-1"),
                    bindings = emptyMap(),
                    owed = emptySet(),
                )
            )
        // A restored backup on another device, or a Keystore reset. The enrollment is unreadable,
        // so this phone enrolls again — the same path a gateway that lost its database takes, and
        // not a crash on the first launch after a restore.
        val elsewhere = RelayStore(dir) { other() }.read()
        assertEquals(RelayStore.State.EMPTY, elsewhere)
    }

    @Test
    fun nothingItHoldsIsReadableBesideTheFile() {
        val dir = Files.createTempDirectory("relay").toFile()
        RelayStore(dir) { KEY }
            .write(
                RelayStore.State(
                    enrollment = RelayEnrollment("installation-1", "the-installation-secret"),
                    bindings = mapOf(A to "binding-a"),
                    owed = emptySet(),
                )
            )
        val bytes = File(dir, "relay").readBytes().toString(Charsets.ISO_8859_1)
        // The secret proves this device is this installation: everything that can change where its
        // wake-ups go needs it, so it is sealed rather than sitting in a file beside the state.
        for (plain in listOf("the-installation-secret", "installation-1", "binding-a")) {
            assertFalse(plain, plain in bytes)
        }
    }

    @Test
    fun clearingForgetsTheEnrollmentEntirely() {
        val store = store()
        store.write(
            RelayStore.State(RelayEnrollment("installation-1", "secret-1"), emptyMap(), emptySet())
        )
        store.clear()
        assertEquals(RelayStore.State.EMPTY, store.read())
    }

    private fun store(): RelayStore = RelayStore(directory) { KEY }

    private val directory: File by lazy { Files.createTempDirectory("relay").toFile() }

    private companion object {
        const val A = "00000000-0000-4000-8000-00000000000a"
        const val B = "00000000-0000-4000-8000-00000000000b"
        val KEY: SecretKey = key()

        fun key(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        fun other(): SecretKey = key()
    }
}
