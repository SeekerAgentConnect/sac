package io.github.brrenat.seekervault

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.brrenat.seekervault.wallet.WalletNetwork

/**
 * A full-height destination. Tabs are peers; [HistoryDetail] returns to Inbox on Back,
 * [AddConnection] to the tab it was opened from, and the other screens to [Home].
 */
sealed interface AppScreen {
    sealed interface Tab : AppScreen

    data object Home : Tab

    data object Inbox : Tab

    /** The feed catalog a configured gateway recommends (SEE-176). */
    data object Discover : Tab

    data object Wallet : Tab

    data object Activity : Tab

    /**
     * Pairing or adding a feed. [from] is the tab it returns to: Home's FAB, or a Discover card's
     * Connect / Request access with the feed's reference prefilled (SEE-176).
     */
    data class AddConnection(val from: Tab = Home) : AppScreen {
        init {
            require(from == Home || from == Discover) {
                "Add connection opens from Home or Discover"
            }
        }
    }

    /**
     * The read-only record behind one Inbox History row (SEE-161). A full page rather than a sheet,
     * with no bottom navigation; Back returns to the History tab it was opened from.
     */
    data class HistoryDetail(val identity: ReviewIdentity) : AppScreen

    /** Retained for the explicit diagnostic activity entry; it has no in-app navigation edge. */
    data object LiveTest : AppScreen
}

/** The two request namespaces that can drive the shared review destination. */
sealed interface ReviewIdentity {
    val connectionId: String
    val requestId: String

    data class Private(
        override val connectionId: String,
        override val requestId: String,
    ) : ReviewIdentity {
        init {
            requireRouteId("connectionId", connectionId)
            requireRouteId("requestId", requestId)
        }
    }

    data class Signal(
        override val connectionId: String,
        override val requestId: String,
    ) : ReviewIdentity {
        init {
            requireRouteId("connectionId", connectionId)
            requireRouteId("requestId", requestId)
        }
    }
}

/** The design currently specifies one wallet hand-off presentation. */
enum class WalletHandoffKind {
    Transfer,
    Signature,
    Operation,
}

enum class AddressEditorKind {
    Recipient,
    Program,
}

enum class AssetEditorKind {
    Allowlisted,
    SpendingLimit,
}

/** A bottom-sheet destination. Every item is immutable route data, never an editor draft. */
sealed interface AppSheet {
    data class RequestReview(val identity: ReviewIdentity) : AppSheet

    data class WalletHandoff(
        val identity: ReviewIdentity,
        val kind: WalletHandoffKind = WalletHandoffKind.Transfer,
    ) : AppSheet

    /**
     * The owner's own part of a signal — side and stake — edited in a sheet stacked over its
     * review: `[review, params]` (design/navigation.md, SEE-158).
     */
    data class OwnerInput(val identity: ReviewIdentity) : AppSheet

    /**
     * Reviewing the sale of the position a History item's purchase went into (SEE-172), over that
     * item's own page and nowhere else.
     */
    data class PositionSale(val identity: ReviewIdentity) : AppSheet

    data class ConnectionDetail(val connectionId: String) : AppSheet {
        init {
            requireRouteId("connectionId", connectionId)
        }
    }

    /** Choosing one saved wallet for the connection detail directly beneath it (SEE-178). */
    data class ConnectionWallet(val connectionId: String) : AppSheet {
        init {
            requireRouteId("connectionId", connectionId)
        }
    }

    /** Adding a wallet from the Wallet tab or from a connection's wallet picker (SEE-178). */
    data class AddWallet(val presetNetwork: WalletNetwork? = null) : AppSheet

    /**
     * One Discover card's details (SEE-176), over the Discover tab and nowhere else. It is the
     * catalog's own identity — the gateway origin and the server ID — never a connection: opening
     * it adds nothing.
     */
    data class CatalogDetail(val gatewayUrl: String, val serverId: String) : AppSheet {
        init {
            requireRouteId("gatewayUrl", gatewayUrl)
            requireRouteId("serverId", serverId)
        }
    }

    data class ConnectionRules(val connectionId: String) : AppSheet {
        init {
            requireRouteId("connectionId", connectionId)
        }
    }

    data object GlobalRules : AppSheet

    /** Where this phone asks each Solana network (SEE-184), over the Wallet tab. */
    data object RpcSettings : AppSheet

    /** [assetId] is null for Add and identifies an existing draft row for Edit. */
    data class AssetEditor(
        val connectionId: String,
        val kind: AssetEditorKind,
        val assetId: String? = null,
    ) : AppSheet {
        init {
            requireRouteId("connectionId", connectionId)
            assetId?.let { requireRouteId("assetId", it) }
        }
    }

