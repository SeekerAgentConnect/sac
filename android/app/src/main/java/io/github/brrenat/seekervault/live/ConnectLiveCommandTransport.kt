package io.github.brrenat.seekervault.live

import com.connectrpc.Code
import com.connectrpc.ConnectException
import com.connectrpc.ProtocolClientConfig
import com.connectrpc.exceptionOrNull
import com.connectrpc.extensions.GoogleJavaLiteProtobufStrategy
import com.connectrpc.impl.ProtocolClient
import com.connectrpc.okhttp.ConnectOkHttpClient
import com.connectrpc.protocols.NetworkProtocol
import com.connectrpc.simpleTimeouts
import io.github.brrenat.seekervault.live.v1.AcknowledgementResult
import io.github.brrenat.seekervault.live.v1.LiveCommandServiceClient
import io.github.brrenat.seekervault.live.v1.WatchCommandsRequest
import io.github.brrenat.seekervault.live.v1.WatchCommandsResponse
import io.github.brrenat.seekervault.live.v1.acknowledgeCommandRequest
import io.github.brrenat.seekervault.live.v1.commandAcknowledgement
import java.io.IOException
import java.net.UnknownServiceException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * [LiveCommandTransport] over the Connect protocol with OkHttp. [httpClient] must not have a read
 * timeout (see `ConnectOkHttpClient.configureClient`), or an idle stream would be cut off.
 */
class ConnectLiveCommandTransport(
    serverUrl: String,
    phoneToken: String,
    httpClient: OkHttpClient,
) : LiveCommandTransport {
    private val headers = mapOf("Authorization" to listOf("Bearer $phoneToken"))
    private val client =
        LiveCommandServiceClient(
            ProtocolClient(
                httpClient = ConnectOkHttpClient(httpClient),
                config =
                    ProtocolClientConfig(
                        host = serverUrl,
                        serializationStrategy = GoogleJavaLiteProtobufStrategy(),
                        networkProtocol = NetworkProtocol.CONNECT,
                        // The stream stays open while the screen is connected; only the
                        // acknowledgement has a deadline.
                        timeoutOracle =
                            simpleTimeouts(unaryTimeout = 10.seconds, streamTimeout = null),
                    ),
            )
        )

    override fun watch(): Flow<WatchEvent> = flow {
        val stream = client.watchCommands(headers)
        try {
            stream.sendAndClose(WatchCommandsRequest.getDefaultInstance()).onFailure {
                throw classify(it)
            }
            val responses = stream.responseChannel()
            while (true) {
                val result = responses.receiveCatching()
                val response = result.getOrNull()
                if (response == null) {
                    val cause = result.exceptionOrNull() ?: break // the sidecar ended the stream
                    if (cause is CancellationException) throw cause
                    throw classify(cause)
                }
                when (response.eventCase) {
                    WatchCommandsResponse.EventCase.READY -> emit(WatchEvent.Ready)
                    WatchCommandsResponse.EventCase.COMMAND ->
                        emit(WatchEvent.Command(response.command))
                    else -> Unit // an event this app version doesn't know
                }
            }
        } finally {
            withContext(NonCancellable) { stream.receiveClose() }
        }
    }

    override suspend fun acknowledge(id: String) {
        val request = acknowledgeCommandRequest {
            acknowledgement = commandAcknowledgement {
                this.id = id
                result = AcknowledgementResult.ACKNOWLEDGEMENT_RESULT_OK
            }
        }
        val response =
            try {
                client.acknowledgeCommand(request, headers)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw classify(e)
            }
        response.exceptionOrNull()?.let { throw classify(it) }
    }

    private fun classify(error: Throwable): LiveTransportException {
        val causes = generateSequence(error) { it.cause }.take(10).toList()
        val code = causes.firstNotNullOfOrNull { (it as? ConnectException)?.code }
        val kind =
            when {
                causes.any {
                    it is UnknownServiceException && "CLEARTEXT" in it.message.orEmpty()
                } -> LiveTransportException.Kind.CleartextBlocked
                code == Code.UNAUTHENTICATED -> LiveTransportException.Kind.Unauthenticated
                code == Code.DEADLINE_EXCEEDED -> LiveTransportException.Kind.TimedOut
                code == Code.CANCELED -> LiveTransportException.Kind.Cancelled
                code == Code.NOT_FOUND -> LiveTransportException.Kind.UnknownCommand
                code == Code.UNAVAILABLE || causes.any { it is IOException } ->
                    LiveTransportException.Kind.Unreachable
                else -> LiveTransportException.Kind.Other
            }
        return LiveTransportException(kind, error.message, error)
    }
}
