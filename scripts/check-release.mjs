/**
 * Prove the release manifest and the repository agree (SEE-168, SEE-182).
 *
 * `release/components.json` is where a component's version is decided. Everything else — the npm
 * manifests, the in-source version constants, the Android build, the Dockerfiles and both
 * workflows — has to follow it, and this check is what says so. It reads only files: it contacts
 * no registry, needs no credential, and publishes nothing, so it runs in PR validation exactly as
 * it runs locally.
 */
import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import { join, resolve } from "node:path";

import { SDK_PACKAGE, publishedDependencies } from "./package-mcp-artifact.mjs";
import { compareVersions, SEMVER } from "./release-plan.mjs";

const ROOT = resolve(import.meta.dirname, "..");
const MANIFEST = "release/components.json";
const REPOSITORY = "SeekerAgentConnect/sac";
const REPOSITORY_URL = `https://github.com/${REPOSITORY}`;
// The Android features a release can be required to have, and the configuration each needs
// (docs/development/releases.md § Android build configuration).
const ANDROID_FEATURES = new Set([
  "firebase",
  "solanaRpc",
  "relayUrl",
  "discoveryUrl",
]);

const failures = [];

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
const ci = read(".github/workflows/ci.yml");
const rootScripts = readJson("package.json").scripts;

