package io.github.brrenat.seekervault.access

import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import org.json.JSONException
import org.json.JSONObject

/**
 * A restricted feed publisher's authentication endpoint, as this phone calls it (SEE-156,
 * docs/integrations/restricted-feeds.md).
 *
 * The origin is always the one the gateway stamped on the feed's manifest ([authOrigin] on every
 * call), never one a link supplied, and a redirect is never followed: an answer from anywhere else
 * is not the publisher's.
 */
interface FeedAccessApi {
    suspend fun challenge(
        authOrigin: String,
        channel: String,
        wallet: String,
        deviceKey: ByteArray,
        label: String,
    ): ChallengeAnswer

    suspend fun request(
        authOrigin: String,
        attempt: String,
        walletSignature: ByteArray,
        deviceSignature: ByteArray,
    ): RequestAnswer

    suspend fun status(
        authOrigin: String,
        requestId: String,
        atMillis: Long,
        deviceSignature: ByteArray,
    ): StatusAnswer

    suspend fun redeem(
        authOrigin: String,
        channel: String,
        invitation: String,
        atMillis: Long,
        deviceSignature: ByteArray,
    ): RedeemAnswer
}

data class ChallengeAnswer(val challenge: FeedAccessProof.Challenge, val message: String)

data class RequestAnswer(val requestId: String, val state: String)

data class StatusAnswer(
    val requestId: String,
    val state: String,
    val connected: Boolean,
    val invitation: String?,
    val invitationExpiresAt: Instant?,
)

data class RedeemAnswer(
    val requestId: String,
    val session: String,
    val until: Instant,
    /** The gateway already holds the grant; otherwise the publisher is still telling it. */
    val synced: Boolean,
)

/** Why a call to the authentication endpoint did not answer. */
class FeedAccessException(val kind: Kind, val code: String = "", message: String? = null) :
    Exception(message ?: code) {
    enum class Kind {
        /** The publisher answered and said no, with [code]. */
        Refused,
        /** It could not be reached. */
        Unreachable,
        /** It answered something that is not this API. */
        BadResponse,
    }
}

class OkHttpFeedAccessApi(httpClient: OkHttpClient) : FeedAccessApi {
    private val client =
        httpClient.newBuilder().followRedirects(false).followSslRedirects(false).build()

    override suspend fun challenge(
        authOrigin: String,
        channel: String,
        wallet: String,
        deviceKey: ByteArray,
        label: String,
    ): ChallengeAnswer {
        val answer =
            post(
                authOrigin,
                "/access/v1/challenges",
                JSONObject()
                    .put("feed", channel)
                    .put("wallet", wallet)
                    .put("device_key", encode(deviceKey))
                    .put("label", label),
            )
        return try {
            ChallengeAnswer(
                FeedAccessProof.Challenge(
                    authOrigin = answer.getString("auth_origin"),
                    channel = answer.getString("feed"),
                    wallet = wallet,
                    installation = answer.getString("installation"),
                    attempt = answer.getString("attempt"),
                    nonce = answer.getString("nonce"),
                    issuedAt = Instant.parse(answer.getString("issued_at")),
                    expiresAt = Instant.parse(answer.getString("expires_at")),
                ),
                answer.getString("message"),
            )
        } catch (e: JSONException) {
            throw FeedAccessException(FeedAccessException.Kind.BadResponse, message = e.message)
        } catch (e: DateTimeParseException) {
            throw FeedAccessException(FeedAccessException.Kind.BadResponse, message = e.message)
        }
    }

    override suspend fun request(
        authOrigin: String,
        attempt: String,
        walletSignature: ByteArray,
        deviceSignature: ByteArray,
    ): RequestAnswer {
        val answer =
            post(
                authOrigin,
                "/access/v1/requests",
                JSONObject()
                    .put("attempt", attempt)
                    .put("wallet_signature", encode(walletSignature))
                    .put("device_signature", encode(deviceSignature)),
            )
        return read { RequestAnswer(answer.getString("request_id"), answer.getString("state")) }
    }

