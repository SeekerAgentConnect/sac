/**
 * The self-hosting guide against the files it describes (SAW-038).
 *
 * A deployment guide is only as good as its smallest detail: a variable renamed here and not there
 * costs an operator an evening. These checks read `docs/guides/self-hosting.md` and hold every
 * name in it to the shipped files — the settings, the Compose services and profiles, the files each
 * command names, the ports, the paths inside the image, and every relative link on the page.
 *
 * They live in the sidecar package because that is where this workspace's Node tests run;
 * `stage-boundary.test.ts` reads repository-wide files for the same reason.
 */
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, it } from "node:test";
import { fileURLToPath } from "node:url";

const ROOT = fileURLToPath(new URL("../../", import.meta.url));
const GUIDE_PATH = "docs/guides/self-hosting.md";
const GUIDE_DIRECTORY = "docs/guides";
const GATEWAY = join(ROOT, "gateway");

const guide = readFileSync(join(ROOT, GUIDE_PATH), "utf8");
const composeFiles = [
  "compose.yaml",
  "compose.public.yaml",
  "compose.oauth.yaml",
].map((name) => readFileSync(join(GATEWAY, name), "utf8"));
const compose = composeFiles.join("\n");

/** The services `compose.yaml` declares, and the profiles it puts them behind. */
const SERVICES = ["sidecar", "gateway", "test-agent"];
const PROFILES = ["agent"];

/** Shell commands, taken from the guide's fenced blocks. */
function commandLines(): string[] {
  return [...guide.matchAll(/```(?:sh|console|bash)\n([\s\S]*?)```/g)]
    .flatMap((block) => (block[1] ?? "").split("\n"))
    .map((line) => line.replace(/^\$ /, "").trim())
    .filter((line) => line !== "");
}

