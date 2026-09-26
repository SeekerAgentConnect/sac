import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { create, type MessageInitShape } from "@bufbuild/protobuf";

import {
  ActionSchema,
  Network,
  StakingOperation,
  type Action,
  type SignMessageActionSchema,
  type StakingAction,
  type StakingActionSchema,
  type SwapAction,
  type SwapActionSchema,
  type TransferAction,
  type TransferActionSchema,
} from "../gen/seekervault/request/v1/request_pb.js";
import {
  MAX_BASE_UNITS,
  MAX_MESSAGE_BYTES,
  actionBinding,
  decodeBase58,
  invalidActionReason,
  invalidNoteReason,
  isAddress,
  messageBytes,
  parseBaseUnits,
  stakingOperationName,
} from "./action.ts";

const WALLET = "G4bAtd9oPdEohgJdzDbeDuwyrWCZ4Ztmi4jxGWFg4faW";
const RECIPIENT = "3YKUMU99pedShDEe76HuSAHo3dt9CXjBwjN8w8NUo9Wh";
const USDC = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
const SYSTEM_PROGRAM = "11111111111111111111111111111111";

// Plain init objects rather than messages, so that two of them can be spread together.
type TransferInit = Exclude<
  MessageInitShape<typeof TransferActionSchema>,
  TransferAction
>;
type SwapInit = Exclude<MessageInitShape<typeof SwapActionSchema>, SwapAction>;
type StakingInit = Exclude<
  MessageInitShape<typeof StakingActionSchema>,
  StakingAction
>;

const TRANSFER: TransferInit = {
  wallet: WALLET,
  network: Network.DEVNET,
  recipient: RECIPIENT,
  asset: { kind: { case: "nativeSol", value: {} } },
  amount: "1",
};

const SWAP: SwapInit = {
  wallet: WALLET,
  network: Network.MAINNET,
  inputAsset: { kind: { case: "tokenMint", value: USDC } },
  outputAsset: { kind: { case: "nativeSol", value: {} } },
  inputAmount: "1000000",
  slippageBps: 50,
};

const STAKING: StakingInit = {
  wallet: WALLET,
  network: Network.MAINNET,
  operation: StakingOperation.STAKE,
  amount: "1000000",
};

function transfer(fields: TransferInit = {}): Action {
  return create(ActionSchema, {
    kind: { case: "transfer", value: { ...TRANSFER, ...fields } },
  });
}

function swap(fields: SwapInit = {}): Action {
  return create(ActionSchema, {
    kind: { case: "swap", value: { ...SWAP, ...fields } },
  });
}

function staking(fields: StakingInit = {}): Action {
  return create(ActionSchema, {
    kind: { case: "staking", value: { ...STAKING, ...fields } },
  });
}

function signMessage(
  fields: MessageInitShape<typeof SignMessageActionSchema>,
): Action {
  return create(ActionSchema, {
    kind: { case: "signMessage", value: { wallet: WALLET, ...fields } },
  });
}

function signText(text: string): Action {
  return signMessage({ content: { case: "text", value: text } });
}

function signData(data: Uint8Array): Action {
  return signMessage({ content: { case: "data", value: data } });
}

describe("parseBaseUnits", () => {
  it("reads decimal digits exactly, up to the u64 maximum", () => {
    assert.equal(parseBaseUnits("0"), 0n);
    assert.equal(parseBaseUnits("1500000"), 1_500_000n);
    // 2^53 + 1: a JSON number would round it to 2^53.
    assert.equal(parseBaseUnits("9007199254740993"), 2n ** 53n + 1n);
    assert.notEqual(String(Number("9007199254740993")), "9007199254740993");
    assert.equal(parseBaseUnits("18446744073709551615"), MAX_BASE_UNITS);
    assert.equal(MAX_BASE_UNITS, 2n ** 64n - 1n);
  });

  it("rejects anything but canonical decimal digits within u64", () => {
    for (const text of [
      "",
      "00",
      "01",
      "+1",
      "-1",
      "1.0",
      "1.",
      ".5",
      "1e3",
      "1E3",
      " 1",
      "1 ",
      "0x10",
      "1_000",
      "1,000",
      "\u{FF11}", // fullwidth digit one
      "\u{661}\u{662}", // Arabic-Indic digits
      "18446744073709551616",
      "99999999999999999999",
      "100000000000000000000",
    ]) {
      assert.equal(parseBaseUnits(text), undefined, JSON.stringify(text));
    }
  });
});

