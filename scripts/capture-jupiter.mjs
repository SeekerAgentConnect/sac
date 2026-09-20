// Captures real Jupiter answers as test fixtures (SEE-93, SEE-94, SEE-96).
//
//   node scripts/capture-jupiter.mjs            # rewrite fixtures/jupiter/swaps.json
//   node scripts/capture-jupiter.mjs --orders   # rewrite fixtures/jupiter/orders.json
//   node scripts/capture-jupiter.mjs --events   # rewrite demo-prediction/internal/jupiter/testdata
//   node scripts/capture-jupiter.mjs --check    # decode what is committed and print it
//
// Why this exists: neither plugin will sign a transaction it cannot read, so the tests have to be
// about transactions Jupiter actually builds — not about ones we invented and can therefore read by
// construction. A prediction order additionally carries the address lookup tables it names, read
// from the same read-only RPC method the app uses, because without them its account indexes mean
// nothing. These are the real answers to real requests, and `JupiterFixturesTest`
// decodes them with the phone's own reader.
//
// It spends nothing and signs nothing. A quote is a public read; building a transaction returns
// unsigned bytes, and nothing here has a key to sign them with. `pnpm check` does not run it,
// because a check that needs the internet is not a check.
import { readFileSync, mkdirSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const file = fileURLToPath(
  new URL("../fixtures/jupiter/swaps.json", import.meta.url),
);
const endpoint = process.env.JUPITER_ENDPOINT ?? "https://lite-api.jup.ag";

// One case per shape a swap can take, because the shape is what the reader is written against: an
// input that has to be wrapped, an output that has to be unwrapped, a token account that does not
// exist yet, and the routing instruction's other variant.
const wanted = [
  {
    name: "sol_to_usdc",
    description:
      "Native SOL in: the wrap, the token program crediting it, the route, and the close that " +
      "returns what is left. The shared-accounts routing variant.",
    owner: "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM",
    inputMint: "So11111111111111111111111111111111111111112",
    inputDecimals: 9,
    inputSymbol: "SOL",
    outputMint: "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
    outputDecimals: 6,
    outputSymbol: "USDC",
    amount: "100000000",
    slippageBps: 50,
    sharedAccounts: true,
  },
  {
    name: "usdc_to_sol",
    description:
      "Native SOL out: the route, then the close that unwraps it back to the owner.",
    owner: "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM",
    inputMint: "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
    inputDecimals: 6,
    inputSymbol: "USDC",
    outputMint: "So11111111111111111111111111111111111111112",
    outputDecimals: 9,
    outputSymbol: "SOL",
    amount: "10000000",
    slippageBps: 50,
    sharedAccounts: true,
  },
  {
    name: "usdc_to_jup_new_account",
    description:
      "Token to token for a wallet that has no account for the output mint yet: the idempotent " +
      "associated-account creation is in the transaction, and it is for the owner.",
    owner: "HJdVwh6EY9XSkh3EyKofGqUF7x5tiujt6iKxcJjwEnft",
    inputMint: "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
    inputDecimals: 6,
    inputSymbol: "USDC",
    outputMint: "JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN",
    outputDecimals: 6,
    outputSymbol: "JUP",
    amount: "5000000",
    slippageBps: 50,
    sharedAccounts: true,
  },
  {
    name: "usdc_to_sol_own_accounts",
    description:
      "The other routing variant, which uses the owner's own accounts rather than the program's " +
      "shared ones and lays them out differently.",
    owner: "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM",
    inputMint: "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
    inputDecimals: 6,
    inputSymbol: "USDC",
    outputMint: "So11111111111111111111111111111111111111112",
    outputDecimals: 9,
    outputSymbol: "SOL",
    amount: "10000000",
    slippageBps: 50,
    sharedAccounts: false,
  },
];

const SYSTEM = "11111111111111111111111111111111";
const TOKEN = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA";
const ASSOCIATED = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL";
const BUDGET = "ComputeBudget111111111111111111111111111111";
const JUPITER = "JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4";
const SHARED_ROUTE = "c1209b3341d69c81";
const OWN_ROUTE = "e517cb977ae3ad2a";

const ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

function base58(bytes) {
  let value = 0n;
  for (const byte of bytes) value = value * 256n + BigInt(byte);
  let text = "";
  while (value > 0n) {
    text = ALPHABET[Number(value % 58n)] + text;
    value /= 58n;
  }
  for (const byte of bytes) {
    if (byte !== 0) break;
    text = "1" + text;
  }
  return text || "1";
}

// A second reader, in another language, so the fixtures pin what a swap looks like rather than
// what one implementation thinks it looks like. It reads only far enough to name each instruction.
function shapeOf(base64) {
  const raw = Buffer.from(base64, "base64");
  let at = 0;
  const u8 = () => raw[at++];
  const short = () => {
    let value = 0;
    for (let shift = 0; shift < 3; shift += 1) {
      const byte = u8();
      value |= (byte & 0x7f) << (shift * 7);
      if ((byte & 0x80) === 0) return value;
    }
    throw new Error("a length that is not one");
  };
  const signatures = short();
  let unsigned = true;
  for (let index = 0; index < signatures; index += 1) {
    const signature = raw.subarray(at, at + 64);
    at += 64;
    if (signature.some((byte) => byte !== 0)) unsigned = false;
  }
  const first = u8();
  const version = first & 0x80 ? first & 0x7f : null;
  const required = version === null ? first : u8();
  u8();
  u8();
  const accounts = [];
  const count = short();
  for (let index = 0; index < count; index += 1) {
    accounts.push(base58(raw.subarray(at, at + 32)));
    at += 32;
  }
  at += 32;
  const instructions = [];
  const total = short();
  for (let index = 0; index < total; index += 1) {
    const program = accounts[u8()];
    const named = short();
    const indexes = [];
    for (let i = 0; i < named; i += 1) indexes.push(u8());
    const length = short();
    const data = raw.subarray(at, at + length);
    at += length;
    instructions.push({ program, indexes, data });
  }
  const lookups = version === 0 ? short() : 0;
  if (at !== raw.length) throw new Error("bytes nobody read");
  return {
    version,
    unsigned,
    signers: accounts.slice(0, required),
    lookups,
    instructions: instructions.map((one) => name(one)),
  };
}

function name({ program, data }) {
  if (program === BUDGET) return "budget";
  if (program === SYSTEM) return "wrap";
  if (program === ASSOCIATED) return "account";
  if (program === TOKEN && data.length === 1 && data[0] === 17) return "sync";
  if (program === TOKEN && data.length === 1 && data[0] === 9) return "unwrap";
  if (
    program === JUPITER &&
    data.subarray(0, 8).toString("hex") === SHARED_ROUTE
  ) {
    return "shared_route";
  }
  if (program === JUPITER && data.subarray(0, 8).toString("hex") === OWN_ROUTE)
    return "route";
  return `unread:${program}`;
}

async function capture(one) {
  const query = new URLSearchParams({
    inputMint: one.inputMint,
    outputMint: one.outputMint,
    amount: one.amount,
    slippageBps: String(one.slippageBps),
    swapMode: "ExactIn",
    onlyDirectRoutes: "true",
    asLegacyTransaction: "true",
  });
  const quoted = await fetch(`${endpoint}/swap/v1/quote?${query}`);
  if (!quoted.ok) throw new Error(`${one.name}: quote HTTP ${quoted.status}`);
  const quote = await quoted.json();
  const built = await fetch(`${endpoint}/swap/v1/swap`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      quoteResponse: quote,
      userPublicKey: one.owner,
      asLegacyTransaction: true,
      wrapAndUnwrapSol: true,
      useSharedAccounts: one.sharedAccounts,
      dynamicComputeUnitLimit: true,
    }),
  });
  if (!built.ok) throw new Error(`${one.name}: swap HTTP ${built.status}`);
  const swap = await built.json();
  if (swap.simulationError) {
    // Expected for the fresh-wallet case: it holds nothing, so Jupiter's own simulation fails. The
    // transaction it built is still exactly the shape the reader has to read, which is what this
    // captures — and the plugin's refusal to prepare one of these is tested separately.
    console.warn(
      `${one.name}: the provider's simulation failed, as this case expects`,
    );
  }
  if (swap.addressesByLookupTableAddress) {
    throw new Error(
      `${one.name}: a legacy transaction came back needing a lookup table`,
    );
  }
  return {
    name: one.name,
    description: one.description,
    owner: one.owner,
    inputMint: one.inputMint,
    inputDecimals: one.inputDecimals,
    inputSymbol: one.inputSymbol,
    outputMint: one.outputMint,
    outputDecimals: one.outputDecimals,
    outputSymbol: one.outputSymbol,
    amount: one.amount,
    slippageBps: one.slippageBps,
    quote: {
      inAmount: quote.inAmount,
      outAmount: quote.outAmount,
      otherAmountThreshold: quote.otherAmountThreshold,
      swapMode: quote.swapMode,
      legs: quote.routePlan.length,
    },
    transaction: swap.swapTransaction,
    shape: shapeOf(swap.swapTransaction),
  };
}

