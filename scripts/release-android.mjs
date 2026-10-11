/**
 * The official Android build's configuration, checked before it is compiled in (SEE-182).
 *
 * Everything here is **client configuration**: it ends up in the APK, where anybody who has the APK
 * can read it. It therefore comes from repository *variables*, not secrets, and this module refuses
 * the values that would turn it into a leak — a Firebase service account (a server credential, never
 * the app's `google-services.json`), and a URL carrying a username or password. The signing key is
 * the only secret an Android release uses, and it never passes through here.
 *
 *   node scripts/release-android.mjs <application id> <gradle-args file> <features file>
 *
 * reads the SEEKERVAULT_* variables from the environment, writes `apps/android/app/
 * google-services.json` when Firebase is configured, one Gradle argument per line to the first
 * file, and the features the build has to the second. With RELEASE=true, every feature named in
 * REQUIRE has to be configured, or the release stops before anything is built.
 */
import { writeFileSync } from "node:fs";
import { join, resolve } from "node:path";

const ROOT = resolve(import.meta.dirname, "..");

/**
 * @param {object} environment  SEEKERVAULT_GOOGLE_SERVICES_JSON, SEEKERVAULT_GATEWAY_URL,
 *   REQUIRE (comma-separated features), RELEASE ("true" enforces it)
 * @param {string} applicationId
 */
export function androidReleaseConfig(environment, applicationId) {
  const problems = [];
  const warnings = [];
  const gradleArgs = [];
  const value = (name) => (environment[name] ?? "").trim();

  let googleServices = null;
  const firebase = value("SEEKERVAULT_GOOGLE_SERVICES_JSON");
  if (firebase) {
    try {
      googleServices = checkGoogleServices(firebase, applicationId);
    } catch (error) {
      problems.push(`SEEKERVAULT_GOOGLE_SERVICES_JSON: ${error.message}`);
    }
  }

  // The one network setting an APK carries (SEE-185): the trusted gateway's origin. Feeds, the
  // Discover catalog, the push relay and the client configuration — the Solana RPC endpoint per
  // network and the SAC swap fee — all come from it, and none of those is compiled in.
  let gatewayUrl = "";
  const gateway = value("SEEKERVAULT_GATEWAY_URL");
  if (gateway) {
    try {
      gatewayUrl = checkUrl(gateway, { origin: true }).origin;
      gradleArgs.push(`-Pseekervault.gatewayUrl=${gatewayUrl}`);
    } catch (error) {
      problems.push(`SEEKERVAULT_GATEWAY_URL: ${error.message}`);
    }
  }
  // Retired with SEE-185. A variable still set is said once, so an owner removes it rather than
  // believing it does something.
  for (const name of [
    "SEEKERVAULT_SOLANA_RPC",
    "SEEKERVAULT_SOLANA_RPC_MAINNET",
    "SEEKERVAULT_SOLANA_RPC_DEVNET",
    "SEEKERVAULT_SOLANA_RPC_TESTNET",
    "SEEKERVAULT_RELAY_URL",
    "SEEKERVAULT_DISCOVERY_URL",
    "SEEKERVAULT_SWAP_FEE_BPS",
    "SEEKERVAULT_SWAP_FEE_OWNER",
    "SEEKERVAULT_SWAP_FEE_ACCOUNTS",
  ]) {
    if (value(name)) {
      warnings.push(
        `${name} is retired and ignored: RPC endpoints and the swap fee are the gateway's ` +
          "client configuration now (docs/wiki/client-config.md)",
      );
    }
  }

  const features = {
    firebase: googleServices !== null,
    gatewayUrl,
  };

  const required = value("REQUIRE")
    .split(",")
    .map((entry) => entry.trim())
    .filter(Boolean);
  const missing = required.filter((feature) => !features[feature]);
  if (missing.length > 0) {
    const message =
      `the official build requires ${missing.join(", ")}, which ${missing.length === 1 ? "is" : "are"} ` +
      "not configured (docs/development/releases.md § Android build configuration)";
    if (environment.RELEASE === "true") problems.push(message);
    else warnings.push(`${message}; a dry run continues without`);
  }

  return { googleServices, gradleArgs, features, problems, warnings };
}

/** A Firebase *client* file for this application, or an error saying why it is not one. */
export function checkGoogleServices(text, applicationId) {
  let document;
  try {
    document = JSON.parse(text);
  } catch {
    throw new Error("is not JSON");
  }
  if (document.type === "service_account" || "private_key" in document) {
    throw new Error(
      "is a Firebase service account — a server credential. The app takes the client " +
        "google-services.json from the Firebase console's Android app settings; the service " +
        "account belongs to the relay or MCP server only",
    );
  }
  if (!document.project_info || !Array.isArray(document.client)) {
    throw new Error(
      "is not a google-services.json (no project_info or client)",
    );
  }
  const packages = document.client.map(
    (client) => client.client_info?.android_client_info?.package_name,
  );
  if (!packages.includes(applicationId)) {
    throw new Error(
      `has no client for ${applicationId} (it has ${packages.filter(Boolean).join(", ") || "none"})`,
    );
  }
  return `${JSON.stringify(document, null, 2)}\n`;
}

/** An https URL with no credentials in it; with `origin`, nothing but scheme, host and port. */
export function checkUrl(text, { origin }) {
  let url;
  try {
    url = new URL(text);
  } catch {
    throw new Error(`"${text}" is not a URL`);
  }
  if (url.protocol !== "https:")
    throw new Error(`${url.protocol} is not https:`);
  if (url.username || url.password) {
    throw new Error(
      "carries a username or password, which would ship inside the APK",
    );
  }
  if (origin && (url.pathname !== "/" || url.search || url.hash)) {
    throw new Error(
      `must be an origin such as https://feeds.example.com, not ${text}`,
    );
  }
  return url;
}

if (process.argv[1] === import.meta.filename) {
  const [applicationId, argsFile, featuresFile] = process.argv.slice(2);
  const config = androidReleaseConfig(process.env, applicationId);
  for (const warning of config.warnings) console.log(`::warning::${warning}`);
  if (config.problems.length > 0) {
    for (const problem of config.problems) console.log(`::error::${problem}`);
    process.exit(1);
  }
  if (config.googleServices) {
    writeFileSync(
      join(ROOT, "apps", "android", "app", "google-services.json"),
      config.googleServices,
    );
  }
  writeFileSync(argsFile, config.gradleArgs.map((arg) => `${arg}\n`).join(""));
  writeFileSync(featuresFile, `${JSON.stringify(config.features)}\n`);
  console.log(JSON.stringify(config.features, null, 2));
}
