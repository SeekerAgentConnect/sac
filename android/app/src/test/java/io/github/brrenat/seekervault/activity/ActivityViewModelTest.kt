package io.github.brrenat.seekervault.activity

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.activity.storage.ActivityStore
import io.github.brrenat.seekervault.connections.Answer
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** The Activity screens' state. It reads the record and does nothing else. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ActivityViewModelTest {
    @get:Rule val folder = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = UnconfinedTestDispatcher(scheduler)
    private val dir by lazy { File(folder.root, "files/activity") }
    private val store by lazy { ActivityStore(dir) }
    private val log by lazy { ActivityLog(store) { Instant.parse("2026-09-11T12:05:00Z") } }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = ActivityViewModel(log, dispatcher)

    @Test
    fun readsWhatIsStoredWhenItOpens() {
        store.put(record())
        store.put(
            record(
                requestId = OTHER_REQUEST,
                kind = ActivityKind.Acknowledgement,
                outcome = ActivityOutcome.Acknowledged,
                signature = null,
                checkedWith = null,
                answeredAt = Instant.parse("2026-09-12T09:00:00Z"),
            )
        )
        val state = viewModel().state.value
        assertTrue(state.loaded)
        assertFalse(state.unreadable)
        // Newest first.
        assertEquals(listOf(OTHER_REQUEST, REQUEST), state.records.map { it.requestId })
    }

    @Test
    fun staysUsableWhenTheHistoryCannotBeRead() {
        store.put(record())
        val model = viewModel()
        assertEquals(1, model.state.value.records.size)
        // The directory becomes a file: a read that can't work, the way a damaged phone gives one.
        dir.deleteRecursively()
        dir.parentFile?.mkdirs()
        dir.writeText("not a directory")
        model.refresh()
        val state = model.state.value
        // Nothing crashed, the records already read are still on screen, and the screen says the
        // list may not be the whole history rather than presenting it as one.
        assertTrue(state.loaded)
        assertTrue(state.unreadable)
        assertEquals(1, state.records.size)

        // And it recovers: a read that works again drops the warning.
        dir.delete()
        dir.mkdirs()
        model.refresh()
        assertFalse(model.state.value.unreadable)
    }

    @Test
    fun saysSoWhenNothingCanOpenALink() {
        val model = viewModel()
        assertFalse(model.state.value.linkFailed)
        model.linkFailed()
        assertTrue(model.state.value.linkFailed)
        model.messageShown()
        assertFalse(model.state.value.linkFailed)
    }

    @Test
    fun clearsEverythingWhenTheOwnerAsks() {
        log.record(
            result(ackRequest(), answer = Answer.Acknowledge, approved = false),
            connection(),
        )
        val model = viewModel()
        assertEquals(1, model.state.value.records.size)
        model.clear()
        assertEquals(emptyList<ActivityRecord>(), model.state.value.records)
        assertEquals(emptyList<ActivityRecord>(), ActivityStore(dir).list())
    }
}
