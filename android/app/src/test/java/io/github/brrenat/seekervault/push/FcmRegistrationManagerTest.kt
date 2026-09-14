package io.github.brrenat.seekervault.push

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.FirebaseApp
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.FcmTokenUpdate
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class FcmRegistrationManagerTest {
    @Test
    fun productionClientIsANoOpWhenFirebaseIsUnconfigured() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertTrue(FirebaseApp.getApps(context).isEmpty())
        FirebaseFcmRegistrationClient(context).register()
        FirebaseFcmRegistrationClient(context).unregister()
    }

    @Test
    fun registersOnlyAfterLoadAndPublishesOneTargetToEveryUsableSidecar() = runTest {
        val loaded = MutableStateFlow(false)
        val connections = MutableStateFlow(listOf(connection(A), connection(B)))
        val client = FakeClient()
        val updates = mutableListOf<Pair<String, FcmTokenUpdate>>()
        val manager = manager(loaded, connections, client, updates)
        manager.start()
        runCurrent()
        assertEquals(0, client.registers)

        loaded.value = true
        runCurrent()
        assertEquals(1, client.registers)
        manager.onRegistered(TARGET)
        runCurrent()

        assertEquals(listOf(A, B), updates.map { it.first })
        assertTrue(updates.all { it.second is FcmTokenUpdate.Register })
        assertTrue(updates.all { it.second.target == TARGET })
        manager.close()
    }

    @Test
    fun serializesRotationAndMakesALateUnregistrationACompareClear() = runTest {
        val loaded = MutableStateFlow(true)
        val connections = MutableStateFlow(listOf(connection(A), connection(B)))
        val client = FakeClient()
        val updates = mutableListOf<Pair<String, FcmTokenUpdate>>()
        val manager = manager(loaded, connections, client, updates)
        manager.start()
        runCurrent()

        manager.onRegistered(OLD_TARGET)
        manager.onRegistered(TARGET)
        manager.onUnregistered(OLD_TARGET)
        runCurrent()

        assertEquals(
            listOf(
                A to "register:$OLD_TARGET",
                B to "register:$OLD_TARGET",
                A to "register:$TARGET",
                B to "register:$TARGET",
                A to "clear:$OLD_TARGET",
                B to "clear:$OLD_TARGET",
            ),
            updates.map { (id, update) ->
                id to
                    when (update) {
                        is FcmTokenUpdate.Register -> "register:${update.target}"
                        is FcmTokenUpdate.ClearIfCurrent -> "clear:${update.target}"
                    }
            },
        )
        manager.close()
    }

    @Test
    fun ignoresInvalidCallbacksAndOneSidecarFailureDoesNotLeakIntoAnother() = runTest {
        val loaded = MutableStateFlow(true)
        val connections = MutableStateFlow(listOf(connection(A), connection(B)))
        val client = FakeClient()
        val updated = mutableListOf<String>()
        val manager =
            FcmRegistrationManager(
                loaded,
                connections,
                client,
                loadConnections = {},
                publish = { id, _ ->
                    if (id == A) error("sidecar unavailable")
                    updated += id
                    true
                },
                dispatcher = StandardTestDispatcher(testScheduler),
            )
        manager.start()
        runCurrent()

        for (invalid in listOf("", "has space", "unicode-🙂", "x".repeat(4_097))) {
            manager.onRegistered(invalid)
            manager.onUnregistered(invalid)
        }
        manager.onRegistered(TARGET)
        runCurrent()

        assertEquals(listOf(B), updated)
        assertFalse(validFcmTarget("has space"))
        assertTrue(validFcmTarget("x".repeat(4_096)))
        manager.close()
    }

    @Test
    fun retriesOnlyTheFailedSidecarUntilItsCurrentTargetPublishes() = runTest {
        val loaded = MutableStateFlow(true)
        val connections = MutableStateFlow(listOf(connection(A), connection(B)))
        val attempts = mutableMapOf<String, Int>()
        val manager =
            FcmRegistrationManager(
                loaded,
                connections,
                FakeClient(),
                loadConnections = {},
                publish = { id, _ ->
                    val attempt = attempts.getOrDefault(id, 0) + 1
                    attempts[id] = attempt
                    id != A || attempt > 1
                },
                dispatcher = StandardTestDispatcher(testScheduler),
            )
        manager.start()
        runCurrent()

        manager.onRegistered(TARGET)
        runCurrent()
        assertEquals(mapOf(A to 1, B to 1), attempts)

        advanceUntilIdle()
        assertEquals(mapOf(A to 2, B to 1), attempts)
        manager.close()
    }

    @Test
    fun boundsRetriesWhileStage52RemainsTheUnavailableSidecarsRecoveryPath() = runTest {
        val loaded = MutableStateFlow(true)
        val connections = MutableStateFlow(listOf(connection(A)))
        var attempts = 0
        val manager =
            FcmRegistrationManager(
                loaded,
                connections,
                FakeClient(),
                loadConnections = {},
                publish = { _, _ ->
                    attempts++
                    false
                },
                dispatcher = StandardTestDispatcher(testScheduler),
            )
        manager.start()
        runCurrent()

        manager.onRegistered(TARGET)
        advanceUntilIdle()

        assertEquals(1 + FcmRegistrationManager.MAX_PUBLISH_RETRIES, attempts)
        manager.close()
    }

    @Test
    fun ignoresALateRegistrationWithNoOwnerAndStartsFreshForANewConnection() = runTest {
        val loaded = MutableStateFlow(true)
        val connections = MutableStateFlow<List<Connection>>(emptyList())
        val client = FakeClient()
        val updates = mutableListOf<Pair<String, FcmTokenUpdate>>()
        val manager = manager(loaded, connections, client, updates)
        manager.start()
        runCurrent()

        manager.onRegistered(OLD_TARGET)
        runCurrent()
        assertTrue(updates.isEmpty())

        connections.value = listOf(connection(A))
        runCurrent()
        assertEquals(1, client.registers)
        assertTrue(updates.isEmpty())

        manager.onRegistered(TARGET)
        runCurrent()
        assertEquals(listOf(A to TARGET), updates.map { it.first to it.second.target })
        manager.close()
    }

    @Test
    fun republishesToANewSidecarAndUnregistersAfterTheLastUsableConnection() = runTest {
        val loaded = MutableStateFlow(true)
        val connections = MutableStateFlow(listOf(connection(A)))
        val client = FakeClient()
        val updates = mutableListOf<Pair<String, FcmTokenUpdate>>()
        val manager = manager(loaded, connections, client, updates)
        manager.start()
        runCurrent()
        manager.onRegistered(TARGET)
        runCurrent()

        connections.value = listOf(connection(A), connection(B))
        runCurrent()
        assertEquals(listOf(A, A, B), updates.map { it.first })

        connections.value = listOf(connection(A, usable = false), connection(B, usable = false))
        runCurrent()
        assertEquals(1, client.unregisters)
        assertEquals(1, client.registers)
        manager.close()
    }

    private fun TestScope.manager(
        loaded: MutableStateFlow<Boolean>,
        connections: MutableStateFlow<List<Connection>>,
        client: FakeClient,
        updates: MutableList<Pair<String, FcmTokenUpdate>>,
    ) =
        FcmRegistrationManager(
            loaded,
            connections,
            client,
            loadConnections = {},
            publish = { id, update ->
                updates += id to update
                true
            },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

    private class FakeClient : FcmRegistrationClient {
        var registers = 0
        var unregisters = 0

        override suspend fun register() {
            registers++
        }

        override suspend fun unregister() {
            unregisters++
        }
    }

    private fun connection(id: String, usable: Boolean = true) =
        Connection(
            id = id,
            label = id,
            serverUrl = "https://$id.example.com",
            serverId = id,
            deviceName = "Seeker",
            pairedAt = Instant.EPOCH,
            hasCredential = usable,
        )

    private companion object {
        const val A = "00000000-0000-4000-8000-00000000000a"
        const val B = "00000000-0000-4000-8000-00000000000b"
        const val OLD_TARGET = "fcm-registration-before-rotation"
        const val TARGET = "fcm-registration-after-rotation"
    }
}
