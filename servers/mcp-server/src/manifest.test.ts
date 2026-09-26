/**
 * What this sidecar publishes about itself, and how its settings revision moves (SEE-88).
 *
 * The revision is the part worth testing: a phone caches a manifest by identity and revision and
 * refuses one that goes backwards, so it has to change exactly when the content does — not on
 * every restart, and never down.
 */
import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { describe, it } from "node:test";

import {
  equals,
  fromBinary,
  fromJsonString,
  toBinary,
} from "@bufbuild/protobuf";

import {
  ConnectionMode,
  ServerEnvironment,
  ServerManifestSchema,
} from "@seeker_agent_connect/server-sdk/protocol";
import {
  manifestFingerprint,
  publishManifest,
  SERVER_PROTOCOL_VERSION,
  serverManifest,
} from "../../../packages/server-sdk/src/manifest.ts";
import {
  IN_MEMORY,
  openDatabase,
} from "../../../packages/server-sdk/src/storage/database.ts";
import { PairingStore } from "../../../packages/server-sdk/src/storage/pairing-store.ts";
import { temporaryDatabasePath } from "./testing/process.ts";

// `pnpm generate` writes each .binpb from the .json beside it with `buf convert`. The Android unit
// tests (ServerManifestFixturesTest) read the same .binpb files and check what the phone makes of
// each one, so both runtimes agree about the document and about which manifests are refused.
const FIXTURES = new URL(
  "../../../packages/protocol/proto/fixtures/seekervault/server/v1/ServerManifest/",
  import.meta.url,
);

const FIXTURE_CASES = [
  "direct",
  "feed",
  "foreign_channel",
  "max_revision",
] as const;

const URL_A = "https://vault.example.com";
const URL_B = "https://vault.example.com:8443";

function store(path = IN_MEMORY) {
  const db = openDatabase(path);
  return {
    db,
    pairing: new PairingStore(db, { now: () => Date.UTC(2026, 8, 17) }),
  };
}

describe("cross-runtime manifest fixtures", () => {
  for (const name of FIXTURE_CASES) {
    it(`${name} encodes and decodes byte for byte like buf`, () => {
      const json = readFileSync(new URL(`${name}.json`, FIXTURES), "utf8");
      const bytes = new Uint8Array(
        readFileSync(new URL(`${name}.binpb`, FIXTURES)),
      );
      const message = fromJsonString(ServerManifestSchema, json);
      assert.deepEqual(toBinary(ServerManifestSchema, message), bytes);
      assert.ok(
        equals(
          ServerManifestSchema,
          fromBinary(ServerManifestSchema, bytes),
          message,
        ),
      );
    });
  }

  it("covers every fixture in the package", () => {
    const files = readdirSync(FIXTURES)
      .filter((file) => file.endsWith(".json"))
      .map((file) => file.replace(/\.json$/, ""))
      .sort();
    assert.deepEqual(files, [...FIXTURE_CASES].sort());
  });

  it("keeps a revision exact to the u64 maximum", () => {
    // The phone compares revisions to decide whether what it cached is current, so the largest
    // one a server could publish has to survive the wire without losing its last digit.
    const message = fromJsonString(
      ServerManifestSchema,
      readFileSync(new URL("max_revision.json", FIXTURES), "utf8"),
    );
    assert.equal(message.settingsRevision, 18_446_744_073_709_551_615n);
  });
});

describe("the server manifest", () => {
  it("describes a direct server that requires no client plugin", () => {
    const manifest = serverManifest({ serverId: "s", url: URL_A }, 7);

    assert.equal(manifest.serverId, "s");
    assert.equal(manifest.protocolVersion, SERVER_PROTOCOL_VERSION);
    assert.equal(manifest.settingsRevision, 7n);
    assert.equal(manifest.mode, ConnectionMode.DIRECT);
    assert.equal(manifest.reference.case, "direct");
    assert.equal(
      manifest.reference.case === "direct"
        ? manifest.reference.value.url
        : undefined,
      URL_A,
    );
    // An acknowledgement, a message signature and a transfer are the app's own actions, so this
    // server asks for nothing to be installed. It also claims no name of its own.
    assert.deepEqual(manifest.requiredPlugins, []);
    assert.deepEqual(manifest.environments, [ServerEnvironment.PRODUCTION]);
    assert.equal(manifest.displayName, "");
  });

  it("fingerprints the content and not the revision", () => {
    const settings = { serverId: "s", url: URL_A };

    assert.equal(manifestFingerprint(settings), manifestFingerprint(settings));
    assert.notEqual(
      manifestFingerprint(settings),
      manifestFingerprint({ ...settings, url: URL_B }),
    );
    assert.notEqual(
      manifestFingerprint(settings),
      manifestFingerprint({ ...settings, serverId: "other" }),
    );
  });

  it("publishes the same revision while the settings stand", () => {
    const { db, pairing } = store();
    const settings = { serverId: pairing.serverId(), url: URL_A };

    const first = publishManifest(pairing, settings);
    const again = publishManifest(pairing, settings);

    assert.equal(first.settingsRevision, 1n);
    assert.equal(again.settingsRevision, 1n);
    db.close();
  });

  it("moves the revision up once when the settings change, and never down", () => {
    const path = temporaryDatabasePath();
    const first = store(path);
    const before = publishManifest(first.pairing, {
      serverId: first.pairing.serverId(),
      url: URL_A,
    });
    first.db.close();

    // A restart on the same database with a different public URL: one step up, and the identity
    // the phone paired with is unchanged.
    const second = store(path);
    const after = publishManifest(second.pairing, {
      serverId: second.pairing.serverId(),
      url: URL_B,
    });
    // And a restart back to the original settings does not reuse the revision it had then: the
    // phone must be able to tell that the content it cached is no longer the current one.
    const back = publishManifest(second.pairing, {
      serverId: second.pairing.serverId(),
      url: URL_A,
    });

    assert.equal(before.serverId, after.serverId);
    assert.equal(before.settingsRevision, 1n);
    assert.equal(after.settingsRevision, 2n);
    assert.equal(back.settingsRevision, 3n);
    second.db.close();
  });
});
