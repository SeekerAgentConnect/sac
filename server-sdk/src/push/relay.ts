/**
 * Waking a phone through the gateway's push relay (SEE-144,
 * docs/guides/server-development.md#the-gateway-push-relay).
 *
 * A server that runs on someone's own machine cannot hold a Firebase service-account credential
 * without its operator creating a Firebase project, mounting a key file and keeping it out of
 * version control. This is the other way: the gateway's operator holds the credential, this server
 * holds a scoped relay credential that can do exactly one thing, and the owner's phone decides
 * which servers may wake it.
 *
 * What is sent is the same content-free invalidation the direct path sends. The gateway builds it
 * from constants, so nothing here can put text, a request ID, a priority number or a target into a
 * message — the only two things this module names are a handle and which of two words describes
 * the change.
 *
 * # Why a handle is not a target
 *
 * They are both opaque strings and they are not the same kind of thing, which is exactly why they
 * have separate types, separate columns and separate RPCs. An FCM target addresses a device and
 * works for whoever holds it. A relay handle addresses one authorization at one gateway and is
 * worth nothing without that gateway's own relay credential — and a phone that authorized this
 * server can revoke it without touching anything else. Putting one where the other belongs would
 * send a wake-up to nobody, and the failure would look exactly like a phone that is switched off.
 */
import { CoalescingInvalidations } from "./coalescing.ts";
import type { PairingStore } from "../storage/pairing-store.ts";

/** The version of the relay contract this module speaks. */
export const RELAY_PROTOCOL_VERSION = 1;

/** Where the relay's calls live under a gateway's origin. */
export const RELAY_PATH = "/relay/v1";

/** How long one call may take before it is a failure to log. A push is never worth waiting on. */
export const RELAY_TIMEOUT_MS = 10_000;

/**
 * The two hints. They exist because one is worth waking a sleeping phone for and the other is not:
 * a request that has just appeared is waiting on its owner, and a state change on one they have
 * already decided is not. The hint chooses the delivery priority and nothing else — it never
 * reaches the payload, so the phone cannot read it and does not act on it.
 */
export type RelayHint = "created" | "updated";

/** One wake-up, in full. There is nothing else a caller may say. */
export interface RelayInvalidation {
  readonly handle: string;
  readonly hint: RelayHint;
}

/**
 * What became of one call. Four cases rather than a thrown error, because the caller does
 * something different with each and the distinction that matters is whether this handle is
 * finished or the attempt was.
 */
export type RelayOutcome =
  /** The gateway took it. That is not a promise a phone was woken, and nothing treats it as one. */
  | "accepted"
  /** This binding is already being woken as fast as it is useful to. Nothing to retry. */
  | "coalesced"
  /** The handle or the credential no longer authorizes this server. The handle is cleared. */
  | "unauthorized"
  /** Nobody knows: an outage, a timeout, throttling, or a gateway with no Firebase credential. */
  | "unavailable";

/** The transport, as the one thing a host may replace. */
export interface RelaySender {
  send(invalidation: RelayInvalidation): Promise<RelayOutcome>;
}

/** What the operator gave this developer, and what the developer configures. */
export interface RelayConfiguration {
  /**
   * The gateway's canonical origin. It is also what this server advertises to a paired phone, and
   * the phone refuses to register with anything but the relay it is configured to trust — so
   * naming an origin of one's own here achieves nothing.
   */
  readonly relayUrl: string;
  /** This server's registered identity at that gateway: the lowercase UUID the operator issued. */
  readonly serverId: string;
  /** The scoped relay credential. It cannot publish a feed and is not an administrator credential. */
  readonly credential: string;
  readonly timeoutMs?: number;
}

/** The reason a relay configuration is not one, or undefined. It never repeats the credential. */
export function invalidRelayReason(
  configuration: RelayConfiguration,
): string | undefined {
  let origin: URL;
  try {
    origin = new URL(configuration.relayUrl);
  } catch {
    return "relay URL must be an absolute URL";
  }
  const loopback =
    origin.hostname === "127.0.0.1" ||
    origin.hostname === "localhost" ||
    origin.hostname === "[::1]";
  if (
    origin.protocol !== "https:" &&
    !(origin.protocol === "http:" && loopback)
  ) {
    return "relay URL must use HTTPS; plain HTTP is allowed only on loopback";
  }
  if (origin.pathname !== "/" || origin.search !== "" || origin.hash !== "") {
    return "relay URL must be an origin with no path, query or fragment";
  }
  if (
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(
      configuration.serverId,
    )
  ) {
    return "relay server ID must be a lowercase UUID";
  }
  if (configuration.credential.length === 0) {
    return "relay credential must not be empty";
  }
  return undefined;
}

