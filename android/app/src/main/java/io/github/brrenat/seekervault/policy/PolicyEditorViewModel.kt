package io.github.brrenat.seekervault.policy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.brrenat.seekervault.policy.storage.PolicyStore
import io.github.brrenat.seekervault.policy.storage.StoredConnectionOverrides
import io.github.brrenat.seekervault.policy.storage.StoredGlobalPolicy
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

/** Which of the two separate phone-local documents is open. */
enum class PolicyEditorScope {
    Global,
    Connection,
}

/** The two forms deliberately have different semantics even though they share Material controls. */
sealed interface PolicyEditorDraft {
    data class Global(val rules: PolicyDraft) : PolicyEditorDraft

    data class Connection(val rules: ConnectionPolicyDraft) : PolicyEditorDraft
}

/** What the editor has to tell the owner after it did something. */
enum class PolicyMessage {
    Saved,
    Removed,
    /** The rules couldn't be written. What is stored is what was stored before. */
    SaveFailed,
}

/** Everything either policy editor shows. */
data class PolicyUiState(
    val scope: PolicyEditorScope? = null,
    /** The connection being edited; null for the global document and before anything is opened. */
    val connectionId: String? = null,
    /** False until the active document and any required global context have been read. */
    val loaded: Boolean = false,
    /** The active document is stored but this build cannot read it. */
    val unreadable: UnreadableReason? = null,
    /** A connection editor keeps an unreadable global document distinct from no global rules. */
    val globalUnreadable: UnreadableReason? = null,
    /** The readable global rules shown as inherited context by a connection editor. */
    val global: GlobalPolicy? = null,
    /** The owner explicitly chose to replace an unreadable active document. */
    val replacing: Boolean = false,
    val draft: PolicyEditorDraft? = null,
    val stored: PolicyEditorDraft? = null,
    /** When the active document was last saved, or null when it has no document. */
    val storedAt: Instant? = null,
    val saving: Boolean = false,
    val message: PolicyMessage? = null,
) {
    val changed: Boolean
        get() = replacing || (draft != null && draft != stored)
}

/**
 * State and actions for both phone-local rules documents (docs/guides/policies.md).
 *
 * The editor reaches no network and no wallet. A second instance may hold an unsaved connection
 * draft while the global editor is on top of it; [refreshGlobal] then refreshes inherited context
 * without touching a single local edit.
 */
