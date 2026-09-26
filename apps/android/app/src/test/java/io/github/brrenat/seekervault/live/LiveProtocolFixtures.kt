package io.github.brrenat.seekervault.live

/**
 * The binary fixtures in proto/fixtures, written by `buf convert` (`pnpm generate`) and checked
 * byte for byte by the sidecar tests too. The test resources include that folder.
 */
internal object LiveProtocolFixtures {
    fun bytes(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/seekervault/live/v1/$name.binpb")) {
                "Missing fixture $name.binpb; run pnpm generate"
            }
            .use { it.readBytes() }
}
