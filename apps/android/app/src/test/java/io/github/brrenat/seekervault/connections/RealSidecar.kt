package io.github.brrenat.seekervault.connections

import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The real MCP server from this repository (`node servers/mcp-server/src/cli.ts start`) on a free
 * loopback port with a throwaway database and tokens, plus the operator's `pnpm pair` commands
 * against it. It serves the demo tool `vault_request_ack` (MCP_DEMO_TOOLS), and [stop] and [start]
 * restart it on the same port and database. Needs Node 24 on PATH and `pnpm install`.
 */
class RealSidecar(private val productionUpdates: Boolean = false) : AutoCloseable {
    private val sidecarDir =
        File(
            checkNotNull(System.getProperty("seekervault.repoRoot")) {
                "run this test through Gradle"
            },
            "servers/mcp-server",
        )
    private val port = ServerSocket(0).use { it.localPort }
    val url = "http://127.0.0.1:$port"
    private val updatePort = if (productionUpdates) ServerSocket(0).use { it.localPort } else null
    val updateUrl: String?
        get() = updatePort?.let { "http://127.0.0.1:$it" }

    private val env =
        mapOf(
            "SIDECAR_HOST" to "127.0.0.1",
            "SIDECAR_PORT" to "$port",
            "MCP_TOKEN" to MCP_TOKEN,
            "PHONE_TOKEN" to PHONE_TOKEN,
            "LIVE_COMMAND_TIMEOUT_SECONDS" to "30",
            "MCP_DEMO_TOOLS" to "true",
            // A throwaway database, never the developer's stable MCP server database.
            "DATABASE_PATH" to
                Files.createTempDirectory("seeker-vault-sidecar").resolve("sidecar.db").toString(),
        ) + (updatePort?.let { mapOf("SIDECAR_UPDATE_PORT" to "$it") } ?: emptyMap())
    private val log = StringBuffer()
    private var process: Process? = null
    private var clockAheadSeconds = 0L

    init {
        check(File(sidecarDir, "node_modules").isDirectory) { "run pnpm install first" }
        start()
    }

    /** Everything the sidecar has logged so far, across restarts. */
    val output: String
        get() = log.toString()

    /**
     * Starts the sidecar on its port and database. [clockAheadSeconds] runs its clock that far
     * ahead of the real one (`servers/mcp-server/src/testing/clock.ts`), for time that passed while
     * it was down. The clock never goes back.
     */
    fun start(clockAheadSeconds: Long = this.clockAheadSeconds) {
        check(process == null) { "the sidecar is already running" }
        this.clockAheadSeconds = maxOf(this.clockAheadSeconds, clockAheadSeconds)
        val started =
            if (this.clockAheadSeconds == 0L) {
                node("src/cli.ts", "start")
            } else {
                node(
                    "--import",
                    "./src/testing/clock.ts",
                    "src/cli.ts",
                    "start",
                    extra =
                        mapOf("SIDECAR_TEST_CLOCK_AHEAD_MS" to "${this.clockAheadSeconds * 1000}"),
                )
            }
        val output = started.inputStream.bufferedReader()
        while (true) {
            val line = output.readLine() ?: error("the sidecar exited before listening:\n$log")
            log.appendLine(line)
            if ("listening on" in line) break
        }
        thread(isDaemon = true) { output.forEachLine { log.appendLine(it) } }
        process = started
    }

    /** Stops the sidecar with SIGTERM, or with SIGKILL when [kill] is set, as a crash would. */
    fun stop(kill: Boolean = false) {
        val running = process ?: return
        if (kill) running.destroyForcibly() else running.destroy()
        running.waitFor(10, TimeUnit.SECONDS)
        process = null
    }

    /** [stop], then [start] on the same port and database. */
    fun restart(kill: Boolean = false, clockAheadSeconds: Long = this.clockAheadSeconds) {
        stop(kill)
        start(clockAheadSeconds)
    }