open class PolicyEditorViewModel(
    private val policies: PolicyStore,
    private val now: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(PolicyUiState())
    val state: StateFlow<PolicyUiState> = _state.asStateFlow()

    /** Opens the global document. Reopening it after rotation keeps its unsaved draft. */
    fun openGlobal() {
        val current = _state.value
        if (current.scope == PolicyEditorScope.Global && current.loaded) return
        _state.value = PolicyUiState(scope = PolicyEditorScope.Global)
        viewModelScope.launch {
            val stored = withContext(io) { policies.getGlobal() }
            if (_state.value.scope != PolicyEditorScope.Global) return@launch
            val policy = (stored as? StoredGlobalPolicy.Policy)?.policy
            val draft = PolicyEditorDraft.Global(globalDraftOf(policy))
            _state.value =
                PolicyUiState(
                    scope = PolicyEditorScope.Global,
                    loaded = true,
                    unreadable = (stored as? StoredGlobalPolicy.Unreadable)?.why,
                    draft = draft,
                    stored = draft,
                    storedAt = policy?.updatedAt,
                )
        }
    }

    /** Opens one connection's override document and the global document it inherits from. */
    fun open(connectionId: String) {
        val current = _state.value
        if (
            current.scope == PolicyEditorScope.Connection &&
                current.connectionId == connectionId &&
                current.loaded
        ) {
            return
        }
        _state.value =
            PolicyUiState(scope = PolicyEditorScope.Connection, connectionId = connectionId)
        viewModelScope.launch {
            val (storedGlobal, storedConnection) =
                withContext(io) { policies.getGlobal() to policies.getOverrides(connectionId) }
            val active = _state.value
            if (
                active.scope != PolicyEditorScope.Connection || active.connectionId != connectionId
            ) {
                return@launch
            }
            val global = (storedGlobal as? StoredGlobalPolicy.Policy)?.policy
            val overrides = (storedConnection as? StoredConnectionOverrides.Policy)?.overrides
            val draft = PolicyEditorDraft.Connection(connectionDraftOf(connectionId, overrides))
            _state.value =
                PolicyUiState(
                    scope = PolicyEditorScope.Connection,
                    connectionId = connectionId,
                    loaded = true,
                    unreadable = (storedConnection as? StoredConnectionOverrides.Unreadable)?.why,
                    globalUnreadable = (storedGlobal as? StoredGlobalPolicy.Unreadable)?.why,
                    global = global,
                    draft = draft,
                    stored = draft,
                    storedAt = overrides?.updatedAt,
                )
        }
    }

    /** Refreshes inherited values after the global editor closes, preserving the local draft. */
    fun refreshGlobal() {
        val before = _state.value
        if (before.scope != PolicyEditorScope.Connection || !before.loaded) return
        val connectionId = before.connectionId ?: return
        viewModelScope.launch {
            val stored = withContext(io) { policies.getGlobal() }
            _state.update { current ->
                if (
                    current.scope != PolicyEditorScope.Connection ||
                        current.connectionId != connectionId
                ) {
                    current
                } else {
                    current.copy(
                        global = (stored as? StoredGlobalPolicy.Policy)?.policy,
                        globalUnreadable = (stored as? StoredGlobalPolicy.Unreadable)?.why,
                    )
                }
            }
        }
    }

    /** The owner changed the active draft. Nothing is written until Save. */
    fun edit(draft: PolicyEditorDraft) {
        if (!_state.value.loaded || _state.value.unreadable != null) return
        _state.update { it.copy(draft = draft) }
    }

    /** Explicitly replaces an active document this build could not read. */
    fun startOver() {
        val state = _state.value
        val draft =
            when (state.scope) {
                PolicyEditorScope.Global -> PolicyEditorDraft.Global(globalDraftOf(null))
                PolicyEditorScope.Connection ->
                    PolicyEditorDraft.Connection(
                        connectionDraftOf(state.connectionId ?: return, null)
                    )
                null -> return
            }
        _state.update {
            it.copy(
                unreadable = null,
                replacing = true,
                draft = draft,
                stored = draft,
                storedAt = null,
            )
        }
    }

    /** Resets only the connection draft to inheritance. Nothing is deleted until Save. */
    fun resetConnectionOverrides() {
        val state = _state.value
        if (state.scope != PolicyEditorScope.Connection || state.unreadable != null) return
        val connectionId = state.connectionId ?: return
        edit(PolicyEditorDraft.Connection(ConnectionPolicyDraft(connectionId)))
    }

    /** Writes the active draft to its own document, or removes only that document when empty. */
    fun save() {
        val state = _state.value
        if (state.saving || !state.loaded || state.unreadable != null) return
        val writing = state.draft ?: return
        val document = documentOf(writing, now()) ?: return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val written =
                withContext(io) {
                    try {
                        when (document) {
                            is Document.Global ->
                                if (document.policy == null) policies.deleteGlobal()
                                else policies.putGlobal(document.policy)
                            is Document.Connection ->
                                if (document.overrides == null) {
                                    policies.delete(document.connectionId)
                                } else {
                                    policies.putOverrides(document.overrides)
                                }
                        }
                        true
                    } catch (_: IOException) {
                        false
                    }
                }
            _state.update { current ->
                if (current.scope != state.scope || current.connectionId != state.connectionId) {
                    return@update current
                }
                if (!written) {
                    return@update current.copy(
                        saving = false,
                        message = PolicyMessage.SaveFailed,
                    )
                }
                current.copy(
                    saving = false,
                    replacing = false,
                    stored = writing,
                    storedAt = document.updatedAt,
                    message =
                        if (document.updatedAt == null) PolicyMessage.Removed
                        else PolicyMessage.Saved,
                )
            }
        }
    }

    /** The next open reads its document again. */
    fun close() {
        _state.value = PolicyUiState()
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private sealed interface Document {
        val updatedAt: Instant?

        data class Global(val policy: GlobalPolicy?) : Document {
            override val updatedAt: Instant?
                get() = policy?.updatedAt
        }

        data class Connection(
            val connectionId: String,
            val overrides: ConnectionPolicyOverrides?,
        ) : Document {
            override val updatedAt: Instant?
                get() = overrides?.updatedAt
        }
    }

    /** Null means validation refused the write; a document containing null means delete it. */
    private fun documentOf(draft: PolicyEditorDraft, at: Instant): Document? =
        when (draft) {
            is PolicyEditorDraft.Global ->
                when (val review = draft.rules.copy(connectionId = GLOBAL_DRAFT_ID).review(at)) {
                    DraftReview.NoRules -> Document.Global(null)
                    is DraftReview.Problems -> null
                    is DraftReview.Ready ->
                        Document.Global(
                            GlobalPolicy(
                                actions = review.policy.actions,
                                assets = review.policy.assets,
                                recipients = review.policy.recipients,
                                programs = review.policy.programs,
                                limits = review.policy.limits,
                                updatedAt = review.policy.updatedAt,
                            )
                        )
                }
            is PolicyEditorDraft.Connection ->
                when (val review = draft.rules.review(at)) {
                    ConnectionDraftReview.InheritAll ->
                        Document.Connection(draft.rules.connectionId, null)
                    is ConnectionDraftReview.Problems -> null
                    is ConnectionDraftReview.Ready ->
                        Document.Connection(draft.rules.connectionId, review.overrides)
                }
        }
}

/** A distinct ViewModel key whose only purpose is retaining the global form above a local form. */
class GlobalPolicyEditorViewModel(
    policies: PolicyStore,
    now: () -> Instant = Instant::now,
    io: CoroutineDispatcher = Dispatchers.IO,
) : PolicyEditorViewModel(policies, now, io)