if (process.argv.includes("--check")) {
  const held = JSON.parse(readFileSync(file, "utf8"));
  for (const one of held.cases) {
    const shape = shapeOf(one.transaction);
    const same = JSON.stringify(shape) === JSON.stringify(one.shape);
    console.log(
      `${one.name}: ${same ? "as recorded" : "CHANGED"} ${JSON.stringify(shape)}`,
    );
  }
  process.exit(0);
}

// --- Prediction discovery (SEE-96) ------------------------------------------
//
// The publisher template that discovers prediction markets reads two endpoints, and its tests serve
// these answers back to it from a local server. They are captured verbatim — the whole body, as it
// arrived, with the request that produced it recorded beside it — because the point of them is that
// the template's decoder is written against what the provider actually sends rather than against a
// tidied-up version of it. The provider's prediction API is in beta by its own documentation, so a
// re-capture that changes a shape is news rather than noise.
//
// Seven cases, which are the seven things a poll can run into: a first page, a second page, an empty
// page, a parameter the provider refuses, a market that is open, one that has closed and settled,
// and one that does not exist. A rate limit is not here, because provoking one deliberately is
// rude; the test constructs that body from the provider's published error schema and says so.
const TESTDATA = fileURLToPath(
  new URL("../demo-prediction/internal/jupiter/testdata", import.meta.url),
);

