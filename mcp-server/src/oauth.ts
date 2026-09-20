/**
 * The OAuth 2.1 resource-server side of the agent-facing MCP endpoint (SAW-036).
 *
 * MCP's authorization specification makes the MCP server a resource server and nothing else: it
 * publishes a protected-resource metadata document naming its authorization server, and it
 * validates the access tokens that arrive. Issuing them is somebody else's job. Nothing in this
 * file authorizes a user, registers a client, shows a consent screen, mints a token, or keeps a
 * secret of its own — the authorization server the operator chose does all of that, and this
 * sidecar only ever reads its public keys.
 *
 * Two rules matter most, and both are here:
 *   - a token is accepted only when it was issued **for this server** (RFC 8707 / RFC 9728: the
 *     audience is this deployment's canonical MCP URI), so a token minted for another resource by
 *     the same authorization server opens nothing here;
 *   - a token is never passed on. It authorizes the MCP call and stops at this boundary; the
 *     phone's API has its own credentials and has never heard of one.
 *
 * https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization
 */
import {
  createRemoteJWKSet,
  decodeProtectedHeader,
  errors,
  jwtVerify,
  type JWTPayload,
} from "jose";

/** What an operator configures; absent unless MCP_OAUTH_ISSUER is set (config.ts). */
export interface OAuthConfig {
  /** The authorization server, exactly as its tokens spell `iss` (MCP_OAUTH_ISSUER). */
  readonly issuer: string;
  /** This deployment's canonical MCP URI, which a token's audience must carry. */
  readonly resource: string;
  /** Where the authorization server publishes its keys; discovered from the issuer when unset. */
  readonly jwksUrl?: string;
  /** Scopes a token must carry, advertised in the metadata and in the challenge. */
  readonly scopes: readonly string[];
}

/** The identity a valid access token carries. Neither value is a credential. */
export interface AccessToken {
  readonly subject: string;
  readonly clientId?: string;
  readonly scopes: readonly string[];
}

export type Verification =
  | { readonly ok: true; readonly token: AccessToken }
  | {
      readonly ok: false;
      /** 401 for a token that isn't valid here, 403 for a valid one that is missing a scope. */
      readonly status: 401 | 403;
      readonly error: "invalid_token" | "insufficient_scope";
      /** Says what is wrong without quoting any part of the token. */
      readonly description: string;
    };

export interface AccessTokenVerifier {
  verify(accessToken: string): Promise<Verification>;
}

/** RFC 9728's well-known path; `/mcp` is appended for the resource this server actually serves. */
const METADATA_PATH = "/.well-known/oauth-protected-resource";

/**
 * Both paths a client may ask for. RFC 9728 inserts the resource's own path, and the MCP
 * specification has clients try that first and the bare path second, so the same document is
 * served at both rather than leaving one of them to answer 404.
 */
export const PROTECTED_RESOURCE_PATHS: readonly string[] = [
  METADATA_PATH,
  `${METADATA_PATH}/mcp`,
];

/**
 * Asymmetric algorithms only. A shared secret has no place here: the authorization server
 * publishes public keys, and `none` is not an algorithm.
 */
const ALGORITHMS = [
  "RS256",
  "RS384",
  "RS512",
  "PS256",
  "PS384",
  "PS512",
  "ES256",
  "ES384",
  "ES512",
  "EdDSA",
];

/**
 * Hosts an http:// endpoint may use: a developer's own authorization server, and the tests. Any
 * other host must be https, because a key fetched over plaintext is a key an attacker can replace.
 */
const LOOPBACK_HOSTS = new Set(["127.0.0.1", "::1", "localhost"]);

/** An authorization server advertising a key set this sidecar will not read. */
class InsecureKeySet extends Error {
  constructor(message: string) {
    super(message);
    this.name = "InsecureKeySet";
  }
}

/**
 * Whether an OAuth endpoint may be trusted: https, or http on a loopback host. Every URL this
 * sidecar fetches keys from passes through here — the one an operator configures (config.ts) and
 * the one an authorization server advertises in its own metadata, which is just as much an input.
 */