/**
 * The gateway relay over HTTP, which is all it is: one authenticated POST with two short fields.
 *
 * Constructing it opens nothing and contacts nothing. Every failure is classified rather than
 * quoted — a gateway's refusal is its own prose and can name a deployment — and none of them is
 * ever thrown at the caller, because a push that could fail a request would make a hint more
 * consequential than the thing it hints about.
 */
export class GatewayRelaySender implements RelaySender {
  readonly #endpoint: string;
  readonly #credential: string;
  readonly #timeoutMs: number;

  constructor(configuration: RelayConfiguration) {
    const problem = invalidRelayReason(configuration);
    if (problem !== undefined) throw new Error(problem);
    this.#endpoint = `${configuration.relayUrl.replace(/\/$/, "")}${RELAY_PATH}/notify`;
    this.#credential = configuration.credential;
    this.#timeoutMs = configuration.timeoutMs ?? RELAY_TIMEOUT_MS;
  }

  async send(invalidation: RelayInvalidation): Promise<RelayOutcome> {
    const stop = AbortSignal.timeout(this.#timeoutMs);
    let response: Response;
    try {
      response = await fetch(this.#endpoint, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          authorization: `Bearer ${this.#credential}`,
        },
        body: JSON.stringify({
          version: String(RELAY_PROTOCOL_VERSION),
          handle: invalidation.handle,
          hint: invalidation.hint,
        }),
        signal: stop,
      });
    } catch {
      // Unreachable, refused, timed out. The call may never have left here, so nothing about this
      // handle is concluded from it: "nobody knows" is not "no longer authorized".
      return "unavailable";
    }
    // The body is read and discarded so the connection is released, and never parsed for a reason:
    // there is nothing in it this decides on beyond the status.
    void response.body?.cancel();
    switch (response.status) {
      case 202:
        return "accepted";
      case 200:
        return "coalesced";
      case 401:
      case 403:
        // The credential was refused, or this handle does not authorize this server. Both mean the
        // handle held here is not going to start working, so it is cleared and the phone
        // re-authorizes on its next reconciliation.
        return "unauthorized";
      default:
        return "unavailable";
    }
  }
}

/**
 * Coalesces committed changes by connection and asks the gateway to wake each one's phone.
 *
 * It is the FCM dispatcher's sibling, sharing its batching and differing only in where a wake-up
 * goes and what a refusal means. The two are never both running: a server that sent through both
 * would wake a phone twice for one change, so configuring both is refused where they are built
 * (../direct-server.ts).
 */
export class RelayInvalidationDispatcher extends CoalescingInvalidations {
  readonly #pairing: PairingStore;
  readonly #sender: RelaySender;
  readonly #log: (message: string) => void;

  constructor(
    pairing: PairingStore,
    sender: RelaySender,
    log: (message: string) => void,
  ) {
    super();
    this.#pairing = pairing;
    this.#sender = sender;
    this.#log = log;
  }

  protected override async deliver(
    connectionId: string,
    timeSensitive: boolean,
  ): Promise<void> {
    const handle = this.#pairing.relayHandle(connectionId);
    // No handle means this phone has not authorized this server at the gateway — which is the
    // ordinary state of a connection paired before the operator enabled the relay, and of one
    // whose owner declined. It is not an error and is not logged per update.
    if (handle === undefined) return;
    let outcome: RelayOutcome;
    try {
      outcome = await this.#sender.send({
        handle,
        hint: timeSensitive ? "created" : "updated",
      });
    } catch {
      // A sender that throws is a host's own transport failing. It is still only a hint.
      this.#log(
        `relay invalidation failed for connection ${connectionId}: delivery unavailable`,
      );
      return;
    }
    if (outcome === "unauthorized") {
      // Compare-clear, for the same reason a rejected FCM target is compare-cleared: the phone may
      // have re-authorized while this send was in flight, and clearing unconditionally would throw
      // away a handle that works.
      this.#pairing.clearRelayHandle(connectionId, handle);
      this.#log(
        `relay invalidation skipped for connection ${connectionId}: the handle no longer authorizes this server`,
      );
    } else if (outcome === "unavailable") {
      this.#log(
        `relay invalidation failed for connection ${connectionId}: delivery unavailable`,
      );
    }
  }
}