/** The query string the template itself would send: the same parameters, in the same order. */
function query(parameters) {
  const values = new URLSearchParams();
  for (const name of Object.keys(parameters).sort()) {
    if (parameters[name] === undefined) continue;
    values.set(name, String(parameters[name]));
  }
  return values.toString();
}

async function captureEvents() {
  // One event per page, from the smallest bucket the provider has, so a committed fixture is
  // readable. `economics` events carry five or six markets each, which is what makes them useful:
  // one event is several candidates.
  const listing = {
    provider: "polymarket",
    category: "economics",
    includeMarkets: true,
  };
  const wantedCases = [
    {
      name: "events-first",
      description: "The first page of a filtered listing, with more to come.",
      path: `/prediction/v1/events?${query({ ...listing, start: 0, end: 1 })}`,
    },
    {
      name: "events-second",
      description: "The second page of the same walk.",
      path: `/prediction/v1/events?${query({ ...listing, start: 1, end: 2 })}`,
    },
    {
      name: "events-empty",
      description:
        "A page past the end: no events, and `hasNext` false, which is what stops a walk.",
      path: `/prediction/v1/events?${query({ ...listing, category: undefined, start: 100000, end: 100002 })}`,
    },
    {
      name: "events-bad-parameter",
      description:
        "A category the provider does not have. Its answer names the parameter, and lists the " +
        "categories it does have — which is where this template's own list came from.",
      path: `/prediction/v1/events?${query({ ...listing, category: "nonsense", start: 0, end: 1 })}`,
    },
    {
      name: "market-closed",
      description:
        "A market that has closed and settled. It is one of September's Bitcoin price markets, " +
        "so it stays settled: what it is a fixture for is the shape of a closure.",
      path: "/prediction/v1/markets/POLY-4052423",
    },
    {
      name: "market-missing",
      description:
        "A market that does not exist. `code` is what tells this apart from any other 404.",
      path: "/prediction/v1/markets/SEEKER-NO-SUCH-MARKET",
    },
  ];

  const captured = [];
  for (const one of wantedCases) {
    captured.push(await answer(one));
    // The keyless allowance is half a request a second (docs/integrations/jupiter.md).
    await new Promise((resume) => setTimeout(resume, 2500));
  }
  // The open market is whichever the first page's event begins with, so this stays a real pair: the
  // listing and the direct read of something in it.
  const first = JSON.parse(captured[0].body);
  const open = (first.data?.[0]?.markets ?? []).find(
    (market) => market.status === "open",
  );
  if (!open) {
    throw new Error("the first page carries no open market to read directly");
  }
  captured.push(
    await answer({
      name: "market-open",
      description:
        "A market read directly, as a reconciliation reads one: the same market the first page " +
        "lists, asked about on its own.",
      path: `/prediction/v1/markets/${encodeURIComponent(open.marketId)}`,
    }),
  );
  return captured;
}

