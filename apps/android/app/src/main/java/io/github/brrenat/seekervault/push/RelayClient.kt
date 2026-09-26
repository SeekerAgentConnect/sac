package io.github.brrenat.seekervault.push

import io.github.brrenat.seekervault.connections.isConnectionId
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * The phone's half of the gateway push relay (SEE-144,
 * docs/guides/server-development.md#the-gateway-push-relay).
 *
 * An independently hosted direct MCP server cannot hold a Firebase credential without its operator
 * running a Firebase project. This is how one is woken anyway: the gateway's operator holds the
 * credential, this phone enrolls with the gateway it is configured to trust, and it authorizes —
 * individually, per direct connection — which servers may wake it.
 *
 * # What this phone gives away, and to whom
 *
 * Its FCM registration goes to the relay it was configured with, and nowhere else. A server may
 * *advertise* a relay (`RelayCapability`), and the app compares that advertisement with its own
 * configured origin and ignores it when they differ — so a server naming a relay of its own gets
 * nothing. What a server receives is an opaque handle, which is worth nothing without that
 * gateway's own relay credential and which the owner can revoke without touching the pairing.
 *
 * # What proves this phone is this phone
 *
 * A secret the gateway minted once, at enrollment, and kept only as a hash. Everything that can
 * change where this device's wake-ups go needs it. Knowing the FCM registration is not enough, and
 * that is deliberate: a paired server already holds one.
 */
interface RelayClient {
    /** Enrolls this installation with [relayUrl] and returns the identity and its secret. */
    suspend fun enroll(relayUrl: String, target: String): RelayEnrollment

    /** Replaces the installation's FCM registration after Firebase rotates it. */
    suspend fun setTarget(relayUrl: String, installation: RelayEnrollment, target: String)

    /** What this gateway still holds for this installation, or null when it holds nothing. */
    suspend fun read(relayUrl: String, installation: RelayEnrollment): RelayInstallation?

    /** Authorizes [serverId] to wake this installation, for one direct connection. */
    suspend fun bind(
        relayUrl: String,
        installation: RelayEnrollment,
        serverId: String,
        connectionId: String,
    ): RelayBinding

    /** Revokes one authorization. Revoking one that is already gone is not a failure. */
    suspend fun unbind(relayUrl: String, installation: RelayEnrollment, bindingId: String)
}

/** The installation identity and the secret that proves it. The secret never reaches a log. */
class RelayEnrollment(val installation: String, val secret: String) {
    override fun toString() = "RelayEnrollment(installation=$installation, secret=<redacted>)"
}

/** What a gateway still holds for this installation: whether it can wake it, and what it may. */
data class RelayInstallation(val hasTarget: Boolean, val bindings: List<RelayBindingRecord>)

/**
 * One authorization as its owner sees it. There is no handle in it: the gateway kept only a hash.
 */
data class RelayBindingRecord(
    val binding: String,
    val serverId: String,
    val connectionId: String,
    val revoked: Boolean,
)

/** A new authorization: the ID the phone revokes it by, and the handle it hands to the server. */
class RelayBinding(val binding: String, val handle: String) {
    override fun toString() = "RelayBinding(binding=$binding, handle=<redacted>)"
}

/**
 * A failed relay call, classified by what the caller does next rather than by what went wrong.
 *
 * The distinction that matters is [gone]: the gateway answered and said this installation is not
 * there, which is what a phone sees after the gateway's database was lost or after the enrollment
 * aged out. Everything else — unreachable, refused, a bad answer — is "nobody knows", and the
 * caller keeps what it has and tries later. Treating a transport failure as a revocation would
 * throw away an enrollment that still works.
 */
class RelayException(val gone: Boolean, message: String) : Exception(message)

/** The contract's own version and route prefix, as the gateway serves them. */
internal const val RELAY_VERSION = "1"
internal const val RELAY_PREFIX = "/relay/v1"

/** What an FCM registration may look like, mirroring the gateway's own bound. */
internal fun validRelayTarget(target: String): Boolean = validFcmTarget(target)

/**
 * Whether a server's advertised relay is the one this phone is configured to trust.
 *
 * This is the rule that makes a hostile advertisement worthless. Comparison is on the canonical
 * origin, character for character, the same way a feed reference is compared with a manifest's
 * gateway URL — a trailing slash is the one difference allowed, because it is not one.
 */
internal fun sameRelay(configured: String, advertised: String): Boolean =
    configured.trimEnd('/').equals(advertised.trimEnd('/'), ignoreCase = true) &&
        configured.isNotEmpty()

/** The OkHttp implementation. It is the only thing here that opens a connection. */
class HttpRelayClient(private val client: OkHttpClient) : RelayClient {

