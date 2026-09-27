package io.github.brrenat.seekervault.feeds

/**
 * Whether the server behind a feed is running, which is a different question from whether this
 * phone can reach the gateway (SEE-150).
 *
 * ## The bug this exists for
 *
 * A feed's row said "Connected" because the gateway answered. That was true and it was not the
 * question: a gateway goes on serving what a publisher last published long after that publisher's
 * own server has stopped, because a publisher stopping does not withdraw anything. So an owner
 * watched a feed that had been dead for hours and was told it was fine. Nothing failed — which is
 * why only a person looking at a phone could notice.
 *
 * Reaching the gateway is evidence about the gateway. This is the second answer, asked for and
 * shown separately, and neither is ever derived from the other.
 *
 * ## Why the phone asks the gateway rather than the publisher
 *
 * Because the phone has no address for a publisher and should not have one: a feed is read from the
 * gateway and nothing else, and a phone that connected to an address a server named would be a
 * phone a server could aim wherever it liked. The gateway does not contact publishers either. A
 * running publisher checks in, and the gateway reports the last thing it was told.
 */
enum class FeedAvailability {
    /**
     * Not asked yet, asked and not answered, or answered with something this build cannot read.
     *
     * Not hearing an answer is not an answer. It is a separate value rather than being folded into
     * [Offline] so that nothing has to decide which of the two "no answer" means — and it is
     * emphatically not folded into [Online], which is the mistake that produced the bug: a feed is
     * never shown as running because the phone failed to find out that it is not.
     */
    Unknown,
    /** The publisher checked in with the gateway recently enough for the gateway to say so. */
    Online,
    /**
     * The gateway hosts this feed and its publisher has not checked in lately. What it last
     * published is still readable; nothing new will arrive until the publisher is back.
     */
    Offline,
}

/**
 * Whether each channel's publisher is running, as the gateway last answered it.
 *
 * A separate seam from [FeedTopics] and [FeedTickets] although it is the same endpoint and the same
 * client, for the reason those two are separate from each other: a fake in a test should be able to
 * answer one of these questions without pretending to answer the others.
 */
interface FeedStatuses {
    /**
     * Whether the publishers behind [channels] at [gatewayUrl] are running — each channel one the
     * phone holds a feed reference for.
     *
     * The answer holds one entry per channel this gateway hosts. A channel missing from it is not
     * an error and is not a verdict: the phone reads it as [FeedAvailability.Unknown] and keeps the
     * answers it did get, because one stale feed reference must not cost the owner the truth about
     * their other feeds.
     *
     * Throws [io.github.brrenat.seekervault.connections.GatewayException] like every other read
     * here. A gateway older than SEE-150 answers
     * [io.github.brrenat.seekervault.connections.GatewayException.Kind.Unimplemented], which is a
     * working deployment: its feeds stay [FeedAvailability.Unknown].
     */
    suspend fun statuses(gatewayUrl: String, channels: List<String>): Map<String, FeedAvailability>
}
