/**
 * A subscriber, as far as the gateway can tell (SEE-98): one device reading a feed over the
 * gateway's own client API, and a record of every request it sent.
 *
 * It posts JSON to the Connect procedure paths rather than using a generated client, for the reason
 * `publisher-support/publish/feedgateway_test.go` already does: the only compiled feed client in this
 * repository is the phone's, and a second implementation here would be a third place for the
 * contract to drift. What matters for this suite is the wire — the request the gateway receives, and
 * the bytes it answers with — and `fetch` shows both without a runtime in between.
 *
 * Two devices are two of these. They share nothing: their own HTTP connections, their own page
 * tokens, their own cursors. That is the whole of what "the same proposal on two devices" means on
 * this side of the contract, because a subscriber tells the gateway nothing about itself and
 * therefore has nothing to keep apart.
 *
 * Every call is recorded, and `sent()` is what the privacy sweep reads: the claim that a
 * subscriber's wallet, amount, decision and signature never leave the phone is checked against the
 * traffic this device actually produced, not against the code that produced it.
 */

/** One request this device sent, as it went out. */
export interface FeedCall {
  /** The Connect procedure, e.g. `/seekervault.gateway.v1.FeedService/ListProposals`. */
  readonly procedure: string;
  /** The request body, verbatim. */
  readonly body: string;
  /** The status the gateway answered with. */
  readonly status: number;
  /** The answer body, verbatim, so two devices can be compared byte for byte. */
  readonly answer: string;
}

export interface Proposal {
  readonly serverId: string;
  readonly channel: string;
  readonly proposalId: string;
  readonly revision: string;
  readonly operation: string;
  readonly pluginId: string;
  readonly status: string;
  readonly expiresAt: string;
  readonly publisherNote?: string;
  readonly values?: readonly { readonly key: string; readonly text: string }[];
}

export interface Manifest {
  readonly serverId: string;
  readonly displayName?: string;
  readonly settingsRevision: string;
  /** `CONNECTION_MODE_GATEWAY_FEED` for a publisher, and never anything else here. */
  readonly mode: string;
  readonly protocolVersion: number;
  readonly environments?: readonly string[];
  readonly requiredPlugins?: readonly {
    readonly pluginId: string;
    readonly minContract: number;
    readonly maxContract: number;
  }[];
  /** The reference: which gateway, and which channel on it. */
  readonly feed?: {
    readonly gatewayUrl: string;
    readonly channel: string;
  };
}

/** What the gateway answered, and the text it answered with. */
export interface Answered<T> {
  readonly ok: boolean;
  readonly status: number;
  readonly text: string;
  readonly value: T;
}

/** A refusal, as a subscriber sees one: a Connect code and the gateway's own problem name. */
export interface Refusal {
  readonly code: string;
  readonly message: string;
}

export interface Device {
  /** For failure messages only. The gateway is never told which device asked. */
  readonly name: string;
  /** Every request this device sent, in order. */
  readonly calls: readonly FeedCall[];
  /** Those requests as one searchable block, for the privacy sweep. */
  sent(): string;
  manifest(
    serverId: string,
    knownSettingsRevision?: number,
  ): Promise<
    Answered<{
      manifest?: Manifest;
      unchanged?: boolean;
      settingsRevision?: string;
    }>
  >;
  proposals(
    channel: string,
    options?: {
      readonly pageSize?: number;
      readonly pageToken?: string;
      readonly knownSnapshotSequence?: string;
    },
  ): Promise<
    Answered<{
      proposals?: readonly Proposal[];
      nextPageToken?: string;
      snapshotSequence?: string;
      unchanged?: boolean;
    }>
  >;
  proposal(
    channel: string,
    proposalId: string,
  ): Promise<Answered<{ proposal?: Proposal }>>;
  ticket(channels: readonly string[]): Promise<
    Answered<{
      ticket?: string;
      channels?: readonly { channel: string; streamChannel: string }[];
      lifetimeSeconds?: number;
    }>
  >;
  topics(
    channels: readonly string[],
  ): Promise<
    Answered<{ topics?: readonly { channel: string; topic: string }[] }>
  >;
  /** Any procedure on any listener, which is how "a read port serves no write" is checked. */
  call<T>(
    procedure: string,
    body: unknown,
    options?: {
      readonly service?: string;
      readonly origin?: string;
      readonly credential?: string;
    },
  ): Promise<Answered<T>>;
}

/** One device, reading `origin`. Two devices are two of these, and they share nothing. */
export function device(origin: string, name: string): Device {
  const calls: FeedCall[] = [];

  async function call<T>(
    procedure: string,
    body: unknown,
    options: {
      readonly service?: string;
      readonly origin?: string;
      readonly credential?: string;
    } = {},
  ): Promise<Answered<T>> {
    const service = options.service ?? "seekervault.gateway.v1.FeedService";
    const path = `/${service}/${procedure}`;
    const text = JSON.stringify(body);
    const answer = await fetch(`${options.origin ?? origin}${path}`, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        ...(options.credential === undefined
          ? {}
          : { authorization: `Bearer ${options.credential}` }),
      },
      body: text,
    });
    const answered = await answer.text();
    calls.push({
      procedure: path,
      body: text,
      status: answer.status,
      answer: answered,
    });
    return {
      ok: answer.ok,
      status: answer.status,
      text: answered,
      // A listener that does not serve a procedure answers in plain text, not in Connect's JSON,
      // which is itself part of what "this port serves no such thing" looks like from outside.
      value: parsed<T>(answered),
    };
  }

  return {
    name,
    calls,
    sent: () => calls.map((one) => `${one.procedure} ${one.body}`).join("\n"),
    manifest: (serverId, knownSettingsRevision = 0) =>
      call("GetServerManifest", {
        serverId,
        ...(knownSettingsRevision === 0
          ? {}
          : { knownSettingsRevision: String(knownSettingsRevision) }),
      }),
    proposals: (channel, options = {}) =>
      call("ListProposals", {
        channel,
        ...(options.pageSize === undefined
          ? {}
          : { pageSize: options.pageSize }),
        ...(options.pageToken === undefined
          ? {}
          : { pageToken: options.pageToken }),
        ...(options.knownSnapshotSequence === undefined
          ? {}
          : { knownSnapshotSequence: options.knownSnapshotSequence }),
      }),
    proposal: (channel, proposalId) =>
      call("GetProposal", { channel, proposalId }),
    ticket: (channels) => call("GetStreamTicket", { channels }),
    topics: (channels) => call("GetFeedTopics", { channels }),
    call,
  };
}

/** The answer as JSON, or an empty object when it is not JSON at all. */
function parsed<T>(text: string): T {
  if (text === "") return {} as T;
  try {
    return JSON.parse(text) as T;
  } catch {
    return {} as T;
  }
}

/** The refusal in an answer that failed, with a message that says what came back if it did not. */
export function refusal(answered: Answered<unknown>): Refusal {
  if (answered.ok) {
    throw new Error(
      `expected a refusal, and the gateway answered ${answered.text}`,
    );
  }
  return answered.value as Refusal;
}

/** What a channel is called, which a phone derives from its own feed reference. */
export function channelFor(serverId: string): string {
  return `server/${serverId}`;
}