    data class AddressEditor(
        val connectionId: String,
        val kind: AddressEditorKind,
    ) : AppSheet {
        init {
            requireRouteId("connectionId", connectionId)
        }
    }
}

/** The whole navigation graph at one instant. The root stays mounted while sheets are pushed. */
data class NavigationState(
    val screen: AppScreen = AppScreen.Home,
    val sheets: List<AppSheet> = emptyList(),
)

/**
 * The single transition owner for the exported user-flow graph.
 *
 * Methods return false instead of mutating when an edge is absent from the flow. That makes an
 * accidental route from the wrong screen observable without turning an ordinary double tap into a
 * crash.
 */
class AppNavigator(initialState: NavigationState = NavigationState()) {
    var state by mutableStateOf(initialState.requireValid())
        private set

    /** Peer-tab navigation replaces the base and closes every sheet. */
    fun selectTab(tab: AppScreen.Tab): Boolean = replace(NavigationState(tab))

    /** Home's FAB opens Add connection, which returns to Home. */
    fun openAddConnection(): Boolean {
        if (state.screen != AppScreen.Home || state.sheets.isNotEmpty()) return false
        return replace(NavigationState(AppScreen.AddConnection()))
    }

    /**
     * A Discover card's Connect or Request access (SEE-176): the same Add connection screen, which
     * returns to Discover. From the tab itself, never over a sheet.
     */
    fun openCatalogConnect(): Boolean {
        if (state.screen != AppScreen.Discover || state.sheets.isNotEmpty()) return false
        return replace(NavigationState(AppScreen.AddConnection(from = AppScreen.Discover)))
    }

    /** A Discover card's details (SEE-176). */
    fun openCatalogDetail(gatewayUrl: String, serverId: String): Boolean =
        push(AppSheet.CatalogDetail(gatewayUrl, serverId))

    /**
     * A History row, or a notification about an item that has since closed (SEE-161). From Inbox
     * itself, or in place of a review that turned out to be about a closed item; never over a sheet
     * the owner is working in.
     */
    fun openHistoryDetail(identity: ReviewIdentity): Boolean {
        val fromInbox = state.screen == AppScreen.Inbox && state.sheets.isEmpty()
        val fromReview =
            state.sheets.size == 1 &&
                (state.sheets.single() as? AppSheet.RequestReview)?.identity == identity
        if (!fromInbox && !fromReview) return false
        return replace(NavigationState(AppScreen.HistoryDetail(identity)))
    }

    /** Home carousel tiles and Inbox pending rows share this destination. */
    fun openReview(identity: ReviewIdentity): Boolean = push(AppSheet.RequestReview(identity))

    /** A production transfer can hand off only from its own review. */
    fun openWalletHandoff(
        identity: ReviewIdentity,
        kind: WalletHandoffKind = WalletHandoffKind.Transfer,
    ): Boolean = push(AppSheet.WalletHandoff(identity, kind))

    fun openConnectionDetail(connectionId: String): Boolean =
        push(AppSheet.ConnectionDetail(connectionId))

    fun openConnectionWallet(connectionId: String): Boolean =
        push(AppSheet.ConnectionWallet(connectionId))

    fun openAddWallet(network: WalletNetwork? = null): Boolean = push(AppSheet.AddWallet(network))

    fun openConnectionRules(connectionId: String): Boolean =
        push(AppSheet.ConnectionRules(connectionId))

    /** Sell the position a History item's purchase went into, over that item's page. */
    fun openPositionSale(identity: ReviewIdentity): Boolean = push(AppSheet.PositionSale(identity))

    /** Choose or change the owner's part of a signal, over its own review. */
    fun openOwnerInput(identity: ReviewIdentity): Boolean = push(AppSheet.OwnerInput(identity))

    fun openGlobalRules(): Boolean = push(AppSheet.GlobalRules)

    fun openRpcSettings(): Boolean = push(AppSheet.RpcSettings)

    fun openAssetEditor(
        connectionId: String,
        kind: AssetEditorKind,
        assetId: String? = null,
    ): Boolean = push(AppSheet.AssetEditor(connectionId, kind, assetId))

    fun openAddressEditor(connectionId: String, kind: AddressEditorKind): Boolean =
        push(AppSheet.AddressEditor(connectionId, kind))

