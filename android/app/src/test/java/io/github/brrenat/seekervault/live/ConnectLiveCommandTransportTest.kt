package io.github.brrenat.seekervault.live

import com.connectrpc.okhttp.ConnectOkHttpClient
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The real transport against the real sidecar, without a device: runs `node sidecar/src/main.ts`
 * and sends text through its MCP endpoint with the MCP SDK, checking the Android client's HTTP/1.1
 * streaming, headers, and error mapping. Needs Node 24 on PATH and `pnpm install`.
 */
class ConnectLiveCommandTransportTest {
    private val sidecarDir =
        File(
            checkNotNull(System.getProperty("seekervault.repoRoot")) {
                "run this test through Gradle"
            },
            "sidecar",
        )
    private val httpClient = ConnectOkHttpClient.configureClient(OkHttpClient.Builder()).build()
    private lateinit var sidecar: Process
    private lateinit var baseUrl: String

    @Before
    fun startSidecar() {
        check(File(sidecarDir, "node_modules").isDirectory) { "run pnpm install first" }
        val port = ServerSocket(0).use { it.localPort }
        baseUrl = "http://127.0.0.1:$port"
        sidecar =
            node(
                "src/main.ts",
                env =
                    mapOf(
                        "SIDECAR_HOST" to "127.0.0.1",
                        "SIDECAR_PORT" to "$port",
                        "MCP_TOKEN" to MCP_TOKEN,
                        "PHONE_TOKEN" to PHONE_TOKEN,
                        "LIVE_COMMAND_TIMEOUT_SECONDS" to "30",
                        // A throwaway database, never the developer's sidecar/data/sidecar.db.
                        "DATABASE_PATH" to
                            java.nio.file.Files.createTempDirectory("seeker-vault-sidecar")
                                .resolve("sidecar.db")
                                .toString(),
                    ),
                mergeErrors = true,
            )
        val output = sidecar.inputStream.bufferedReader()
        while (true) {
            val line = output.readLine() ?: error("the sidecar exited before listening")
            if ("listening on" in line) break
        }
        thread(isDaemon = true) { output.forEachLine {} } // keep the pipe drained
    }

    @After
    fun stopSidecar() {
        sidecar.destroy()
        sidecar.waitFor(10, TimeUnit.SECONDS)
    }

    @Test
    fun deliversTheAgentsTextAndReturnsTheOk() = runBlocking {
        val transport = ConnectLiveCommandTransport(baseUrl, PHONE_TOKEN, httpClient)
        val events = Channel<WatchEvent>(Channel.UNLIMITED)
        val watching = launch { transport.watch().collect { events.send(it) } }
        try {
            withTimeout(30_000) {
                assertEquals(WatchEvent.Ready, events.receive())
                val text = "Hello from the Kotlin transport 👋🏽\nSecond line"
                val agent =
                    node(
                        "--input-type=module",
                        "-e",
                        AGENT_SCRIPT,
                        env =
                            mapOf(
                                "MCP_URL" to "$baseUrl/mcp",
                                "MCP_TOKEN" to MCP_TOKEN,
                                "TEXT" to text,
                            ),
                    )
                val command = (events.receive() as WatchEvent.Command).command
                assertEquals(text, command.text)
                transport.acknowledge(command.id)
                val result =
                    withContext(Dispatchers.IO) {
                        agent.inputStream.bufferedReader().readText().also { agent.waitFor() }
                    }
                assertEquals("""{"id":"${command.id}","result":"OK"}""", result.trim())
            }
        } finally {
            watching.cancel()
        }
    }

    @Test
    fun refusesAWrongPhoneToken() = runBlocking {
        val error = failureOf {
            ConnectLiveCommandTransport(baseUrl, "wrong-token", httpClient).watch().first()
        }
        assertEquals(LiveTransportException.Kind.Unauthenticated, error.kind)
    }

    @Test
    fun reportsAnAcknowledgementForAnUnknownCommand() = runBlocking {
        val error = failureOf {
            ConnectLiveCommandTransport(baseUrl, PHONE_TOKEN, httpClient)
                .acknowledge("not-a-command")
        }
        assertEquals(LiveTransportException.Kind.UnknownCommand, error.kind)
    }

    @Test
    fun failsTheStreamWhenTheSidecarStops() = runBlocking {
        val transport = ConnectLiveCommandTransport(baseUrl, PHONE_TOKEN, httpClient)
        val ready = CompletableDeferred<Unit>()
        val ended = CompletableDeferred<Throwable?>()
        launch {
            try {
                transport.watch().collect { if (it == WatchEvent.Ready) ready.complete(Unit) }
                ended.complete(null)
            } catch (e: LiveTransportException) {
                ended.complete(e)
            }
        }
        withTimeout(30_000) {
            ready.await()
            sidecar.destroy() // SIGTERM: a graceful stop ends the stream with `unavailable`
            val error = ended.await()
            assertEquals(
                LiveTransportException.Kind.Unreachable,
                (error as? LiveTransportException)?.kind,
            )
        }
    }

    private suspend fun failureOf(block: suspend () -> Unit): LiveTransportException =
        try {
            withTimeout(30_000) { block() }
            error("expected a LiveTransportException")
        } catch (e: LiveTransportException) {
            e
        }

    private fun node(vararg args: String, env: Map<String, String>, mergeErrors: Boolean = false) =
        ProcessBuilder(listOf("node") + args)
            .directory(sidecarDir)
            .apply {
                environment().putAll(env)
                if (mergeErrors) redirectErrorStream(true)
                else redirectError(ProcessBuilder.Redirect.INHERIT)
            }
            .start()

    private companion object {
        val MCP_TOKEN = "m".repeat(64)
        val PHONE_TOKEN = "p".repeat(64)

        // Stands in for Hermes: one MCP tool call that prints the structured result.
        val AGENT_SCRIPT =
            """
            import { Client } from "@modelcontextprotocol/sdk/client/index.js";
            import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
            const client = new Client({ name: "android-transport-test", version: "0.0.0" });
            await client.connect(new StreamableHTTPClientTransport(new URL(process.env.MCP_URL), {
              requestInit: { headers: { Authorization: "Bearer " + process.env.MCP_TOKEN } },
            }));
            const result = await client.callTool(
              { name: "vault_display_command", arguments: { text: process.env.TEXT } },
              undefined,
              { timeout: 25000 },
            );
            console.log(JSON.stringify(result.isError ? result.content : result.structuredContent));
            await client.close();
            """
                .trimIndent()
    }
}
