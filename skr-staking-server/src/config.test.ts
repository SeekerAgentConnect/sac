/**
 * What the configuration refuses, and — the point of most of this file — that it refuses it out
 * loud. A problem collected after the decision to throw has already been taken is a problem
 * nobody is ever told about, which is worse than not checking at all: the check reads as done.
 */
import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { ConfigError, loadConfig, type Env } from "./config.ts";

/** Everything required, all of it valid. Each test spoils exactly one thing. */
const VALID: Env = {
  SKR_STAKING_HOST: "127.0.0.1",
  SKR_STAKING_PORT: "8790",
  SKR_STAKING_MCP_TOKEN: "a".repeat(64),
  SKR_STAKING_RPC_URL: "https://api.mainnet-beta.solana.com",
  SKR_STAKING_DATA_DIR: "/tmp/skr-staking-server-test",
};

function problemsOf(env: Env): readonly string[] {
  try {
    loadConfig(env);
  } catch (error) {
    assert.ok(
      error instanceof ConfigError,
      `expected a ConfigError, got ${String(error)}`,
    );
    return error.problems;
  }
  return [];
}

describe("the configuration as a whole", () => {
  it("reads a valid environment", () => {
    const config = loadConfig(VALID);
    assert.equal(config.host, "127.0.0.1");
    assert.equal(config.port, 8790);
    assert.deepEqual(config.allowedHosts, []);
  });

  it("names every problem at once rather than one per run", () => {
    const problems = problemsOf({
      ...VALID,
      SKR_STAKING_PORT: "nope",
      SKR_STAKING_H2C: "perhaps",
    });
    assert.equal(problems.length, 2);
  });
});

describe("SKR_STAKING_ALLOWED_HOSTS", () => {
  it("takes a comma-separated list of host names", () => {
    const config = loadConfig({
      ...VALID,
      SKR_STAKING_ALLOWED_HOSTS: "Staking.Internal, 10.0.0.4 ,[::1]",
    });
    assert.deepEqual(config.allowedHosts, [
      "staking.internal",
      "10.0.0.4",
      "[::1]",
    ]);
  });

  // The regression: the parse used to happen inside the returned object, after the only
  // `problems.length` check. A malformed value recorded a problem into a list nobody read again,
  // the host was silently dropped, and the server came up refusing the very agent it was
  // configured to let in — with nothing at startup to say why.
  it("refuses a URL where a host name belongs", () => {
    const problems = problemsOf({
      ...VALID,
      SKR_STAKING_ALLOWED_HOSTS: "https://staking.internal",
    });
    assert.equal(problems.length, 1);
    assert.match(problems[0] ?? "", /SKR_STAKING_ALLOWED_HOSTS/);
  });

  it("refuses a wildcard", () => {
    const problems = problemsOf({
      ...VALID,
      SKR_STAKING_ALLOWED_HOSTS: "*.internal",
    });
    assert.equal(problems.length, 1);
    assert.match(problems[0] ?? "", /SKR_STAKING_ALLOWED_HOSTS/);
  });

  it("reports a bad host alongside the other problems, not instead of them", () => {
    const problems = problemsOf({
      ...VALID,
      SKR_STAKING_PORT: "0",
      SKR_STAKING_ALLOWED_HOSTS: "https://staking.internal",
    });
    assert.equal(problems.length, 2);
  });
});

describe("SKR_STAKING_UPDATE_PORT", () => {
  it("reads a loopback update port", () => {
    const config = loadConfig({ ...VALID, SKR_STAKING_UPDATE_PORT: "8791" });
    assert.equal(config.updatePort, 8791);
  });

  it("is absent unless set, and then this server serves no live updates", () => {
    assert.equal(loadConfig(VALID).updatePort, undefined);
  });

  // Both answer the same question — where the phone opens its update stream — and they answer it
  // differently. Choosing one quietly would advertise an origin the operator did not mean.
  it("refuses to be combined with SKR_STAKING_H2C", () => {
    const problems = problemsOf({
      ...VALID,
      SKR_STAKING_H2C: "true",
      SKR_STAKING_UPDATE_PORT: "8791",
    });
    assert.equal(problems.length, 1);
    assert.match(problems[0] ?? "", /SKR_STAKING_UPDATE_PORT/);
  });

  it("refuses a wildcard bind, which is not an origin a phone could reach", () => {
    const problems = problemsOf({
      ...VALID,
      SKR_STAKING_HOST: "0.0.0.0",
      SKR_STAKING_PUBLIC_URL: "http://127.0.0.1:8790",
      SKR_STAKING_UPDATE_PORT: "8791",
    });
    assert.equal(problems.length, 1);
    assert.match(problems[0] ?? "", /SKR_STAKING_UPDATE_PORT/);
  });
});

describe("the gateway push relay", () => {
  const RELAY: Env = {
    SKR_STAKING_RELAY_URL: "https://feeds.example.com",
    SKR_STAKING_RELAY_SERVER_ID: "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
    SKR_STAKING_RELAY_CREDENTIAL: "c".repeat(43),
  };

  it("is off, and not a problem, when none of the three is set", () => {
    assert.equal(loadConfig(VALID).relay, undefined);
  });

  it("reads all three together", () => {
    const config = loadConfig({ ...VALID, ...RELAY });
    assert.deepEqual(config.relay, {
      relayUrl: "https://feeds.example.com",
      serverId: "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
      credential: "c".repeat(43),
    });
  });

  // A partial relay is a server that looks like it wakes phones and never does, and the only sign
  // of it would be an owner whose staking requests stop arriving until they open the app.
  it("refuses two of the three", () => {
    const problems = problemsOf({
      ...VALID,
      SKR_STAKING_RELAY_URL: RELAY.SKR_STAKING_RELAY_URL,
      SKR_STAKING_RELAY_SERVER_ID: RELAY.SKR_STAKING_RELAY_SERVER_ID,
    });
    assert.equal(problems.length, 1);
    assert.match(problems[0] ?? "", /SKR_STAKING_RELAY_CREDENTIAL/);
  });

  it("refuses a relay URL that is not an origin", () => {
    const problems = problemsOf({
      ...VALID,
      ...RELAY,
      SKR_STAKING_RELAY_URL: "not-a-url",
    });
    assert.equal(problems.length, 1);
  });

  // The credential is a secret, and a configuration problem is printed at startup and pasted into
  // places an operator does not control.
  it("never repeats the credential in a problem", () => {
    const problems = problemsOf({
      ...VALID,
      ...RELAY,
      SKR_STAKING_RELAY_SERVER_ID: "not-a-uuid",
    });
    assert.ok(problems.length >= 1);
    for (const problem of problems) {
      assert.doesNotMatch(problem, /c{10}/);
    }
  });
});