    /**
     * Finishes an answered review from either the review itself or its hand-off. Leaving a hand-off
     * without answering uses [back] instead and therefore preserves the review.
     */
    fun closeReview(identity: ReviewIdentity): Boolean {
        val review = state.sheets.firstOrNull() as? AppSheet.RequestReview ?: return false
        if (review.identity != identity) return false
        if (
            state.sheets.size == 2 &&
                (state.sheets.lastOrNull() as? AppSheet.WalletHandoff)?.identity != identity
        ) {
            return false
        }
        if (state.sheets.size !in 1..2) return false
        return replace(state.copy(sheets = emptyList()))
    }

    /**
     * Pops one sheet, returns a record's details to Inbox, or returns another non-tab screen to
     * Home. Back on a tab is left to Android.
     */
    fun back(): Boolean =
        when {
            state.sheets.isNotEmpty() -> replace(state.copy(sheets = state.sheets.dropLast(1)))
            state.screen is AppScreen.HistoryDetail -> replace(NavigationState(AppScreen.Inbox))
            state.screen is AppScreen.AddConnection ->
                replace(NavigationState((state.screen as AppScreen.AddConnection).from))
            state.screen !is AppScreen.Tab -> replace(NavigationState())
            else -> false
        }

    private fun push(sheet: AppSheet): Boolean {
        if (!state.canPush(sheet)) return false
        return replace(state.copy(sheets = state.sheets + sheet))
    }

    private fun replace(next: NavigationState): Boolean {
        if (state == next) return false
        state = next
        return true
    }

    companion object {
        /** Process-restorable representation containing only destination tags and route IDs. */
        val Saver: Saver<AppNavigator, List<String>> =
            Saver(
                save = { encodeNavigationState(it.state) },
                restore = { saved ->
                    decodeNavigationState(saved)?.let { restored -> AppNavigator(restored) }
                },
            )
    }
}

@Composable
fun rememberAppNavigator(initialState: NavigationState = NavigationState()): AppNavigator =
    rememberSaveable(saver = AppNavigator.Saver) { AppNavigator(initialState) }

private fun NavigationState.canPush(sheet: AppSheet): Boolean =
    when (sheet) {
        is AppSheet.RequestReview ->
            sheets.isEmpty() && (screen == AppScreen.Home || screen == AppScreen.Inbox)
        is AppSheet.WalletHandoff ->
            sheets.size == 1 &&
                (sheets.single() as? AppSheet.RequestReview)?.identity == sheet.identity
        // Over Home, or over Discover for a feed already added (SEE-176).
        is AppSheet.ConnectionDetail ->
            sheets.isEmpty() && (screen == AppScreen.Home || screen == AppScreen.Discover)
        is AppSheet.ConnectionWallet ->
            sheets.size == 1 &&
                (sheets.single() as? AppSheet.ConnectionDetail)?.connectionId == sheet.connectionId
        is AppSheet.AddWallet ->
            (sheets.isEmpty() && screen == AppScreen.Wallet) ||
                (sheets.size == 2 && sheets.last() is AppSheet.ConnectionWallet)
        is AppSheet.CatalogDetail -> sheets.isEmpty() && screen == AppScreen.Discover
        AppSheet.RpcSettings -> sheets.isEmpty() && screen == AppScreen.Wallet
        is AppSheet.OwnerInput ->
            sheets.size == 1 &&
                (sheets.single() as? AppSheet.RequestReview)?.identity == sheet.identity
        is AppSheet.PositionSale ->
            sheets.isEmpty() && (screen as? AppScreen.HistoryDetail)?.identity == sheet.identity
        // Usually `[connection, rules]`; `[review, rules]` from a review verdict's Rules action,
        // for the connection the request came from (design/navigation.md).
        is AppSheet.ConnectionRules ->
            sheets.size == 1 &&
                when (val under = sheets.single()) {
                    is AppSheet.ConnectionDetail -> under.connectionId == sheet.connectionId
                    is AppSheet.RequestReview -> under.identity.connectionId == sheet.connectionId
                    else -> false
                }
        AppSheet.GlobalRules ->
            (sheets.isEmpty() && screen == AppScreen.Home) ||
                (sheets.size == 2 && sheets.last() is AppSheet.ConnectionRules)
        is AppSheet.AssetEditor ->
            sheets.size == 2 &&
                (sheets.last() as? AppSheet.ConnectionRules)?.connectionId == sheet.connectionId
        is AppSheet.AddressEditor ->
            sheets.size == 2 &&
                (sheets.last() as? AppSheet.ConnectionRules)?.connectionId == sheet.connectionId
    }

