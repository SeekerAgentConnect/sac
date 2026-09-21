package io.github.brrenat.seekervault.push

import io.github.brrenat.seekervault.connections.Connection
import io.github.brrenat.seekervault.push.storage.RelayStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The phone's gateway relay lifecycle (SEE-144).
 *
 * It is [FcmRegistrationManager]'s counterpart for servers that hold no Firebase credential, and it
 * is built the same way and for the same reasons: Firebase callbacks and connection changes enter
 * one serialized channel, so a rotation is applied in the order it happened rather than in
 * whichever order two coroutines woke up.
 *
 * # Reconcile, never remember
 *
 * What this phone holds is an enrollment and, per connection, the ID of the authorization it made.
 * Everything else is asked for: which servers advertise a relay comes from the authenticated direct
 * connection to each of them, and what the gateway still holds comes from the gateway. A gateway
 * that lost its database, an app that was reinstalled, and a connection removed while offline all
 * end in the same place — a state that is re-derived rather than repaired.
 *
 * # Never in the way
 *
 * Nothing here can fail a pairing, a synchronization, a request or a decision. Every call is best
 * effort with a bounded retry, and the worst outcome of all of it going wrong is that a phone finds
 * out about a request when its owner next opens the app, which is what a phone without any push
 * does all the time.
 */
