/**
 * The official Android build configuration (SEE-182): what is compiled into the APK, what is
 * refused because it would leak a credential, and what a real release insists on.
 */
import assert from "node:assert/strict";
import test from "node:test";

import {
  androidReleaseConfig,
  checkGoogleServices,
  checkUrl,
} from "./release-android.mjs";

const APP = "io.github.brrenat.seekervault";
const client = (packageName = APP) =>
  JSON.stringify({
    project_info: { project_id: "sac-example" },
    client: [
      {
        client_info: { android_client_info: { package_name: packageName } },
        api_key: [{ current_key: "AIza-client-key" }],
      },
    ],
  });

test("an unconfigured dry run builds a plain app and says what it lacks", () => {
  const config = androidReleaseConfig({ REQUIRE: "firebase,relayUrl" }, APP);
  assert.deepEqual(config.problems, []);
  assert.deepEqual(config.gradleArgs, []);
  assert.equal(config.googleServices, null);
  assert.match(config.warnings.join(), /requires firebase, relayUrl/);
});

test("a real release stops when a required feature is missing", () => {
  const config = androidReleaseConfig(
    { REQUIRE: "firebase,solanaRpc,relayUrl", RELEASE: "true" },
    APP,
  );
  assert.match(
    config.problems.join(),
    /requires firebase, solanaRpc, relayUrl/,
  );
});

test("the full official configuration reaches Gradle", () => {
  const config = androidReleaseConfig(
    {
      SEEKERVAULT_GOOGLE_SERVICES_JSON: client(),
      SEEKERVAULT_SOLANA_RPC: "https://rpc.example.com",
      SEEKERVAULT_SOLANA_RPC_MAINNET: "https://mainnet.example.com",
      SEEKERVAULT_SOLANA_RPC_DEVNET: "https://devnet.example.com/?key=public",
      SEEKERVAULT_RELAY_URL: "https://feeds.example.com/",
      REQUIRE: "firebase,solanaRpc,relayUrl,discoveryUrl",
      RELEASE: "true",
    },
    APP,
  );
  assert.deepEqual(config.problems, []);
  assert.deepEqual(config.gradleArgs, [
    "-Pseekervault.solanaRpc=https://rpc.example.com",
    "-Pseekervault.solanaRpc.mainnet=https://mainnet.example.com",
    "-Pseekervault.solanaRpc.devnet=https://devnet.example.com/?key=public",
    "-Pseekervault.relayUrl=https://feeds.example.com",
  ]);
  assert.deepEqual(config.features, {
    firebase: true,
    solanaRpc: true,
    solanaRpcNetworks: ["mainnet", "devnet"],
    relayUrl: "https://feeds.example.com",
    discoveryUrl: "https://feeds.example.com",
  });
  assert.match(config.warnings.join(), /DEVNET carries a query string/);
  assert.match(config.googleServices, /sac-example/);
});

test("an explicit discovery origin is passed on its own", () => {
  const config = androidReleaseConfig(
    { SEEKERVAULT_DISCOVERY_URL: "https://catalog.example.com" },
    APP,
  );
  assert.deepEqual(config.gradleArgs, [
    "-Pseekervault.discoveryUrl=https://catalog.example.com",
  ]);
});

test("a Firebase service account is refused: it is a server credential", () => {
  assert.throws(
    () =>
      checkGoogleServices(
        JSON.stringify({
          type: "service_account",
          private_key: "-----BEGIN PRIVATE KEY-----",
          client_email: "x@sac.iam.gserviceaccount.com",
        }),
        APP,
      ),
    /service account/,
  );
  const config = androidReleaseConfig(
    { SEEKERVAULT_GOOGLE_SERVICES_JSON: '{"private_key":"x"}' },
    APP,
  );
  assert.match(config.problems.join(), /service account/);
});

test("a google-services.json for another application or not JSON is refused", () => {
  assert.throws(
    () => checkGoogleServices(client("com.example.other"), APP),
    /no client for/,
  );
  assert.throws(() => checkGoogleServices("{", APP), /not JSON/);
  assert.throws(() => checkGoogleServices("{}", APP), /no project_info/);
});

test("URLs must be https and carry no credentials; relay and discovery are origins", () => {
  assert.throws(
    () => checkUrl("http://rpc.example.com", { origin: false }),
    /not https/,
  );
  assert.throws(
    () => checkUrl("https://user:secret@rpc.example.com", { origin: false }),
    /username or password/,
  );
  assert.throws(
    () => checkUrl("https://feeds.example.com/v1", { origin: true }),
    /must be an origin/,
  );
  assert.throws(() => checkUrl("feeds", { origin: true }), /not a URL/);
  const config = androidReleaseConfig(
    { SEEKERVAULT_RELAY_URL: "https://feeds.example.com/path" },
    APP,
  );
  assert.match(config.problems.join(), /SEEKERVAULT_RELAY_URL/);
});