private fun NavigationState.requireValid(): NavigationState {
    var rebuilt = NavigationState(screen)
    for (sheet in sheets) {
        require(rebuilt.canPush(sheet)) { "Invalid navigation stack: $this" }
        rebuilt = rebuilt.copy(sheets = rebuilt.sheets + sheet)
    }
    return this
}

private fun requireRouteId(name: String, value: String) {
    require(value.isNotBlank()) { "$name must not be blank" }
}

internal fun encodeNavigationState(state: NavigationState): List<String> = buildList {
    add(SAVE_VERSION)
    add(state.screen.savedTag())
    (state.screen as? AppScreen.HistoryDetail)?.let { addIdentity(it.identity) }
    state.sheets.forEach { sheet ->
        when (sheet) {
            is AppSheet.RequestReview -> {
                add(SHEET_REVIEW)
                addIdentity(sheet.identity)
            }
            is AppSheet.WalletHandoff -> {
                add(SHEET_HANDOFF)
                addIdentity(sheet.identity)
                add(sheet.kind.name)
            }
            is AppSheet.OwnerInput -> {
                add(SHEET_OWNER_INPUT)
                addIdentity(sheet.identity)
            }
            is AppSheet.PositionSale -> {
                add(SHEET_POSITION_SALE)
                addIdentity(sheet.identity)
            }
            is AppSheet.ConnectionDetail -> {
                add(SHEET_DETAIL)
                add(sheet.connectionId)
            }
            is AppSheet.ConnectionWallet -> {
                add(SHEET_CONNECTION_WALLET)
                add(sheet.connectionId)
            }
            is AppSheet.AddWallet -> {
                add(SHEET_ADD_WALLET)
                add(if (sheet.presetNetwork == null) ABSENT else PRESENT)
                sheet.presetNetwork?.let { add(it.name) }
            }
            is AppSheet.CatalogDetail -> {
                add(SHEET_CATALOG)
                add(sheet.gatewayUrl)
                add(sheet.serverId)
            }
            is AppSheet.ConnectionRules -> {
                add(SHEET_RULES)
                add(sheet.connectionId)
            }
            AppSheet.GlobalRules -> add(SHEET_GLOBAL_RULES)
            AppSheet.RpcSettings -> add(SHEET_RPC)
            is AppSheet.AssetEditor -> {
                add(SHEET_ASSET)
                add(sheet.connectionId)
                add(sheet.kind.name)
                add(if (sheet.assetId == null) ABSENT else PRESENT)
                sheet.assetId?.let(::add)
            }
            is AppSheet.AddressEditor -> {
                add(SHEET_ADDRESS)
                add(sheet.connectionId)
                add(sheet.kind.name)
            }
        }
    }
}

internal fun decodeNavigationState(saved: List<String>): NavigationState? = runCatching {
    val cursor = SavedCursor(saved)
    check(cursor.next() == SAVE_VERSION)
    val screen =
        when (val tag = cursor.next()) {
            SCREEN_HISTORY_DETAIL -> AppScreen.HistoryDetail(cursor.nextIdentity())
            else -> tag.restoredScreen()
        }
    var restored = NavigationState(screen)
    while (cursor.hasNext()) {
        val sheet =
            when (cursor.next()) {
                SHEET_REVIEW -> AppSheet.RequestReview(cursor.nextIdentity())
                SHEET_HANDOFF ->
                    AppSheet.WalletHandoff(
                        identity = cursor.nextIdentity(),
                        kind = enumValueOf(cursor.next()),
                    )
                SHEET_OWNER_INPUT -> AppSheet.OwnerInput(cursor.nextIdentity())
                SHEET_POSITION_SALE -> AppSheet.PositionSale(cursor.nextIdentity())
                SHEET_DETAIL -> AppSheet.ConnectionDetail(cursor.next())
                SHEET_CONNECTION_WALLET -> AppSheet.ConnectionWallet(cursor.next())
                SHEET_ADD_WALLET ->
                    AppSheet.AddWallet(
                        when (cursor.next()) {
                            ABSENT -> null
                            PRESENT -> enumValueOf(cursor.next())
                            else -> error("Unknown optional route value")
                        }
                    )
                SHEET_CATALOG -> AppSheet.CatalogDetail(cursor.next(), cursor.next())
                SHEET_RULES -> AppSheet.ConnectionRules(cursor.next())
                SHEET_GLOBAL_RULES -> AppSheet.GlobalRules
                SHEET_RPC -> AppSheet.RpcSettings
                SHEET_ASSET -> {
                    val connectionId = cursor.next()
                    val kind = enumValueOf<AssetEditorKind>(cursor.next())
                    val assetId =
                        when (cursor.next()) {
                            ABSENT -> null
                            PRESENT -> cursor.next()
                            else -> error("Unknown optional route value")
                        }
                    AppSheet.AssetEditor(connectionId, kind, assetId)
                }
                SHEET_ADDRESS -> AppSheet.AddressEditor(cursor.next(), enumValueOf(cursor.next()))
                else -> error("Unknown saved sheet")
            }
        check(restored.canPush(sheet))
        restored = restored.copy(sheets = restored.sheets + sheet)
    }
    restored
}
    .getOrNull()

