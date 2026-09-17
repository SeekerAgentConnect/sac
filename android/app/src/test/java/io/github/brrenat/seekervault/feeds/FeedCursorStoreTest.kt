package io.github.brrenat.seekervault.feeds

import io.github.brrenat.seekervault.feeds.storage.FeedCursorStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Where a listener left off (SEE-91).
 *
 * The store holds progress and no content, so the interesting cases are all about refusing to hold
 * a position that would mean something wrong: an offset with no epoch counts in a history nobody
 * can name, and a document from a newer version of the app is guessed at by nobody.
 */
@RunWith(RobolectricTestRunner::class)
class FeedCursorStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val store by lazy { FeedCursorStore(File(folder.root, "feeds")) }

    @Test
    fun aPositionSurvivesBeingWrittenAndReadBack() {
        store.put(
            FeedCursorStore.Progress(
                serverId = SERVER,
                cursor = FeedCursor("epoch-1", 42),
                sequence = 7,
            )
        )

        val held = store.get(SERVER)

        assertEquals(FeedCursor("epoch-1", 42), held?.cursor)
        assertEquals(7L, held?.sequence)
        assertEquals(setOf(SERVER), store.serverIds())
    }

    @Test
    fun aFeedWithNoProgressIsNothingRatherThanAZeroPosition() {
        assertNull(store.get(SERVER))
        // A cursor-less record is a real state: the snapshot boundary is known and the broker's
        // position is not, which is what a listener holds after reading a feed it never streamed.
        store.put(FeedCursorStore.Progress(serverId = SERVER, cursor = null, sequence = 3))
        assertNull(store.get(SERVER)?.cursor)
        assertEquals(3L, store.get(SERVER)?.sequence)
    }

    @Test
    fun anOffsetWithoutAnEpochIsNotAPosition() {
        assertThrows(IllegalArgumentException::class.java) {
            store.put(FeedCursorStore.Progress(serverId = SERVER, cursor = FeedCursor("", 42)))
        }
    }

    @Test
    fun aFileNameIsNeverSomethingACallerChose() {
        assertThrows(IllegalArgumentException::class.java) {
            store.put(FeedCursorStore.Progress(serverId = "../../etc/passwd"))
        }
        assertNull(store.get("../../etc/passwd"))
        store.delete("../../etc/passwd") // and deleting one is not a way to reach a path either
    }

    @Test
    fun aDocumentFromANewerVersionIsRefusedRatherThanGuessedAt() {
        val file = File(folder.root, "feeds/$SERVER.json")
        file.parentFile?.mkdirs()
        file.writeText("""{"version":2,"serverId":"$SERVER","epoch":"e","offset":9}""")

        // Refusing costs one snapshot: the listener starts with no cursor, the broker cannot prove
        // continuity, and the authoritative read fills it in.
        assertNull(store.get(SERVER))
    }

    @Test
    fun rubbishOnDiskIsNothingRatherThanACrash() {
        val file = File(folder.root, "feeds/$SERVER.json")
        file.parentFile?.mkdirs()
        file.writeText("not json at all")

        assertNull(store.get(SERVER))
    }

    @Test
    fun removingAFeedTakesItsProgressWithIt() {
        store.put(FeedCursorStore.Progress(serverId = SERVER, cursor = FeedCursor("e", 1)))
        store.delete(SERVER)

        assertNull(store.get(SERVER))
        assertTrue(store.serverIds().isEmpty())
    }

    private companion object {
        const val SERVER = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
    }
}
