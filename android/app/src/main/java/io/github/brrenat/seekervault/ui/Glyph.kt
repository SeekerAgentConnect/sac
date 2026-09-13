package io.github.brrenat.seekervault.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Paid
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The design's icons (SEE-57).
 *
 * The prototype draws Phosphor Icons and says to substitute the codebase's own set. This codebase
 * had none, so Material's outlined set is that substitute, mapped once here by what each icon
 * *means* rather than by what it is called — so a screen asks for [Glyph.Transfer], not for a coin,
 * and swapping the set later is one file.
 */
object Glyph {
    /** A tab, and the app's own root. */
    val Home: ImageVector = Icons.Outlined.Home

    /** Requests waiting to be answered. */
    val Requests: ImageVector = Icons.Outlined.Inbox

    val Wallet: ImageVector = Icons.Outlined.AccountBalanceWallet

    /** What this phone has already done. */
    val Activity: ImageVector = Icons.Outlined.History

    /** Funds moving. */
    val Transfer: ImageVector = Icons.Outlined.Paid

    /** A message the wallet would sign. */
    val Signature: ImageVector = Icons.Outlined.Draw

    /** An acknowledgement: nothing moves, nothing is signed. */
    val Acknowledge: ImageVector = Icons.Outlined.Check

    /** A verdict that matched the rules the owner set. */
    val WithinRules: ImageVector = Icons.Outlined.VerifiedUser

    /** A verdict under restrictions, and any other warning. */
    val Warning: ImageVector = Icons.Outlined.WarningAmber

    /** The rules themselves. */
    val Rules: ImageVector = Icons.Outlined.Tune

    /** A program a transaction calls. */
    val Program: ImageVector = Icons.Outlined.Code

    val Network: ImageVector = Icons.Outlined.Public
    val Back: ImageVector = Icons.Outlined.ChevronLeft
    val Forward: ImageVector = Icons.Outlined.ChevronRight
    val Next: ImageVector = Icons.Outlined.ArrowForward
    val Refresh: ImageVector = Icons.Outlined.Refresh
    val Close: ImageVector = Icons.Outlined.Close
    val Add: ImageVector = Icons.Outlined.Add
    val Scan: ImageVector = Icons.Outlined.QrCodeScanner
}