export function isSecureEndpoint(url: URL): boolean {
  if (url.protocol === "https:") return true;
  return (
    url.protocol === "http:" &&
    LOOPBACK_HOSTS.has(url.hostname.replace(/^\[|\]$/g, ""))
  );
}

/** Tolerated clock difference between this host and the authorization server. */
const CLOCK_TOLERANCE_SECONDS = 30;

/** How long a read of the authorization server's metadata or keys may take. */
const DISCOVERY_TIMEOUT_MS = 5000;

/** The protected-resource metadata document (RFC 9728), as this deployment publishes it. */
export function protectedResourceMetadata(
  config: OAuthConfig,
): Record<string, unknown> {
  return {
    resource: config.resource,
    authorization_servers: [config.issuer],
    bearer_methods_supported: ["header"],
    ...(config.scopes.length > 0
      ? { scopes_supported: [...config.scopes] }
      : {}),
    resource_name: "Seeker Agent Connect",
  };
}

/** Where this deployment publishes that document, for the WWW-Authenticate challenge. */
export function metadataUrl(config: OAuthConfig): string {
  const resource = new URL(config.resource);
  const path = resource.pathname === "/" ? "" : resource.pathname;
  return `${resource.origin}${METADATA_PATH}${path}`;
}

/**
 * The WWW-Authenticate challenge. It carries the metadata URL, which is how a client that has
 * never seen this server finds the authorization server to send its user to, and the scopes to
 * ask for, so nothing has to request more than this endpoint needs.
 */
export function challenge(
  config: OAuthConfig,
  failure?: { readonly error: string; readonly description: string },
): string {
  const parameters = [
    `resource_metadata="${metadataUrl(config)}"`,
    ...(config.scopes.length > 0 ? [`scope="${config.scopes.join(" ")}"`] : []),
    ...(failure === undefined
      ? []
      : [
          `error="${failure.error}"`,
          `error_description="${failure.description}"`,
        ]),
  ];
  return `Bearer ${parameters.join(", ")}`;
}

/**
 * A verifier for this deployment's access tokens. The authorization server's keys are read once
 * and then cached and rotated by the key set itself; a key that has never been seen makes it fetch
 * again, at its own rate limit.
 */
export function createAccessTokenVerifier(
  config: OAuthConfig,
): AccessTokenVerifier {
  let keys: ReturnType<typeof createRemoteJWKSet> | undefined;
  let discovering: Promise<ReturnType<typeof createRemoteJWKSet>> | undefined;

  async function keySet(): Promise<ReturnType<typeof createRemoteJWKSet>> {
    if (keys !== undefined) return keys;
    // One discovery at a time, and a failed one is not remembered: the authorization server may
    // simply have been restarting, and the next request should try again.
    discovering ??= (async () => {
      const url = config.jwksUrl ?? (await discoverJwksUrl(config.issuer));
      return createRemoteJWKSet(new URL(url), {
        timeoutDuration: DISCOVERY_TIMEOUT_MS,
      });
    })().then(
      (resolved) => {
        keys = resolved;
        discovering = undefined;
        return resolved;
      },
      (error: unknown) => {
        discovering = undefined;
        throw error;
      },
    );
    return discovering;
  }

  return {
    async verify(accessToken: string): Promise<Verification> {
      try {
        decodeProtectedHeader(accessToken);
      } catch {
        return invalid(
          "the access token is not a JSON Web Token; this server validates JWT access tokens",
        );
      }
      let payload: JWTPayload;
      try {
        ({ payload } = await jwtVerify(accessToken, await keySet(), {
          issuer: config.issuer,
          audience: config.resource,
          algorithms: ALGORITHMS,
          clockTolerance: CLOCK_TOLERANCE_SECONDS,
          requiredClaims: ["sub", "exp"],
        }));
      } catch (error) {
        return invalid(reasonFor(error));
      }
      const scopes = scopesOf(payload);
      const missing = config.scopes.filter((scope) => !scopes.includes(scope));
      if (missing.length > 0) {
        return {
          ok: false,
          status: 403,
          error: "insufficient_scope",
          description: `the access token is missing the scope ${missing.join(" ")}`,
        };
      }
      return {
        ok: true,
        token: {
          subject: String(payload.sub),
          ...(typeof payload.client_id === "string"
            ? { clientId: payload.client_id }
            : typeof payload.azp === "string"
              ? { clientId: payload.azp }
              : {}),
          scopes,
        },
      };
    },
  };
}

