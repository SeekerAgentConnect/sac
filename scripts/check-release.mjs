/**
 * Prove the release manifest and the repository agree (SEE-168).
 *
 * `release/components.json` is where a component's version is decided. Everything else — the npm
 * manifests, the Dockerfiles, the release workflow — has to follow it, and this check is what says
 * so. It reads only files: it contacts no registry, needs no credential, and publishes nothing, so
 * it runs in PR validation exactly as it runs locally.
 */
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { join, resolve } from "node:path";

import { SDK_PACKAGE, publishedDependencies } from "./package-mcp-artifact.mjs";

const ROOT = resolve(import.meta.dirname, "..");
const MANIFEST = "release/components.json";
const SEMVER =
  /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-(?:rc|alpha|beta)\.(?:0|[1-9]\d*))?$/;

const failures = [];

/**
 * Semantic version ordering, enough of it for the comparison this file makes: release before
 * prerelease at the same triple, and prerelease identifiers compared left to right, numerically
 * where both sides are numeric.
 */
function compareVersions(left, right) {
  const split = (version) => {
    const [core, prerelease] = version.split("-");
    return [
      core.split(".").map(Number),
      prerelease ? prerelease.split(".") : null,
    ];
  };
  const [leftCore, leftPre] = split(left);
  const [rightCore, rightPre] = split(right);
  for (let index = 0; index < 3; index += 1) {
    if (leftCore[index] !== rightCore[index])
      return leftCore[index] - rightCore[index];
  }
  if (leftPre === null && rightPre === null) return 0;
  if (leftPre === null) return 1;
  if (rightPre === null) return -1;
  for (
    let index = 0;
    index < Math.max(leftPre.length, rightPre.length);
    index += 1
  ) {
    const a = leftPre[index];
    const b = rightPre[index];
    if (a === undefined) return -1;
    if (b === undefined) return 1;
    if (a === b) continue;
    const numeric = /^\d+$/.test(a) && /^\d+$/.test(b);
    return numeric ? Number(a) - Number(b) : a < b ? -1 : 1;
  }
  return 0;
}

function check(description, assertion) {
  try {
    assertion();
  } catch (error) {
    failures.push(`${description}: ${error.message}`);
  }
}

const read = (path) => readFileSync(join(ROOT, path), "utf8");
const readJson = (path) => JSON.parse(read(path));

const manifest = readJson(MANIFEST);
const { registries, components } = manifest;
const workflow = read(".github/workflows/release.yml");

check("the npm scope is the one the release token can write", () => {
  assert.equal(registries.npmScope, "@seeker_agent_connect");
});
check("the GHCR namespace is the agreed one", () => {
  assert.equal(registries.ghcr, "ghcr.io/seekeragentconnect");
});