    /** Runs `pnpm pair` and returns the pairing code it printed as text. */
    fun pairingCode(): String = cli().lines().first { it.startsWith("seekervault://pair?") }

    /** Runs `pnpm pair revoke`. It works on the database, whether or not the sidecar is running. */
    fun revokePairedPhone(): String = cli("revoke")

    /**
     * Stores a PENDING ack request, as an agent does with `vault_request_ack`, and returns its
     * request ID. [expiresInSeconds] is the request's lifetime; the sidecar's default otherwise.
     */
    fun requestAck(text: String, idempotencyKey: String, expiresInSeconds: Int? = null): String {
        val lifetime =
            if (expiresInSeconds == null) "" else ""","expires_in_seconds":$expiresInSeconds"""
        val view =
            tool(
                "vault_request_ack",
                """{"text":${quote(text)},"idempotency_key":${quote(idempotencyKey)}$lifetime}""",
            )
        check("\"PENDING\"" in view) { "vault_request_ack didn't store a request: $view" }
        return checkNotNull(REQUEST_ID.find(view)?.groupValues?.get(1)) { "no request ID: $view" }
    }

    /** The request's status as the agent reads it with `vault_get_request`, such as COMPLETED. */
    fun status(requestId: String): String =
        statusOf(tool("vault_get_request", """{"request_id":"$requestId"}"""))

    /**
     * Cancels the request as the agent does with `vault_cancel_request`, and returns its status.
     */
    fun cancel(requestId: String): String =
        statusOf(tool("vault_cancel_request", """{"request_id":"$requestId"}"""))

    // Calls one MCP tool as the agent, and returns the structured result, or the error, as JSON.
    private fun tool(name: String, arguments: String): String {
        val agent =
            node(
                "--input-type=module",
                "-e",
                AGENT_SCRIPT,
                extra = mapOf("MCP_URL" to "$url/mcp", "TOOL" to name, "ARGS" to arguments),
            )
        val result = agent.inputStream.bufferedReader().readText()
        check(agent.waitFor(30, TimeUnit.SECONDS) && agent.exitValue() == 0) {
            "the agent failed: $result"
        }
        return result
    }

    private fun statusOf(view: String): String =
        checkNotNull(STATUS.find(view)?.groupValues?.get(1)) { "no status: $view" }

    private fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    override fun close() = stop()

    private fun cli(vararg args: String): String {
        val cli = node("src/cli.ts", "pair", *args)
        val output = cli.inputStream.bufferedReader().readText()
        check(cli.waitFor(30, TimeUnit.SECONDS) && cli.exitValue() == 0) {
            "pnpm pair ${args.joinToString(" ")} failed:\n$output"
        }
        return output
    }

    private fun node(vararg args: String, extra: Map<String, String> = emptyMap()): Process =
        ProcessBuilder(listOf("node") + args)
            .directory(sidecarDir)
            .redirectErrorStream(true)
            .apply { environment().putAll(env + extra) }
            .start()

    companion object {
        val MCP_TOKEN = "m".repeat(64)
        val PHONE_TOKEN = "p".repeat(64)

        private val REQUEST_ID = Regex("\"request_id\":\"([0-9a-f-]{36})\"")
        private val STATUS = Regex("\"status\":\"([A-Z]+)\"")

        // Stands in for Hermes: one durable tool call that prints the structured result.
        private val AGENT_SCRIPT =
            """
            import { Client } from "@modelcontextprotocol/sdk/client/index.js";
            import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
            const client = new Client({ name: "android-connections-test", version: "0.0.0" });
            await client.connect(new StreamableHTTPClientTransport(new URL(process.env.MCP_URL), {
              requestInit: { headers: { Authorization: "Bearer " + process.env.MCP_TOKEN } },
            }));
            const result = await client.callTool({
              name: process.env.TOOL,
              arguments: JSON.parse(process.env.ARGS),
            });
            console.log(JSON.stringify(result.isError ? result.content : result.structuredContent));
            await client.close();
            """
                .trimIndent()
    }
}
