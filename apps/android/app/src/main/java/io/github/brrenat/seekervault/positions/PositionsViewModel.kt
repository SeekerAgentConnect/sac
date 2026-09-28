package io.github.brrenat.seekervault.positions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.brrenat.seekervault.plugins.HeldPosition
import io.github.brrenat.seekervault.plugins.PluginDestination
import io.github.brrenat.seekervault.plugins.ProviderRegistry
import io.github.brrenat.seekervault.wallet.SelectedWallet
import io.github.brrenat.seekervault.wallet.WalletRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the History item's position block and its sale sheet ask of [PositionTracker] (SEE-172).
 *
 * Reading is free to repeat. Selling goes through exactly one call, [sell], which hands the
 * reviewed bytes to the wallet under the wallet's own lock and never twice; everything else here
 * only reads or forgets.
 */
class PositionsViewModel(
    private val tracker: PositionTracker,
    private val wallet: WalletRepository,
    private val providers: () -> ProviderRegistry,
) : ViewModel() {

    val state: StateFlow<PositionsState> = tracker.state

    val selected: StateFlow<SelectedWallet?> = wallet.wallet

    private val _notices = MutableStateFlow<Map<String, SellOutcome>>(emptyMap())

    /** What the last approval came to, by position, when it did not reach the wallet. */
    val notices: StateFlow<Map<String, SellOutcome>> = _notices.asStateFlow()

    fun refresh(account: String, force: Boolean = true) {
        viewModelScope.launch { tracker.refresh(account, force) }
    }

    /** Builds and reads a sale for review. Opening the sheet asks for this; nothing is signed. */
    fun prepareSale(account: String) {
        _notices.update { it - account }
        viewModelScope.launch { tracker.prepareSale(account, wallet.wallet.value) }
    }

    /** Hands the reviewed sale of [account] to the wallet, if it is still the one reviewed. */
    fun sell(account: String) {
        val draft =
            (tracker.state.value.reviews[account] as? SaleReviewState.Ready)?.draft ?: return
        viewModelScope.launch {
            val outcome =
                tracker.sell(
                    draft = draft,
                    selected = { wallet.wallet.value },
                    withWallet = { block -> wallet.withWallet { session -> block(session) } },
                )
            if (outcome != SellOutcome.Handed) _notices.update { it + (account to outcome) }
            // Whatever happened, the position is read again: a sale sent is followed, and a
            // refusal is checked against what the provider holds now.
            tracker.refresh(account, force = true)
        }
    }

    /** The owner closed the review without selling. */
    fun closeSale(account: String) {
        _notices.update { it - account }
        tracker.discard(account)
    }

    fun destinations(held: HeldPosition): List<PluginDestination> =
        providers().byId(held.provider)?.positions?.destinations(held).orEmpty()
}

/** The words for an approval that did not reach the wallet. */
fun SellOutcome.notice(): String? =
    when (this) {
        SellOutcome.Handed -> null
        SellOutcome.Stale -> "This review ran out. Prepare it again for current terms."
        SellOutcome.Changed ->
            "The position changed since you reviewed it. Prepare the sale again to see what " +
                "would be sold now."
        SellOutcome.Busy -> "Another sale of this position is still being settled."
        SellOutcome.WrongWallet ->
            "The selected wallet isn't the one this position belongs to, on its network."
        SellOutcome.NotApprovable -> "This sale can't be approved."
    }
