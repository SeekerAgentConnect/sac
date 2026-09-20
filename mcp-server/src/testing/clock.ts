/**
 * A test-only clock for a sidecar process (process.ts): `node --import` loads it before
 * src/cli.ts, and it runs Date.now() ahead by SIDECAR_TEST_CLOCK_AHEAD_MS. The request and pairing
 * stores read the time through Date.now(), so a sidecar restarted with its clock ahead sees the
 * time that passed while it was down, and expires what that time expired. Timers are unaffected.
 * Shipped code never imports this file.
 */
const ahead = Number(process.env.SIDECAR_TEST_CLOCK_AHEAD_MS ?? "0");
if (!Number.isSafeInteger(ahead) || ahead < 0) {
  throw new Error(
    "SIDECAR_TEST_CLOCK_AHEAD_MS must be a whole number of milliseconds, 0 or more",
  );
}
const realNow = Date.now.bind(Date);
Date.now = () => realNow() + ahead;
