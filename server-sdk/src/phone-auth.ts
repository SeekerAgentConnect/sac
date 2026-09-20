import { createHash, timingSafeEqual } from "node:crypto";

/** Reads one Bearer credential without interpreting which trust domain owns it. */
export function bearerToken(
  authorization: string | null | undefined,
): string | undefined {
  return /^Bearer (\S+)$/.exec(authorization ?? "")?.[1];
}

/** Constant-time comparison for the legacy live-phone token. */
export function bearerTokenMatches(
  authorization: string | null | undefined,
  expected: string,
): boolean {
  const presented = bearerToken(authorization);
  if (presented === undefined) return false;
  return timingSafeEqual(sha256(presented), sha256(expected));
}

function sha256(value: string): Buffer {
  return createHash("sha256").update(value, "utf8").digest();
}