async function answer(one) {
  const response = await fetch(`${endpoint}${one.path}`, {
    headers: { accept: "application/json" },
  });
  const body = await response.text();
  console.log(`${one.name}: HTTP ${response.status}, ${body.length} bytes`);
  return { ...one, status: response.status, body };
}

if (process.argv.includes("--events")) {
  const captured = await captureEvents();
  mkdirSync(TESTDATA, { recursive: true });
  const cases = [];
  for (const one of captured) {
    const file = `${one.name}.json`;
    // Pretty-printed, because a fixture nobody can read is a fixture nobody checks. It is the same
    // document either way: the test parses it and serves it back.
    writeFileSync(
      `${TESTDATA}/${file}`,
      `${JSON.stringify(JSON.parse(one.body), null, 2)}\n`,
    );
    cases.push({
      name: one.name,
      description: one.description,
      path: one.path,
      status: one.status,
      body: file,
    });
  }
  writeFileSync(
    `${TESTDATA}/captured.json`,
    `${JSON.stringify(
      {
        note:
          "Real answers from Jupiter's keyless prediction API, captured by " +
          "scripts/capture-jupiter.mjs --events and served back to the publisher template's own " +
          "client by internal/jupiter's tests. `path` is the request as the template sends it, " +
          "parameters and all, so a change to what it asks for fails a test instead of quietly " +
          "testing something else.",
        endpoint,
        capturedAt: new Date().toISOString(),
        cases,
      },
      null,
      2,
    )}\n`,
  );
  console.log(`wrote ${cases.length} answers to ${TESTDATA}`);
  process.exit(0);
}

// --- Prediction orders (SEE-94) ---------------------------------------------
//
// A prediction order only ever comes back as a versioned transaction that loads its accounts from
// address lookup tables, so a fixture has to carry the tables too: without them the account indexes
// are numbers with no meaning, and the phone's reader would have nothing to resolve. The tables are
// read from the same read-only RPC method the app uses, so what is committed here is what a device
// would have seen.
const ORDERS = fileURLToPath(
  new URL("../fixtures/jupiter/orders.json", import.meta.url),
);
const rpc = process.env.SOLANA_RPC_URL ?? "https://api.mainnet-beta.solana.com";
const LOOKUP_PROGRAM = "AddressLookupTab1e1111111111111111111111111";

/** The tables a versioned message names, and the indexes it takes from each. */
function tablesOf(base64) {
  const raw = Buffer.from(base64, "base64");
  let at = 0;
  const u8 = () => raw[at++];
  const short = () => {
    let value = 0;
    for (let shift = 0; shift < 3; shift += 1) {
      const byte = u8();
      value |= (byte & 0x7f) << (shift * 7);
      if ((byte & 0x80) === 0) return value;
    }
    throw new Error("a length that is not one");
  };
  const signatures = short();
  at += 64 * signatures;
  const first = u8();
  if ((first & 0x80) === 0) return { version: null, tables: [] };
  u8();
  u8();
  u8();
  const accounts = short();
  at += 32 * accounts;
  at += 32;
  const instructions = short();
  for (let index = 0; index < instructions; index += 1) {
    u8();
    const named = short();
    at += named;
    const length = short();
    at += length;
  }
  const count = short();
  const tables = [];
  for (let index = 0; index < count; index += 1) {
    const table = base58(raw.subarray(at, at + 32));
    at += 32;
    const writable = [];
    for (let w = short(); w > 0; w -= 1) writable.push(u8());
    const readonly = [];
    for (let r = short(); r > 0; r -= 1) readonly.push(u8());
    tables.push({ table, writable, readonly });
  }
  if (at !== raw.length) throw new Error("bytes nobody read");
  return { version: first & 0x7f, tables };
}

async function lookupTables(addresses) {
  const answer = await fetch(rpc, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      jsonrpc: "2.0",
      id: 1,
      method: "getMultipleAccounts",
      params: [addresses, { encoding: "base64", commitment: "confirmed" }],
    }),
  });
  if (!answer.ok) throw new Error(`rpc HTTP ${answer.status}`);
  const body = await answer.json();
  if (body.error) throw new Error(`rpc error ${body.error.code}`);
  return addresses.map((address, index) => {
    const account = body.result.value[index];
    if (account === null) throw new Error(`${address}: no such table`);
    if (account.owner !== LOOKUP_PROGRAM)
      throw new Error(`${address}: owned by ${account.owner}`);
    return { address, owner: account.owner, data: account.data[0] };
  });
}