class RelayRegistrationManager(
    /** The relay this app is configured to trust, or "" when it was built without one. */
    private val relayUrl: String,
    private val loaded: StateFlow<Boolean>,
    private val connections: StateFlow<List<Connection>>,
    private val client: RelayClient,
    private val store: RelayStore,
    private val loadConnections: suspend () -> Unit,
    /** What a connection's own server says about the relay, over the authenticated connection. */
    private val coordinates: suspend (String) -> RelayCoordinates?,
    /** Hands a handle to the server the binding is for, or clears it. Says whether it worked. */
    private val publish: suspend (String, RelayHandleUpdate) -> Boolean,
    dispatcher: CoroutineDispatcher,
) {
    private sealed interface Event {
        class Connections(val ids: List<String>) : Event

        class Registered(val target: String) : Event

        object Reconcile : Event

        class Retry(val attempt: Int) : Event
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val started = AtomicBoolean(false)
    private var activeIds = emptyList<String>()
    private var currentTarget: String? = null

    /** Whether this build has a relay at all. Without one, nothing here does anything. */
    val configured: Boolean
        get() = relayUrl.isNotEmpty()

    fun start() {
        if (!configured || !started.compareAndSet(false, true)) return
        scope.launch {
            combine(loaded, connections) { ready, values ->
                    if (ready) values.filter(Connection::usable).map(Connection::id).sorted()
                    else emptyList()
                }
                .distinctUntilChanged()
                .collect { events.send(Event.Connections(it)) }
        }
        scope.launch {
            // Every event is handled inside the guard, so one failure — a file that cannot be
            // read, a Keystore that is gone — costs one pass rather than the rest of the process's
            // life. A loop that died silently would look exactly like a relay nobody configured.
            for (event in events) bestEffort { handle(event) }
        }
        scope.launch { bestEffort { loadConnections() } }
    }

    /** Called by [SeekerVaultMessagingService] for an initial registration or a later rotation. */
    fun onRegistered(target: String) {
        if (configured && validRelayTarget(target)) events.trySend(Event.Registered(target))
    }

    internal fun close() {
        events.close()
        scope.cancel()
    }

    private suspend fun handle(event: Event) {
        when (event) {
            is Event.Connections -> {
                activeIds = event.ids
                reconcile(attempt = 0)
            }
            is Event.Registered -> {
                currentTarget = event.target
                // A rotation is the one thing that must reach the gateway even when nothing else
                // changed: the enrollment is the same, and where it points is not.
                reconcile(attempt = 0)
            }
            Event.Reconcile -> reconcile(attempt = 0)
            is Event.Retry -> reconcile(event.attempt)
        }
    }

    /**
     * One pass over everything, in the order that makes each step safe if the next one fails.
     *
     * Owed revocations first, because they are the only part with a deadline the owner cares about.
     * Then the enrollment, because everything else needs it. Then the target, because a binding
     * with nowhere to send is not worth making. Then the bindings themselves.
     */
    private suspend fun reconcile(attempt: Int) {
        val state = store.read()
        var held = state
        var complete = true

        held = payOwed(held).also { complete = complete && it.owed.isEmpty() }

        val enrollment = held.enrollment
        val target = currentTarget
        if (enrollment == null) {
            // Nothing to enroll with yet: Firebase has not called back, or this build has no
            // registration at all. The next callback starts a pass of its own.
            if (target == null) return
            val fresh =
                try {
                    client.enroll(relayUrl, target)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failed: RelayException) {
                    return later(attempt)
                }
            // A fresh enrollment holds no authorizations, whatever this phone used to think.
            held = RelayStore.State(enrollment = fresh, bindings = emptyMap(), owed = held.owed)
            store.write(held)
        } else if (target != null) {
            try {
                client.setTarget(relayUrl, enrollment, target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: RelayException) {
                if (failed.gone) {
                    // The gateway does not hold this installation: its database was lost, or the
                    // enrollment aged out. Forget it and enroll again on the next pass — the
                    // direct connections were never the gateway's and are untouched.
                    store.write(RelayStore.State(null, emptyMap(), held.owed))
                    return later(attempt)
                }
                return later(attempt)
            }
        }

        val (next, settled) = rebind(held)
        if (next != held) store.write(next)
        complete = complete && settled
        if (!complete) later(attempt)
    }

    /**
     * Makes the gateway's authorizations match the connections this phone holds.
     *
     * Three cases, and the order they are handled in is the order that leaves the least behind: a
     * connection that is gone is revoked, a connection whose server advertises this relay and has
     * no authorization gets one, and everything else is left exactly as it is.
     */
    private suspend fun rebind(state: RelayStore.State): Pair<RelayStore.State, Boolean> {
        val enrollment = state.enrollment ?: return state to false
        var bindings = state.bindings
        var owed = state.owed
        var settled = true

        for ((connectionId, bindingId) in state.bindings) {
            if (connectionId in activeIds) continue
            // The connection is gone. The gateway is told, and if it cannot be told now the
            // revocation is kept as work rather than dropped.
            if (!revoke(enrollment, bindingId)) owed = owed + bindingId
            bindings = bindings - connectionId
        }

        for (connectionId in activeIds) {
            if (connectionId in bindings) continue
            val advertised =
                try {
                    coordinates(connectionId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    settled = false
                    continue
                }
            // A server that advertises nothing sends its own push, or none. A server that
            // advertises a relay this phone is not configured to trust is ignored, which is what
            // stops an advertisement from being a way to collect device registrations.
            if (advertised == null || !advertised.trusted(relayUrl)) continue
            val authorized =
                try {
                    client.bind(relayUrl, enrollment, advertised.serverId, connectionId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failed: RelayException) {
                    settled = false
                    if (failed.gone) {
                        // The enrollment is not there any more. Stop this pass rather than
                        // authorizing the rest against an identity that does not exist.
                        return RelayStore.State(null, emptyMap(), owed) to false
                    }
                    continue
                }
            // The handle goes to the server over the authenticated direct connection it is for,
            // and is not kept here: the gateway stored only its hash, so a handle this phone lost
            // is re-authorized rather than recovered.
            if (
                !bestEffortResult {
                    publish(connectionId, RelayHandleUpdate.Register(authorized.handle))
                }
            ) {
                // The server did not take it, so the authorization is of no use to anyone. Revoke
                // it rather than leave a live handle nothing holds.
                if (!revoke(enrollment, authorized.binding)) owed = owed + authorized.binding
                settled = false
                continue
            }
            bindings = bindings + (connectionId to authorized.binding)
        }
        return RelayStore.State(enrollment, bindings, owed) to settled
    }

    /** Makes the revocations this phone owes, and keeps the ones it still cannot make. */
    private suspend fun payOwed(state: RelayStore.State): RelayStore.State {
        if (state.owed.isEmpty()) return state
        val enrollment = state.enrollment ?: return state
        val remaining = state.owed.filterNot { revoke(enrollment, it) }.toSet()
        if (remaining == state.owed) return state
        val next = state.copy(owed = remaining)
        store.write(next)
        return next
    }

    private suspend fun revoke(enrollment: RelayEnrollment, bindingId: String): Boolean =
        try {
            client.unbind(relayUrl, enrollment, bindingId)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: RelayException) {
            // An enrollment the gateway no longer holds cannot be holding this authorization
            // either, so the debt is settled by the gateway having forgotten everything.
            failed.gone
        }

    private fun later(attempt: Int) {
        if (attempt >= MOST_RETRIES) return
        val next = attempt + 1
        scope.launch {
            delay(RETRY_BASE_DELAY_MILLIS * (1L shl (next - 1)))
            events.trySend(Event.Retry(next))
        }
    }

    private suspend fun bestEffortResult(block: suspend () -> Boolean): Boolean =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }

    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The relay is an optional hint path. A foreground read remains the recovery path.
        }
    }

    internal companion object {
        internal const val MOST_RETRIES = 3
        internal const val RETRY_BASE_DELAY_MILLIS = 5_000L
    }
}

/** A handle update whose diagnostic representation can never disclose the opaque value. */
sealed class RelayHandleUpdate(val handle: String) {
    class Register(handle: String) : RelayHandleUpdate(handle) {
        override fun toString() = "RelayHandleUpdate.Register(<redacted>)"
    }

    class ClearIfCurrent(handle: String) : RelayHandleUpdate(handle) {
        override fun toString() = "RelayHandleUpdate.ClearIfCurrent(<redacted>)"
    }
}
