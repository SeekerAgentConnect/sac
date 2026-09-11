package io.github.brrenat.seekervault.connections

import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The real sidecar from this repository (`node sidecar/src/main.ts`) on a free loopback port with a
 * throwaway database and tokens, plus the operator's `pnpm pair` commands against it. Needs Node 24
 * on PATH and `pnpm install`.
 */
class RealSidecar : AutoCloseable {
    private val sidecarDir =
        File(
            checkNotNull(System.getProperty("seekervault.repoRoot")) {
                "run this test through Gradle"
            },
            "sidecar",
        )
    private val port = ServerSocket(0).use { it.localPort }
    val url = "http://127.0.0.1:$port"
    private val env =
        mapOf(
            "SIDECAR_HOST" to "127.0.0.1",
            "SIDECAR_PORT" to "$port",
            "MCP_TOKEN" to MCP_TOKEN,
            "PHONE_TOKEN" to PHONE_TOKEN,
            "LIVE_COMMAND_TIMEOUT_SECONDS" to "30",
            // A throwaway database, never the developer's sidecar/data/sidecar.db.
            "DATABASE_PATH" to
                Files.createTempDirectory("seeker-vault-sidecar").resolve("sidecar.db").toString(),
        )
    private val log = StringBuffer()
    private val process: Process

    init {
        check(File(sidecarDir, "node_modules").isDirectory) { "run pnpm install first" }
        process = node("src/main.ts")
        val output = process.inputStream.bufferedReader()
        while (true) {
            val line = output.readLine() ?: error("the sidecar exited before listening:\n$log")
            log.appendLine(line)
            if ("listening on" in line) break
        }
        thread(isDaemon = true) { output.forEachLine { log.appendLine(it) } }
    }

    /** Everything the sidecar has logged so far. */
    val output: String
        get() = log.toString()

    /** Runs `pnpm pair` and returns the pairing code it printed as text. */
    fun pairingCode(): String = cli().lines().first { it.startsWith("seekervault://pair?") }

    /** Runs `pnpm pair revoke`. */
    fun revokePairedPhone(): String = cli("revoke")

    /** Stores a PENDING ack request, as an agent does with `vault_request_ack`. */
    fun requestAck(text: String, idempotencyKey: String) {
        val agent =
            node(
                "--input-type=module",
                "-e",
                AGENT_SCRIPT,
                extra = mapOf("MCP_URL" to "$url/mcp", "TEXT" to text, "KEY" to idempotencyKey),
            )
        val result = agent.inputStream.bufferedReader().readText()
        check(agent.waitFor(30, TimeUnit.SECONDS) && agent.exitValue() == 0) {
            "the agent failed: $result"
        }
        check("\"PENDING\"" in result) { "vault_request_ack didn't store a request: $result" }
    }

    override fun close() {
        process.destroy()
        process.waitFor(10, TimeUnit.SECONDS)
    }

    private fun cli(vararg args: String): String {
        val cli = node("src/pairing/cli.ts", *args)
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
              name: "vault_request_ack",
              arguments: { text: process.env.TEXT, idempotency_key: process.env.KEY },
            });
            console.log(JSON.stringify(result.isError ? result.content : result.structuredContent));
            await client.close();
            """
                .trimIndent()
    }
}
