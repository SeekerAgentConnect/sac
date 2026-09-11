import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readdirSync, readFileSync } from "node:fs";
import { describe, it } from "node:test";

import {
  create,
  equals,
  fromBinary,
  fromJsonString,
  toBinary,
  type DescMessage,
} from "@bufbuild/protobuf";

import {
  ActionRequestSchema,
  Network,
  PreparedTransactionSchema,
  RequestError,
  RequestErrorDetailSchema,
  RequestRefSchema,
  RequestState,
  WalletBindingSchema,
  type ActionRequest,
} from "../gen/seekervault/request/v1/request_pb.js";
import {
  ListPendingResponseSchema,
  PublishWalletRequestSchema,
  SubmitResultRequestSchema,
} from "../gen/seekervault/request/v1/service_pb.js";
import {
  MAX_BASE_UNITS,
  invalidActionReason,
  invalidBindingReason,
  messageBytes,
} from "./action.ts";
import { checkRef } from "./identity.ts";

// `pnpm generate` writes each .binpb from the .json beside it with `buf convert`. The Android unit
// tests (RequestProtocolFixturesTest) check the same .binpb files, so all three implementations
// agree.
const FIXTURES = new URL(
  "../../../proto/fixtures/seekervault/request/v1/",
  import.meta.url,
);

const cases: ReadonlyArray<readonly [DescMessage, string]> = [
  [ActionRequestSchema, "ack_pending"],
  [ActionRequestSchema, "same_id_other_connection"],
  [ActionRequestSchema, "transfer_max_amount"],
  [ActionRequestSchema, "transfer_confirmed"],
  [ActionRequestSchema, "sign_message_text"],
  [ActionRequestSchema, "sign_message_data"],
  [ActionRequestSchema, "swap_pending"],
  [ActionRequestSchema, "empty"],
  [PreparedTransactionSchema, "v2"],
  [PreparedTransactionSchema, "max_values"],
  [SubmitResultRequestSchema, "approval"],
  [SubmitResultRequestSchema, "rejection"],
  [SubmitResultRequestSchema, "transaction_submission"],
  [ListPendingResponseSchema, "page"],
  [RequestErrorDetailSchema, "invalid_state"],
  [WalletBindingSchema, "mainnet"],
  [PublishWalletRequestSchema, "devnet"],
  [PublishWalletRequestSchema, "cleared"],
];

function binary(path: string): Uint8Array {
  return new Uint8Array(readFileSync(new URL(`${path}.binpb`, FIXTURES)));
}

function request(name: string): ActionRequest {
  return fromBinary(ActionRequestSchema, binary(`ActionRequest/${name}`));
}

function sha256(bytes: Uint8Array): Uint8Array {
  return new Uint8Array(createHash("sha256").update(bytes).digest());
}