private fun MutableList<String>.addIdentity(identity: ReviewIdentity) {
    add(
        when (identity) {
            is ReviewIdentity.Private -> IDENTITY_PRIVATE
            is ReviewIdentity.Signal -> IDENTITY_SIGNAL
        }
    )
    add(identity.connectionId)
    add(identity.requestId)
}

private fun AppScreen.savedTag(): String =
    when (this) {
        AppScreen.Home -> SCREEN_HOME
        AppScreen.Inbox -> SCREEN_INBOX
        AppScreen.Discover -> SCREEN_DISCOVER
        AppScreen.Wallet -> SCREEN_WALLET
        AppScreen.Activity -> SCREEN_ACTIVITY
        is AppScreen.AddConnection ->
            if (from == AppScreen.Discover) SCREEN_ADD_FROM_DISCOVER else SCREEN_ADD_CONNECTION
        AppScreen.LiveTest -> SCREEN_LIVE_TEST
        is AppScreen.HistoryDetail -> SCREEN_HISTORY_DETAIL
    }

private fun String.restoredScreen(): AppScreen =
    when (this) {
        SCREEN_HOME -> AppScreen.Home
        SCREEN_INBOX -> AppScreen.Inbox
        SCREEN_DISCOVER -> AppScreen.Discover
        SCREEN_WALLET -> AppScreen.Wallet
        SCREEN_ACTIVITY -> AppScreen.Activity
        SCREEN_ADD_CONNECTION -> AppScreen.AddConnection()
        SCREEN_ADD_FROM_DISCOVER -> AppScreen.AddConnection(from = AppScreen.Discover)
        SCREEN_LIVE_TEST -> AppScreen.LiveTest
        else -> error("Unknown saved screen")
    }

private class SavedCursor(private val values: List<String>) {
    private var index = 0

    fun hasNext(): Boolean = index < values.size

    fun next(): String = values.getOrElse(index++) { error("Incomplete saved navigation state") }

    fun nextIdentity(): ReviewIdentity {
        val kind = next()
        val connectionId = next()
        val requestId = next()
        return when (kind) {
            IDENTITY_PRIVATE -> ReviewIdentity.Private(connectionId, requestId)
            IDENTITY_SIGNAL -> ReviewIdentity.Signal(connectionId, requestId)
            else -> error("Unknown saved review identity")
        }
    }
}

private const val SAVE_VERSION = "1"
private const val SCREEN_HOME = "home"
private const val SCREEN_INBOX = "inbox"
private const val SCREEN_DISCOVER = "discover"
private const val SCREEN_WALLET = "wallet"
private const val SCREEN_ACTIVITY = "activity"
private const val SCREEN_ADD_CONNECTION = "add_connection"
private const val SCREEN_ADD_FROM_DISCOVER = "add_connection_discover"
private const val SCREEN_LIVE_TEST = "live_test"
private const val SCREEN_HISTORY_DETAIL = "history_detail"
private const val SHEET_REVIEW = "review"
private const val SHEET_HANDOFF = "wallet_handoff"
private const val SHEET_OWNER_INPUT = "owner_input"
private const val SHEET_POSITION_SALE = "position_sale"
private const val SHEET_DETAIL = "connection_detail"
private const val SHEET_CONNECTION_WALLET = "connection_wallet"
private const val SHEET_ADD_WALLET = "add_wallet"
private const val SHEET_CATALOG = "catalog_detail"
private const val SHEET_RULES = "connection_rules"
private const val SHEET_GLOBAL_RULES = "global_rules"
private const val SHEET_RPC = "rpc_settings"
private const val SHEET_ASSET = "asset_editor"
private const val SHEET_ADDRESS = "address_editor"
private const val IDENTITY_PRIVATE = "private"
private const val IDENTITY_SIGNAL = "signal"
private const val ABSENT = "absent"
private const val PRESENT = "present"