check("the public npm scope and image namespace are the agreed ones", () => {
  assert.equal(manifest.repository, REPOSITORY);
  assert.equal(registries.npmScope, "@seekeragentconnect");
  assert.equal(registries.images, "ghcr.io/seekeragentconnect");
  assert.equal(registries.npm, "https://registry.npmjs.org");
});
check("npm is never forced into a repository-wide dry run", () => {
  assert.equal("npmDryRun" in registries, false);
});
check("the pinned npm CLI can use trusted publishing (11.5.1 or later)", () => {
  assert.match(manifest.toolchain.npm, SEMVER);
  assert.ok(compareVersions(manifest.toolchain.npm, "11.5.1") >= 0);
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
      component.npm || component.image || component.android,
      "neither an npm package, an image nor an APK",
    );
  });

  check(`${where} points at a directory that exists`, () => {
    assert.ok(existsSync(join(ROOT, component.directory)), component.directory);
  });

  check(`${where} vendors only components that exist`, () => {
    for (const vendored of component.vendors) {
      assert.ok(
        components.some(({ id }) => id === vendored),
        `unknown component ${vendored}`,
      );
    }
  });

  // These commands gate publication on the released commit, so each must be a real script.
  check(`${where} names checks that exist`, () => {
    assert.ok(
      Array.isArray(component.checks) && component.checks.length > 0,
      "no checks: nothing would stand between a tag and a publication",
    );
    for (const command of component.checks) {
      const match = /^pnpm ([a-z0-9:-]+)$/.exec(command);
      assert.ok(match, `${command} is not \`pnpm <script>\``);
      assert.ok(
        rootScripts[match[1]],
        `package.json has no ${match[1]} script`,
      );
    }
    if (component.toolchains.go) {
      assert.ok(existsSync(join(ROOT, component.toolchains.go)));
    }
  });

  check(`${where} records its history`, () => {
    assert.ok(Array.isArray(component.previousVersions));
    for (const version of component.previousVersions) {
      assert.match(version, SEMVER);
    }
    assert.ok(Array.isArray(component.previousArtifacts));
    for (const previous of component.previousArtifacts) {
      assert.match(
        previous,
        /^[a-z0-9.]+\/\S+:\S+$/,
        `${previous} is not a fully qualified ref`,
      );
    }
  });

  if (component.npm) {
    const packageJson = `${component.directory}/package.json`;
    const npm = readJson(packageJson);

    check(`${packageJson} carries the manifest's name`, () => {
      assert.equal(npm.name, component.npm.name);
      assert.equal(npm.name, `${registries.npmScope}/${component.id}`);
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
      for (const field of ["description", "license", "engines"]) {
        assert.ok(npm[field], `missing ${field}`);
      }
      assert.deepEqual(npm.repository, {
        type: "git",
        url: `git+${REPOSITORY_URL}.git`,
        directory: component.directory,
      });
      assert.equal(
        npm.homepage,
        `${REPOSITORY_URL}/tree/master/${component.directory}#readme`,
      );
      assert.deepEqual(npm.bugs, { url: `${REPOSITORY_URL}/issues` });
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
        publishedDependencies(dependencies, ROOT);
      },
    );
    // Versions are independent (SEE-182): a server is never required to match or exceed the SDK's
    // version. What is required is that the artifact records which SDK it carries, which
    // scripts/package-mcp-artifact.mjs writes and the package tests verify.
    check(`${where} declares the SDK it vendors`, () => {
      assert.equal(
        component.vendors.includes("server-sdk"),
        Boolean(npm.dependencies?.[SDK_PACKAGE]),
      );
    });
  }

  if (component.image) {
    const { image } = component;
    check(`${where} has its own GHCR repository`, () => {
      assert.equal(image.repository, `${registries.images}/${component.id}`);
    });
    check(`${where} names a Dockerfile and context that exist`, () => {
      assert.ok(existsSync(join(ROOT, image.dockerfile)), image.dockerfile);
      assert.ok(existsSync(join(ROOT, image.context ?? ".")));
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
        "org.opencontainers.image.revision",
        "org.opencontainers.image.version",
      ]) {
        assert.ok(dockerfile.includes(label), `no ${label} label`);
      }
      // GHCR links a package to its repository through this label, which is also what lets the
      // package inherit the repository's Actions access.
      assert.ok(
        dockerfile.includes(
          `org.opencontainers.image.source="${REPOSITORY_URL}"`,
        ),
        `org.opencontainers.image.source is not ${REPOSITORY_URL}`,
      );
    });
  }

  if (component.android) {
    const { android } = component;
    const gradle = read(`${component.directory}/app/build.gradle.kts`);
    check(`${where} keeps the installed app's application id`, () => {
      assert.ok(
        gradle.includes(`applicationId = "${android.applicationId}"`),
        `build.gradle.kts does not set applicationId = "${android.applicationId}"`,
      );
    });
    check(`${where} is the only place the app's version is written`, () => {
      assert.doesNotMatch(gradle, /versionCode = \d/);
      assert.doesNotMatch(gradle, /versionName = "/);
      assert.ok(gradle.includes("release/components.json"));
    });
    check(`${where} has an increasing versionCode`, () => {
      assert.ok(Number.isInteger(android.versionCode));
      assert.ok(Number.isInteger(android.previousVersionCode));
      assert.ok(
        android.versionCode > android.previousVersionCode,
        `versionCode ${android.versionCode} is not above ${android.previousVersionCode}`,
      );
    });
    check(`${where} names its APK by version`, () => {
      assert.match(android.apk, /^[a-z0-9-]+-<version>\.apk$/);
    });
    check(`${where} requires only features the release knows`, () => {
      for (const feature of android.releaseRequires) {
        assert.ok(ANDROID_FEATURES.has(feature), `unknown feature ${feature}`);
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
}

// --- the release workflow -------------------------------------------------------------------

check(
  ".github/workflows/release.yml publishes only from a tag or a deliberate dispatch",
  () => {
    const triggers = workflow.split("\njobs:")[0];
    assert.doesNotMatch(triggers, /^\s*pull_request/m);
    assert.match(triggers, /tags:\s*\n\s*- "\*-v\*"/);
    assert.match(triggers, /workflow_dispatch:/);
  },
);
check(
  ".github/workflows/release.yml offers exactly the manifest's components",
  () => {
    const block = /component:[\s\S]*?options:\s*\n((?:\s+- .+\n)+)/.exec(
      workflow,
    );
    assert.ok(block, "no component choice input");
    const options = block[1]
      .split("\n")
      .map((line) => line.trim().replace(/^- /, ""))
      .filter(Boolean);
    assert.deepEqual(
      options,
      components.map(({ id }) => id),
    );
  },
);
check(".github/workflows/release.yml is driven by the manifest", () => {
  assert.ok(workflow.includes("scripts/release-plan.mjs"));
  assert.ok(workflow.includes("scripts/release-state.mjs"));
});
check(".github/workflows/release.yml grants write access narrowly", () => {
  // Defaults are read-only; each write scope is granted to exactly one publishing job.
  assert.match(
    workflow.split("\njobs:")[0],
    /permissions:\s*\n\s+contents: read/,
  );
  assert.equal(workflow.match(/id-token: write/g)?.length, 1);
  assert.equal(workflow.match(/packages: write/g)?.length, 1);
});
check(".github/workflows/release.yml needs no Docker Hub credential", () => {
  assert.ok(
    !/DOCKERHUB|docker\.io|registry: docker/.test(workflow),
    "the release workflow still refers to Docker Hub",
  );
});
check(".github/workflows/release.yml never cancels a publication", () => {
  assert.ok(
    !workflow.includes("cancel-in-progress: true"),
    "a group cancels in progress",
  );
});

// --- CI -------------------------------------------------------------------------------------

check(
  ".github/workflows/ci.yml validates pull requests and publishes nothing",
  () => {
    assert.ok(
      /^\s*pull_request:/m.test(ci.split("\njobs:")[0]),
      "ci.yml does not run on pull requests",
    );
    const publishing =
      /npm publish|docker push|push: true|docker\/login-action|secrets\.|id-token|packages: write|contents: write/.exec(
        ci,
      );
    assert.equal(publishing, null, `ci.yml contains ${publishing?.[0]}`);
  },
);

if (failures.length > 0) {
  console.error(`release manifest check failed (${failures.length}):`);
  for (const failure of failures) console.error(`  - ${failure}`);
  process.exit(1);
}
console.log(
  `release manifest check passed for ${components.length} components`,
);
