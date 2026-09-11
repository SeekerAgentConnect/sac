@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.brrenat.seekervault.live

import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LiveCommandViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val sidecar = FakeSidecar()
    private val start = Instant.parse("2026-09-11T12:00:00Z")
    private val token = "p".repeat(64)

    @Before fun setMain() = Dispatchers.setMain(dispatcher)

    @After fun resetMain() = Dispatchers.resetMain()

    private fun TestScope.viewModel() =
        LiveCommandViewModel(sidecar) { start.plusMillis(testScheduler.currentTime) }

    private fun TestScope.connected(): LiveCommandViewModel {
        val viewModel = viewModel()
        viewModel.onPhoneTokenChange(token)
        viewModel.connect()
        sidecar.stream.ready()
        runCurrent()
        assertEquals(ConnectionState.Connected, viewModel.state.value.connection)
        return viewModel
    }

    private fun TestScope.receive(viewModel: LiveCommandViewModel, seconds: Long = 60) {
        sidecar.stream.send(command(expiresAt = start.plusSeconds(seconds)))
        runCurrent()
        assertEquals(CommandStatus.AwaitingOk, viewModel.state.value.command?.status)
    }

    private fun LiveCommandViewModel.status() = state.value.command?.status

    @Test
    fun showsTheExactTextAndSendsOneAcknowledgement() =
        runTest(dispatcher) {
            val viewModel = connected()
            assertEquals(
                listOf(LiveCommandUiState.DEFAULT_SERVER_URL to token),
                sidecar.connections,
            )
            receive(viewModel)
            assertEquals(
                ReceivedCommand("c1", "Hello Seeker", CommandStatus.AwaitingOk),
                viewModel.state.value.command,
            )
            viewModel.acknowledge()
            runCurrent()
            assertEquals(listOf("c1"), sidecar.acknowledgements)
            assertEquals(CommandStatus.Acknowledged, viewModel.status())
            viewModel.acknowledge()
            assertEquals(listOf("c1"), sidecar.acknowledgements)
        }

    @Test
    fun ignoresARapidSecondTapWhileSending() =
        runTest(dispatcher) {
            val viewModel = connected()
            receive(viewModel)
            val answer = CompletableDeferred<Unit>()
            sidecar.onAcknowledge = { answer.await() }
            viewModel.acknowledge()
            viewModel.acknowledge()
            assertEquals(CommandStatus.Sending, viewModel.status())
            answer.complete(Unit)
            runCurrent()
            assertEquals(listOf("c1"), sidecar.acknowledgements)
            assertEquals(CommandStatus.Acknowledged, viewModel.status())
        }

    @Test
    fun timesOutAtTheDeadlineAndThenSendsNothing() =
        runTest(dispatcher) {
            val viewModel = connected()
            receive(viewModel, seconds = 60)
            advanceTimeBy(59_999)
            runCurrent()
            assertEquals(CommandStatus.AwaitingOk, viewModel.status())
            advanceTimeBy(1)
            runCurrent()
            assertEquals(CommandStatus.TimedOut, viewModel.status())
            viewModel.acknowledge()
            assertTrue(sidecar.acknowledgements.isEmpty())
        }

    @Test
    fun showsACommandThatArrivesAlreadyExpiredAsTimedOut() =
        runTest(dispatcher) {
            val viewModel = connected()
            sidecar.stream.send(command(expiresAt = start))
            runCurrent()
            assertEquals(CommandStatus.TimedOut, viewModel.status())
        }

    @Test
    fun reportsTheSidecarsAnswerToAnOk() =
        runTest(dispatcher) {
            val expected =
                mapOf(
                    LiveTransportException.Kind.TimedOut to CommandStatus.TimedOut,
                    LiveTransportException.Kind.Cancelled to
                        CommandStatus.Failed(AcknowledgeFailure.Cancelled),
                    LiveTransportException.Kind.UnknownCommand to
                        CommandStatus.Failed(AcknowledgeFailure.UnknownCommand),
                    LiveTransportException.Kind.Unreachable to
                        CommandStatus.Failed(AcknowledgeFailure.Unreachable),
                )
            val viewModel = connected()
            for ((kind, status) in expected) {
                sidecar.onAcknowledge = { throw LiveTransportException(kind, "fake $kind") }
                receive(viewModel)
                viewModel.acknowledge()
                runCurrent()
                assertEquals(status, viewModel.status())
            }
        }

    @Test
    fun clearsTheCommandWhenTheConnectionIsLost() =
        runTest(dispatcher) {
            val viewModel = connected()
            receive(viewModel)
            sidecar.stream.fail(LiveTransportException.Kind.Unreachable)
            runCurrent()
            assertEquals(
                ConnectionState.Disconnected(DisconnectReason.Lost("fake Unreachable")),
                viewModel.state.value.connection,
            )
            assertNull(viewModel.state.value.command)
        }

    @Test
    fun explainsAWrongTokenAndAReplacedStream() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.onPhoneTokenChange("wrong")
            viewModel.connect()
            sidecar.stream.fail(LiveTransportException.Kind.Unauthenticated)
            runCurrent()
            assertEquals(
                ConnectionState.Disconnected(DisconnectReason.Unauthenticated),
                viewModel.state.value.connection,
            )
            viewModel.connect()
            sidecar.stream.ready()
            sidecar.stream.fail(LiveTransportException.Kind.Cancelled)
            runCurrent()
            assertEquals(
                ConnectionState.Disconnected(DisconnectReason.Replaced),
                viewModel.state.value.connection,
            )
        }

    @Test
    fun pointsToAdbReverseWhenTheFirstConnectionCannotReachTheSidecar() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.onPhoneTokenChange(token)
            viewModel.connect()
            sidecar.stream.fail(LiveTransportException.Kind.Unreachable)
            runCurrent()
            assertEquals(
                ConnectionState.Disconnected(
                    DisconnectReason.Unreachable(LiveCommandUiState.DEFAULT_SERVER_URL, 8080)
                ),
                viewModel.state.value.connection,
            )
            // Once connected, the same failure is a lost connection instead.
            viewModel.connect()
            sidecar.stream.ready()
            sidecar.stream.fail(LiveTransportException.Kind.Unreachable)
            runCurrent()
            assertEquals(
                ConnectionState.Disconnected(DisconnectReason.Lost("fake Unreachable")),
                viewModel.state.value.connection,
            )
        }

    @Test
    fun reconnectsWithAFreshStreamAndNoOldCommand() =
        runTest(dispatcher) {
            val viewModel = connected()
            receive(viewModel)
            sidecar.stream.end()
            runCurrent()
            assertEquals(
                ConnectionState.Disconnected(DisconnectReason.Lost(null)),
                viewModel.state.value.connection,
            )
            viewModel.connect()
            assertEquals(2, sidecar.streams.size)
            assertEquals(ConnectionState.Connecting, viewModel.state.value.connection)
            sidecar.stream.ready()
            runCurrent()
            assertEquals(ConnectionState.Connected, viewModel.state.value.connection)
            assertNull(viewModel.state.value.command)
            sidecar.stream.send(command(id = "c2", expiresAt = start.plusSeconds(60)))
            runCurrent()
            assertEquals("c2", viewModel.state.value.command?.id)
        }

    @Test
    fun disconnectClosesTheStreamAndClearsTheCommand() =
        runTest(dispatcher) {
            val viewModel = connected()
            receive(viewModel)
            viewModel.disconnect()
            runCurrent()
            assertFalse(sidecar.stream.open)
            assertEquals(ConnectionState.Disconnected(), viewModel.state.value.connection)
            assertNull(viewModel.state.value.command)
            viewModel.onAppVisible()
            assertEquals(1, sidecar.streams.size)
        }

    @Test
    fun closesTheStreamInTheBackgroundAndReopensItInTheForeground() =
        runTest(dispatcher) {
            val viewModel = connected()
            receive(viewModel)
            viewModel.onAppHidden()
            runCurrent()
            assertFalse(sidecar.stream.open)
            assertEquals(
                ConnectionState.Disconnected(DisconnectReason.Background),
                viewModel.state.value.connection,
            )
            assertNull(viewModel.state.value.command)
            viewModel.onAppVisible()
            assertEquals(2, sidecar.streams.size)
            sidecar.stream.ready()
            runCurrent()
            assertEquals(ConnectionState.Connected, viewModel.state.value.connection)
        }

    @Test
    fun staysDisconnectedInTheForegroundUnlessItWasConnected() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.onAppHidden()
            viewModel.onAppVisible()
            assertTrue(sidecar.streams.isEmpty())
        }

    @Test
    fun validatesTheUrlAndTheTokenBeforeConnecting() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            viewModel.onServerUrlChange("127.0.0.1:8080")
            viewModel.onPhoneTokenChange(token)
            viewModel.connect()
            assertEquals(
                ConnectionState.Disconnected(DisconnectReason.InvalidUrl),
                viewModel.state.value.connection,
            )
            viewModel.onServerUrlChange(" http://127.0.0.1:8080 ")
            viewModel.onPhoneTokenChange("  ")
            viewModel.connect()
            assertEquals(
                ConnectionState.Disconnected(DisconnectReason.MissingToken),
                viewModel.state.value.connection,
            )
            assertTrue(sidecar.connections.isEmpty())
        }
}
