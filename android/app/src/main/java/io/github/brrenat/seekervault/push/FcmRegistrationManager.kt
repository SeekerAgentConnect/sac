package io.github.brrenat.seekervault.push

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.connections.FcmTokenUpdate
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** The optional Firebase operation, isolated so an unconfigured build remains an ordinary app. */
interface FcmRegistrationClient {
    suspend fun register()

    suspend fun unregister()
}

/** Uses the current direct-send registration API only when Google Services initialized Firebase. */
class FirebaseFcmRegistrationClient(context: Context) : FcmRegistrationClient {
    private val applicationContext = context.applicationContext

    override suspend fun register() {
        messaging()?.let { client ->
            client.setAutoInitEnabled(true)
            client.register().await()
        }
    }

    override suspend fun unregister() {
        messaging()?.let { client ->
            client.setAutoInitEnabled(false)
            client.unregister().await()
        }
    }

    private fun messaging(): FirebaseMessaging? =
        if (FirebaseApp.getApps(applicationContext).isEmpty()) null
        else FirebaseMessaging.getInstance()
}

/**
 * One process-wide registration lifecycle for every usable paired connection (SAW-055).
 *
 * Firebase callbacks and connection changes enter one serialized channel. A rotation is therefore
 * published after the old target and to every currently usable sidecar. The sidecar's
 * compare-and-delete operation also makes a delayed unregistration for the old target harmless. No
 * target is stored on the phone or written to a log.
 */
class FcmRegistrationManager(
    private val loaded: StateFlow<Boolean>,
    private val connections: StateFlow<List<Connection>>,
    private val client: FcmRegistrationClient,
    private val loadConnections: suspend () -> Unit,
    private val publish: suspend (String, FcmTokenUpdate) -> Unit,
    dispatcher: CoroutineDispatcher,
) {
    private sealed interface Event {
        class Connections(val ids: List<String>) : Event

        class Registered(val target: String) : Event

        class Unregistered(val target: String) : Event
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val started = AtomicBoolean(false)
    private var activeIds = emptyList<String>()
    private var currentTarget: String? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            combine(loaded, connections) { ready, values ->
                    if (ready) values.filter(Connection::usable).map(Connection::id).sorted()
                    else emptyList()
                }
                .distinctUntilChanged()
                .collect { events.send(Event.Connections(it)) }
        }
        scope.launch {
            for (event in events) handle(event)
        }
        scope.launch { bestEffort { loadConnections() } }
    }

    /** Called by [SeekerVaultMessagingService] for an initial registration or later rotation. */
    fun onRegistered(target: String) {
        if (validFcmTarget(target)) events.trySend(Event.Registered(target))
    }

    /** Called when Firebase invalidates or explicitly unregisters one particular target. */
    fun onUnregistered(target: String) {
        if (validFcmTarget(target)) events.trySend(Event.Unregistered(target))
    }

    internal fun close() {
        events.close()
        scope.cancel()
    }

    private suspend fun handle(event: Event) {
        when (event) {
            is Event.Connections -> {
                val hadConnections = activeIds.isNotEmpty()
                activeIds = event.ids
                when {
                    !hadConnections && activeIds.isNotEmpty() -> bestEffort { client.register() }
                    hadConnections && activeIds.isEmpty() -> {
                        currentTarget = null
                        bestEffort { client.unregister() }
                    }
                }
                currentTarget?.let { publishToAll(FcmTokenUpdate.Register(it)) }
            }
            is Event.Registered -> {
                // A late callback after the last connection was removed owns nothing. A later
                // connection starts a fresh explicit registration and gets its own callback.
                if (activeIds.isEmpty()) return
                currentTarget = event.target
                publishToAll(FcmTokenUpdate.Register(event.target))
            }
            is Event.Unregistered -> {
                if (currentTarget == event.target) currentTarget = null
                publishToAll(FcmTokenUpdate.ClearIfCurrent(event.target))
            }
        }
    }

    private suspend fun publishToAll(update: FcmTokenUpdate) {
        for (id in activeIds) bestEffort { publish(id, update) }
    }

    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Registration is an optional hint path. Stage 5.2 remains the recovery path.
        }
    }
}

/** Mirrors the sidecar's bounded opaque-target rule before any value reaches a network call. */
internal fun validFcmTarget(target: String): Boolean =
    target.isNotEmpty() &&
        target.toByteArray(Charsets.UTF_8).size <= 4_096 &&
        target.all { it.code in 0x21..0x7e }

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { completed ->
        if (!continuation.isActive) return@addOnCompleteListener
        val failure = completed.exception
        if (failure == null) continuation.resume(completed.result)
        else continuation.resumeWithException(failure)
    }
}
