package io.github.brrenat.seekervault.connections

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/**
 * A pairing code from `pnpm pair` (docs/protocol.md#pairing): the sidecar's URL, its lasting ID,
 * and a one-use pairing token.
 */
data class PairingCode(val serverUrl: String, val serverId: String, val token: String) {
    // The token is a secret: keep it out of logs, crash reports, and test failure messages.
    override fun toString() =
        "PairingCode(serverUrl=$serverUrl, serverId=$serverId, token=<redacted>)"
}

sealed interface PairingCodeResult {
    data class Valid(val code: PairingCode) : PairingCodeResult

    data class Invalid(val problem: PairingCodeProblem) : PairingCodeResult
}

/** Why a scanned or entered text can't be used to pair. */
enum class PairingCodeProblem {
    /** Not a URI at all. */
    NotACode,
    /** A URI, but not a `seekervault://pair` one. */
    NotSeekerVault,
    /** A pairing code for another version of the format. */
    OtherVersion,
    /** The server URL is missing or malformed, or has a user name, password, query, or fragment. */
    BadServerUrl,
    /** The server URL isn't HTTPS, and this build doesn't allow plain HTTP to it. */
    InsecureServerUrl,
    BadServerId,
    BadToken,
}

/**
 * Reads pairing codes by the same rules as the sidecar's `parsePairingUri`
 * (sidecar/src/pairing/uri.ts). The one difference: plain HTTP to a loopback host is accepted only
 * where the platform's network security policy permits cleartext to that host, which is in debug
 * builds (docs/development/android.md).
 */
object PairingCodes {
    const val VERSION = "1"

    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "[::1]")

    /**
     * Parses [text], a `seekervault://pair?v=1&url=…&server=…&token=…` URI. [cleartextPermitted]
     * says whether plain HTTP may reach a host (without brackets for IPv6).
     */
    fun parse(text: String, cleartextPermitted: (host: String) -> Boolean): PairingCodeResult {
        val uri =
            try {
                URI(text.trim())
            } catch (e: URISyntaxException) {
                return invalid(PairingCodeProblem.NotACode)
            }
        if (uri.scheme == null) return invalid(PairingCodeProblem.NotACode)
        if (!uri.scheme.equals("seekervault", ignoreCase = true) || uri.rawAuthority != "pair") {
            return invalid(PairingCodeProblem.NotSeekerVault)
        }
        val query = queryOf(uri.rawQuery.orEmpty())
        if (query["v"] != VERSION) return invalid(PairingCodeProblem.OtherVersion)
        val serverUrl = query["url"].orEmpty()
        val serverId = query["server"].orEmpty()
        val token = query["token"].orEmpty()
        val problem =
            serverUrlProblem(serverUrl, cleartextPermitted)
                ?: PairingCodeProblem.BadServerId.takeUnless { isConnectionId(serverId) }
                ?: PairingCodeProblem.BadToken.takeUnless { isSecret(token) }
        if (problem != null) return invalid(problem)
        return PairingCodeResult.Valid(PairingCode(normalizeServerUrl(serverUrl), serverId, token))
    }

    /** Why [url] can't be a server URL, or null if it can. */
    fun serverUrlProblem(url: String, cleartextPermitted: (host: String) -> Boolean) = run {
        val uri =
            try {
                URI(url)
            } catch (e: URISyntaxException) {
                return@run PairingCodeProblem.BadServerUrl
            }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        when {
            scheme == null || uri.isOpaque || host.isNullOrEmpty() ->
                PairingCodeProblem.BadServerUrl
            uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ->
                PairingCodeProblem.BadServerUrl
            uri.port != -1 && uri.port !in 1..65535 -> PairingCodeProblem.BadServerUrl
            scheme == "https" -> null
            scheme == "http" &&
                host in LOOPBACK_HOSTS &&
                cleartextPermitted(host.removeSurrounding("[", "]")) -> null
            else -> PairingCodeProblem.InsecureServerUrl
        }
    }

    /**
     * The canonical form of a valid server URL, as the sidecar computes it: a lowercase scheme and
     * host, no default port, and no trailing slash.
     */
    fun normalizeServerUrl(url: String): String {
        val uri = URI(url)
        val scheme = uri.scheme.lowercase()
        val port =
            uri.port.takeUnless {
                it == -1 || (scheme == "https" && it == 443) || (scheme == "http" && it == 80)
            }
        val path = uri.rawPath.orEmpty().trimEnd('/')
        return "$scheme://${uri.host.lowercase()}${port?.let { ":$it" }.orEmpty()}$path"
    }

    /**
     * The host of a server URL, with its port when it has one: the default name of a connection.
     */
    fun hostOf(serverUrl: String): String {
        val uri = URI(serverUrl)
        return if (uri.port == -1) uri.host else "${uri.host}:${uri.port}"
    }

    /**
     * A URI's query, as `URLSearchParams.get` reads it. A feed reference is parsed by the same
     * rules ([io.github.brrenat.seekervault.servers.FeedReferences]), so a repeated or
     * badly-escaped parameter can't mean one thing in a pairing code and another in a reference.
     */
    internal fun queryOf(rawQuery: String): Map<String, String> =
        rawQuery
            .split('&')
            .filter { it.isNotEmpty() }
            .map { parameter ->
                val name = parameter.substringBefore('=')
                val value = parameter.substringAfter('=', missingDelimiterValue = "")
                decode(name) to decode(value)
            }
            // Like URLSearchParams.get: the first value of a repeated parameter.
            .reversed()
            .toMap()

    private fun decode(value: String): String =
        try {
            // The charset-name overload: decode(String, Charset) needs API 33, and minSdk is 31.
            URLDecoder.decode(value, "UTF-8")
        } catch (e: IllegalArgumentException) {
            "" // a malformed percent escape: the parameter counts as missing
        }

    private fun invalid(problem: PairingCodeProblem) = PairingCodeResult.Invalid(problem)
}

private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
private val SECRET = Regex("^[A-Za-z0-9_-]{43}$")

/** Whether [value] is a lowercase UUID, the form of every connection and server ID. */
fun isConnectionId(value: String): Boolean = UUID.matches(value)

/**
 * Whether [value] has the form of a pairing token or a phone credential: 43 base64url characters.
 */
fun isSecret(value: String): Boolean = SECRET.matches(value)