describe("the self-hosting guide", () => {
  it("names only settings the deployment actually reads", () => {
    // The deployment's own namespace, which is what an operator types into gateway/.env. Error
    // codes and protocol words are not settings and are not checked here.
    const configured = new Set(
      [
        readFileSync(join(GATEWAY, ".env.example"), "utf8"),
        readFileSync(join(ROOT, ".env.example"), "utf8"),
        compose,
      ]
        .join("\n")
        .match(
          /\b(?:MCP|SIDECAR|GATEWAY|ACME|PHONE|AGENT|SOLANA|FCM|REQUEST|PAIRING|DATABASE|LIVE)_[A-Z0-9_]+\b/g,
        ) ?? [],
    );
    assert.ok(configured.size > 10, "found the deployment's settings");

    const named = new Set(
      guide.match(
        /\b(?:MCP|SIDECAR|GATEWAY|ACME|PHONE|AGENT|SOLANA|FCM|REQUEST|PAIRING|DATABASE|LIVE)_[A-Z0-9_]+\b/g,
      ) ?? [],
    );
    assert.ok(named.size > 10, "the guide names the settings");
    assert.deepEqual(
      [...named].filter((name) => !configured.has(name)).sort(),
      [],
      "every setting the guide names is one the stack reads",
    );
  });

  it("runs only Compose files, services, and profiles that exist", () => {
    const lines = commandLines().filter((line) =>
      line.startsWith("docker compose"),
    );
    assert.ok(lines.length > 10, "found the guide's compose commands");

    for (const line of lines) {
      const tokens = line.split(/\s+/).slice(2);
      // Compose's own options come before the subcommand; -f after it is somebody else's flag,
      // such as `logs -f`. Walk the leading options, then read the subcommand and its arguments.
      let index = 0;
      while (tokens[index]?.startsWith("-") === true) {
        const option = tokens[index] ?? "";
        const value = tokens[index + 1] ?? "";
        if (option === "-f" || option === "--file") {
          assert.ok(
            existsSync(join(GATEWAY, value)),
            `${line}: gateway/${value} does not exist`,
          );
        }
        if (option === "--profile") {
          assert.ok(PROFILES.includes(value), `${line}: no such profile`);
        }
        index += option.includes("=") ? 1 : 2;
      }
      const subcommand = tokens[index] ?? "";
      // The subcommand's own options, and the ones that take a value: `run --user root` names a
      // user, not a service. Skipping those values is what leaves the service itself.
      const takesValue = new Set([
        "--user",
        "-u",
        "--entrypoint",
        "--workdir",
        "-w",
        "--env",
        "-e",
        "--volume",
        "-v",
        "--publish",
        "-p",
        "--label",
        "-l",
        "--name",
      ]);
      let cursor = index + 1;
      while (tokens[cursor]?.startsWith("-") === true) {
        cursor += takesValue.has(tokens[cursor] ?? "") ? 2 : 1;
      }
      const argument = tokens[cursor] ?? "";

      if (
        ["exec", "run", "logs", "stop", "start", "restart"].includes(subcommand)
      ) {
        assert.ok(
          SERVICES.includes(argument),
          `${line}: ${argument} is not a service of this stack`,
        );
      }
      if (subcommand === "cp") {
        for (const token of tokens.slice(index + 1)) {
          const service = /^([a-z][a-z-]*):\//.exec(token)?.[1];
          if (service !== undefined) {
            assert.ok(SERVICES.includes(service), `${line}: no such service`);
          }
        }
      }
      if (subcommand === "--profile" || subcommand === "-f") {
        assert.fail(`${line}: unparsed option`);
      }
    }
  });

  it("names the ports the stack actually publishes", () => {
    const base = composeFiles[0] ?? "";
    const publicOverlay = composeFiles[1] ?? "";
    // The host port forwards to the gateway's 8081, never to the sidecar's 8080.
    assert.match(
      base,
      /\$\{GATEWAY_BIND:-127\.0\.0\.1\}:\$\{GATEWAY_PORT:-8080\}:8081/,
    );
    assert.match(guide, /`GATEWAY_PORT` \(default `8080`\) is the host port/);
    assert.match(guide, /127\.0\.0\.1:8080\/healthz/);
    // The private endpoint inside the namespace, which the guide tells operators to reach.
    assert.match(guide, /127\.0\.0\.1:8081\/healthz/);
    // The public overlay's two ports, and nothing else.
    assert.match(publicOverlay, /- "80:80"/);
    assert.match(publicOverlay, /- "443:443"/);
    assert.match(guide, /\*\*Open exactly two ports\.\*\* 80 and 443/);
    assert.match(
      guide,
      /\*\*Do not open 8080\*\*/,
      "the guide says which port not to open",
    );
  });

  it("uses the paths the image and the stack really have", () => {
    const dockerfile = readFileSync(
      join(ROOT, "mcp-server/Dockerfile"),
      "utf8",
    );
    // `docker compose exec sidecar node mcp-server/dist/cli.js pair` is relative to WORKDIR.
    assert.match(dockerfile, /^WORKDIR \/app$/m);
    assert.match(dockerfile, /mcp-server\/dist\/cli\.js/);
    assert.ok(
      existsSync(join(ROOT, "mcp-server/src/pairing/cli.ts")),
      "the pairing CLI the guide runs is a real entry point",
    );
    for (const command of commandLines()) {
      const path = /node (mcp-server\/dist\/cli\.js)/.exec(command)?.[1];
      if (path !== undefined) {
        const source = path.replace("dist/", "src/").replace(/\.js$/, ".ts");
        assert.ok(existsSync(join(ROOT, source)), `${command}: no ${source}`);
      }
    }
    // The database path the guide backs up is the one compose gives the sidecar, and the volume
    // name is the project name plus the volume's.
    assert.match(compose, /DATABASE_PATH: \/data\/sidecar\.db/);
    assert.match(compose, /^name: seeker-agent-wallet$/m);
    assert.match(compose, /^ {2}sidecar-data:$/m);
    assert.match(guide, /seeker-agent-wallet_sidecar-data/);
  });

  it("links only to files that exist", () => {
    const links = [...guide.matchAll(/\]\((\.[^)#]*)(?:#[^)]*)?\)/g)].map(
      (match) => match[1] ?? "",
    );
    assert.ok(links.length > 10, "found the guide's links");
    const missing = links.filter(
      (link) => !existsSync(join(ROOT, GUIDE_DIRECTORY, link)),
    );
    assert.deepEqual(missing, []);
  });

  it("links only to headings that exist on its own page", () => {
    const anchors = new Set(
      [...guide.matchAll(/^#{2,3} (.+)$/gm)].map((match) =>
        (match[1] ?? "")
          .toLowerCase()
          .replace(/[^a-z0-9 -]/g, "")
          .replace(/ /g, "-"),
      ),
    );
    const used = [...guide.matchAll(/\]\(#([a-z0-9-]+)\)/g)].map(
      (match) => match[1] ?? "",
    );
    assert.ok(used.length > 5, "found the guide's own-page links");
    assert.deepEqual(
      used.filter((anchor) => !anchors.has(anchor)),
      [],
    );
  });
});
