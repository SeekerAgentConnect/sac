package io.github.brrenat.seekervault.policy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.brrenat.seekervault.policy.storage.PolicyStore
import io.github.brrenat.seekervault.policy.storage.StoredPolicy
import io.github.brrenat.seekervault.policy.storage.UnreadableReason
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the editor has to tell the owner after it did something. */
enum class PolicyMessage {
    Saved,
    Removed,
    /** The rules couldn't be written. What is stored is what was stored before. */
    SaveFailed,
}

/** Everything the policy editor shows. */
data class PolicyUiState(
    /** The connection being edited, or null before one is opened. */
    val connectionId: String? = null,
    /** False until the stored rules have been read. Nothing is editable before that. */
    val loaded: Boolean = false,
    /**
     * Set when rules are stored and this build can't read them. The form is not opened over them: a
     * blank form saved on top would delete rules the owner set and never saw.
     */
    val unreadable: UnreadableReason? = null,
    /**
     * Whether the owner chose to start over on top of rules this build couldn't read. What is on
     * disk is then unknown, so anything the draft says is a change worth saving — including nothing
     * at all, which removes the rules that couldn't be read.
     */
    val replacing: Boolean = false,
    val draft: PolicyDraft = PolicyDraft(""),
    /** What is stored, as a draft, so the screen can tell whether anything was changed. */
    val stored: PolicyDraft = PolicyDraft(""),
    /** When the rules were last saved, or null when this connection has none. */
    val storedAt: Instant? = null,
    val saving: Boolean = false,
    val message: PolicyMessage? = null,
) {
    /** Whether the draft says anything different from what is stored. */
    val changed: Boolean
        get() = replacing || draft != stored
}

/**
 * State and actions of the policy editor (docs/guides/policies.md).
 *
 * It reads one connection's rules, holds the owner's edits until they save, and writes them back.
 * That is the whole of what it does: it reaches no network, opens no wallet, and tells no agent
 * anything. A policy edited here changes what the owner is *told* about a request and nothing about
 * what the app will do with one.
 */
class PolicyEditorViewModel(
    private val policies: PolicyStore,
    private val now: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(PolicyUiState())
    val state: StateFlow<PolicyUiState> = _state.asStateFlow()

    /**
     * Opens [connectionId]'s rules, reading them from disk. Opening the connection that is already
     * open does nothing, so a rotation keeps edits that haven't been saved.
     */
    fun open(connectionId: String) {
        val current = _state.value
        if (current.connectionId == connectionId && current.loaded) return
        _state.value = PolicyUiState(connectionId = connectionId)
        viewModelScope.launch {
            val stored = withContext(io) { policies.get(connectionId) }
            // Another connection was opened while this one was being read.
            if (_state.value.connectionId != connectionId) return@launch
            val policy = (stored as? StoredPolicy.Policy)?.policy
            val draft = draftOf(connectionId, policy)
            _state.value =
                PolicyUiState(
                    connectionId = connectionId,
                    loaded = true,
                    unreadable = (stored as? StoredPolicy.Unreadable)?.why,
                    draft = draft,
                    stored = draft,
                    storedAt = policy?.updatedAt,
                )
        }
    }

    /** The owner changed something. Nothing is written until they save. */
    fun edit(draft: PolicyDraft) {
        if (!_state.value.loaded || _state.value.unreadable != null) return
        _state.update { it.copy(draft = draft) }
    }

    /**
     * Starts from no rules, over rules this build couldn't read. The owner asks for this: it
     * replaces what is stored, and nothing here can show them what they are replacing.
     */
    fun startOver() {
        val connectionId = _state.value.connectionId ?: return
        _state.update {
            it.copy(
                unreadable = null,
                replacing = true,
                draft = PolicyDraft(connectionId),
                stored = PolicyDraft(connectionId),
                storedAt = null,
            )
        }
    }

    /**
     * Writes the draft. A draft that configures nothing removes the connection's rules instead of
     * storing a document that says nothing.
     */
    fun save() {
        val state = _state.value
        val connectionId = state.connectionId ?: return
        if (state.saving || !state.loaded || state.unreadable != null) return
        // The draft that is about to be written, held apart from the one on screen: the form stays
        // interactive while a slow write runs, and marking whatever is typed by the time it
        // finishes as "stored" would lose those edits without a word.
        val writing = state.draft
        val review = writing.review(now())
        val policy =
            when (review) {
                is DraftReview.Ready -> review.policy
                DraftReview.NoRules -> null
                // The Save button is off while anything is wrong, so this is a draft that was
                // built rather than typed. It is refused rather than written half.
                is DraftReview.Problems -> return
            }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val written =
                withContext(io) {
                    try {
                        if (policy == null) policies.delete(connectionId) else policies.put(policy)
                        true
                    } catch (e: IOException) {
                        false
                    }
                }
            _state.update { current ->
                if (current.connectionId != connectionId) return@update current
                if (!written) {
                    return@update current.copy(saving = false, message = PolicyMessage.SaveFailed)
                }
                current.copy(
                    saving = false,
                    replacing = false,
                    stored = writing,
                    storedAt = policy?.updatedAt,
                    message = if (policy == null) PolicyMessage.Removed else PolicyMessage.Saved,
                )
            }
        }
    }

    /** The owner left the editor. The next connection opened is read from disk again. */
    fun close() {
        _state.value = PolicyUiState()
    }

    fun messageShown() = _state.update { it.copy(message = null) }
}
