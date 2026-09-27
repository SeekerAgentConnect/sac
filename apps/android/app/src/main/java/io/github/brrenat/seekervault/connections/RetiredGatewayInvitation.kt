package io.github.brrenat.seekervault.connections

import java.net.URI
import java.net.URISyntaxException

/**
 * Minimal legacy recognition for a retired gateway invitation. It extracts no token, resolves no
 * endpoint, and exists only so old links can produce an explicit inert explanation.
 */
fun isRetiredGatewayInvitation(text: String): Boolean {
    val uri =
        try {
            URI(text.trim())
        } catch (_: URISyntaxException) {
            return false
        }
    if (uri.scheme.equals("seekervault", ignoreCase = true)) return uri.host == "invite"
    if (uri.scheme != "https" && uri.scheme != "http") return false
    return uri.path?.startsWith("/invite/") == true
}
