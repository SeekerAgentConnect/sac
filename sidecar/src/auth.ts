import { createHash, timingSafeEqual } from "node:crypto";

/** The token in an Authorization header of exactly `Bearer <token>`, or undefined. */
export function bearerToken(
  authorization: string | null | undefined,
): string | undefined {
  return /^Bearer (\S+)$/.exec(authorization ?? "")?.[1];
}

/**
 * Whether an Authorization header value is exactly `Bearer <token>`. The comparison
 * hashes both sides first, so it takes the same time whatever the input's length.
 */
export function bearerTokenMatches(
  authorization: string | null | undefined,
  token: string,
): boolean {
  const given = bearerToken(authorization);
  return given !== undefined && timingSafeEqual(sha256(given), sha256(token));
}

function sha256(value: string): Buffer {
  return createHash("sha256").update(value, "utf8").digest();
}
