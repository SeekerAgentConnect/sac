package io.github.brrenat.seekervault.inbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.connections.Answer
import io.github.brrenat.seekervault.connections.ConnectionRepository
import io.github.brrenat.seekervault.connections.Delivery
import io.github.brrenat.seekervault.connections.FakeConnectionGateway
import io.github.brrenat.seekervault.connections.GatewayException
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.connections.softwareKey
import io.github.brrenat.seekervault.connections.storage.ConnectionStore
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import io.github.brrenat.seekervault.connections.storage.ResultStore
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
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

/** The inbox's state and actions against fake sidecars. Coroutines run eagerly. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class InboxViewModelTest {
    @get:Rule val folder = TemporaryFolder()

    private val gateway = FakeConnectionGateway()
    private val server = gateway.serve(URL)
    private val other = gateway.serve(OTHER_URL)
    private val key = softwareKey()
    private val repository by lazy {
        ConnectionRepository(
            store = ConnectionStore(File(folder.root, "connections")),
            vault = CredentialVault(File(folder.root, "credentials")) { key },
            results = ResultStore(File(folder.root, "results")),
            gateway = gateway,
            deviceName = "Seeker",
            io = Dispatchers.Unconfined,
        )
    }

    @Before fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun resetMain() = Dispatchers.resetMain()

    private fun pendingRequest(
        target: FakeConnectionGateway.Server = server,
        url: String = URL,
    ): RequestKey = runBlocking {
        val connection = repository.pair(target.issue(url))
        val request = target.addPending(connection.id)
        repository.refresh(connection.id)
        RequestKey(connection.id, request.ref.requestId)
    }

    @Test
    fun aRapidSecondTapSendsNothingMore() {
        val request = pendingRequest()
        val release = CompletableDeferred<Unit>()
        gateway.beforeSubmit = { release.await() }
        val viewModel = InboxViewModel(repository)
        viewModel.answer(request, Answer.Acknowledge)
        assertEquals(setOf(request), viewModel.state.value.sending)
        viewModel.answer(request, Answer.Acknowledge)
        viewModel.answer(request, Answer.Reject)
        release.complete(Unit)
        assertEquals(1, gateway.submits.size)
        assertTrue(gateway.submits.single().second.hasAcknowledgement())
        assertTrue(viewModel.state.value.sending.isEmpty())
        assertEquals(Delivery.Accepted, viewModel.state.value.inbox.result(request)?.delivery)
        // Tapping again on the answered request changes nothing either.
        viewModel.answer(request, Answer.Reject)
        assertEquals(1, gateway.submits.size)
    }

    @Test
    fun refreshFetchesEveryUsableConnection() {
        val a = pendingRequest(server, URL)
        val b = pendingRequest(other, OTHER_URL)
        server.addPending(a.connectionId)
        other.addPending(b.connectionId)
        val viewModel = InboxViewModel(repository)
        viewModel.refresh()
        assertFalse(viewModel.state.value.refreshing)
        assertEquals(2, viewModel.state.value.inbox.pending[a.connectionId]?.size)
        assertEquals(2, viewModel.state.value.inbox.pending[b.connectionId]?.size)
        assertEquals(emptyList<Any>(), gateway.submits)
    }

    @Test
    fun sendsAWaitingAnswerAgainOnRequest() {
        val request = pendingRequest()
        server.failure = GatewayException.Kind.Unreachable
        val viewModel = InboxViewModel(repository)
        viewModel.answer(request, Answer.Acknowledge)
        assertEquals(Delivery.Waiting, viewModel.state.value.inbox.result(request)?.delivery)
        server.failure = null
        viewModel.sendAgain(request)
        assertEquals(Delivery.Accepted, viewModel.state.value.inbox.result(request)?.delivery)
    }

    @Test
    fun ignoresAnAnswerForARequestThatIsNotPending() {
        val request = pendingRequest()
        val viewModel = InboxViewModel(repository)
        viewModel.answer(request.copy(requestId = OTHER_REQUEST), Answer.Acknowledge)
        assertTrue(viewModel.state.value.sending.isEmpty())
        assertEquals(emptyList<Any>(), gateway.submits)
    }

    private companion object {
        const val URL = "https://vault.example.com"
        const val OTHER_URL = "https://other.example.com"
        const val OTHER_REQUEST = "de03846e-d435-4705-b2e3-ec67da539f12"
    }
}