describe("cross-runtime request fixtures", () => {
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

  it("covers every fixture in the package", () => {
    const files = readdirSync(FIXTURES, { recursive: true, encoding: "utf8" })
      .filter((file) => file.endsWith(".json"))
      .map((file) => file.replace(/\.json$/, ""))
      .sort();
    assert.deepEqual(
      files,
      cases.map(([schema, name]) => `${schema.name}/${name}`).sort(),
    );
  });

  it("keeps amounts as exact strings, up to the u64 maximum", () => {
    const transfer = request("transfer_max_amount").action?.kind;
    assert.equal(transfer?.case, "transfer");
    assert.equal(transfer.value.amount, "18446744073709551615");
    assert.equal(BigInt(transfer.value.amount), MAX_BASE_UNITS);
    const swap = request("swap_pending").action?.kind;
    assert.equal(swap?.case, "swap");
    // Above 2^53, where a JSON number would lose the last digit.
    assert.equal(swap.value.inputAmount, "9007199254740993");
  });

  it("keeps message text and bytes exactly, and binds the approval to them", () => {
    const signed = request("sign_message_text");
    const kind = signed.action?.kind;
    assert.equal(kind?.case, "signMessage");
    // A decomposed e-acute (e + U+0301), a precomposed one (U+00E9), two spaces, and a ZWJ emoji.
    const text =
      "Sign in to Example\r\ne\u{301} caf\u{E9}  \u{1F469}\u{200D}\u{1F4BB}\n";
    assert.equal(kind.value.content.value, text);
    assert.notEqual(text, text.normalize("NFC"));
    assert.equal(messageBytes(kind.value).length, 43);
    assert.deepEqual(
      signed.outcome?.approval?.contentHash,
      sha256(messageBytes(kind.value)),
    );
    assert.equal(signed.outcome?.signature.length, 64);

    const data = request("sign_message_data").action?.kind;
    assert.equal(data?.case, "signMessage");
    assert.deepEqual(
      data.value.content.value,
      Uint8Array.of(0x00, 0xff, 0x00, 0x7f, 0x80, 0x0a),
    );
  });

  it("binds an approval to a prepared version and the SHA-256 of its bytes", () => {
    const prepared = fromBinary(
      PreparedTransactionSchema,
      binary("PreparedTransaction/v2"),
    );
    assert.deepEqual(prepared.contentHash, sha256(prepared.transaction));
    assert.equal(prepared.lastValidBlockHeight, 412_345_678n);
    const approval = fromBinary(
      SubmitResultRequestSchema,
      binary("SubmitResultRequest/approval"),
    ).result;
    assert.equal(approval.case, "approval");
    assert.equal(approval.value.preparedVersion, prepared.version);
    assert.deepEqual(approval.value.contentHash, prepared.contentHash);
    const confirmed = request("transfer_confirmed");
    assert.equal(confirmed.state, RequestState.CONFIRMED);
    assert.deepEqual(confirmed.outcome?.approval, approval.value);
  });

  it("reads the u64 and u32 maximums", () => {
    const prepared = fromBinary(
      PreparedTransactionSchema,
      binary("PreparedTransaction/max_values"),
    );
    assert.equal(prepared.lastValidBlockHeight, 2n ** 64n - 1n);
    assert.equal(prepared.version, 2 ** 32 - 1);
  });

  it("keeps the same request ID under two connections apart", () => {
    const ours = request("ack_pending");
    const theirs = request("same_id_other_connection");
    assert.equal(ours.ref?.requestId, theirs.ref?.requestId);
    assert.notEqual(ours.ref?.connectionId, theirs.ref?.connectionId);
    assert.ok(!equals(ActionRequestSchema, ours, theirs));
    // They differ in nothing else.
    const moved = create(ActionRequestSchema, {
      ...theirs,
      ref: create(RequestRefSchema, ours.ref),
    });
    assert.ok(equals(ActionRequestSchema, ours, moved));
    assert.equal(checkRef(theirs.ref, ours.ref?.connectionId ?? "").ok, false);
  });

  it("tells an empty result from a missing one, and a missing action from an empty one", () => {
    const rejection = fromBinary(
      SubmitResultRequestSchema,
      binary("SubmitResultRequest/rejection"),
    );
    assert.equal(rejection.result.case, "rejection");
    assert.equal(
      fromBinary(SubmitResultRequestSchema, new Uint8Array()).result.case,
      undefined,
    );
    const empty = request("empty");
    assert.equal(empty.ref, undefined);
    assert.equal(empty.action, undefined);
    assert.equal(empty.state, RequestState.UNSPECIFIED);
    assert.equal(invalidActionReason(empty.action), "action is missing");
  });

  it("lists pending requests oldest first", () => {
    const page = fromBinary(
      ListPendingResponseSchema,
      binary("ListPendingResponse/page"),
    );
    assert.deepEqual(
      page.requests.map((pending) => pending.state),
      [RequestState.PENDING, RequestState.PENDING],
    );
    const [first, second] = page.requests.map((pending) => pending.createdAt);
    assert.ok(first && second);
    assert.ok(
      first.seconds < second.seconds ||
        (first.seconds === second.seconds && first.nanos < second.nanos),
    );
    assert.notEqual(page.nextPageToken, "");
  });

  it("names the owner's wallet and network, and tells a cleared binding from a set one", () => {
    const binding = fromBinary(
      WalletBindingSchema,
      binary("WalletBinding/mainnet"),
    );
    assert.equal(binding.network, Network.MAINNET);
    assert.equal(
      invalidBindingReason(binding.wallet, binding.network),
      undefined,
    );
    const devnet = fromBinary(
      PublishWalletRequestSchema,
      binary("PublishWalletRequest/devnet"),
    );
    assert.equal(devnet.binding?.network, Network.DEVNET);
    // The phone doesn't set bound_at: the sidecar stamps it.
    assert.equal(devnet.binding.boundAt, undefined);
    const cleared = fromBinary(
      PublishWalletRequestSchema,
      binary("PublishWalletRequest/cleared"),
    );
    assert.equal(cleared.connectionId, devnet.connectionId);
    assert.equal(cleared.binding, undefined);
  });

  it("carries the request as it is now in an error detail", () => {
    const detail = fromBinary(
      RequestErrorDetailSchema,
      binary("RequestErrorDetail/invalid_state"),
    );
    assert.equal(detail.error, RequestError.INVALID_STATE);
    assert.equal(detail.request?.state, RequestState.CANCELLED);
  });

  it("uses only valid actions in the non-empty requests", () => {
    for (const [schema, name] of cases) {
      if (schema !== ActionRequestSchema || name === "empty") continue;
      assert.equal(invalidActionReason(request(name).action), undefined, name);
    }
  });
});
