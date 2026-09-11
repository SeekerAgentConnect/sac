import { createHash, timingSafeEqual } from "node:crypto";

/**
 * Whether an Authorization header value is exactly `Bearer <token>`. The comparison
 * hashes both sides first, so it takes the same time whatever the input's length.
 */
export function bearerTokenMatches(
  authorization: string | null | undefined,
  token: string,
): boolean {
  const given = /^Bearer (\S+)$/.exec(authorization ?? "")?.[1];
  return given !== undefined && timingSafeEqual(sha256(given), sha256(token));
}

function sha256(value: string): Buffer {
  return createHash("sha256").update(value, "utf8").digest();
}