    override suspend fun enroll(relayUrl: String, target: String): RelayEnrollment {
        val answer =
            post(
                url = "$relayUrl$RELAY_PREFIX/installations",
                secret = null,
                body = JSONObject().put("version", RELAY_VERSION).put("target", target),
            )
        val installation = answer.optString("installation")
        val secret = answer.optString("secret")
        if (installation.isEmpty() || secret.isEmpty()) {
            throw RelayException(gone = false, message = "the relay did not enroll this device")
        }
        return RelayEnrollment(installation, secret)
    }

    override suspend fun setTarget(
        relayUrl: String,
        installation: RelayEnrollment,
        target: String,
    ) {
        post(
            url = "$relayUrl$RELAY_PREFIX/installations/${installation.installation}/target",
            secret = installation.secret,
            body = JSONObject().put("version", RELAY_VERSION).put("target", target),
        )
    }

    override suspend fun read(
        relayUrl: String,
        installation: RelayEnrollment,
    ): RelayInstallation? {
        val answer =
            try {
                send(
                    Request.Builder()
                        .url("$relayUrl$RELAY_PREFIX/installations/${installation.installation}")
                        .get()
                        .header("Authorization", "Bearer ${installation.secret}")
                        .build()
                )
            } catch (missing: RelayException) {
                if (missing.gone) return null else throw missing
            }
        val listed = answer.optJSONArray("bindings") ?: JSONArray()
        val bindings = buildList {
            for (index in 0 until listed.length()) {
                val one = listed.optJSONObject(index) ?: continue
                add(
                    RelayBindingRecord(
                        binding = one.optString("binding"),
                        serverId = one.optString("server"),
                        connectionId = one.optString("connection"),
                        revoked = one.optBoolean("revoked"),
                    )
                )
            }
        }
        return RelayInstallation(answer.optBoolean("has_target"), bindings)
    }

    override suspend fun bind(
        relayUrl: String,
        installation: RelayEnrollment,
        serverId: String,
        connectionId: String,
    ): RelayBinding {
        val answer =
            post(
                url = "$relayUrl$RELAY_PREFIX/installations/${installation.installation}/bindings",
                secret = installation.secret,
                body =
                    JSONObject()
                        .put("version", RELAY_VERSION)
                        .put("server", serverId)
                        .put("connection", connectionId),
            )
        val binding = answer.optString("binding")
        val handle = answer.optString("handle")
        if (binding.isEmpty() || handle.isEmpty()) {
            throw RelayException(gone = false, message = "the relay did not authorize the binding")
        }
        return RelayBinding(binding, handle)
    }

    override suspend fun unbind(
        relayUrl: String,
        installation: RelayEnrollment,
        bindingId: String,
    ) {
        send(
            Request.Builder()
                .url(
                    "$relayUrl$RELAY_PREFIX/installations/${installation.installation}" +
                        "/bindings/$bindingId"
                )
                .delete()
                .header("Authorization", "Bearer ${installation.secret}")
                .build()
        )
    }

    private suspend fun post(url: String, secret: String?, body: JSONObject): JSONObject {
        val builder =
            Request.Builder().url(url).post(body.toString().toRequestBody(JSON)).apply {
                if (secret != null) header("Authorization", "Bearer $secret")
            }
        return send(builder.build())
    }

    /**
     * One call, with every failure turned into [RelayException] and none of them quoting the
     * answer. A gateway's refusal is its own prose and can name a deployment; what this phone acts
     * on is the status.
     */
    private suspend fun send(request: Request): JSONObject =
        withContext(Dispatchers.IO) {
            val response =
                try {
                    client.newCall(request).execute()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failed: IOException) {
                    // The call may never have left this phone. Nothing is concluded about the
                    // enrollment from a transport failure.
                    throw RelayException(gone = false, message = "the relay is not reachable")
                }
            response.use {
                when {
                    // 404 is the one answer that means this gateway does not hold this
                    // installation: its database was lost, or the enrollment aged out. It is also
                    // what a wrong secret gets, which is the same thing from here — this phone is
                    // not the owner of that identity and re-enrolls either way.
                    it.code == 401 || it.code == 404 ->
                        throw RelayException(gone = true, message = "the relay holds no enrollment")
                    !it.isSuccessful ->
                        throw RelayException(
                            gone = false,
                            message = "the relay answered ${it.code}",
                        )
                }
                val body = it.body?.string().orEmpty()
                if (body.isBlank()) JSONObject() else JSONObject(body)
            }
        }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** A server's advertised relay, as the phone reads it off an authenticated direct connection. */
data class RelayCoordinates(val relayUrl: String, val serverId: String) {
    /** Whether this is a relay this phone could act on: its own gateway, and a real identity. */
    fun trusted(configured: String): Boolean =
        sameRelay(configured, relayUrl) && isConnectionId(serverId)
}
