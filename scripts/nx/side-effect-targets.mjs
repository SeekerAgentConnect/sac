// Targets that must never be replayed from the Nx cache (SEE-187). Publishing and signing read
// live registry or keystore state. `nx.json` sets `cache: false` on `publish` and `sign`; this
// command is what those targets run, and scripts/nx/cache-safety.test.mjs runs them twice.
const kind = process.argv[2];
if (kind !== "publish" && kind !== "sign") {
  console.error("usage: node scripts/nx/side-effect-targets.mjs publish|sign");
  process.exit(1);
}
console.log(`${kind}-ok`);
