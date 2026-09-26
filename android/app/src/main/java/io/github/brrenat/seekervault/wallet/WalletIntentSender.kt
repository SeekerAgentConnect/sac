package io.github.brrenat.seekervault.wallet

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.withResumed
import com.solana.mobilewalletadapter.common.AssociationContract

/**
 * How the app opens a wallet from the screen that is on it (SEE-159). Mobile Wallet Adapter runs a
 * wallet from an Activity, so [MainActivity][io.github.brrenat.seekervault.MainActivity] registers
 * one of these in `onCreate` and the wallet adapter asks for it when it associates.
 *
 * Mobile Wallet Adapter ships its own `ActivityResultSender` doing exactly this, but its launch
 * method is `internal` to the library, so it can only ever send the intent the library built — the
 * one with no wallet in it. This app builds the association intent itself, aimed at the wallet the
 * owner connected, so it needs its own launcher. Everything else about the handshake is the
 * library's: the scenario, the intent's shape, the session, and the protocol.
 */
class WalletIntentSender(private val activity: ComponentActivity) {
    /** Whether a launch hasn't come back yet. One at a time: an Activity result has one home. */
    private var waiting = false

    private val launcher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            waiting = false
        }

    /**
     * Opens [intent]. It waits for the screen to be resumed first, which is what guarantees any
     * earlier result has already been delivered and its slot cleared; a screen that is being
     * destroyed instead cancels the caller's job.
     *
     * [ActivityNotFoundException] is thrown on through, because it is how this phone learns that
     * the wallet the intent named isn't there to open.
     */
    suspend fun send(intent: Intent) {
        activity.lifecycle.withResumed {
            check(!waiting) { "a wallet is already being opened" }
            waiting = true
            try {
                launcher.launch(intent)
            } catch (e: ActivityNotFoundException) {
                waiting = false
                throw e
            }
        }
    }
}

/**
 * The wallet apps installed on this phone, read from `PackageManager` (SEE-159).
 *
 * The intent it asks about is Mobile Wallet Adapter's own local association: the scheme and the
 * path are the library's constants, not strings this app made up, and the answer is whatever the
 * system says handles them. The app therefore never holds a wallet's package name it wasn't told,
 * and a wallet that isn't installed can't be named at all. The library's manifest already declares
 * the matching `<queries>` entry, so this works under Android 11's package visibility without the
 * app asking for anything.
 */
class PackageWalletTargets(private val packages: PackageManager) : WalletTargets {
    override fun installed(): List<InstalledWallet> =
        packages
            .queryIntentActivities(association(), PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { resolved ->
                val activity = resolved.activityInfo ?: return@mapNotNull null
                InstalledWallet(
                    packageName = activity.packageName,
                    label =
                        resolved.loadLabel(packages).toString().ifBlank { activity.packageName },
                )
            }
            // One wallet app can answer with several activities; it is still one wallet.
            .distinctBy { it.packageName }

    private companion object {
        /**
         * An association intent with nothing in it but its shape, which is all the system needs to
         * say who would handle a real one. The port and token of a real association are per-session
         * and say nothing about which app answers.
         */
        fun association(): Intent =
            Intent()
                .setAction(Intent.ACTION_VIEW)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setData(
                    Uri.Builder()
                        .scheme(AssociationContract.SCHEME_MOBILE_WALLET_ADAPTER)
                        .appendEncodedPath(AssociationContract.LOCAL_PATH_SUFFIX)
                        .build()
                )
    }
}