describe("base58 addresses", () => {
  it("decodes addresses to 32 bytes, with each leading 1 as a zero byte", () => {
    assert.deepEqual(decodeBase58(SYSTEM_PROGRAM), new Uint8Array(32));
    for (const address of [SYSTEM_PROGRAM, WALLET, RECIPIENT, USDC]) {
      assert.equal(decodeBase58(address)?.length, 32, address);
      assert.ok(isAddress(address), address);
    }
    assert.deepEqual(decodeBase58(""), new Uint8Array());
    assert.deepEqual(decodeBase58("1"), Uint8Array.of(0));
    assert.deepEqual(decodeBase58("2"), Uint8Array.of(1));
    assert.deepEqual(decodeBase58("5R"), Uint8Array.of(1, 0));
  });

  it("rejects characters outside the alphabet and values that aren't 32 bytes", () => {
    for (const char of ["0", "O", "I", "l", "+", "/", "é"]) {
      const text = WALLET.slice(0, -1) + char;
      assert.equal(decodeBase58(text), undefined, char);
      assert.equal(isAddress(text), false, char);
    }
    assert.equal(isAddress("1".repeat(31)), false); // 31 zero bytes
    assert.equal(isAddress("2".repeat(32)), false); // fewer than 32 bytes
    assert.equal(isAddress("z".repeat(44)), false); // 33 bytes
    assert.equal(isAddress(`1${WALLET}`), false); // 33 bytes, 45 characters
  });
});

describe("messageBytes", () => {
  it("signs text as its exact UTF-8 bytes, without normalizing or trimming it", () => {
    const text = "Sign in\r\ne\u{301} caf\u{E9}  "; // decomposed and precomposed e-acute
    const { kind } = signText(text);
    assert.equal(kind.case, "signMessage");
    const bytes = messageBytes(kind.value);
    assert.deepEqual(bytes, new Uint8Array(Buffer.from(text, "utf8")));
    assert.notDeepEqual(
      bytes,
      new Uint8Array(Buffer.from(text.normalize("NFC"), "utf8")),
    );
  });

  it("signs data as it is", () => {
    const data = Uint8Array.of(0x00, 0xff, 0x00, 0x7f, 0x80, 0x0a);
    const action = signData(data);
    assert.equal(action.kind.case, "signMessage");
    if (action.kind.case === "signMessage") {
      assert.deepEqual(messageBytes(action.kind.value), data);
    }
  });
});

