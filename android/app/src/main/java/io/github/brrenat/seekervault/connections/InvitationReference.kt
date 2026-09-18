package io.github.brrenat.seekervault.connections

import io.github.brrenat.seekervault.servers.FeedReferenceProblem
import io.github.brrenat.seekervault.servers.FeedReferences
import java.net.URI
import java.net.URISyntaxException

/** A temporary gateway invitation. Its token stays in memory until confirmation or dismissal. */
data class InvitationReference(val gatewayUrl: String, val token: String) {
    override fun toString() = "InvitationReference(gatewayUrl=$gatewayUrl, token=<redacted>)"
}

sealed interface InvitationReferenceResult {
    data class Valid(val reference: InvitationReference) : InvitationReferenceResult

    data class Invalid(val problem: InvitationProblem) : InvitationReferenceResult
}

enum class InvitationProblem {
    NotAnInvitation,
    OtherVersion,
    BadGatewayUrl,
    InsecureGatewayUrl,
    BadToken,
    Invalid,
    Expired,
    Used,
    Failed,
}

object InvitationReferences {
    const val VERSION = "1"

    /** Accepts both the QR/app URI and the gateway-hosted shareable page URL. */
    fun parse(
        text: String,
        cleartextPermitted: (host: String) -> Boolean,
    ): InvitationReferenceResult {
        val uri =
            try {
                URI(text.trim())
            } catch (e: URISyntaxException) {
                return invalid(InvitationProblem.NotAnInvitation)
            }
        if (uri.scheme == null) return invalid(InvitationProblem.NotAnInvitation)
        if (uri.scheme.equals("seekervault", ignoreCase = true) && uri.rawAuthority == "invite") {
            val query = PairingCodes.queryOf(uri.rawQuery.orEmpty())
            if (query["v"] != VERSION) return invalid(InvitationProblem.OtherVersion)
            return checked(query["gateway"].orEmpty(), query["token"].orEmpty(), cleartextPermitted)
        }
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val token = path.substringAfterLast('/', missingDelimiterValue = "")
        if (
            !path.endsWith("/invite/$token") ||
                uri.rawQuery != null ||
                uri.rawFragment != null ||
                uri.rawUserInfo != null
        )
            return invalid(InvitationProblem.NotAnInvitation)
        val port = uri.port.takeIf { it != -1 }?.let { ":$it" }.orEmpty()
        val origin = "${uri.scheme}://${uri.host.orEmpty()}$port"
        return checked(origin, token, cleartextPermitted)
    }

    private fun checked(
        gateway: String,
        token: String,
        cleartextPermitted: (host: String) -> Boolean,
    ): InvitationReferenceResult {
        val urlProblem = FeedReferences.gatewayUrlProblem(gateway, cleartextPermitted)
        val problem =
            when (urlProblem) {
                FeedReferenceProblem.InsecureGatewayUrl -> InvitationProblem.InsecureGatewayUrl
                null -> InvitationProblem.BadToken.takeUnless { isSecret(token) }
                else -> InvitationProblem.BadGatewayUrl
            }
        if (problem != null) return invalid(problem)
        return InvitationReferenceResult.Valid(
            InvitationReference(PairingCodes.normalizeServerUrl(gateway), token)
        )
    }

    private fun invalid(problem: InvitationProblem) = InvitationReferenceResult.Invalid(problem)
}
