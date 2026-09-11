package io.github.brrenat.seekervault.connections

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.connectrpc.okhttp.ConnectOkHttpClient
import io.github.brrenat.seekervault.connections.storage.ConnectionStore
import io.github.brrenat.seekervault.connections.storage.CredentialVault
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** One phone paired with two real sidecars at once: the connections stay independent. */
@RunWith(AndroidJUnit4::class)
class TwoSidecarsTest {
    @get:Rule val folder = TemporaryFolder()

    private val a = RealSidecar()
    private val b = RealSidecar()
    private val key = softwareKey()

    @After
    fun stop() {
        a.close()
        b.close()
    }

    private fun code(sidecar: RealSidecar): PairingCode =
        (PairingCodes.parse(sidecar.pairingCode()) { it == "127.0.0.1" } as PairingCodeResult.Valid)
            .code

    @Test
    fun removingOneServersConnectionLeavesTheOther() = runBlocking {
        val repository =
            ConnectionRepository(
                store = ConnectionStore(File(folder.root, "connections")),
                vault = CredentialVault(File(folder.root, "credentials")) { key },
                gateway =
                    ConnectConnectionGateway(
                        ConnectOkHttpClient.configureClient(OkHttpClient.Builder()).build()
                    ),
                deviceName = "Seeker",
                io = Dispatchers.IO,
            )
        val connectionA = repository.pair(code(a))
        val connectionB = repository.pair(code(b))
        a.requestAck("For A only", "a-1")
        repository.refresh(connectionA.id)
        repository.refresh(connectionB.id)
        assertEquals(1, repository.connection(connectionA.id)?.lastCheck?.pending)
        assertEquals(0, repository.connection(connectionB.id)?.lastCheck?.pending)

        repository.disconnect(connectionA.id)
        assertNull(repository.connection(connectionA.id))
        assertTrue(a.output.contains("connection ${connectionA.id} revoked by the phone"))
        repository.refresh(connectionB.id)
        val stillB = checkNotNull(repository.connection(connectionB.id))
        assertTrue(stillB.usable)
        assertEquals(0, stillB.lastCheck?.pending)
        // B's sidecar never saw A's connection, and A's request never reached B.
        assertFalse(connectionA.id in b.output)
    }
}
