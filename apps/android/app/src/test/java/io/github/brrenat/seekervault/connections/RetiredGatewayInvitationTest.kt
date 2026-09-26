package io.github.brrenat.seekervault.connections

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Legacy invitation links are recognized only so the app can explain their retirement. */
class RetiredGatewayInvitationTest {
    @Test
    fun recognizesOnlyTheRetiredInvitationShapesWithoutExtractingCredentials() {
        assertTrue(isRetiredGatewayInvitation("seekervault://invite?v=1&token=secret"))
        assertTrue(isRetiredGatewayInvitation("https://gateway.example/invite/secret"))
        assertFalse(isRetiredGatewayInvitation("seekervault://pair?v=1&token=secret"))
        assertFalse(isRetiredGatewayInvitation("https://gateway.example/feed/server-id"))
        assertFalse(isRetiredGatewayInvitation("not a uri"))
    }
}