describe("invalidActionReason", () => {
  it("accepts a valid action of each kind", () => {
    for (const action of [
      create(ActionSchema, {
        kind: { case: "ack", value: { text: "Deploy finished" } },
      }),
      signText("Sign in to Example"),
      signData(Uint8Array.of(0, 1, 2)),
      transfer(),
      transfer({ asset: { kind: { case: "tokenMint", value: USDC } } }),
      swap(),
      staking(),
    ]) {
      assert.equal(invalidActionReason(action), undefined);
    }
  });

  it("reports a missing action", () => {
    assert.equal(invalidActionReason(undefined), "action is missing");
    assert.equal(
      invalidActionReason(create(ActionSchema)),
      "action is missing",
    );
  });

  it("names each missing field", () => {
    const cases: ReadonlyArray<readonly [Action, string]> = [
      [transfer({ wallet: "" }), "wallet is missing"],
      [transfer({ network: Network.UNSPECIFIED }), "network is missing"],
      [transfer({ recipient: "" }), "recipient is missing"],
      [
        create(ActionSchema, {
          kind: {
            case: "transfer",
            value: { ...TRANSFER, asset: undefined },
          },
        }),
        "asset is missing",
      ],
      [transfer({ asset: {} }), "asset is missing"],
      [
        transfer({ asset: { kind: { case: "tokenMint", value: "" } } }),
        "asset.token_mint is missing",
      ],
      [transfer({ amount: "" }), "amount is missing"],
      [
        create(ActionSchema, {
          kind: { case: "swap", value: { ...SWAP, inputAsset: undefined } },
        }),
        "input_asset is missing",
      ],
      [
        create(ActionSchema, {
          kind: { case: "swap", value: { ...SWAP, outputAsset: undefined } },
        }),
        "output_asset is missing",
      ],
      [swap({ inputAmount: "" }), "input_amount is missing"],
      [swap({ slippageBps: 0 }), "slippage_bps must be 1 to 10000"],
      [signMessage({ wallet: "" }), "wallet is missing"],
      [signMessage({}), "message is missing"],
      [
        create(ActionSchema, { kind: { case: "ack", value: {} } }),
        "text is empty",
      ],
    ];
    for (const [action, reason] of cases) {
      assert.equal(invalidActionReason(action), reason);
    }
  });

  it("rejects malformed values", () => {
    const cases: ReadonlyArray<readonly [Action, string]> = [
      [
        transfer({ wallet: "not-an-address" }),
        "wallet is not a base58 Solana address",
      ],
      [
        transfer({ network: 99 as Network }),
        "network must be mainnet, devnet, or testnet",
      ],
      [
        transfer({ recipient: USDC.toLowerCase() }),
        "recipient is not a base58 Solana address",
      ],
      [
        transfer({ asset: { kind: { case: "tokenMint", value: "USDC" } } }),
        "asset.token_mint is not a base58 Solana address",
      ],
      [
        transfer({ amount: "1.5" }),
        "amount must be a whole number of base units in decimal digits, with no sign, decimal point, exponent, or leading zeros",
      ],
      [transfer({ amount: "0" }), "amount must be at least 1"],
      [
        transfer({ amount: "18446744073709551616" }),
        "amount is larger than 18446744073709551615, the u64 maximum",
      ],
      [
        swap({ outputAsset: { kind: { case: "tokenMint", value: USDC } } }),
        "output_asset must differ from input_asset",
      ],
      [swap({ slippageBps: 10_001 }), "slippage_bps must be 1 to 10000"],
    ];
    for (const [action, reason] of cases) {
      assert.equal(invalidActionReason(action), reason);
    }
  });

  it("accepts the largest amounts exactly", () => {
    assert.equal(
      invalidActionReason(transfer({ amount: "18446744073709551615" })),
      undefined,
    );
    assert.equal(
      invalidActionReason(swap({ inputAmount: "18446744073709551615" })),
      undefined,
    );
    assert.equal(invalidActionReason(swap({ slippageBps: 1 })), undefined);
    assert.equal(invalidActionReason(swap({ slippageBps: 10_000 })), undefined);
  });

  it("keeps messages exact, from 1 to 4096 bytes", () => {
    // Whitespace is signed as it is, so the phone must show it as it is.
    assert.equal(invalidActionReason(signText("  \n")), undefined);
    assert.equal(
      invalidActionReason(signText("€".repeat(1365) + "!")),
      undefined,
    ); // 4096 bytes
    assert.equal(
      invalidActionReason(signText("€".repeat(1365) + "!!")),
      "message is 4097 bytes; the limit is 4096",
    );
    assert.equal(
      invalidActionReason(signData(new Uint8Array(MAX_MESSAGE_BYTES))),
      undefined,
    );
    assert.equal(
      invalidActionReason(signData(new Uint8Array(MAX_MESSAGE_BYTES + 1))),
      "message is 4097 bytes; the limit is 4096",
    );
    assert.equal(invalidActionReason(signText("")), "message is empty");
    assert.equal(
      invalidActionReason(signData(new Uint8Array())),
      "message is empty",
    );
    assert.equal(
      invalidActionReason(signText("broken \ud83d text")),
      "message is not valid Unicode (it contains an unpaired surrogate)",
    );
  });
});

