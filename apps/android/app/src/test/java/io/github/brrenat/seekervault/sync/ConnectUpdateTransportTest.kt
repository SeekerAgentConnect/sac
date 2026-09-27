package io.github.brrenat.seekervault.sync

import com.connectrpc.Code
import com.connectrpc.ConnectErrorDetail
import com.connectrpc.ConnectException
import com.connectrpc.extensions.GoogleJavaLiteProtobufStrategy
import io.github.brrenat.seekervault.update.v1.UpdateError
import io.github.brrenat.seekervault.update.v1.updateErrorDetail
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectUpdateTransportTest {
    @Test
    fun failedPreconditionDetailSeparatesProtocolUpgradeFromSnapshotRecovery() {
        assertEquals(
            UpdateTransportException.Kind.UpgradeRequired,
            ConnectUpdateTransport.classify(
                    failedPrecondition(UpdateError.UPDATE_ERROR_PROTOCOL_UNSUPPORTED)
                )
                .kind,
        )
        assertEquals(
            UpdateTransportException.Kind.SnapshotInvalid,
            ConnectUpdateTransport.classify(
                    failedPrecondition(UpdateError.UPDATE_ERROR_SNAPSHOT_INVALID)
                )
                .kind,
        )
    }

    private fun failedPrecondition(error: UpdateError): ConnectException {
        val detail = updateErrorDetail { this.error = error }
        return ConnectException(Code.FAILED_PRECONDITION)
            .withErrorDetails(
                GoogleJavaLiteProtobufStrategy().errorDetailParser(),
                listOf(
                    ConnectErrorDetail(
                        "seekervault.update.v1.UpdateErrorDetail",
                        detail.toByteArray().toByteString(),
                    )
                ),
            )
    }
}
