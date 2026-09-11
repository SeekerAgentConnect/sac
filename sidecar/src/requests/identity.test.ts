import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  create,
  fromBinary,
  toBinary,
  type MessageInitShape,
} from "@bufbuild/protobuf";

import {
  ActionRequestSchema,
  ActionSchema,
  Network,
  RequestError,
  type Action,
  type TransferAction,
  type TransferActionSchema,
} from "../gen/seekervault/request/v1/request_pb.js";
import {
  actionFingerprint,
  checkRef,
  invalidIdempotencyKeyReason,
  invalidRefReason,
  resolveIdempotency,
} from "./identity.ts";

const CONNECTION_A = "5d3c8f0e-2b7a-4c1d-9e6f-0a1b2c3d4e5f";
const CONNECTION_B = "a7e9c1b3-4d5f-4a6b-8c7d-9e0f1a2b3c4d";
const REQUEST = "3f2a1b0c-9d8e-4f7a-8b6c-5d4e3f2a1b0c";
const WALLET = "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW";
const RECIPIENT = "3YKUMU99pedShDEe76HuSAHo3dt9CXjBwjN8w8NUo9Wh";
const USDC = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";

// Plain init objects rather than messages, so that two of them can be spread together.
type TransferInit = Exclude<
  MessageInitShape<typeof TransferActionSchema>,
  TransferAction
>;

const TRANSFER: TransferInit = {
  wallet: WALLET,
  network: Network.DEVNET,
  recipient: RECIPIENT,
  asset: { kind: { case: "nativeSol", value: {} } },
  amount: "1500000",
};

function transfer(fields: TransferInit = {}): Action {
  return create(ActionSchema, {
    kind: { case: "transfer", value: { ...TRANSFER, ...fields } },
  });
}

function signText(text: string): Action {
  return create(ActionSchema, {
    kind: {
      case: "signMessage",
      value: { wallet: WALLET, content: { case: "text", value: text } },
    },
  });
}

describe("request references", () => {
  it("scopes a request ID by its connection", () => {
    const ours = { connectionId: CONNECTION_A, requestId: REQUEST };
    const theirs = { connectionId: CONNECTION_B, requestId: REQUEST };
    assert.deepEqual(checkRef(ours, CONNECTION_A), { ok: true });
    // The same request ID under another connection is another request, and it isn't ours.
    for (const [ref, caller] of [
      [theirs, CONNECTION_A],
      [ours, CONNECTION_B],
    ] as const) {
      assert.deepEqual(checkRef(ref, caller), {
        ok: false,
        error: RequestError.NOT_FOUND,
        message: "no such request",
      });
    }
  });

  it("requires both IDs, as lowercase UUIDs", () => {
    const cases = [
      [undefined, "ref is missing"],
      [
        { connectionId: "", requestId: REQUEST },
        "ref.connection_id is missing",
      ],
      [
        { connectionId: CONNECTION_A, requestId: "" },
        "ref.request_id is missing",
      ],
      [
        { connectionId: CONNECTION_A.toUpperCase(), requestId: REQUEST },
        "ref.connection_id is not a lowercase UUID",
      ],
      [
        { connectionId: CONNECTION_A, requestId: "request-1" },
        "ref.request_id is not a lowercase UUID",
      ],
    ] as const;
    for (const [ref, reason] of cases) {
      assert.equal(invalidRefReason(ref), reason);
      assert.deepEqual(checkRef(ref, CONNECTION_A), {
        ok: false,
        error: RequestError.INVALID_PARAMETERS,
        message: reason,
      });
    }
  });
});

describe("idempotency keys", () => {
  it("accepts 1 to 128 characters from A-Z, a-z, 0-9, and . _ : -", () => {
    for (const key of [
      "a",
      "deploy-2026-09-11-1",
      "invoice:42.retry_1",
      "0b7e2a44-9c31-4f8d-8a55-6d2e1f3c9b10",
      "x".repeat(128),
    ]) {
      assert.equal(invalidIdempotencyKeyReason(key), undefined, key);
    }
  });

  it("rejects empty, long, and other keys", () => {
    assert.equal(invalidIdempotencyKeyReason(""), "idempotency_key is missing");
    for (const key of [
      "x".repeat(129),
      "has space",
      "slash/key",
      "tab\t",
      "ключ",
      "emoji😀",
    ]) {
      assert.equal(
        invalidIdempotencyKeyReason(key),
        `idempotency_key must be 1 to 128 characters from A-Z, a-z, 0-9, ".", "_", ":", and "-"`,
        key,
      );
    }
  });
});