async function captureOrder() {
  // One live market, chosen rather than hardcoded: a fixture pinned to a market that has since
  // settled would still exercise the reader, but a capture that cannot be repeated is worse.
  const listed = await fetch(
    `${endpoint}/prediction/v1/events?filter=trending&end=20`,
  );
  if (!listed.ok) throw new Error(`events HTTP ${listed.status}`);
  const events = (await listed.json()).data ?? [];
  const found = events
    .flatMap((event) =>
      (event.markets ?? []).map((market) => ({ event, market })),
    )
    .find(
      (one) =>
        one.market.status === "open" && one.market.pricing?.buyYesPriceUsd > 0,
    );
  if (!found) throw new Error("no open market was listed");
  const { event, market } = found;
  const owner = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM";
  const depositMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v";
  const depositAmount = "5000000";
  const built = await fetch(`${endpoint}/prediction/v1/orders`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      ownerPubkey: owner,
      marketId: market.marketId,
      isYes: true,
      isBuy: true,
      depositAmount,
      depositMint,
    }),
  });
  if (!built.ok)
    throw new Error(`orders HTTP ${built.status}: ${await built.text()}`);
  const order = await built.json();
  const { version, tables } = tablesOf(order.transaction);
  if (version !== 0 || tables.length === 0) {
    // If this ever stops being true, the phone's review gets simpler rather than harder — but the
    // fixture would no longer cover what it was written for, so say so loudly.
    console.warn(
      `the order came back as version ${version} with ${tables.length} tables`,
    );
  }
  return {
    name: "buy_yes_with_usdc",
    description:
      "A YES order funded with USDC: two compute-budget settings, the owner's own JupUSD account " +
      "created idempotently, a Jupiter route that swaps the deposit, and the prediction program's " +
      "own order instruction. Versioned, with its accounts behind lookup tables.",
    owner,
    eventId: event.eventId,
    eventTitle: event.metadata?.title ?? "",
    marketId: market.marketId,
    marketTitle: market.title ?? "",
    marketStatus: market.status,
    outcomes: market.outcomes ?? [],
    pricing: market.pricing ?? {},
    isYes: true,
    depositMint,
    depositAmount,
    order: order.order,
    requiredSigners: order.requiredSigners ?? [],
    transaction: order.transaction,
    txMeta: order.txMeta ?? {},
    tables: await lookupTables(tables.map((one) => one.table)),
    lookups: tables,
  };
}

if (process.argv.includes("--orders")) {
  const captured = await captureOrder();
  mkdirSync(fileURLToPath(new URL("../fixtures/jupiter", import.meta.url)), {
    recursive: true,
  });
  writeFileSync(
    ORDERS,
    `${JSON.stringify(
      {
        note:
          "A real prediction order from Jupiter's keyless API, with the address lookup tables it " +
          "names read from a read-only RPC, captured by scripts/capture-jupiter.mjs --orders and " +
          "read by the phone's PredictionFixturesTest. Nothing here is signed. The market may have " +
          "settled since: what the fixture is for is the shape of the transaction and the contents " +
          "of the tables, neither of which depends on when it was captured.",
        endpoint,
        rpc,
        capturedAt: new Date().toISOString(),
        cases: [captured],
      },
      null,
      2,
    )}\n`,
  );
  console.log(`wrote 1 order to ${ORDERS}`);
  process.exit(0);
}

const cases = [];
for (const one of wanted) {
  // One at a time, deliberately: the keyless allowance is half a request a second, and a capture
  // that trips a rate limit is a capture that records an error page.
  cases.push(await capture(one));
  await new Promise((resume) => setTimeout(resume, 2500));
}

mkdirSync(fileURLToPath(new URL("../fixtures/jupiter", import.meta.url)), {
  recursive: true,
});
writeFileSync(
  file,
  `${JSON.stringify(
    {
      note:
        "Real answers from Jupiter's keyless swap API, captured by scripts/capture-jupiter.mjs " +
        "and read by the phone's JupiterFixturesTest. Nothing here is signed, and the " +
        "blockhashes are long expired, which is why these stay valid as fixtures: the review " +
        "reads what a transaction does, and that does not depend on when it was built.",
      endpoint,
      capturedAt: new Date().toISOString(),
      cases,
    },
    null,
    2,
  )}\n`,
);
console.log(`wrote ${cases.length} cases to ${file}`);
