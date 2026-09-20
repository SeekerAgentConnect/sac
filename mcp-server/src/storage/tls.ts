/** Reads the operator-supplied TLS identity. Keeping file access here preserves the sidecar's
 * single audited file-system boundary; the update listener receives only the bytes. */
import { readFileSync } from "node:fs";

export interface TlsIdentity {
  readonly cert: Buffer;
  readonly key: Buffer;
}

/** Reads an optional operator-supplied trust anchor for the local readiness probe. */
export function readTlsTrustAnchor(path: string): Buffer {
  return readFileSync(path);
}

export function readTlsIdentity(
  certificatePath: string,
  privateKeyPath: string,
): TlsIdentity {
  return {
    cert: readFileSync(certificatePath),
    key: readFileSync(privateKeyPath),
  };
}
