package io.github.brrenat.seekervault.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InvitationReferenceTest {
    private val token = "abcdefghijklmnopqrstuvwxyzABCDEFGH123456789"

    @Test
    fun appAndHostedLinksResolveToTheSameTemporaryReference() {
        val app =
            parse("seekervault://invite?v=1&gateway=https%3A%2F%2Fgateway.example&token=$token")
        val page = parse("https://gateway.example/invite/$token")

        assertEquals(app, page)
        assertEquals(InvitationReference("https://gateway.example", token), app)
        assertFalse(app.toString().contains(token))
        assertTrue(app.toString().contains("<redacted>"))
    }

    @Test
    fun malformedAndInsecureReferencesReachNoGateway() {
        assertEquals(
            InvitationProblem.OtherVersion,
            invalid("seekervault://invite?v=2&gateway=https%3A%2F%2Fgateway.example&token=$token"),
        )
        assertEquals(
            InvitationProblem.InsecureGatewayUrl,
            invalid("http://gateway.example/invite/$token"),
        )
        assertEquals(
            InvitationProblem.BadToken,
            invalid("https://gateway.example/invite/short"),
        )
        assertEquals(
            InvitationProblem.NotAnInvitation,
            invalid("https://gateway.example/invite/$token?leak=1"),
        )
    }

    private fun parse(text: String): InvitationReference =
        (InvitationReferences.parse(text) { false } as InvitationReferenceResult.Valid).reference

    private fun invalid(text: String): InvitationProblem =
        (InvitationReferences.parse(text) { false } as InvitationReferenceResult.Invalid).problem
}
