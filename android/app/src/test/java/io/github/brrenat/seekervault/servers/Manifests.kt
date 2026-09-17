package io.github.brrenat.seekervault.servers

import io.github.brrenat.seekervault.server.v1.ConnectionMode
import io.github.brrenat.seekervault.server.v1.ServerEnvironment
import io.github.brrenat.seekervault.server.v1.ServerManifest
import io.github.brrenat.seekervault.server.v1.directServer
import io.github.brrenat.seekervault.server.v1.gatewayFeed
import io.github.brrenat.seekervault.server.v1.pluginRequirement
import io.github.brrenat.seekervault.server.v1.serverManifest

/**
 * Manifests as a server publishes them, for the tests that read one (SEE-88).
 *
 * These build the protocol message, not the validated model: what the phone does with a manifest is
 * the thing under test, so a test that wants a bad one has to be able to write a bad one.
 */
const val SERVER_A = "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"

const val SERVER_B = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"

const val URL_A = "https://vault.example.com"

const val GATEWAY = "https://gateway.example.com"

const val SWAP_PLUGIN = "jupiter.swap"

const val PREDICTION_PLUGIN = "jupiter.prediction"

/** A direct server's manifest, as the Node sidecar publishes one. */
fun directManifest(
    serverId: String = SERVER_A,
    url: String = URL_A,
    revision: Long = 1,
    protocol: Int = SERVER_PROTOCOL,
    required: List<Pair<String, IntRange>> = emptyList(),
    environments: List<ServerEnvironment> = listOf(ServerEnvironment.SERVER_ENVIRONMENT_PRODUCTION),
    name: String = "",
): ServerManifest = serverManifest {
    this.serverId = serverId
    protocolVersion = protocol
    settingsRevision = revision
    mode = ConnectionMode.CONNECTION_MODE_DIRECT
    direct = directServer { this.url = url }
    requiredPlugins.addAll(required.map(::requirement))
    this.environments.addAll(environments)
    displayName = name
}

/** A publisher's manifest, as the shared gateway holds one. */
fun feedManifest(
    serverId: String = SERVER_B,
    gateway: String = GATEWAY,
    channel: String = channelFor(serverId),
    revision: Long = 1,
    protocol: Int = SERVER_PROTOCOL,
    required: List<Pair<String, IntRange>> = listOf(SWAP_PLUGIN to 1..1),
    environments: List<ServerEnvironment> = listOf(ServerEnvironment.SERVER_ENVIRONMENT_PRODUCTION),
    name: String = "",
): ServerManifest = serverManifest {
    this.serverId = serverId
    protocolVersion = protocol
    settingsRevision = revision
    mode = ConnectionMode.CONNECTION_MODE_GATEWAY_FEED
    feed = gatewayFeed {
        gatewayUrl = gateway
        this.channel = channel
    }
    requiredPlugins.addAll(required.map(::requirement))
    this.environments.addAll(environments)
    displayName = name
}

private fun requirement(required: Pair<String, IntRange>) = pluginRequirement {
    pluginId = required.first
    minContract = required.second.first
    maxContract = required.second.last
}
