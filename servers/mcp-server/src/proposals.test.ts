/**
 * The shared proposal contract, checked in this runtime too (SEE-89).
 *
 * This sidecar publishes no proposal and never will: a proposal is a developer's broadcast, sent by
 * the Go publisher templates (SEE-95, SEE-96) through the shared gateway (SEE-90), while this is the
 * owner's own private server and speaks `seekervault.request.v1`. The file exists for the contract's
 * own sake, and that turned out to matter for the manifest: where a runtime puts a field's bytes is
 * not settled by looking at one runtime.
 *
 * `pnpm generate` writes each .binpb from the .json beside it with `buf convert`, which is Go's
 * protobuf. The Android unit tests (ProposalFixturesTest) read the same files and check what the
 * phone makes of each one. So three runtimes have to agree about the same bytes: the publisher that
 * writes them, the phone that acts on them, and this one.
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
  ProposalSchema,
  ProposalStatus,
} from "./gen/seekervault/proposal/v1/proposal_pb.js";

const FIXTURES = new URL(
  "../../../packages/protocol/proto/fixtures/seekervault/proposal/v1/Proposal/",
  import.meta.url,
);

const FIXTURE_CASES = [
  "open",
  "cancelled",
  "foreign_channel",
  "max_revision",
] as const;

function proposal(name: string) {
  return fromJsonString(
    ProposalSchema,
    readFileSync(new URL(`${name}.json`, FIXTURES), "utf8"),
  );
}

describe("cross-runtime proposal fixtures", () => {
  for (const name of FIXTURE_CASES) {
    it(`${name} encodes and decodes byte for byte like buf`, () => {
      const bytes = new Uint8Array(
        readFileSync(new URL(`${name}.binpb`, FIXTURES)),
      );
      const message = proposal(name);
      assert.deepEqual(toBinary(ProposalSchema, message), bytes);
      assert.ok(
        equals(ProposalSchema, fromBinary(ProposalSchema, bytes), message),
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

  it("carries the publisher's own terms and nothing about a subscriber", () => {
    const open = proposal("open");

    assert.equal(open.revision, 4n);
    assert.equal(open.operation, "swap");
    assert.equal(open.pluginId, "jupiter.swap");
    assert.equal(open.status, ProposalStatus.OPEN);
    assert.equal(open.channel, `server/${open.serverId}`);
    // Common intent, named by the publisher and read by the plugin that serves the operation.
    assert.deepEqual(
      open.values.map((value) => value.key),
      ["input_mint", "output_mint", "published_price", "slippage_bps"],
    );
    // The document has no field for a subscriber's wallet, their chosen quantity, or anything
    // prepared for them to sign, and the phone's guard fails if one is added (StageBoundaryTest).
    assert.deepEqual(
      Object.keys(open).filter((field) =>
        /wallet|amount|signature|approval|transaction/i.test(field),
      ),
      [],
    );
  });

  it("keeps a revision exact to the u64 maximum", () => {
    // A phone compares revisions to decide whether what it holds is current, so the largest one a
    // publisher could send has to survive the wire without losing its last digit. The phone refuses
    // that value, because its own runtime cannot order it (ProposalFixturesTest).
    assert.equal(
      proposal("max_revision").revision,
      18_446_744_073_709_551_615n,
    );
  });

  it("says nothing about a proposal with no status", () => {
    // Zero is never published, and the phone refuses it rather than reading a missing status as
    // the permissive one.
    assert.equal(ProposalStatus.UNSPECIFIED, 0);
  });
});
