/**
 * The networks a direct server declares in its manifest (SEE-174), and how declaring them moves the
 * settings revision.
 *
 * What is worth pinning down is that nothing here is a guess. A blank configuration declares no
 * network rather than Mainnet, a name the SDK doesn't know is refused rather than dropped, and the
 * order a host happened to list its networks in never reaches the manifest's bytes — because the
 * phone caches a manifest by revision, and a revision that moved on a reordering would make it
 * reread a manifest that says nothing new.
 */
import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  canonicalSupportedNetworks,
  manifestFingerprint,
  parseSupportedNetworks,
  publishManifest,
  serverManifest,
  solanaNetworkName,
  SOLANA_NETWORK_NAMES,
} from "./manifest.ts";
import {
  SolanaNetwork,
  type ServerManifest,
} from "./gen/seekervault/server/v1/manifest_pb.js";
import { openDatabase } from "./storage/database.ts";
import { PairingStore } from "./storage/pairing-store.ts";
import { temporaryDatabasePath } from "./testing/process.ts";

const URL_A = "https://vault.example.com";
const { MAINNET, DEVNET, TESTNET, UNSPECIFIED } = SolanaNetwork;

/** The networks a direct manifest declares, which live in its reference. */
function declared(manifest: ServerManifest): SolanaNetwork[] | undefined {
  return manifest.reference.case === "direct"
    ? manifest.reference.value.supportedNetworks
    : undefined;
}

describe("parsing configured networks", () => {
  it("reads the canonical names and writes them in canonical order", () => {
    assert.deepEqual(parseSupportedNetworks("mainnet"), [MAINNET]);
    assert.deepEqual(parseSupportedNetworks("testnet,mainnet,devnet"), [
      MAINNET,
      DEVNET,
      TESTNET,
    ]);
    assert.deepEqual(parseSupportedNetworks(" devnet , mainnet "), [
      MAINNET,
      DEVNET,
    ]);
    assert.deepEqual(SOLANA_NETWORK_NAMES, ["mainnet", "devnet", "testnet"]);
  });

  it("reads a blank value or none as no network, never as Mainnet", () => {
    assert.deepEqual(parseSupportedNetworks(""), []);
    assert.deepEqual(parseSupportedNetworks("   "), []);
    assert.deepEqual(parseSupportedNetworks(" none "), []);
    assert.throws(
      () => parseSupportedNetworks("none,mainnet"),
      /none together with a network/,
    );
  });

  it("refuses unknown names, other cases and aliases, naming the source", () => {
    for (const value of ["mainnet-beta", "Mainnet", "MAINNET", "localnet"]) {
      assert.throws(
        () => parseSupportedNetworks(value, "SAC_SUPPORTED_NETWORKS"),
        (error: Error) =>
          error.message.startsWith("SAC_SUPPORTED_NETWORKS names ") &&
          error.message.includes("mainnet, devnet, testnet"),
        value,
      );
    }
  });

  it("refuses a repeated name and an empty entry", () => {
    assert.throws(
      () => parseSupportedNetworks("mainnet,devnet,mainnet"),
      /mainnet more than once/,
    );
    assert.throws(
      () => parseSupportedNetworks("mainnet,,devnet"),
      /empty entry/,
    );
    assert.throws(() => parseSupportedNetworks("mainnet,"), /empty entry/);
  });
});

describe("canonical supported networks", () => {
  it("orders by value and refuses what the contract refuses", () => {
    assert.deepEqual(canonicalSupportedNetworks([TESTNET, MAINNET]), [
      MAINNET,
      TESTNET,
    ]);
    assert.deepEqual(canonicalSupportedNetworks([]), []);
    assert.throws(
      () => canonicalSupportedNetworks([UNSPECIFIED]),
      /SOLANA_NETWORK_UNSPECIFIED/,
    );
    assert.throws(
      () => canonicalSupportedNetworks([9 as SolanaNetwork]),
      /value 9/,
    );
    assert.throws(
      () => canonicalSupportedNetworks([DEVNET, DEVNET]),
      /devnet more than once/,
    );
  });

  it("names each network the way a binding's network is named", () => {
    assert.equal(solanaNetworkName(MAINNET), "mainnet");
    assert.equal(solanaNetworkName(DEVNET), "devnet");
    assert.equal(solanaNetworkName(TESTNET), "testnet");
    assert.equal(solanaNetworkName(UNSPECIFIED), undefined);
  });
});

describe("the manifest's supported networks", () => {
  it("publishes the declared networks in the direct reference, in canonical order", () => {
    const manifest = serverManifest(
      { serverId: "s", url: URL_A, supportedNetworks: [DEVNET, MAINNET] },
      1,
    );
    assert.deepEqual(declared(manifest), [MAINNET, DEVNET]);
  });

  it("publishes an empty list when the host declares nothing", () => {
    assert.deepEqual(
      declared(serverManifest({ serverId: "s", url: URL_A }, 1)),
      [],
    );
  });

  it("throws rather than publishing a list the phone would refuse", () => {
    assert.throws(() =>
      serverManifest(
        { serverId: "s", url: URL_A, supportedNetworks: [MAINNET, MAINNET] },
        1,
      ),
    );
  });

  it("fingerprints the networks, but not their order or an empty list", () => {
    const base = { serverId: "s", url: URL_A };
    // A server that still declares nothing has the fingerprint it had before the field existed, so
    // upgrading the SDK alone moves no revision.
    assert.equal(
      manifestFingerprint(base),
      manifestFingerprint({ ...base, supportedNetworks: [] }),
    );
    assert.notEqual(
      manifestFingerprint(base),
      manifestFingerprint({ ...base, supportedNetworks: [MAINNET] }),
    );
    assert.notEqual(
      manifestFingerprint({ ...base, supportedNetworks: [MAINNET] }),
      manifestFingerprint({ ...base, supportedNetworks: [MAINNET, DEVNET] }),
    );
    assert.equal(
      manifestFingerprint({ ...base, supportedNetworks: [DEVNET, MAINNET] }),
      manifestFingerprint({ ...base, supportedNetworks: [MAINNET, DEVNET] }),
    );
  });

  it("keeps the revision for unchanged networks and moves it once for a change", () => {
    const path = temporaryDatabasePath();
    const publish = (supportedNetworks: readonly SolanaNetwork[]) => {
      // A fresh store each time, because the settings a manifest describes change only across a
      // restart.
      const db = openDatabase(path);
      const pairing = new PairingStore(db);
      const manifest = publishManifest(pairing, {
        serverId: pairing.serverId(),
        url: URL_A,
        supportedNetworks,
      });
      db.close();
      return manifest.settingsRevision;
    };

    assert.equal(publish([MAINNET]), 1n);
    assert.equal(publish([DEVNET, MAINNET]), 2n);
    // The same networks in another order are the same settings.
    assert.equal(publish([MAINNET, DEVNET]), 2n);
    // Dropping a network is a change like any other, and the revision never goes back.
    assert.equal(publish([MAINNET]), 3n);
    assert.equal(publish([]), 4n);
  });
});
