package io.github.brrenat.seekervault.config

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.brrenat.seekervault.config.storage.ClientConfigStore
import io.github.brrenat.seekervault.gateway.v1.ClientConfig as WireClientConfig
import io.github.brrenat.seekervault.plugins.ServiceFeeTerms
import io.github.brrenat.seekervault.request.v1.Network
import io.github.brrenat.seekervault.server.v1.SolanaNetwork
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * The trusted gateway's client configuration on this phone: asked for, validated, cached and aged
 * (SEE-185, docs/wiki/client-config.md#the-policy).
 *
 * The rule every case here comes back to: no usable configuration is said as such, and it is never
 * a zero fee. A gateway that answered "no fee" and a gateway that could not be asked are two
 * different things, and only the first prepares anything.
 */
@RunWith(AndroidJUnit4::class)
class ClientConfigRepositoryTest {
    @get:Rule val folder = TemporaryFolder()

    private var clock = Instant.parse("2026-10-01T12:00:00Z")

    /** What the gateway answers next, or throws. */
    private var answer: () -> WireClientConfig = { wireConfig(revision = 0) }
    private var fetched = 0

    private val dir by lazy { File(folder.root, "config") }

    private fun repository(gateway: String = GATEWAY_URL) =
        ClientConfigRepository(
            gatewayUrl = gateway,
            store = ClientConfigStore(dir),
            fetch = { url ->
                assertEquals(gateway, url)
                fetched++
                answer()
            },
            allowCleartext = false,
            now = { clock },
            io = Dispatchers.Unconfined,
        )

    /** Moves the clock on, so the next refresh is a new question rather than a joined one. */
    private fun later(by: Duration = Duration.ofMinutes(1)) {
        clock = clock.plus(by)
    }

    private val configured =
        wireConfig(
            revision = 3,
            rpc = listOf(SolanaNetwork.SOLANA_NETWORK_MAINNET to MAINNET_URL),
            fee = wireFee(bps = 20),
        )

    @Test
    fun revisionZeroIsAnAnswerAndItIsUsableWithNoFee() = runBlocking {
        val config = repository()

        val usable = config.forOperation() as ConfigAvailability.Usable

        assertEquals(ClientConfig(0, emptyMap(), SwapFeeConfig.Zero), usable.config)
        assertEquals(false, usable.cached)
        assertEquals(ServiceFeeTerms.None, usable.config.swapFee.termsFor(Network.NETWORK_MAINNET))
        assertEquals(1, fetched)
    }

    @Test
    fun aGatewayThatCannotBeAskedWithNothingCachedIsUnavailableAndNeverAZeroFee() = runBlocking {
        answer = { throw IOException("offline") }
        val config = repository()

        val availability = config.forOperation()

        assertTrue(availability is ConfigAvailability.Unavailable)
        assertTrue(
            (availability as ConfigAvailability.Unavailable).problem is ConfigProblem.Unreachable
        )
        assertNull(config.current)
        assertTrue(config.held() is ConfigAvailability.Unavailable)
    }

    @Test
    fun aCachedConfigurationStandsInForTheGatewayFor24HoursAndNotOneMoment() = runBlocking {
        answer = { configured }
        val config = repository()
        config.refresh()
        val fresh = checkNotNull(config.current)

        answer = { throw IOException("offline") }
        later(MAX_CONFIG_AGE.minusMinutes(1))
        val stillUsable = config.forOperation()
        assertEquals(ConfigAvailability.Usable(fresh, cached = true), stillUsable)
        // The failure is said beside it: the phone knows it could not ask.
        assertTrue(config.state.value.problem is ConfigProblem.Unreachable)
        assertEquals(ConfigAvailability.Usable(fresh, cached = true), config.held())

        later(Duration.ofMinutes(2))
        val tooOld = config.forOperation()
        assertTrue(tooOld is ConfigAvailability.Unavailable)
        assertTrue(config.held() is ConfigAvailability.Unavailable)
        // What was sent already may still be read through the cached endpoints, whatever their age.
        assertEquals(fresh, config.current)
    }

    @Test
    fun anInvalidAnswerIsAProblemAndTheValidatedCacheIsKeptUnderItsOwnAge() = runBlocking {
        answer = { configured }
        val config = repository()
        config.refresh()
        val held = checkNotNull(config.current)

        // The operator saved a fee the phone refuses: never partly used, never a zero fee.
        answer = { wireConfig(revision = 4, fee = wireFee(bps = 500)) }
        later()
        val after = config.refresh()
        assertEquals(ConfigProblem.Invalid(ConfigInvalid.BadRate), after.problem)
        assertEquals(held, after.config)
        assertEquals(ConfigAvailability.Usable(held, cached = true), config.forOperation())

        // And once the cache is too old, the invalid answer is the reason nothing is prepared.
        later(MAX_CONFIG_AGE)
        assertEquals(
            ConfigAvailability.Unavailable(ConfigProblem.Invalid(ConfigInvalid.BadRate)),
            config.forOperation(),
        )

        // With nothing cached at all, an invalid answer is simply unavailable.
        File(folder.root, "other").mkdirs()
        val empty =
            ClientConfigRepository(
                gatewayUrl = GATEWAY_URL,
                store = ClientConfigStore(File(folder.root, "other")),
                fetch = { wireConfig(fee = wireFee(owner = "")) },
                allowCleartext = false,
                now = { clock },
                io = Dispatchers.Unconfined,
            )
        assertEquals(
            ConfigAvailability.Unavailable(ConfigProblem.Invalid(ConfigInvalid.IncompleteFee)),
            empty.forOperation(),
        )
    }

    @Test
    fun theValidatedAnswerOutlivesARestartWithTheTimeItWasFetched() = runBlocking {
        answer = { configured }
        repository().refresh()
        val fetchedAt = clock

        // A new process: the same file, a new repository, and a gateway that cannot be reached.
        answer = { throw IOException("offline") }
        later(Duration.ofHours(2))
        val restarted = repository()

        val held = checkNotNull(restarted.current)
        assertEquals(3L, held.revision)
        assertEquals(mapOf(Network.NETWORK_MAINNET to MAINNET_URL), held.rpc)
        assertEquals(feeConfig(bps = 20), held.swapFee)
        assertEquals(fetchedAt, restarted.state.value.fetchedAt)
        assertEquals(ConfigAvailability.Usable(held, cached = true), restarted.forOperation())

        // Its age is counted from when it was fetched, not from when the process started.
        later(MAX_CONFIG_AGE)
        assertTrue(repository().forOperation() is ConfigAvailability.Unavailable)
    }

    @Test
    fun aCacheFileThisBuildCannotReadIsNoCacheAtAll() = runBlocking {
        answer = { configured }
        repository().refresh()
        val file = File(dir, "client.json")
        assertTrue(file.exists())

        file.writeText("not json")
        assertNull(repository().current)
        file.writeText("""{"version":99}""")
        assertNull(repository().current)
    }

    @Test
    fun listenersHearOnlyAboutAConfigurationThatChanged() = runBlocking {
        val heard = mutableListOf<Pair<Long?, Long>>()
        answer = { configured }
        val config = repository()
        config.onChange { previous, current -> heard += previous?.revision to current.revision }

        config.refresh()
        assertEquals(listOf<Pair<Long?, Long>>(null to 3L), heard)

        // The same answer again: nothing to hear.
        later()
        config.refresh()
        assertEquals(listOf<Pair<Long?, Long>>(null to 3L), heard)

        // A failure, and an invalid answer: nothing in effect changed, so nothing is heard.
        answer = { throw IOException("offline") }
        later()
        config.refresh()
        answer = { wireConfig(revision = 9, fee = wireFee(bps = 500)) }
        later()
        config.refresh()
        assertEquals(listOf<Pair<Long?, Long>>(null to 3L), heard)

        // A new fee is heard once, and is in effect when it is.
        answer = { configured.toBuilder().setRevision(4).setSwapFee(wireFee(bps = 30)).build() }
        later()
        config.refresh()
        assertEquals(listOf(null to 3L, 3L to 4L), heard)
        assertEquals(30, config.current?.swapFee?.bps)
    }

    @Test
    fun aBuildWithNoGatewayAsksNobodyAndSaysSo() = runBlocking {
        val config = repository(gateway = "")

        assertEquals(ConfigAvailability.Unavailable(ConfigProblem.NoGateway), config.forOperation())
        assertEquals(0, fetched)
    }

    private companion object {
        const val GATEWAY_URL = "https://gateway.example.com"
        const val MAINNET_URL = "https://mainnet.rpc.example.com"
    }
}
