package io.github.brrenat.seekervault.connections

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.designsystem.SourceColour
import io.github.brrenat.seekervault.designsystem.theme.SeekerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The connection sheet's colour card, on Robolectric. */
@RunWith(AndroidJUnit4::class)
class ServerColourScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun theSheetNamesTheCurrentColourAndAColourAnotherServerUses() {
        val chosen = mutableListOf<ServerColour>()
        val home =
            ConnectionsScreenTest.HOME.copy(label = "studio-mac", colour = ServerColour.Tangerine)
        val other = ConnectionsScreenTest.VPS.copy(label = "hermes-box", colour = ServerColour.Sky)
        compose.setContent {
            SeekerTheme {
                ConnectionDetailLibraryScreen(
                    connection = home,
                    connections = listOf(home, other),
                    refreshing = false,
                    disconnect = null,
                    message = null,
                    overrideCount = 0,
                    onBack = {},
                    onRefresh = {},
                    onRename = { null },
                    onDisconnect = {},
                    onConfirmDisconnect = {},
                    onConfirmRemove = {},
                    onDismissDisconnect = {},
                    onMessageShown = {},
                    onRules = {},
                    onInbox = {},
                    onPairDirect = {},
                    onColour = { chosen += it },
                )
            }
        }

        compose
            .onNodeWithText("Tangerine · marks this server everywhere")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("Sky · used by hermes-box").performClick()
        compose.onNodeWithContentDescription("Sand").assertExists()
        assertEquals(listOf(ServerColour.Sky), chosen)
    }

    @Test
    fun homeCopiesAServersColourOntoItsRowAndItsWaitingPill() {
        val home = ConnectionsScreenTest.HOME.copy(label = "studio-mac", colour = ServerColour.Rose)
        val state =
            homeScreenState(
                connectionsState = ConnectionsUiState(connections = listOf(home), loaded = true),
                inboxSummary = null,
                wallet = null,
                pendingItems = emptyList(),
                requestAssessments = emptyMap(),
            )
        assertEquals(SourceColour.Rose, state.servers.single().model.sourceColour)
    }
}