function invalid(description: string): Verification {
  return { ok: false, status: 401, error: "invalid_token", description };
}

/** The scopes a token carries: `scope` as OAuth spells it, or `scp` as some servers do. */
function scopesOf(payload: JWTPayload): readonly string[] {
  if (typeof payload.scope === "string") {
    return payload.scope.split(/\s+/).filter((scope) => scope !== "");
  }
  if (Array.isArray(payload.scp)) {
    return payload.scp.filter(
      (scope): scope is string => typeof scope === "string",
    );
  }
  return [];
}

/**
 * Why a token was refused, in words an operator can act on and without any part of the token in
 * them: a rejected credential must never be written down, and a description reaches the client.
 */
function reasonFor(error: unknown): string {
  if (error instanceof errors.JWTExpired) return "the access token has expired";
  if (error instanceof errors.JWTClaimValidationFailed) {
    if (error.claim === "aud") {
      return "the access token was not issued for this MCP server";
    }
    if (error.claim === "iss") {
      return "the access token was not issued by the configured authorization server";
    }
    return `the access token's ${error.claim} claim is not accepted here`;
  }
  if (error instanceof errors.JOSEAlgNotAllowed) {
    return "the access token uses an algorithm this server does not accept";
  }
  if (
    error instanceof errors.JWSSignatureVerificationFailed ||
    error instanceof errors.JWKSNoMatchingKey
  ) {
    return "the access token's signature is not from the authorization server's published keys";
  }
  if (error instanceof errors.JOSEError) return "the access token is not valid";
  // Our own refusal, and worth repeating rather than flattening: it is a configuration the
  // operator has to fix at the authorization server, not a token the client can do anything about.
  if (error instanceof InsecureKeySet) return error.message;
  return "the authorization server's keys could not be read";
}

/**
 * The authorization server's `jwks_uri`, from its own metadata. The MCP specification has clients
 * try these endpoints in this order, and this reads the same documents for the same reason: an
 * operator should have to name the issuer and nothing else.
 */
async function discoverJwksUrl(issuer: string): Promise<string> {
  const url = new URL(issuer);
  const path = url.pathname.replace(/\/$/, "");
  const candidates = [
    `${url.origin}/.well-known/oauth-authorization-server${path}`,
    `${url.origin}/.well-known/openid-configuration${path}`,
    `${url.origin}${path}/.well-known/openid-configuration`,
  ];
  for (const candidate of candidates) {
    const metadata = await readJson(candidate);
    const jwksUri = metadata?.jwks_uri;
    if (typeof jwksUri !== "string" || jwksUri === "") continue;
    // A discovered URL is an input like any other, and it decides which keys sign the tokens this
    // endpoint accepts. Fetching it over plaintext would let anyone on the path substitute a key
    // and mint a token for this deployment, so it is held to the rule MCP_OAUTH_JWKS_URL is.
    const url = URL.parse(jwksUri);
    if (url === null || !isSecureEndpoint(url)) {
      throw new InsecureKeySet(
        "the authorization server advertises its keys over plaintext; this server will not read signing keys that a network could replace",
      );
    }
    return url.toString();
  }
  throw new Error(
    "the authorization server published no jwks_uri; set MCP_OAUTH_JWKS_URL",
  );
}

async function readJson(
  url: string,
): Promise<Record<string, unknown> | undefined> {
  try {
    const response = await fetch(url, {
      headers: { Accept: "application/json" },
      signal: AbortSignal.timeout(DISCOVERY_TIMEOUT_MS),
    });
    if (!response.ok) return undefined;
    return (await response.json()) as Record<string, unknown>;
  } catch {
    return undefined;
  }
}