const identifiers = new Set();
for (const component of components) {
  const where = `${MANIFEST} [${component.id}]`;

  check(`${where} has a unique identifier`, () => {
    assert.ok(
      !identifiers.has(component.id),
      `${component.id} is declared twice`,
    );
    identifiers.add(component.id);
    assert.match(component.id, /^[a-z0-9]+(?:-[a-z0-9]+)*$/);
  });

  check(`${where} carries a semantic version`, () => {
    assert.match(component.version, SEMVER);
  });

  check(`${where} publishes at least one artifact`, () => {
    assert.ok(
      component.npm || component.image,
      "neither an npm package nor an image",
    );
  });

  check(`${where} points at a directory that exists`, () => {
    assert.ok(existsSync(join(ROOT, component.directory)), component.directory);
  });

  check(`${where} declares dependencies that exist`, () => {
    for (const dependency of component.dependsOn) {
      assert.ok(
        components.some(({ id }) => id === dependency),
        `unknown component ${dependency}`,
      );
    }
  });

  // The release workflow is driven by this file, but the tag that starts it is typed by a human.
  // A component nobody can tag is a component nobody can release.
  check(`${where} is reachable from the release workflow`, () => {
    assert.ok(
      workflow.includes(`${component.id}-v`) || workflow.includes(MANIFEST),
      "the workflow neither matches its tag nor reads the manifest",
    );
  });

  if (component.npm) {
    const packageJson = `${component.directory}/package.json`;
    const npm = readJson(packageJson);

    check(`${packageJson} carries the manifest's name`, () => {
      assert.equal(npm.name, component.npm.name);
      assert.ok(
        npm.name.startsWith(`${registries.npmScope}/`),
        `${npm.name} is outside ${registries.npmScope}`,
      );
    });
    check(`${packageJson} carries the manifest's version`, () => {
      assert.equal(npm.version, component.version);
    });
    check(`${packageJson} is publishable`, () => {
      assert.notEqual(npm.private, true, "still marked private");
      assert.equal(
        npm.publishConfig?.access,
        "public",
        "publishConfig.access must be public, or npm makes a scoped package private",
      );
    });
    check(`${packageJson} tells a consumer where it came from`, () => {
      for (const field of [
        "description",
        "license",
        "repository",
        "homepage",
        "bugs",
        "engines",
      ]) {
        assert.ok(npm[field], `missing ${field}`);
      }
      assert.equal(npm.repository.directory, component.directory);
    });
    check(`${packageJson} ships an explicit file allowlist`, () => {
      assert.ok(
        Array.isArray(npm.files) && npm.files.length > 0,
        "no files allowlist",
      );
      assert.ok(
        npm.files.includes("README.md") && npm.files.includes("LICENSE"),
      );
    });
    check(
      `${component.directory} ships the documents the allowlist promises`,
      () => {
        for (const name of ["README.md", "LICENSE"]) {
          assert.ok(
            existsSync(join(ROOT, component.directory, name)),
            `missing ${name}`,
          );
        }
      },
    );
    // A package packed from its own directory publishes this manifest verbatim, so every
    // specifier in it has to be one a registry can resolve. A package packed from a staged
    // directory is allowed the two specifiers its build removes — the vendored SDK, and a
    // `catalog:` the workspace pins — and this proves the build can actually resolve them rather
    // than assuming it.
    const staged = component.npm.packDirectory !== component.directory;
    check(
      `${packageJson} declares only specifiers the published artifact can resolve`,
      () => {
        const dependencies = { ...npm.dependencies };
        if (staged) {
          assert.equal(
            dependencies[SDK_PACKAGE],
            "workspace:*",
            `${component.id} vendors the SDK, so it must depend on it as a workspace package`,
          );
        }
        // Throws on anything a registry could not resolve, and resolves the catalog the way the
        // staging step will.
        publishedDependencies(dependencies, ROOT);
      },
    );

    // A vendored SDK means a dependent's published bytes contain the SDK's. Releasing the SDK
    // without releasing them leaves consumers on the old copy with no way to tell — so a
    // dependent may never be *behind* the SDK it vendors. It may be ahead: a server with a fix of
    // its own should not have to drag an unchanged SDK release along with it.
    if (component.dependsOn.includes("server-sdk")) {
      check(`${where} is not behind the SDK it vendors`, () => {
        const sdk = components.find(({ id }) => id === "server-sdk");
        assert.ok(
          compareVersions(component.version, sdk.version) >= 0,
          `vendors server-sdk ${sdk.version} but is versioned ${component.version}; ` +
            "an SDK release has to re-release its dependents",
        );
      });
    }
  }

  if (component.image) {
    const { image } = component;
    check(`${where} names a Dockerfile that exists`, () => {
      assert.ok(existsSync(join(ROOT, image.dockerfile)), image.dockerfile);
    });
    check(`${where} names an image a registry accepts`, () => {
      assert.match(
        image.name,
        /^[a-z0-9]+(?:[._-][a-z0-9]+)*$/,
        "image names are lowercase",
      );
    });
    check(`${where} supports both published platforms`, () => {
      assert.deepEqual([...image.platforms].sort(), [
        "linux/amd64",
        "linux/arm64",
      ]);
    });
    check(`${image.dockerfile} records where it came from`, () => {
      const dockerfile = read(image.dockerfile);
      for (const label of [
        "org.opencontainers.image.source",
        "org.opencontainers.image.revision",
        "org.opencontainers.image.version",
      ]) {
        assert.ok(dockerfile.includes(label), `no ${label} label`);
      }
    });
  }

  // A server that reports its own version has to report the released one. `--version` is what an
  // operator reads off a running container, so a stale constant there is a wrong answer to the
  // only question the flag exists for.
  check(`${where} states the released version in its source`, () => {
    for (const file of component.versionConstants ?? []) {
      assert.ok(
        read(file).includes(`VERSION = "${component.version}"`),
        `${file} does not declare VERSION = "${component.version}"`,
      );
    }
  });

  check(`${where} records what it replaces`, () => {
    assert.ok(
      Array.isArray(component.previousArtifacts),
      "previousArtifacts must be a list",
    );
    for (const previous of component.previousArtifacts) {
      assert.match(
        previous,
        /^[a-z0-9.]+\/\S+:\S+$/,
        `${previous} is not a fully qualified ref`,
      );
    }
  });
}

// The workflow must not be able to publish from anything but a tag: a pull request that could
// publish is the failure mode the ticket names by name.
check(
  ".github/workflows/release.yml publishes only from a tag or a deliberate dispatch",
  () => {
    assert.doesNotMatch(
      workflow.split("jobs:")[0],
      /^\s*pull_request:/m,
      "the release workflow reacts to pull requests",
    );
    assert.match(workflow, /tags:/, "the release workflow is not tag-driven");
  },
);

check(".github/workflows/ci.yml publishes nothing", () => {
  const ci = read(".github/workflows/ci.yml");
  assert.doesNotMatch(ci, /npm publish|docker push|docker\/build-push-action/);
});

if (failures.length > 0) {
  console.error(`release manifest check failed (${failures.length}):`);
  for (const failure of failures) console.error(`  - ${failure}`);
  process.exit(1);
}
console.log(
  `release manifest check passed for ${components.length} components`,
);
