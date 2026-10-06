import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { SolanaNetwork } from "@seekeragentconnect/server-sdk/protocol";

import {
  ConfigError,
  DEFAULT_DATABASE_PATH,
  loadSidecarConfig,
} from "./config.ts";

const MCP_TOKEN = "m".repeat(64);
const PHONE_TOKEN = "p".repeat(64);

const validEnv = {
  SIDECAR_HOST: "127.0.0.1",
  SIDECAR_PORT: "8080",
  MCP_TOKEN,
  PHONE_TOKEN,
  LIVE_COMMAND_TIMEOUT_SECONDS: "60",
};

function problemsFor(
  env: Record<string, string | undefined>,
): readonly string[] {
  try {
    loadSidecarConfig(env);
  } catch (error) {
    assert.ok(error instanceof ConfigError);
    return error.problems;
  }
  assert.fail("expected a ConfigError");
}

describe("loadSidecarConfig", () => {
  it("parses a valid configuration, with the storage defaults", () => {
    assert.deepEqual(loadSidecarConfig(validEnv), {
      host: "127.0.0.1",
      port: 8080,
      mcpToken: MCP_TOKEN,
      phoneToken: PHONE_TOKEN,
      liveCommandTimeoutSeconds: 60,
      mcpAllowedHosts: [],
      demoTools: false,
      databasePath: DEFAULT_DATABASE_PATH,
      requestTtlSeconds: 86_400,
      pendingLimit: 100,
      solanaRpcUrl: undefined,
      solanaRpcTimeoutMs: 10_000,
      publicUrl: "http://127.0.0.1:8080",
      pairingTokenTtlSeconds: 600,
    });
    assert.match(
      DEFAULT_DATABASE_PATH,
      /[/\\]\.seeker-agent-connect[/\\]mcp-server[/\\]direct-server\.db$/,
    );
  });

  /**
   * MCP is one adapter over the request core, and MCP_ENABLED is its switch (SEE-87,
   * docs/wiki/mcp-adapter.md). The default is on, so a deployment that has never heard of the
   * setting keeps every behaviour it had; off, nothing MCP-only is required and the endpoint is
   * never built.
   */
  describe("the optional MCP adapter", () => {
    const withoutMcp = {
      SIDECAR_HOST: "127.0.0.1",
      SIDECAR_PORT: "8080",
      PHONE_TOKEN,
      LIVE_COMMAND_TIMEOUT_SECONDS: "60",
      MCP_ENABLED: "false",
    };

    it("keeps the adapter on when nothing says otherwise", () => {
      assert.equal(loadSidecarConfig(validEnv).mcpToken, MCP_TOKEN);
      assert.equal(
        loadSidecarConfig({ ...validEnv, MCP_ENABLED: "true" }).mcpToken,
        MCP_TOKEN,
      );
      assert.equal(loadSidecarConfig(validEnv).ignoredSettings, undefined);
    });

    it("needs no MCP token, and no other MCP setting, when it is off", () => {
      const config = loadSidecarConfig(withoutMcp);

      // Absent rather than empty: one place says whether the adapter exists.
      assert.equal(config.mcpToken, undefined);
      assert.equal(config.demoTools, false);
      assert.deepEqual(config.mcpAllowedHosts, []);
      assert.equal(config.oauth, undefined);
      assert.equal(config.ignoredSettings, undefined);
      // Everything that is not the adapter is configured exactly as before.
      assert.equal(config.phoneToken, PHONE_TOKEN);
      assert.equal(config.databasePath, DEFAULT_DATABASE_PATH);
      assert.equal(config.requestTtlSeconds, 86_400);
      assert.equal(config.pendingLimit, 100);
      assert.equal(config.publicUrl, "http://127.0.0.1:8080");
      assert.equal(config.pairingTokenTtlSeconds, 600);
    });

    it("names the MCP settings it will not act on rather than dropping them in silence", () => {
      // Turning the adapter off must not force an operator to delete a token they may want back,
      // so a leftover is ignored — but startup says which ones, so nothing quietly does nothing.
      const config = loadSidecarConfig({
        ...withoutMcp,
        MCP_TOKEN,
        MCP_ALLOWED_HOSTS: "vault.example.com",
        MCP_DEMO_TOOLS: "true",
      });

      assert.equal(config.mcpToken, undefined);
      assert.deepEqual(config.ignoredSettings, [
        "MCP_TOKEN",
        "MCP_ALLOWED_HOSTS",
        "MCP_DEMO_TOOLS",
      ]);
      // A setting left at its own default is not something anybody is waiting on.
      assert.equal(
        loadSidecarConfig({ ...withoutMcp, MCP_DEMO_TOOLS: "false" })
          .ignoredSettings,
        undefined,
      );
    });

    it("refuses an OAuth profile for an endpoint it would not serve", () => {
      // The other MCP settings can outlive the adapter; this one contradicts it. The profile makes
      // a public promise — metadata telling a client where to authorize — for an endpoint that
      // would not exist.
      assert.deepEqual(
        problemsFor({
          ...withoutMcp,
          MCP_OAUTH_ISSUER: "https://auth.example.com/realms/seeker",
        }),
        [
          "MCP_OAUTH_ISSUER configures authorization for /mcp, which MCP_ENABLED=false does not serve: either enable MCP or remove the OAuth profile.",
        ],
      );
      assert.deepEqual(
        problemsFor({
          ...withoutMcp,
          MCP_OAUTH_RESOURCE: "https://vault.example.com/mcp",
          MCP_OAUTH_SCOPE: "vault.use",
        }),
        [
          "MCP_OAUTH_RESOURCE, MCP_OAUTH_SCOPE configures authorization for /mcp, which MCP_ENABLED=false does not serve: either enable MCP or remove the OAuth profile.",
        ],
      );
    });

    it("says so when the switch itself is not true or false", () => {
      assert.deepEqual(problemsFor({ ...validEnv, MCP_ENABLED: "off" }), [
        "MCP_ENABLED must be true or false.",
      ]);
      // A word nobody meant leaves the adapter on, so the token is still required and a broken
      // switch never silently removes the endpoint.
      assert.deepEqual(problemsFor({ ...withoutMcp, MCP_ENABLED: "no" }), [
        "MCP_ENABLED must be true or false.",
        "MCP_TOKEN is not set. Set it, or set MCP_ENABLED=false to run without the MCP adapter.",
      ]);
    });

    it("still refuses a token that is the phone's, whichever way the switch is set", () => {
      assert.deepEqual(problemsFor({ ...validEnv, MCP_TOKEN: PHONE_TOKEN }), [
        "MCP_TOKEN and PHONE_TOKEN must be different values.",
      ]);
    });
  });

  it("accepts the other loopback hosts", () => {
    for (const host of ["::1", "localhost"]) {
      assert.equal(
        loadSidecarConfig({ ...validEnv, SIDECAR_HOST: host }).host,
        host,
      );
    }
  });

  it("reports every missing variable at once", () => {
    assert.deepEqual(problemsFor({ SIDECAR_HOST: " " }), [
      "SIDECAR_HOST is not set.",
      "SIDECAR_PORT is not set.",
      // The MCP adapter is optional (SEE-87), so its own problem names the other way out.
      "MCP_TOKEN is not set. Set it, or set MCP_ENABLED=false to run without the MCP adapter.",
      "PHONE_TOKEN is not set.",
      "LIVE_COMMAND_TIMEOUT_SECONDS is not set.",
    ]);
  });

  it("allows a documented container wildcard only with an explicit public URL", () => {
    for (const host of ["0.0.0.0", "::"]) {
      assert.equal(
        loadSidecarConfig({
          ...validEnv,
          SIDECAR_HOST: host,
          SIDECAR_PUBLIC_URL: "http://127.0.0.1:8080",
        }).host,
        host,
      );
      assert.deepEqual(problemsFor({ ...validEnv, SIDECAR_HOST: host }), [
        "SIDECAR_PUBLIC_URL must be set when SIDECAR_HOST is a container wildcard.",
      ]);
    }
  });

  it("rejects listener names that are neither loopback nor container wildcards", () => {
    for (const host of ["192.168.1.20", "example.com"]) {
      assert.deepEqual(problemsFor({ ...validEnv, SIDECAR_HOST: host }), [
        "SIDECAR_HOST must be loopback (127.0.0.1, ::1, localhost) or a container wildcard (0.0.0.0, ::).",
      ]);
    }
  });

  it("rejects ports and timeouts outside their ranges", () => {
    for (const port of ["0", "65536", "80a", "-1", "8080.5"]) {
      assert.deepEqual(problemsFor({ ...validEnv, SIDECAR_PORT: port }), [
        "SIDECAR_PORT must be a whole number from 1 to 65535.",
      ]);
    }
    for (const timeout of ["0", "3601", "1.5"]) {
      assert.deepEqual(
        problemsFor({ ...validEnv, LIVE_COMMAND_TIMEOUT_SECONDS: timeout }),
        ["LIVE_COMMAND_TIMEOUT_SECONDS must be a whole number from 1 to 3600."],
      );
    }
  });

  it("rejects the .env.example token placeholders", () => {
    const problems = problemsFor({
      ...validEnv,
      MCP_TOKEN: "REPLACE_WITH_A_RANDOM_DEVELOPMENT_TOKEN",
      PHONE_TOKEN: "REPLACE_WITH_A_DIFFERENT_RANDOM_DEVELOPMENT_TOKEN",
    });
    assert.equal(problems.length, 2);
    assert.match(
      problems[0] ?? "",
      /^MCP_TOKEN still has the \.env\.example placeholder/,
    );
    assert.match(
      problems[1] ?? "",
      /^PHONE_TOKEN still has the \.env\.example placeholder/,
    );
  });

  it("requires tokens that are long enough and different from each other", () => {
    assert.deepEqual(problemsFor({ ...validEnv, PHONE_TOKEN: "short" }), [
      "PHONE_TOKEN must be at least 32 characters long.",
    ]);
    assert.deepEqual(problemsFor({ ...validEnv, PHONE_TOKEN: MCP_TOKEN }), [
      "MCP_TOKEN and PHONE_TOKEN must be different values.",
    ]);
  });

  it("rejects tokens that can't travel as bearer credentials", () => {
    const spaced = `${"m".repeat(20)} ${"m".repeat(20)}`;
    assert.match(
      problemsFor({ ...validEnv, MCP_TOKEN: spaced }).join("\n"),
      /MCP_TOKEN may contain only/,
    );
    assert.match(
      problemsFor({ ...validEnv, PHONE_TOKEN: `${"p".repeat(40)}"` }).join(
        "\n",
      ),
      /PHONE_TOKEN may contain only/,
    );
  });

  it("reads the extra /mcp host names in MCP_ALLOWED_HOSTS", () => {
    assert.deepEqual(
      loadSidecarConfig({
        ...validEnv,
        MCP_ALLOWED_HOSTS: " 100.64.0.1, Mac.tailnet.ts.net ,[fd7a:115c::1],",
      }).mcpAllowedHosts,
      ["100.64.0.1", "mac.tailnet.ts.net", "[fd7a:115c::1]"],
    );
    assert.deepEqual(
      loadSidecarConfig({ ...validEnv, MCP_ALLOWED_HOSTS: "" }).mcpAllowedHosts,
      [],
    );
  });

  it("rejects MCP_ALLOWED_HOSTS entries with a scheme, port, or wildcard", () => {
    for (const value of ["http://100.64.0.1", "100.64.0.1:8081", "*.ts.net"]) {
      assert.match(
        problemsFor({ ...validEnv, MCP_ALLOWED_HOSTS: value }).join("\n"),
        /MCP_ALLOWED_HOSTS must list host names/,
        value,
      );
    }
  });

  it("serves the demo tool only when MCP_DEMO_TOOLS is true", () => {
    for (const [value, on] of [
      ["true", true],
      [" TRUE ", true],
      ["false", false],
      ["", false],
      [undefined, false],
    ] as const) {
      assert.equal(
        loadSidecarConfig({ ...validEnv, MCP_DEMO_TOOLS: value }).demoTools,
        on,
        String(value),
      );
    }
    for (const value of ["1", "yes", "on"]) {
      assert.deepEqual(problemsFor({ ...validEnv, MCP_DEMO_TOOLS: value }), [
        "MCP_DEMO_TOOLS must be true or false.",
      ]);
    }
  });

  it("reads the optional storage settings, and treats empty ones as unset", () => {
    const config = loadSidecarConfig({
      ...validEnv,
      DATABASE_PATH: " /var/lib/seeker-vault/sidecar.db ",
      REQUEST_TTL_SECONDS: "3600",
      REQUEST_PENDING_LIMIT: "5",
    });
    assert.equal(config.databasePath, "/var/lib/seeker-vault/sidecar.db");
    assert.equal(config.requestTtlSeconds, 3600);
    assert.equal(config.pendingLimit, 5);
    const empty = loadSidecarConfig({
      ...validEnv,
      DATABASE_PATH: "",
      REQUEST_TTL_SECONDS: " ",
      REQUEST_PENDING_LIMIT: "",
    });
    assert.equal(empty.databasePath, DEFAULT_DATABASE_PATH);
    assert.equal(empty.requestTtlSeconds, 86_400);
    assert.equal(empty.pendingLimit, 100);
  });

  it("resolves default and relative database paths under one stable data directory", () => {
    assert.equal(
      loadSidecarConfig({
        ...validEnv,
        MCP_SERVER_DATA_DIR: "/var/lib/seeker-agent-connect",
      }).databasePath,
      "/var/lib/seeker-agent-connect/direct-server.db",
    );
    assert.equal(
      loadSidecarConfig({
        ...validEnv,
        MCP_SERVER_DATA_DIR: "/var/lib/seeker-agent-connect",
        DATABASE_PATH: "state/direct.db",
      }).databasePath,
      "/var/lib/seeker-agent-connect/state/direct.db",
    );
    assert.deepEqual(
      problemsFor({ ...validEnv, MCP_SERVER_DATA_DIR: "relative/data" }),
      ["MCP_SERVER_DATA_DIR must be an absolute path."],
    );
  });

  it("rejects request lifetimes and pending limits outside their ranges", () => {
    for (const ttl of ["59", "604801", "1h"]) {
      assert.deepEqual(problemsFor({ ...validEnv, REQUEST_TTL_SECONDS: ttl }), [
        "REQUEST_TTL_SECONDS must be a whole number from 60 to 604800.",
      ]);
    }
    for (const limit of ["0", "10001", "-5"]) {
      assert.deepEqual(
        problemsFor({ ...validEnv, REQUEST_PENDING_LIMIT: limit }),
        ["REQUEST_PENDING_LIMIT must be a whole number from 1 to 10000."],
      );
    }
  });

  it("reads SIDECAR_PUBLIC_URL and PAIRING_TOKEN_TTL_SECONDS, with loopback and 10-minute defaults", () => {
    assert.equal(
      loadSidecarConfig({ ...validEnv, SIDECAR_HOST: "::1" }).publicUrl,
      "http://[::1]:8080",
    );
    const config = loadSidecarConfig({
      ...validEnv,
      SIDECAR_PUBLIC_URL: " https://Vault.example.ts.net/seeker/ ",
      PAIRING_TOKEN_TTL_SECONDS: "120",
    });
    assert.equal(config.publicUrl, "https://vault.example.ts.net/seeker");
    assert.equal(config.pairingTokenTtlSeconds, 120);
    assert.equal(
      loadSidecarConfig({
        ...validEnv,
        SIDECAR_PUBLIC_URL: "http://localhost:8080",
      }).publicUrl,
      "http://localhost:8080",
    );
  });

  it("reads SOLANA_RPC_URL and SOLANA_RPC_TIMEOUT_MS, with no endpoint by default", () => {
    assert.equal(loadSidecarConfig(validEnv).solanaRpcUrl, undefined);
    assert.equal(loadSidecarConfig(validEnv).solanaRpcTimeoutMs, 10_000);
    const config = loadSidecarConfig({
      ...validEnv,
      SOLANA_RPC_URL: " https://api.devnet.solana.com ",
      SOLANA_RPC_TIMEOUT_MS: "2500",
    });
    assert.equal(config.solanaRpcUrl, "https://api.devnet.solana.com/");
    assert.equal(config.solanaRpcTimeoutMs, 2500);
  });

  /**
   * The networks the manifest declares (SEE-174). Nothing defaults to Mainnet, and the RPC endpoint
   * is not read to guess one: a server declares what its operator said, or nothing.
   */
  it("declares no network unless SAC_SUPPORTED_NETWORKS says one", () => {
    const networks = (env: Record<string, string>) =>
      loadSidecarConfig({ ...validEnv, ...env }).supportedNetworks;
    assert.equal(networks({}), undefined);
    assert.equal(
      networks({ SOLANA_RPC_URL: "https://rpc.example.com/?key=k" }),
      undefined,
    );
    // Written in canonical order, whatever order the operator used.
    assert.deepEqual(
      networks({ SAC_SUPPORTED_NETWORKS: " devnet, mainnet " }),
      [SolanaNetwork.MAINNET, SolanaNetwork.DEVNET],
    );
    assert.deepEqual(networks({ SAC_SUPPORTED_NETWORKS: "testnet" }), [
      SolanaNetwork.TESTNET,
    ]);
    // Blank is unset, like every other optional variable here, and none says so explicitly.
    assert.equal(networks({ SAC_SUPPORTED_NETWORKS: "  " }), undefined);
    assert.deepEqual(networks({ SAC_SUPPORTED_NETWORKS: "none" }), []);
  });

  it("refuses an unknown or repeated network in SAC_SUPPORTED_NETWORKS", () => {
    for (const [value, reason] of [
      ["mainnet-beta", /SAC_SUPPORTED_NETWORKS names "mainnet-beta"/],
      ["Mainnet", /SAC_SUPPORTED_NETWORKS names "Mainnet"/],
      ["mainnet,devnet,mainnet", /names mainnet more than once/],
      ["mainnet,,devnet", /empty entry/],
    ] as const) {
      const problems = problemsFor({
        ...validEnv,
        SAC_SUPPORTED_NETWORKS: value,
      });
      assert.equal(problems.length, 1, problems.join("\n"));
      assert.match(problems[0] ?? "", reason);
    }
  });

  it("configures FCM only for an explicit valid project ID", () => {
    assert.equal(loadSidecarConfig(validEnv).fcmProjectId, undefined);
    assert.equal(
      loadSidecarConfig({
        ...validEnv,
        FCM_PROJECT_ID: " seeker-vault-prod-123 ",
      }).fcmProjectId,
      "seeker-vault-prod-123",
    );
    for (const value of ["UPPERCASE", "short", "starts-with-a-dash-"]) {
      assert.deepEqual(problemsFor({ ...validEnv, FCM_PROJECT_ID: value }), [
        "FCM_PROJECT_ID must be a 6-30 character lowercase Google Cloud project ID.",
      ]);
    }
  });

  it("configures the gateway relay as one setting in three variables", () => {
    const relay = {
      RELAY_URL: " https://feeds.example.com ",
      RELAY_SERVER_ID: "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
      RELAY_CREDENTIAL: " a-scoped-relay-credential ",
    };
    assert.equal(loadSidecarConfig(validEnv).relay, undefined);
    assert.deepEqual(loadSidecarConfig({ ...validEnv, ...relay }).relay, {
      relayUrl: "https://feeds.example.com",
      serverId: "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
      credential: "a-scoped-relay-credential",
    });

    // Two of the three is a server that looks like it wakes phones and never does, and the only
    // sign of it would be an owner who stops getting notifications.
    for (const missing of [
      "RELAY_URL",
      "RELAY_SERVER_ID",
      "RELAY_CREDENTIAL",
    ]) {
      const partial: Record<string, string> = { ...validEnv, ...relay };
      delete partial[missing];
      assert.deepEqual(problemsFor(partial), [
        "RELAY_URL, RELAY_SERVER_ID and RELAY_CREDENTIAL must all be set together: " +
          "they are one setting in three variables, and a partial one sends nothing.",
      ]);
    }

    // A relay URL that is not an origin is a startup failure with a named reason rather than
    // wake-ups that quietly go nowhere — and the reason never repeats the credential.
    const [problem] = problemsFor({
      ...validEnv,
      ...relay,
      RELAY_URL: "https://feeds.example.com/relay",
    });
    assert.ok(problem?.includes("no path"), problem);
    assert.equal(problem?.includes("a-scoped-relay-credential"), false);
  });

  it("refuses both ways of sending the same wake-up", () => {
    // Both fire on the same committed update, so a phone would be woken twice for one change and
    // an operator would have two places to look when it stopped happening.
    assert.deepEqual(
      problemsFor({
        ...validEnv,
        FCM_PROJECT_ID: "seeker-vault-prod-123",
        RELAY_URL: "https://feeds.example.com",
        RELAY_SERVER_ID: "3f1b2c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
        RELAY_CREDENTIAL: "a-scoped-relay-credential",
      }),
      [
        "FCM_PROJECT_ID and RELAY_URL configure two ways of sending the same wake-up. " +
          "Set one: FCM_PROJECT_ID sends through this server's own Firebase project, " +
          "RELAY_URL asks a gateway operator to send on its behalf.",
      ],
    );
  });

  it("configures either loopback HTTP/2 development or the production TLS listener", () => {
    assert.equal(
      loadSidecarConfig({ ...validEnv, SIDECAR_UPDATE_PORT: "8081" })
        .updatePort,
      8081,
    );
    const tls = loadSidecarConfig({
      ...validEnv,
      SIDECAR_PUBLIC_URL: "https://vault.example.test:8443",
      SIDECAR_TLS_CERT_PATH: " /run/secrets/fullchain.pem ",
      SIDECAR_TLS_KEY_PATH: " /run/secrets/privkey.pem ",
    });
    assert.equal(tls.tlsCertificatePath, "/run/secrets/fullchain.pem");
    assert.equal(tls.tlsPrivateKeyPath, "/run/secrets/privkey.pem");
  });

  it("refuses partial, insecure, or conflicting update listener settings", () => {
    assert.deepEqual(
      problemsFor({ ...validEnv, SIDECAR_TLS_CERT_PATH: "cert.pem" }),
      [
        "SIDECAR_TLS_CERT_PATH and SIDECAR_TLS_KEY_PATH must both be set or both be unset.",
      ],
    );
    assert.deepEqual(
      problemsFor({
        ...validEnv,
        SIDECAR_TLS_CERT_PATH: "cert.pem",
        SIDECAR_TLS_KEY_PATH: "key.pem",
      }),
      [
        "SIDECAR_PUBLIC_URL must use https:// when the production TLS listener is configured.",
      ],
    );
    assert.deepEqual(
      problemsFor({
        ...validEnv,
        SIDECAR_PUBLIC_URL: "https://vault.example.test",
        SIDECAR_UPDATE_PORT: "8081",
        SIDECAR_TLS_CERT_PATH: "cert.pem",
        SIDECAR_TLS_KEY_PATH: "key.pem",
      }),
      [
        "SIDECAR_UPDATE_PORT is the loopback development listener and cannot be combined with the production TLS listener.",
      ],
    );
  });

  it("configures cleartext HTTP/2 on the main listener for a TLS HTTP/2 reverse proxy", () => {
    const config = loadSidecarConfig({
      ...validEnv,
      SIDECAR_PUBLIC_URL: "https://seeker-mcp.example",
      SIDECAR_H2C: "true",
    });
    assert.equal(config.h2c, true);
    assert.equal(config.publicUrl, "https://seeker-mcp.example");
    assert.deepEqual(
      problemsFor({
        ...validEnv,
        SIDECAR_H2C: "true",
      }),
      ["SIDECAR_PUBLIC_URL must use https:// when SIDECAR_H2C is true."],
    );
    assert.deepEqual(
      problemsFor({
        ...validEnv,
        SIDECAR_PUBLIC_URL: "https://seeker-mcp.example",
        SIDECAR_H2C: "true",
        SIDECAR_UPDATE_PORT: "8081",
      }),
      ["SIDECAR_H2C cannot be combined with SIDECAR_UPDATE_PORT."],
    );
    assert.deepEqual(
      problemsFor({
        ...validEnv,
        SIDECAR_PUBLIC_URL: "https://seeker-mcp.example",
        SIDECAR_H2C: "true",
        SIDECAR_TLS_CERT_PATH: "cert.pem",
        SIDECAR_TLS_KEY_PATH: "key.pem",
      }),
      [
        "SIDECAR_H2C is the reverse-proxy HTTP/2 listener and cannot be combined with the production TLS listener.",
      ],
    );
  });

  it("refuses an RPC endpoint that isn't an HTTP URL, without echoing it", () => {
    for (const url of [
      "wss://rpc.example.com/?api-key=s3cret",
      "rpc.example.com/?api-key=s3cret",
    ]) {
      const problems = problemsFor({ ...validEnv, SOLANA_RPC_URL: url });
      assert.match(problems.join("\n"), /SOLANA_RPC_URL/);
      // The endpoint can carry an API key, so a problem names the variable and nothing else.
      assert.ok(!problems.join("\n").includes("s3cret"), problems.join("\n"));
    }
    assert.match(
      problemsFor({ ...validEnv, SOLANA_RPC_TIMEOUT_MS: "0" }).join("\n"),
      /SOLANA_RPC_TIMEOUT_MS must be a whole number from 1000 to 20000/,
    );
  });

  it("refuses a public URL without HTTPS off loopback, and pairing codes outside 1 to 60 minutes", () => {
    for (const url of [
      "http://192.168.1.20:8080",
      "ftp://vault.example.com",
      "https://owner:secret@vault.example.com",
      "https://vault.example.com/?code=1",
      "https://vault.example.com:0",
      "not a URL",
    ]) {
      assert.match(
        problemsFor({ ...validEnv, SIDECAR_PUBLIC_URL: url }).join("\n"),
        /^SIDECAR_PUBLIC_URL: /,
        url,
      );
    }
    for (const ttl of ["59", "3601"]) {
      assert.deepEqual(
        problemsFor({ ...validEnv, PAIRING_TOKEN_TTL_SECONDS: ttl }),
        ["PAIRING_TOKEN_TTL_SECONDS must be a whole number from 60 to 3600."],
      );
    }
  });

  it("configures the OAuth profile from the issuer, and takes the resource from the public URL", () => {
    const config = loadSidecarConfig({
      ...validEnv,
      SIDECAR_PUBLIC_URL: "https://vault.example.com",
      MCP_OAUTH_ISSUER: "https://auth.example.com/realms/seeker",
      MCP_OAUTH_SCOPE: " seeker-vault:agent  openid ",
    });
    assert.deepEqual(config.oauth, {
      issuer: "https://auth.example.com/realms/seeker",
      resource: "https://vault.example.com/mcp",
      scopes: ["seeker-vault:agent", "openid"],
    });
    // A trailing slash belongs to the issuer, because that is how some servers spell `iss`.
    assert.equal(
      loadSidecarConfig({
        ...validEnv,
        SIDECAR_PUBLIC_URL: "https://vault.example.com",
        MCP_OAUTH_ISSUER: "https://tenant.example.com/",
      }).oauth?.issuer,
      "https://tenant.example.com/",
    );
  });

  it("has no OAuth without an issuer, and says so rather than ignoring the rest", () => {
    assert.equal(loadSidecarConfig(validEnv).oauth, undefined);
    assert.deepEqual(
      problemsFor({
        ...validEnv,
        MCP_OAUTH_RESOURCE: "https://vault.example.com/mcp",
        MCP_OAUTH_SCOPE: "seeker-vault:agent",
      }),
      [
        "MCP_OAUTH_RESOURCE, MCP_OAUTH_SCOPE needs MCP_OAUTH_ISSUER: without an authorization " +
          "server /mcp takes MCP_TOKEN and nothing else.",
      ],
    );
  });

  it("refuses an authorization server, a resource, or a key set that is not on HTTPS", () => {
    const base = {
      ...validEnv,
      SIDECAR_PUBLIC_URL: "https://vault.example.com",
      MCP_OAUTH_ISSUER: "https://auth.example.com",
    };
    for (const name of [
      "MCP_OAUTH_ISSUER",
      "MCP_OAUTH_RESOURCE",
      "MCP_OAUTH_JWKS_URL",
    ]) {
      for (const value of [
        "http://auth.example.com",
        "ftp://auth.example.com",
        "auth.example.com",
      ]) {
        assert.match(
          problemsFor({ ...base, [name]: value }).join("\n"),
          new RegExp(`^${name} must be an`),
          `${name}=${value}`,
        );
      }
      // Loopback is the exception: a developer's own authorization server, and these tests.
      assert.ok(
        loadSidecarConfig({ ...base, [name]: "http://127.0.0.1:9000" }).oauth,
      );
    }
    assert.deepEqual(
      problemsFor({
        ...base,
        MCP_OAUTH_ISSUER: "https://auth.example.com/?a=1",
      }),
      ["MCP_OAUTH_ISSUER must carry no query string and no fragment."],
    );
    assert.deepEqual(problemsFor({ ...base, MCP_OAUTH_SCOPE: 'a "b"' }), [
      'MCP_OAUTH_SCOPE must be scopes separated by spaces (invalid: "b").',
    ]);
  });

  it("never includes token values in the error message", () => {
    const secret = "do-not-print-this-token-value";
    try {
      loadSidecarConfig({
        ...validEnv,
        MCP_TOKEN: secret,
        PHONE_TOKEN: secret,
      });
      assert.fail("expected a ConfigError");
    } catch (error) {
      assert.ok(error instanceof ConfigError);
      assert.ok(!error.message.includes(secret));
    }
  });
});
