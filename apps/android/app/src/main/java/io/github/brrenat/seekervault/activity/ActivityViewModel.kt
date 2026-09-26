package io.github.brrenat.seekervault.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.brrenat.seekervault.confirmations.ChainCheck
import io.github.brrenat.seekervault.confirmations.ConfirmationTracker
import io.github.brrenat.seekervault.connections.RequestKey
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the Activity screens show. */
data class ActivityUiState(
    val records: List<ActivityRecord> = emptyList(),
    /** True once the history has been read at least once. */
    val loaded: Boolean = false,
    /** A read that failed. The records already on screen stay, and the screen says so. */
    val unreadable: Boolean = false,
    /** Nothing on this phone could open the last link. */
    val linkFailed: Boolean = false,
    /** Transactions the owner asked the chain about, while the answer is out (SEE-165). */
    val checking: Set<RequestKey> = emptySet(),
) {
    fun record(key: RequestKey): ActivityRecord? = records.firstOrNull { it.key == key }
}

/**
 * State and actions of Activity and its details screen. It only reads what this phone already
 * recorded: it answers nothing, reaches no server, and opens no wallet.
 */
class ActivityViewModel(
    private val log: ActivityLog,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * What follows sent transactions to the chain (SEE-165). The only thing this screen asks of it
     * is a check the owner requested; it opens no wallet and sends nothing.
     */
    private val confirmations: ConfirmationTracker? = null,
) : ViewModel() {
    private data class Screen(
        val loaded: Boolean = false,
        val unreadable: Boolean = false,
        val linkFailed: Boolean = false,
        val checking: Set<RequestKey> = emptySet(),
    )

    private val screen = MutableStateFlow(Screen())

    val state: StateFlow<ActivityUiState> =
        combine(log.records, screen) { records, now ->
                ActivityUiState(records, now.loaded, now.unreadable, now.linkFailed, now.checking)
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ActivityUiState(log.records.value))

    /**
     * What the phone last found on chain, by request, for the screens built from answers and
     * proposals rather than from History records (Inbox History and its details).
     */
    val chainChecks: StateFlow<Map<RequestKey, ChainCheck>> =
        confirmations?.checks ?: MutableStateFlow(emptyMap())

    /**
     * The owner asked whether [key]'s transaction landed. It asks the chain now, whatever the
     * schedule says, and the answer arrives through the records and [chainChecks] like any other.
     */
    fun checkChain(key: RequestKey) {
        val tracker = confirmations ?: return
        if (key in screen.value.checking) return
        screen.update { it.copy(checking = it.checking + key) }
        viewModelScope.launch {
            try {
                withContext(io) { tracker.check(key) }
            } catch (_: IOException) {
                // The tracking record couldn't be written; the last check still stands.
            } finally {
                screen.update { it.copy(checking = it.checking - key) }
            }
        }
    }

    init {
        refresh()
    }

    /**
     * Reads the history again. A read that fails leaves the records as they were and says so: a
     * history this phone can't read is a reason to tell the owner, not a reason to stop working.
     */
    fun refresh() {
        viewModelScope.launch {
            val failed =
                try {
                    withContext(io) { log.load() }
                    false
                } catch (e: IOException) {
                    true
                } catch (e: SecurityException) {
                    true
                }
            screen.update { it.copy(loaded = true, unreadable = failed) }
        }
    }

    /** Removes every record, which is the only way one goes. */
    fun clear() {
        viewModelScope.launch {
            try {
                withContext(io) { log.clear() }
                screen.update { it.copy(unreadable = false) }
            } catch (e: IOException) {
                screen.update { it.copy(unreadable = true) }
            }
        }
    }

    /** Nothing on this phone opened the link; the record itself is unaffected. */
    fun linkFailed() = screen.update { it.copy(linkFailed = true) }

    fun messageShown() = screen.update { it.copy(linkFailed = false) }
}