describe("actionFingerprint", () => {
  it("is the same for equal parameters, however the action was built", () => {
    const built = transfer();
    const parsed = fromBinary(ActionSchema, toBinary(ActionSchema, built));
    const reordered = create(ActionSchema, {
      kind: {
        case: "transfer",
        value: {
          amount: "1500000",
          asset: { kind: { case: "nativeSol", value: {} } },
          recipient: RECIPIENT,
          network: Network.DEVNET,
          wallet: WALLET,
        },
      },
    });
    const fingerprint = actionFingerprint(built);
    assert.match(fingerprint, /^[0-9a-f]{64}$/);
    assert.equal(actionFingerprint(parsed), fingerprint);
    assert.equal(actionFingerprint(reordered), fingerprint);
  });

  it("ignores unknown fields a newer client might add", () => {
    const bytes = toBinary(ActionSchema, transfer());
    // Field 15, varint 1: a field this version doesn't know.
    const withUnknown = fromBinary(
      ActionSchema,
      Uint8Array.from([...bytes, 0x78, 0x01]),
    );
    assert.equal(actionFingerprint(withUnknown), actionFingerprint(transfer()));
  });

  it("changes when any parameter changes, down to a digit or a byte", () => {
    const variants = [
      transfer(),
      transfer({ wallet: RECIPIENT, recipient: WALLET }),
      transfer({ network: Network.MAINNET }),
      transfer({ recipient: USDC }),
      transfer({ asset: { kind: { case: "tokenMint", value: USDC } } }),
      transfer({ amount: "1500001" }),
      transfer({ amount: "18446744073709551615" }),
      transfer({ amount: "18446744073709551614" }),
      signText("Sign in"),
      signText("Sign in "),
      signText("Sign in\n"),
      signText("caf\u{E9}"), // precomposed e-acute
      signText("cafe\u{301}"), // e + combining acute: equal after NFC, other bytes
      create(ActionSchema, {
        kind: {
          case: "signMessage",
          value: {
            wallet: WALLET,
            content: {
              case: "data",
              value: new TextEncoder().encode("Sign in"),
            },
          },
        },
      }),
    ];
    const fingerprints = new Set(variants.map(actionFingerprint));
    assert.equal(fingerprints.size, variants.length);
  });
});

describe("resolveIdempotency", () => {
  const original = transfer();
  const record = {
    requestId: REQUEST,
    fingerprint: actionFingerprint(original),
  };

  it("creates a request for a new key", () => {
    assert.deepEqual(
      resolveIdempotency(undefined, actionFingerprint(original)),
      {
        kind: "create",
      },
    );
  });

  it("replays the original request for the same key and the same parameters", () => {
    const retry = fromBinary(ActionSchema, toBinary(ActionSchema, original));
    assert.deepEqual(resolveIdempotency(record, actionFingerprint(retry)), {
      kind: "replay",
      requestId: REQUEST,
    });
  });

  it("rejects the same key with changed parameters instead of reusing it", () => {
    for (const changed of [
      transfer({ amount: "1500001" }),
      transfer({ recipient: USDC }),
      transfer({ network: Network.MAINNET }),
    ]) {
      assert.deepEqual(resolveIdempotency(record, actionFingerprint(changed)), {
        kind: "conflict",
        requestId: REQUEST,
      });
    }
  });

  it("replays a retry that only rewords the agent's note, which isn't a parameter", () => {
    const first = create(ActionRequestSchema, {
      action: original,
      agentNote: "Pay invoice 42",
    });
    const retry = create(ActionRequestSchema, {
      action: transfer(),
      agentNote: "Paying invoice #42 (retry)",
    });
    assert.ok(first.action && retry.action);
    assert.equal(
      actionFingerprint(retry.action),
      actionFingerprint(first.action),
    );
  });
});
