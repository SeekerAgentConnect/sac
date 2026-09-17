/**
 * An authorization server the tests control (SAW-036). It publishes the two documents a resource
 * server reads — OAuth 2.0 authorization server metadata and the JWKS behind it — and mints access
 * tokens, so the whole OAuth path can be exercised without an account anywhere.
 *
 * It is a test double for the *authorization* side only, and nothing here ships: the sidecar is a
 * resource server, and never issues a token of its own.
 */
import { once } from "node:events";
import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";

import { SignJWT, exportJWK, generateKeyPair, type JWK } from "jose";

export interface TokenOptions {
  /** The audience the token is minted for; a deployment's canonical MCP URI. */
  readonly audience?: string | string[];
  readonly issuer?: string;
  readonly subject?: string;
  readonly clientId?: string;
  readonly scope?: string;
  /** Seconds from now until `exp`; negative mints one that already expired. */
  readonly expiresInSeconds?: number;
  /** Mints with a key this server does not publish, as another authorization server would. */
  readonly unknownKey?: boolean;
  /** Mints with the symmetric algorithm no resource server here accepts. */
  readonly sharedSecret?: boolean;
}

export interface FakeAuthorizationServer {
  /** The issuer, exactly as its tokens spell `iss`. */
  readonly issuer: string;
  readonly jwksUrl: string;
  /** How many times each document has been read, so caching can be asserted. */
  readonly reads: { metadata: number; jwks: number };
  issue(options?: TokenOptions): Promise<string>;
  close(): Promise<void>;
}

export interface AuthorizationServerOptions {
  /**
   * Which discovery document to publish: OAuth 2.0 authorization server metadata, OpenID Connect
   * discovery, or neither, so a resource server that must find the keys itself can be tested
   * against each.
   */
  readonly discovery?: "oauth" | "openid" | "none";
  /** A path component on the issuer, the way a Keycloak realm or an Auth0 tenant has one. */
  readonly path?: string;
  /**
   * The `jwks_uri` the metadata advertises, when it should not be the real one — a compromised or
   * misconfigured server pointing a resource server at plaintext keys.
   */
  readonly jwksUri?: string;
}

/** Starts one on a loopback port. Close it when the test is done. */
export async function startAuthorizationServer(
  options: AuthorizationServerOptions = {},
): Promise<FakeAuthorizationServer> {
  const discovery = options.discovery ?? "oauth";
  const path = options.path ?? "";
  const published = await generateKeyPair("ES256", { extractable: true });
  const other = await generateKeyPair("ES256", { extractable: true });
  const secret = new TextEncoder().encode("a".repeat(48));
  const publicJwk: JWK = {
    ...(await exportJWK(published.publicKey)),
    kid: "test-key",
  };
  const reads = { metadata: 0, jwks: 0 };

  const server = createServer((req, res) => {
    const url = new URL(req.url ?? "/", "http://authorization-server");
    const send = (body: unknown) => {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(body));
    };
    if (url.pathname === `${path}/jwks`) {
      reads.jwks += 1;
      send({ keys: [publicJwk] });
      return;
    }
    const metadataPaths =
      discovery === "oauth"
        ? [`/.well-known/oauth-authorization-server${path}`]
        : discovery === "openid"
          ? [`${path}/.well-known/openid-configuration`]
          : [];
    if (metadataPaths.includes(url.pathname)) {
      reads.metadata += 1;
      send({
        issuer: issuerOf(),
        jwks_uri: options.jwksUri ?? `${issuerOf()}/jwks`,
        authorization_endpoint: `${issuerOf()}/authorize`,
        token_endpoint: `${issuerOf()}/token`,
        response_types_supported: ["code"],
        grant_types_supported: ["authorization_code", "refresh_token"],
        code_challenge_methods_supported: ["S256"],
        client_id_metadata_document_supported: true,
      });
      return;
    }
    res.writeHead(404, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ error: "not_found" }));
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const { port } = server.address() as AddressInfo;

  function issuerOf(): string {
    return `http://127.0.0.1:${String(port)}${path}`;
  }

  return {
    issuer: issuerOf(),
    jwksUrl: `${issuerOf()}/jwks`,
    reads,
    async issue(token: TokenOptions = {}): Promise<string> {
      const now = Math.floor(Date.now() / 1000);
      const claims = new SignJWT({
        ...(token.scope === undefined ? {} : { scope: token.scope }),
        ...(token.clientId === undefined ? {} : { client_id: token.clientId }),
      })
        .setIssuer(token.issuer ?? issuerOf())
        .setSubject(token.subject ?? "owner@example.com")
        .setIssuedAt(now)
        .setExpirationTime(now + (token.expiresInSeconds ?? 300));
      if (token.audience !== undefined) claims.setAudience(token.audience);
      if (token.sharedSecret === true) {
        return claims.setProtectedHeader({ alg: "HS256" }).sign(secret);
      }
      return claims
        .setProtectedHeader({ alg: "ES256", kid: "test-key" })
        .sign(
          token.unknownKey === true ? other.privateKey : published.privateKey,
        );
    },
    close(): Promise<void> {
      return closeServer(server);
    },
  };
}

function closeServer(server: Server): Promise<void> {
  return new Promise((resolve) => {
    server.closeAllConnections();
    server.close(() => resolve());
  });
}