    override suspend fun status(
        authOrigin: String,
        requestId: String,
        atMillis: Long,
        deviceSignature: ByteArray,
    ): StatusAnswer {
        val answer =
            post(
                authOrigin,
                "/access/v1/requests/$requestId/status",
                JSONObject().put("at", atMillis).put("device_signature", encode(deviceSignature)),
            )
        return read {
            val invitation = answer.optJSONObject("invitation")
            StatusAnswer(
                requestId = answer.getString("request_id"),
                state = answer.getString("state"),
                connected = answer.optBoolean("connected"),
                invitation = invitation?.getString("token"),
                invitationExpiresAt = invitation?.getString("expires_at")?.let(Instant::parse),
            )
        }
    }

    override suspend fun redeem(
        authOrigin: String,
        channel: String,
        invitation: String,
        atMillis: Long,
        deviceSignature: ByteArray,
    ): RedeemAnswer {
        val answer =
            post(
                authOrigin,
                "/access/v1/redeem",
                JSONObject()
                    .put("feed", channel)
                    .put("invitation", invitation)
                    .put("at", atMillis)
                    .put("device_signature", encode(deviceSignature)),
            )
        return read {
            RedeemAnswer(
                requestId = answer.getString("request_id"),
                session = answer.getString("session"),
                until = Instant.parse(answer.getString("until")),
                synced = answer.optString("gateway") == "synced",
            )
        }
    }

    private inline fun <T> read(block: () -> T): T =
        try {
            block()
        } catch (e: JSONException) {
            throw FeedAccessException(FeedAccessException.Kind.BadResponse, message = e.message)
        } catch (e: DateTimeParseException) {
            throw FeedAccessException(FeedAccessException.Kind.BadResponse, message = e.message)
        }

    private suspend fun post(origin: String, path: String, body: JSONObject): JSONObject {
        val request =
            Request.Builder()
                .url(origin.trimEnd('/') + path)
                .post(body.toString().toRequestBody(JSON))
                .build()
        val answer =
            try {
                client.newCall(request).await()
            } catch (e: IOException) {
                // Including a body that broke off after its headers: nothing usable arrived.
                throw FeedAccessException(FeedAccessException.Kind.Unreachable, message = e.message)
            }
        if (answer.text == null) throw FeedAccessException(FeedAccessException.Kind.BadResponse)
        val json =
            try {
                JSONObject(answer.text)
            } catch (e: JSONException) {
                if (answer.code in 500..599 || answer.code == 429) {
                    throw FeedAccessException(FeedAccessException.Kind.Unreachable)
                }
                throw FeedAccessException(FeedAccessException.Kind.BadResponse)
            }
        if (answer.code !in 200..299) {
            if (answer.code in 500..599 || answer.code == 429) {
                throw FeedAccessException(FeedAccessException.Kind.Unreachable, json.optString("error"))
            }
            throw FeedAccessException(FeedAccessException.Kind.Refused, json.optString("error"))
        }
        return json
    }

    /** A response read to its end: its status, and its body, or null when it was too long. */
    private class Answer(val code: Int, val text: String?)

    /**
     * Runs the call and reads the whole response on OkHttp's thread. OkHttp answers as soon as the
     * headers arrive, so reading the body after resuming would do the socket reads on whichever
     * thread the caller is on — the main thread, for a screen's button.
     */
    private suspend fun Call.await(): Answer = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val answer =
                        try {
                            response.use { Answer(it.code, it.body?.let(::bounded)) }
                        } catch (e: IOException) {
                            continuation.resumeWithException(e)
                            return
                        }
                    continuation.resume(answer)
                }
            }
        )
    }

    /** The body as text, or null when it is longer than any answer this endpoint gives. */
    private fun bounded(body: ResponseBody): String? {
        val source = body.source()
        if (source.request(MOST_ANSWER_BYTES + 1)) return null
        return source.buffer.readUtf8()
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val MOST_ANSWER_BYTES = 64L * 1024

        fun encode(bytes: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
