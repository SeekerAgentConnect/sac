package io.github.brrenat.seekervault.activity

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.brrenat.seekervault.R
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.designsystem.ActivityRow
import io.github.brrenat.seekervault.designsystem.ActivityRowKind
import io.github.brrenat.seekervault.designsystem.ActivityRowModel
import io.github.brrenat.seekervault.designsystem.EmptyState
import io.github.brrenat.seekervault.designsystem.EmptyStateScreen
import io.github.brrenat.seekervault.designsystem.ScreenCaption
import io.github.brrenat.seekervault.designsystem.ScreenDestination
import io.github.brrenat.seekervault.designsystem.ScreenNavigationCallbacks
import io.github.brrenat.seekervault.designsystem.ScreenScaffold
import io.github.brrenat.seekervault.designsystem.ScreenScrollBody
import io.github.brrenat.seekervault.transactions.LAMPORT_DECIMALS
import io.github.brrenat.seekervault.transactions.formatBaseUnits
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Display-only state for [ActivityScreen]. */
data class ActivityScreenState(
    val title: String,
    val rows: List<ActivityScreenRow>,
    val footer: String? = null,
    val empty: ActivityEmptyState? = null,
    val unreadableMessage: String? = null,
)

data class ActivityScreenRow(
    val key: RequestKey,
    val title: String,
    val supportingText: String,
    val kind: ActivityRowKind,
    val testTag: String,
)

data class ActivityEmptyState(val title: String, val body: String)

/** Every interaction owned by the Activity route. */
data class ActivityScreenCallbacks(
    val onOpen: ((RequestKey) -> Unit)?,
    val onRefresh: () -> Unit,
    val onClear: () -> Unit,
    val onBack: () -> Unit,
    val navigation: ScreenNavigationCallbacks,
)

/** Keeps history collection and ViewModel actions outside the display-only screen. */
@Composable
fun ActivityRoute(
    viewModel: ActivityViewModel,
    onBack: () -> Unit,
    navigationCallbacks: ScreenNavigationCallbacks,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ActivityScreen(
        state = activityScreenState(state),
        callbacks =
            ActivityScreenCallbacks(
                // The design flow has no Activity-detail destination. Rows report history only.
                onOpen = null,
                onRefresh = viewModel::refresh,
                onClear = viewModel::clear,
                onBack = onBack,
                navigation = navigationCallbacks,
            ),
        modifier = modifier,
    )
}

/** Activity composed entirely from the shared design-system library. */
@Composable
fun ActivityScreen(
    state: ActivityScreenState,
    callbacks: ActivityScreenCallbacks,
    modifier: Modifier = Modifier,
) {
    ScreenScaffold(
        title = state.title,
        selectedDestination = ScreenDestination.Activity,
        navigationCallbacks = callbacks.navigation,
        onBack = callbacks.onBack,
        modifier = modifier,
    ) {
        ScreenScrollBody(modifier = Modifier.testTag(ActivityTags.LIST)) {
            state.unreadableMessage?.let { message ->
                EmptyState(
                    screen = EmptyStateScreen.Activity,
                    title = null,
                    body = message,
                    modifier =
                        Modifier.testTag(ActivityTags.UNREADABLE).semantics(
                            mergeDescendants = true
                        ) {},
                )
            }
            state.empty?.let { empty ->
                EmptyState(
                    screen = EmptyStateScreen.Activity,
                    title = empty.title,
                    body = empty.body,
                    modifier =
                        Modifier.testTag(ActivityTags.EMPTY).semantics(mergeDescendants = true) {},
                )
            }
            state.rows.forEach { row ->
                ActivityRow(
                    model = ActivityRowModel(row.title, row.supportingText),
                    kind = row.kind,
                    onClick = callbacks.onOpen?.let { open -> { open(row.key) } },
                    modifier = Modifier.testTag(row.testTag).semantics(mergeDescendants = true) {},
                )
            }
            state.footer?.let { footer -> ScreenCaption(text = footer) }
        }
    }
}

@Composable
internal fun activityScreenState(state: ActivityUiState): ActivityScreenState =
    ActivityScreenState(
        title = stringResource(R.string.activity_title),
        rows = state.records.map { it.toScreenRow() },
        footer =
            state.records
                .takeIf { it.isNotEmpty() }
                ?.let {
                    stringResource(R.string.activity_footer)
                },
        empty =
            if (state.loaded && state.records.isEmpty()) {
                ActivityEmptyState(
                    title = stringResource(R.string.activity_empty_title),
                    body = stringResource(R.string.activity_empty_body),
                )
            } else {
                null
            },
        unreadableMessage =
            if (state.unreadable) stringResource(R.string.activity_unreadable) else null,
    )

@Composable
private fun ActivityRecord.toScreenRow(): ActivityScreenRow =
    ActivityScreenRow(
        key = key,
        title = activityRowTitle(this),
        supportingText =
            stringResource(
                R.string.activity_summary,
                stringResource(recordOutcomeText(this)),
                activityTimeFormatter.format(answeredAt),
            ),
        kind = activityRowKind(this),
        testTag = ActivityTags.item(this),
    )

@Composable
private fun activityRowTitle(record: ActivityRecord): String {
    val transfer = record.transfer ?: return operationText(record)
    val amount = transfer.amount.toULongOrNull()
    return if (transfer.mint == null && amount != null) {
        stringResource(
            R.string.activity_row_sol_amount,
            formatBaseUnits(amount, LAMPORT_DECIMALS),
        )
    } else {
        operationText(record)
    }
}

private fun activityRowKind(record: ActivityRecord): ActivityRowKind =
    when {
        record.outcome == ActivityOutcome.Unknown -> ActivityRowKind.Unknown
        record.kind == ActivityKind.Acknowledgement -> ActivityRowKind.Acknowledgement
        record.kind == ActivityKind.MessageSignature -> ActivityRowKind.Signature
        record.kind == ActivityKind.Transfer || record.kind == ActivityKind.Operation ->
            ActivityRowKind.Transfer
        else -> ActivityRowKind.Unknown
    }

private val activityTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())
