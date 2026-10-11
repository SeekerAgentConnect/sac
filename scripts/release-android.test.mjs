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
  const config = androidReleaseConfig({ REQUIRE: "firebase,gatewayUrl" }, APP);
  assert.deepEqual(config.problems, []);
  assert.deepEqual(config.gradleArgs, []);
  assert.equal(config.googleServices, null);
  assert.match(config.warnings.join(), /requires firebase, gatewayUrl/);
});

test("a real release stops when a required feature is missing", () => {
  const config = androidReleaseConfig(
    { REQUIRE: "firebase,gatewayUrl", RELEASE: "true" },
    APP,
  );
  assert.match(config.problems.join(), /requires firebase, gatewayUrl/);
});

test("the full official configuration is one gateway origin and Firebase", () => {
  const config = androidReleaseConfig(
    {
      SEEKERVAULT_GOOGLE_SERVICES_JSON: client(),
      SEEKERVAULT_GATEWAY_URL: "https://feeds.example.com/",
      REQUIRE: "firebase,gatewayUrl",
      RELEASE: "true",
    },
    APP,
  );
  assert.deepEqual(config.problems, []);
  assert.deepEqual(config.warnings, []);
  assert.deepEqual(config.gradleArgs, [
    "-Pseekervault.gatewayUrl=https://feeds.example.com",
  ]);
  assert.deepEqual(config.features, {
    firebase: true,
    gatewayUrl: "https://feeds.example.com",
  });
  assert.match(config.googleServices, /sac-example/);
});

test("no RPC endpoint or swap fee reaches the APK, and a leftover variable is said once (SEE-185)", () => {
  const config = androidReleaseConfig(
    {
      SEEKERVAULT_GATEWAY_URL: "https://feeds.example.com",
      SEEKERVAULT_SOLANA_RPC_MAINNET: "https://mainnet.example.com",
      SEEKERVAULT_RELAY_URL: "https://feeds.example.com",
      SEEKERVAULT_SWAP_FEE_BPS: "20",
    },
    APP,
  );
  assert.deepEqual(config.problems, []);
  assert.deepEqual(config.gradleArgs, [
    "-Pseekervault.gatewayUrl=https://feeds.example.com",
  ]);
  assert.equal(config.warnings.length, 3);
  assert.match(
    config.warnings.join(),
    /SEEKERVAULT_SOLANA_RPC_MAINNET is retired/,
  );
  assert.match(config.warnings.join(), /SEEKERVAULT_SWAP_FEE_BPS is retired/);
  assert.ok(!config.gradleArgs.join().includes("solanaRpc"));
  assert.ok(!config.gradleArgs.join().includes("swapFee"));
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

test("URLs must be https and carry no credentials; the gateway is an origin", () => {
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
    { SEEKERVAULT_GATEWAY_URL: "https://feeds.example.com/path" },
    APP,
  );
  assert.match(config.problems.join(), /SEEKERVAULT_GATEWAY_URL/);
});
