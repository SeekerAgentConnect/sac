package io.github.brrenat.seekervault.connections

import java.net.URLEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The phone's reading of pairing codes, against the MCP server's rules
 * (`packages/server-sdk/src/pairing/uri.ts`).
 */
class PairingCodeTest {
    private val serverId = "1f0e2d3c-4b5a-4698-8776-5a4b3c2d1e0f"
    private val token = "Lq3v7Yk2Qm9XwTzR4bN8cJ1dH6fG0sA5eP-uV_iKoLw"

    // The debug build's network security config: cleartext to 127.0.0.1 and localhost only.
    private val debug: (String) -> Boolean = { it == "127.0.0.1" || it == "localhost" }
    private val release: (String) -> Boolean = { false }

    private fun uri(
        url: String = "https://vault.example.com",
        server: String = serverId,
        token: String = this.token,
        version: String = "1",
    ) =
        "seekervault://pair?v=$version&url=${URLEncoder.encode(url, Charsets.UTF_8)}" +
            "&server=$server&token=$token"

    private fun codeOf(text: String, cleartext: (String) -> Boolean = debug) =
        (PairingCodes.parse(text, cleartext) as PairingCodeResult.Valid).code

    private fun problemOf(text: String, cleartext: (String) -> Boolean = debug) =
        (PairingCodes.parse(text, cleartext) as? PairingCodeResult.Invalid)?.problem

    @Test
    fun readsTheCodeThatPnpmPairPrints() {
        // The sidecar's own example (uri.test.ts), byte for byte.
        val text =
            "seekervault://pair?v=1&url=https%3A%2F%2Fvault.example.com%2Fseeker" +
                "&server=$serverId&token=$token"
        assertEquals(
            PairingCode("https://vault.example.com/seeker", serverId, token),
            codeOf(text, release),
        )
        assertEquals(codeOf(text), codeOf("  $text\n"), "surrounding white space is ignored")
    }

    @Test
    fun readsTheHttpsLandingPageAsTheSameCode() {
        val landing =
            "https://vault.example.com/pair?v=1&url=${URLEncoder.encode("https://vault.example.com", Charsets.UTF_8)}" +
                "&server=$serverId&token=$token"
        assertEquals(
            PairingCode("https://vault.example.com", serverId, token),
            codeOf(landing, release),
        )
        assertEquals(
            PairingCode("http://127.0.0.1:8080", serverId, token),
            codeOf(
                "http://127.0.0.1:8080/pair?v=1&url=${URLEncoder.encode("http://127.0.0.1:8080", Charsets.UTF_8)}" +
                    "&server=$serverId&token=$token"
            ),
        )
        assertEquals(
            PairingCodeProblem.NotSeekerVault,
            problemOf("https://vault.example.com/invite?v=1&url=https%3A%2F%2Fvault.example.com"),
        )
    }

    @Test
    fun acceptsPlainHttpOnlyToLoopbackAndOnlyWherethePlatformAllowsIt() {
        assertEquals("http://127.0.0.1:8080", codeOf(uri("http://127.0.0.1:8080")).serverUrl)
        assertEquals("http://localhost:8080", codeOf(uri("http://localhost:8080")).serverUrl)
        // A release build permits no cleartext at all.
        assertEquals(
            PairingCodeProblem.InsecureServerUrl,
            problemOf(uri("http://127.0.0.1:8080"), release),
        )
        for (url in
            listOf("http://192.168.1.20:8080", "http://[::1]:8080", "ws://127.0.0.1:8080")) {
            assertEquals(url, PairingCodeProblem.InsecureServerUrl, problemOf(uri(url)))
        }
    }

    @Test
    fun normalizesTheUrlAsTheSidecarDoes() {
        assertEquals(
            "https://vault.example.com/seeker",
            codeOf(uri("https://Vault.Example.com:443/seeker/")).serverUrl,
        )
        assertEquals(
            "https://vault.tailnet.ts.net:8443",
            codeOf(uri("https://vault.tailnet.ts.net:8443/")).serverUrl,
        )
        assertEquals("127.0.0.1:8080", PairingCodes.hostOf("http://127.0.0.1:8080"))
        assertEquals("vault.example.com", PairingCodes.hostOf("https://vault.example.com/seeker"))
    }

    @Test
    fun refusesMalformedCodesAndSaysWhy() {
        val encoded = URLEncoder.encode("https://vault.example.com", Charsets.UTF_8)
        val cases =
            listOf(
                "not a code" to PairingCodeProblem.NotACode,
                "vault.example.com" to PairingCodeProblem.NotACode,
                "" to PairingCodeProblem.NotACode,
                "https://vault.example.com" to PairingCodeProblem.NotSeekerVault,
                "seekervault://connect?v=1" to PairingCodeProblem.NotSeekerVault,
                uri(version = "2") to PairingCodeProblem.OtherVersion,
                "seekervault://pair?url=$encoded&server=$serverId&token=$token" to
                    PairingCodeProblem.OtherVersion,
                "seekervault://pair?v=1&server=$serverId&token=$token" to
                    PairingCodeProblem.BadServerUrl,
                uri("vault.example.com") to PairingCodeProblem.BadServerUrl,
                uri("https://owner:secret@vault.example.com") to PairingCodeProblem.BadServerUrl,
                uri("https://vault.example.com/?token=1") to PairingCodeProblem.BadServerUrl,
                uri("https://vault.example.com/#pair") to PairingCodeProblem.BadServerUrl,
                uri(server = "vault") to PairingCodeProblem.BadServerId,
                uri(server = serverId.uppercase()) to PairingCodeProblem.BadServerId,
                uri(token = token.dropLast(1)) to PairingCodeProblem.BadToken,
                uri(token = "$token=") to PairingCodeProblem.BadToken,
                uri(token = "") to PairingCodeProblem.BadToken,
                "seekervault://pair?v=1&url=$encoded&server=$serverId" to
                    PairingCodeProblem.BadToken,
            )
        for ((text, expected) in cases) {
            assertEquals(text, expected, problemOf(text))
        }
    }

    @Test
    fun neverPrintsTheToken() {
        val code = PairingCode("https://vault.example.com", serverId, token)
        assertFalse(token in code.toString())
        assertFalse(token in PairingCodeResult.Valid(code).toString())
        assertFalse(token in PairedConnection(serverId, token, serverId).toString())
    }

    private fun assertEquals(expected: Any?, actual: Any?, message: String) =
        assertEquals(message, expected, actual)
}