describe("a staking action", () => {
  const DIGITS =
    "amount must be a whole number of base units in decimal digits, with no sign, decimal point, exponent, or leading zeros";
  const AMOUNTS: readonly StakingOperation[] = [
    StakingOperation.STAKE,
    StakingOperation.UNSTAKE,
  ];
  const WHOLE_POSITION: readonly StakingOperation[] = [
    StakingOperation.CANCEL_UNSTAKE,
    StakingOperation.WITHDRAW,
  ];

  it("accepts each of the four operations", () => {
    for (const operation of AMOUNTS) {
      assert.equal(invalidActionReason(staking({ operation })), undefined);
    }
    for (const operation of WHOLE_POSITION) {
      assert.equal(
        invalidActionReason(staking({ operation, amount: "" })),
        undefined,
      );
    }
  });

  it("refuses an operation it doesn't know, an unspecified one included", () => {
    for (const operation of [
      StakingOperation.UNSPECIFIED,
      99 as StakingOperation,
    ]) {
      assert.equal(
        invalidActionReason(staking({ operation })),
        "operation must be stake, unstake, cancel_unstake, or withdraw",
        String(operation),
      );
    }
  });

  it("refuses a wallet or a network it can't act on, before the operation", () => {
    const cases: ReadonlyArray<readonly [Action, string]> = [
      [staking({ wallet: "" }), "wallet is missing"],
      [
        staking({ wallet: "not-an-address" }),
        "wallet is not a base58 Solana address",
      ],
      [staking({ network: Network.UNSPECIFIED }), "network is missing"],
      [
        staking({ network: 99 as Network }),
        "network must be mainnet, devnet, or testnet",
      ],
      // The binding comes first, so an unusable wallet is named even when nothing else fits.
      [
        staking({ wallet: "", operation: StakingOperation.UNSPECIFIED }),
        "wallet is missing",
      ],
    ];
    for (const [action, reason] of cases) {
      assert.equal(invalidActionReason(action), reason);
    }
  });

  it("holds a stake and an unstake to a whole number of base units", () => {
    const cases: ReadonlyArray<readonly [string, string]> = [
      ["", "amount is missing"],
      ["0", "amount must be at least 1"],
      ["01", DIGITS],
      ["-1", DIGITS],
      ["1.5", DIGITS],
      [
        "18446744073709551616",
        "amount is larger than 18446744073709551615, the u64 maximum",
      ],
    ];
    for (const operation of AMOUNTS) {
      const name = stakingOperationName(operation);
      for (const [amount, reason] of cases) {
        assert.equal(
          invalidActionReason(staking({ operation, amount })),
          reason,
          `${name} ${JSON.stringify(amount)}`,
        );
      }
      for (const amount of ["1", "18446744073709551615"]) {
        assert.equal(
          invalidActionReason(staking({ operation, amount })),
          undefined,
          `${name} ${amount}`,
        );
      }
    }
  });

  it("refuses an amount on a cancellation or a withdrawal, which act on the whole pending unstake", () => {
    for (const operation of WHOLE_POSITION) {
      const name = stakingOperationName(operation);
      for (const amount of ["1", "0", "1.5"]) {
        assert.equal(
          invalidActionReason(staking({ operation, amount })),
          `amount must be empty for ${name}, which acts on the whole pending unstake`,
          `${name} ${amount}`,
        );
      }
      assert.equal(
        invalidActionReason(staking({ operation, amount: "" })),
        undefined,
        name,
      );
    }
  });

  it("is carried out with the owner's wallet on the network it names", () => {
    assert.deepEqual(actionBinding(staking()), {
      wallet: WALLET,
      network: Network.MAINNET,
    });
    assert.deepEqual(actionBinding(staking({ network: Network.DEVNET })), {
      wallet: WALLET,
      network: Network.DEVNET,
    });
  });

  it("names every operation the way the protocol document writes it", () => {
    assert.equal(stakingOperationName(StakingOperation.STAKE), "stake");
    assert.equal(stakingOperationName(StakingOperation.UNSTAKE), "unstake");
    assert.equal(
      stakingOperationName(StakingOperation.CANCEL_UNSTAKE),
      "cancel_unstake",
    );
    assert.equal(stakingOperationName(StakingOperation.WITHDRAW), "withdraw");
    assert.equal(
      stakingOperationName(StakingOperation.UNSPECIFIED),
      "unspecified",
    );
  });
});

describe("invalidNoteReason", () => {
  it("allows an empty note and up to 1024 UTF-8 bytes of valid Unicode", () => {
    assert.equal(invalidNoteReason("note", ""), undefined);
    assert.equal(invalidNoteReason("note", "€".repeat(341) + "!"), undefined); // 1024 bytes
    assert.equal(
      invalidNoteReason("note", "€".repeat(341) + "!!"),
      "note is 1025 UTF-8 bytes; the limit is 1024",
    );
    assert.equal(
      invalidNoteReason("note", "broken \udc00"),
      "note is not valid Unicode (it contains an unpaired surrogate)",
    );
  });
});
