import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";

import {
  equals,
  fromBinary,
  fromJsonString,
  toBinary,
  type DescMessage,
} from "@bufbuild/protobuf";

import {
  AcknowledgeCommandRequestSchema,
  CommandAcknowledgementSchema,
  LiveCommandSchema,
  WatchCommandsResponseSchema,
} from "../gen/seekervault/live/v1/live_pb.js";

// `pnpm generate` writes each .binpb from the .json beside it with `buf convert`. The
// Android unit tests check the same .binpb files, so all three implementations agree.
const FIXTURES = new URL(
  "../../../proto/fixtures/seekervault/live/v1/",
  import.meta.url,
);

function binary(path: string): Uint8Array {
  return new Uint8Array(readFileSync(new URL(`${path}.binpb`, FIXTURES)));
}

const cases: ReadonlyArray<readonly [DescMessage, string]> = [
  [LiveCommandSchema, "ascii"],
  [LiveCommandSchema, "unicode"],
  [LiveCommandSchema, "max_text"],
  [LiveCommandSchema, "deadline_nanos"],
  [LiveCommandSchema, "max_timestamp"],
  [LiveCommandSchema, "empty"],
  [CommandAcknowledgementSchema, "ok"],
  [WatchCommandsResponseSchema, "ready"],
  [WatchCommandsResponseSchema, "command"],
  [AcknowledgeCommandRequestSchema, "ok"],
];

describe("cross-runtime protocol fixtures", () => {
  for (const [schema, name] of cases) {
    it(`${schema.name}/${name} encodes and decodes byte for byte like buf`, () => {
      const json = readFileSync(
        new URL(`${schema.name}/${name}.json`, FIXTURES),
        "utf8",
      );
      const bytes = binary(`${schema.name}/${name}`);
      const message = fromJsonString(schema, json);
      assert.deepEqual(toBinary(schema, message), bytes);
      assert.ok(equals(schema, fromBinary(schema, bytes), message));
    });
  }

  it("decodes Unicode text unchanged", () => {
    assert.equal(
      fromBinary(LiveCommandSchema, binary("LiveCommand/unicode")).text,
      "Привет 👋🏽 你好 مرحبا é 👩‍💻\nSecond line",
    );
  });

  it("keeps a 4096-byte text intact", () => {
    assert.equal(
      fromBinary(LiveCommandSchema, binary("LiveCommand/max_text")).text,
      "€".repeat(1365) + "!",
    );
  });

  it("keeps nanosecond deadlines up to the largest timestamp", () => {
    const nanos = fromBinary(
      LiveCommandSchema,
      binary("LiveCommand/deadline_nanos"),
    ).expiresAt;
    assert.deepEqual(
      [nanos?.seconds, nanos?.nanos],
      [BigInt(Date.UTC(2026, 8, 11, 12) / 1000), 999_999_999],
    );
    const max = fromBinary(
      LiveCommandSchema,
      binary("LiveCommand/max_timestamp"),
    ).expiresAt;
    assert.deepEqual(
      [max?.seconds, max?.nanos],
      [253_402_300_799n, 999_999_999],
    );
  });

  it("distinguishes the empty `ready` event from an event-less message", () => {
    assert.equal(
      fromBinary(
        WatchCommandsResponseSchema,
        binary("WatchCommandsResponse/ready"),
      ).event.case,
      "ready",
    );
    assert.equal(
      fromBinary(WatchCommandsResponseSchema, new Uint8Array()).event.case,
      undefined,
    );
  });
});
