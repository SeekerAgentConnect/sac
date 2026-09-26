package io.github.brrenat.seekervault.designpreviews

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.brrenat.seekervault.activity.ActivityScreen
import io.github.brrenat.seekervault.activity.ActivityScreenCallbacks
import io.github.brrenat.seekervault.activity.ActivityScreenRow
import io.github.brrenat.seekervault.activity.ActivityScreenState
import io.github.brrenat.seekervault.connections.RequestKey
import io.github.brrenat.seekervault.designsystem.ActivityRowKind
import io.github.brrenat.seekervault.designsystem.ScreenNavigationCallbacks
import io.github.brrenat.seekervault.designsystem.preview.DesignRef
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme

@DesignRef(component = "screens", variant = "activity")
@Preview(
    name = "screens/activity",
    widthDp = 390,
    heightDp = 844,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ActivityScreenPreview() {
    SeekerTheme(darkTheme = true) {
        ActivityScreen(
            state =
                ActivityScreenState(
                    title = "Activity",
                    rows =
                        listOf(
                            activityPreviewRow(
                                id = "transfer-one",
                                title = "0.4 SOL",
                                supportingText = "Confirmed on the network · 8:38 PM",
                                kind = ActivityRowKind.Transfer,
                            ),
                            activityPreviewRow(
                                id = "transfer-unknown",
                                title = "1.1 SOL",
                                supportingText = "Outcome unknown · 8:37 PM",
                                kind = ActivityRowKind.Unknown,
                            ),
                            activityPreviewRow(
                                id = "transfer-two",
                                title = "4.5 SOL",
                                supportingText = "Confirmed on the network · 8:04 PM",
                                kind = ActivityRowKind.Transfer,
                            ),
                            activityPreviewRow(
                                id = "signature",
                                title = "Message signature",
                                supportingText = "Message signed · 7:52 PM",
                                kind = ActivityRowKind.Signature,
                            ),
                            activityPreviewRow(
                                id = "acknowledgement",
                                title = "Acknowledgement",
                                supportingText = "Acknowledged · 7:42 PM",
                                kind = ActivityRowKind.Acknowledgement,
                            ),
                        ),
                    footer =
                        "Every request you answer is recorded here, and the record stays after " +
                            "the request itself is gone.",
                ),
            callbacks = ActivityPreviewCallbacks,
        )
    }
}

private fun activityPreviewRow(
    id: String,
    title: String,
    supportingText: String,
    kind: ActivityRowKind,
) =
    ActivityScreenRow(
        key = RequestKey("preview", id),
        title = title,
        supportingText = supportingText,
        kind = kind,
        testTag = "activityPreview:$id",
    )

private val ActivityPreviewCallbacks =
    ActivityScreenCallbacks(
        onOpen = {},
        onRefresh = {},
        onClear = {},
        onBack = {},
        navigation =
            ScreenNavigationCallbacks(
                onHome = {},
                onInbox = {},
                onWallet = {},
                onActivity = {},
            ),
    )
