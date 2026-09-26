package io.github.brrenat.seekervault.wallet

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Which wallet app the adapter opens (SEE-159). Every association carries the route stored with the
 * owner's account, so the wallet they connected is the one that opens — the first time, every time,
 * and after a restart. The wallet is a [FakeWalletClient], so what is exercised here is the aim, not
 * a handshake: what the adapter asks a session to be, and what it refuses to open at all.
 */
@RunWith(AndroidJUnit4::class)
class MwaWalletAdapterRoutingTest {
    private val selected =
        SelectedWallet(
            address = WALLET,
            network = WalletNetwork.Devnet,
            selectedAt = Instant.parse("2026-09-26T10:00:00Z"),
        )

    private val opened = mutableListOf<FakeWalletClient>()
    private val targets = FakeWalletTargets(listOf(seeker, other))
    private var wallet: FakeWalletClient.() -> Unit = {
        authorizes(WALLET, REFRESHED, uriBase = URI_BASE)
        sends = listOf(ByteArray(64) { 3 })
    }
    private val adapter =
        MwaWalletAdapter(
            identity =
                ConnectionIdentity(
                    identityUri = Uri.parse("https://seekervault.example"),
                    iconUri = Uri.parse("icon.png"),
                    identityName = "Seeker Vault",
                ),
            sender = { null },
            targets = targets,
            io = Dispatchers.Unconfined,
            clients = { network, target ->
                FakeWalletClient(network, target).apply(wallet).also { opened += it }
            },
        )

    private val transaction = ByteString.copyFrom(ByteArray(215) { (it * 7).toByte() })

    @Test
    fun offersTheWalletAppsThisPhoneHas() = runBlocking {
        // Only ever what the system reported. Nothing here is a package name the app made up.
        assertEquals(listOf(seeker, other), adapter.installed())
    }

    @Test
    fun connectingAimsAtTheWalletAppTheOwnerPicked() = runBlocking {
        val route = WalletRouting(packageName = seeker.packageName, appLabel = seeker.label)

        val result = adapter.connect(WalletNetwork.Devnet, null, route)

        assertEquals(WalletTarget.App(seeker.packageName), opened.single().target)
        val connected = result as WalletResult.Connected
        // The app the owner picked, and the association URI the wallet itself reported, are what
        // the phone now holds: the next approval opens that app without asking anybody.
        assertEquals(
            WalletRouting(uriBase = URI_BASE, packageName = seeker.packageName, appLabel = "Seeker"),
            connected.route,
        )
    }

    @Test
    fun connectingWithNoRouteLeavesTheChoiceToAndroidAndLearnsNoApp() = runBlocking {
        val result = adapter.connect(WalletNetwork.Devnet, null, null)

        assertEquals(WalletTarget.Wide, opened.single().target)
        // Android resolved it, so this phone was never told which app answered and doesn't pretend
        // it was. What the wallet said about itself is kept, and that is enough to route by.
        assertEquals(WalletRouting(uriBase = URI_BASE), (result as WalletResult.Connected).route)
    }

    @Test
    fun signingAndSendingOpensTheSameWalletApp() = runBlocking {
        val route =
            WalletRouting(uriBase = URI_BASE, packageName = seeker.packageName, appLabel = "Seeker")

        val answer = adapter.signAndSendTransaction(transaction, selected, SECRET, route)

        // The wallet's own association URI comes first, narrowed to the app the owner connected.
        assertEquals(
            WalletTarget.Endpoint(URI_BASE, seeker.packageName),
            opened.single().target,
        )
        assertTrue(answer.result is SendResult.Sent)
        assertEquals(REFRESHED, answer.authToken)
        assertEquals(URI_BASE, answer.uriBase)
    }

    @Test
    fun signingOpensTheSameWalletAppWithNoUriToGoOn() = runBlocking {
        wallet = { authorizes(WALLET, REFRESHED, uriBase = null) }
        val route = WalletRouting(packageName = seeker.packageName, appLabel = "Seeker")

        adapter.signMessage(ByteString.copyFromUtf8("hello"), selected, SECRET, route)

        assertEquals(WalletTarget.App(seeker.packageName), opened.single().target)
    }

    @Test
    fun refusesToSignWhenTheWalletAppTheOwnerConnectedIsGone() = runBlocking {
        targets.wallets = listOf(other)
        val route =
            WalletRouting(uriBase = URI_BASE, packageName = seeker.packageName, appLabel = "Seeker")

        val signing = adapter.signMessage(ByteString.copyFromUtf8("hello"), selected, SECRET, route)
        val sending = adapter.signAndSendTransaction(transaction, selected, SECRET, route)

        // Nothing was opened, so the other installed wallet never saw either request: an approval
        // the owner gave for one wallet is not inherited by another.
        assertEquals(emptyList<FakeWalletClient>(), opened)
        assertEquals(SignResult.NoWallet, signing.result)
        assertEquals(SendResult.NoWallet, sending.result)
        assertNull(signing.authToken)
        assertNull(sending.uriBase)
    }

    @Test
    fun connectingAgainAsksAndroidWhenTheAppTheOwnerHadIsGone() = runBlocking {
        targets.wallets = listOf(other)
        val route = WalletRouting(packageName = seeker.packageName, appLabel = "Seeker")

        adapter.connect(WalletNetwork.Devnet, SECRET, route)

        // Connecting is the owner choosing a wallet, so an app that has gone doesn't stop them the
        // way it stops a signing: it is dropped, and they are asked.
        assertEquals(WalletTarget.Wide, opened.single().target)
    }

    @Test
    fun disconnectingTellsNobodyWhenTheWalletAppIsGone() = runBlocking {
        targets.wallets = emptyList()
        val route = WalletRouting(packageName = seeker.packageName)
        // Nothing lists the app, but a launch would still be the authority, so it is told.
        adapter.disconnect(selected, SECRET, route)
        assertEquals(listOf<String?>(SECRET), opened.single().closes)

        opened.clear()
        targets.wallets = listOf(other)
        adapter.disconnect(selected, SECRET, route)
        // Now this phone does say the app is gone. There is nobody to tell, and nothing to undo.
        assertEquals(emptyList<FakeWalletClient>(), opened)
    }

    @Test
    fun keepsOneSessionPerWalletAppAndNeverReusesAnothers() = runBlocking {
        val seekerRoute = WalletRouting(packageName = seeker.packageName)
        val otherRoute = WalletRouting(packageName = other.packageName)

        adapter.signAndSendTransaction(transaction, selected, SECRET, seekerRoute)
        adapter.signAndSendTransaction(transaction, selected, SECRET, seekerRoute)
        adapter.signAndSendTransaction(transaction, selected, SECRET, otherRoute)

        // Two sessions for three interactions: the first two shared one, and the third wallet app
        // got its own rather than inheriting a session that was opened at another.
        assertEquals(
            listOf(
                WalletTarget.App(seeker.packageName),
                WalletTarget.App(other.packageName),
            ),
            opened.map { it.target },
        )
    }

    private companion object {
        val seeker = InstalledWallet("com.example.seekerwallet", "Seeker")
        val other = InstalledWallet("com.example.otherwallet", "Other Wallet")
        const val WALLET = "3YKUMU99pedShDEe76HuSAHo3dt9CXjBwjN8w8NUo9Wh"
        const val SECRET = "authorization-the-wallet-issued-0123456789"
        const val REFRESHED = "authorization-the-wallet-issued-later-9876543210"
        const val URI_BASE = "https://wallet.example/ul"
    }
}
