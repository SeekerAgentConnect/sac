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

const NETWORKS = ["mainnet", "devnet", "testnet"];

/**
 * @param {object} environment  SEEKERVAULT_GOOGLE_SERVICES_JSON, SEEKERVAULT_SOLANA_RPC,
 *   SEEKERVAULT_SOLANA_RPC_{MAINNET,DEVNET,TESTNET}, SEEKERVAULT_RELAY_URL,
 *   SEEKERVAULT_DISCOVERY_URL, REQUIRE (comma-separated features), RELEASE ("true" enforces it)
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

  const rpc = (name, property) => {
    const text = value(name);
    if (!text) return "";
    try {
      const url = checkUrl(text, { origin: false });
      if (url.search) {
        warnings.push(
          `${name} carries a query string; it is compiled into the APK and readable by anyone ` +
            "who has it, so it must be a key meant for distribution",
        );
      }
      gradleArgs.push(`-P${property}=${text}`);
      return text;
    } catch (error) {
      problems.push(`${name}: ${error.message}`);
      return "";
    }
  };
  const solanaRpc = rpc("SEEKERVAULT_SOLANA_RPC", "seekervault.solanaRpc");
  const networks = {};
  for (const network of NETWORKS) {
    networks[network] = rpc(
      `SEEKERVAULT_SOLANA_RPC_${network.toUpperCase()}`,
      `seekervault.solanaRpc.${network}`,
    );
  }

  const origin = (name, property) => {
    const text = value(name);
    if (!text) return "";
    try {
      const normalized = checkUrl(text, { origin: true }).origin;
      gradleArgs.push(`-P${property}=${normalized}`);
      return normalized;
    } catch (error) {
      problems.push(`${name}: ${error.message}`);
      return "";
    }
  };
  const relayUrl = origin("SEEKERVAULT_RELAY_URL", "seekervault.relayUrl");
  // Unset, Gradle falls back to the relay's origin, so the feature exists whenever either does.
  const discoveryUrl =
    origin("SEEKERVAULT_DISCOVERY_URL", "seekervault.discoveryUrl") || relayUrl;

  const features = {
    firebase: googleServices !== null,
    // The official build's chain reads are mainnet's (prediction orders, the swap fee): a mainnet
    // endpoint satisfies it on its own (SEE-184), and a general-only build still does, because the
    // app proves the general endpoint's cluster before using it for any network.
    solanaRpc: Boolean(networks.mainnet || solanaRpc),
    solanaRpcNetworks: NETWORKS.filter((network) => networks[network]),
    relayUrl,
    discoveryUrl,
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
