/**
 * Which jobs of .github/workflows/release.yml run, for every component (SEE-182).
 *
 * Every component skips at least one of npm-pack, image-build and android-build. GitHub adds an
 * implicit `success()` to any job condition without a status function, and that `success()` fails
 * on a skipped ancestor — transitively, so a publisher can be skipped even after the gate it needs
 * succeeded (https://github.com/actions/runner/issues/2205). This reads the job graph and the
 * job-level conditions out of the workflow and evaluates them with those rules, so a publisher
 * that would silently not run, or a GitHub Release recorded without its publications, fails here
 * rather than in a live release. No YAML library: the reader understands the shapes the workflow
 * uses (`needs:` as a name or a flow list, `if:` on one line or as a `>-` block) and fails loudly
 * on anything else.
 */
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join, resolve } from "node:path";
import test from "node:test";

import { loadManifest, releasePlan } from "./release-plan.mjs";

const ROOT = resolve(import.meta.dirname, "..");
const COMMIT = "0123456789abcdef0123456789abcdef01234567";
const BUILDS = ["npm-pack", "image-build", "android-build"];
const PUBLISHERS = {
  has_npm: "npm-publish",
  has_image: "image-publish",
  has_android: "android-publish",
};
const STATUS_FUNCTION = /\b(?:success|failure|always|cancelled)\(\)/;

/** The jobs, in file order, each with its `needs` and its job-level `if` (or null). */
export function readJobs(text) {
  const body = text.split(/^jobs:\n/m)[1];
  assert.ok(body, "the workflow has no jobs");
  const jobs = new Map();
  let current = null;
  const lines = body.split("\n");
  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index];
    const job = /^ {2}([a-z0-9-]+):\s*$/.exec(line);
    if (job) {
      current = { id: job[1], needs: [], condition: null };
      jobs.set(current.id, current);
      continue;
    }
    if (!current) continue;
    const needs = /^ {4}needs:\s*(.+)$/.exec(line);
    if (needs) {
      const value = needs[1].trim();
      current.needs = value.startsWith("[")
        ? value
            .slice(1, -1)
            .split(",")
            .map((name) => name.trim())
        : [value];
      continue;
    }
    const condition = /^ {4}if:\s*(.*)$/.exec(line);
    if (condition) {
      let value = condition[1].trim();
      if (value === ">-" || value === ">") {
        const block = [];
        while (/^ {6}\S/.test(lines[index + 1] ?? "")) {
          block.push(lines[(index += 1)].trim());
        }
        value = block.join(" ");
      }
      assert.ok(value, `${current.id} has an if: this reader does not follow`);
      current.condition = value.replace(/^\$\{\{\s*([\s\S]*?)\s*\}\}$/, "$1");
    }
  }
  for (const { id, needs } of jobs.values()) {
    for (const need of needs) {
      assert.ok(jobs.has(need), `${id} needs an unknown job ${need}`);
    }
  }
  return jobs;
}

/** Every job `id` depends on, directly or not. */
function ancestors(jobs, id, seen = new Set()) {
  for (const need of jobs.get(id).needs) {
    if (seen.has(need)) continue;
    seen.add(need);
    ancestors(jobs, need, seen);
  }
  return seen;
}

/**
 * Evaluate a job condition the way GitHub does for the subset the workflow uses: `needs.<job>.
 * result`, `needs.<job>.outputs.<name>`, the four status functions, `==`, `!=`, `!`, `&&`, `||`,
 * parentheses and single-quoted strings. Without a status function it is `success() && (…)`.
 */
function evaluate(condition, { jobs, id, results, outputs }) {
  const expression = condition ?? "success()";
  const wrapped = STATUS_FUNCTION.test(expression)
    ? expression
    : `success() && (${expression})`;
  const before = [...ancestors(jobs, id)];
  const needs = Object.fromEntries(
    jobs
      .get(id)
      .needs.map((need) => [
        need,
        { result: results.get(need), outputs: outputs.get(need) ?? {} },
      ]),
  );
  const source = wrapped.replace(
    /needs\.([a-z0-9-]+)\.(result|outputs\.([a-z0-9_]+))/g,
    (_, job, field, output) => {
      assert.ok(job in needs, `${id} reads ${job}, which it does not need`);
      return output
        ? `(needs[${JSON.stringify(job)}].outputs[${JSON.stringify(output)}] ?? "")`
        : `needs[${JSON.stringify(job)}].result`;
    },
  );
  assert.doesNotMatch(
    source.replace(/'[^']*'/g, "''"),
    /[a-zA-Z_]+\.[a-zA-Z_]/,
    `${id}: the condition reads a context this test does not model: ${condition}`,
  );
  const functions = {
    success: () => before.every((job) => results.get(job) === "success"),
    failure: () => before.some((job) => results.get(job) === "failure"),
    always: () => true,
    cancelled: () => false,
  };
  const javascript = source.replace(/==/g, "===").replace(/!===/g, "!==");
  return Boolean(
    new Function(
      "needs",
      "success",
      "failure",
      "always",
      "cancelled",
      `return (${javascript});`,
    )(
      needs,
      functions.success,
      functions.failure,
      functions.always,
      functions.cancelled,
    ),
  );
}

/**
 * Run the graph: each job runs if its condition holds once its needs are done, and then ends with
 * `success` unless `fail` names it. `resolve`'s outputs are the planner's for `environment`.
 */
export function simulate(jobs, { outputs: resolved, fail = [] }) {
  const results = new Map();
  const outputs = new Map([
    [
      "resolve",
      Object.fromEntries(
        Object.entries(resolved).map(([key, value]) => [key, String(value)]),
      ),
    ],
  ]);
  for (const id of jobs.keys()) {
    for (const need of jobs.get(id).needs) {
      assert.ok(results.has(need), `${id} is declared before its need ${need}`);
    }
    const runs = evaluate(jobs.get(id).condition, {
      jobs,
      id,
      results,
      outputs,
    });
    results.set(
      id,
      !runs ? "skipped" : fail.includes(id) ? "failure" : "success",
    );
  }
  return results;
}

const jobs = readJobs(
  readFileSync(join(ROOT, ".github/workflows/release.yml"), "utf8"),
);
const manifest = loadManifest();
const planFor = (id, environment = {}) => {
  const { version } = manifest.components.find((entry) => entry.id === id);
  return releasePlan(
    { COMMIT, TAG: `${id}-v${version}`, ...environment },
    manifest,
  );
};
const ran = (results) =>
  [...results].filter(([, result]) => result !== "skipped").map(([id]) => id);

test("the reader finds the release graph", () => {
  assert.deepEqual(
    [...jobs.keys()],
    [
      "resolve",
      "checks",
      ...BUILDS,
      "gate",
      "npm-publish",
      "image-publish",
      "android-publish",
      "github-release",
    ],
  );
  assert.deepEqual(jobs.get("gate").needs, ["resolve", "checks", ...BUILDS]);
});

test("the evaluator reproduces the skipped-ancestor rule it guards against", () => {
  // The publisher condition this workflow used to have: no status function, so it inherits
  // `success()`, which a skipped build branch fails even though the gate succeeded.
  const graph = readJobs(`jobs:
  resolve:
    runs-on: x
  build:
    needs: resolve
    if: needs.resolve.outputs.has_build == 'true'
  gate:
    needs: [resolve, build]
    if: \${{ !cancelled() && !failure() }}
  publish:
    needs: [resolve, gate]
    if: needs.resolve.outputs.has_publish == 'true'
  explicit:
    needs: [resolve, gate]
    if: >-
      !cancelled() && needs.gate.result == 'success' &&
      needs.resolve.outputs.has_publish == 'true'
`);
  const results = simulate(graph, {
    outputs: { has_build: false, has_publish: true },
  });
  assert.equal(results.get("build"), "skipped");
  assert.equal(results.get("gate"), "success");
  assert.equal(results.get("publish"), "skipped");
  assert.equal(results.get("explicit"), "success");
});

test("every job after a build that can be skipped states its own status condition", () => {
  for (const [id, { condition }] of jobs) {
    if (!BUILDS.some((build) => ancestors(jobs, id).has(build))) continue;
    assert.match(
      condition ?? "",
      STATUS_FUNCTION,
      `${id} has no status function, so a skipped build branch would skip it`,
    );
  }
});

for (const { id } of manifest.components) {
  test(`a real ${id} release runs exactly its own builds and publishers, then records them`, () => {
    const outputs = planFor(id);
    const results = simulate(jobs, { outputs });
    const expected = [
      "resolve",
      "checks",
      ...(outputs.has_npm ? ["npm-pack"] : []),
      ...(outputs.has_image ? ["image-build"] : []),
      ...(outputs.has_android ? ["android-build"] : []),
      "gate",
      ...Object.entries(PUBLISHERS)
        .filter(([flag]) => outputs[flag])
        .map(([, publisher]) => publisher),
      ...(outputs.has_android ? [] : ["github-release"]),
    ];
    assert.deepEqual(ran(results), expected);
    // The point of the regression: at least one build branch is skipped, and still nothing the
    // component selects is.
    assert.ok(BUILDS.some((build) => results.get(build) === "skipped"));
  });

  test(`a ${id} dry run stops before the gate`, () => {
    const results = simulate(jobs, {
      outputs: planFor(id, {
        TAG: "",
        INPUT_COMPONENT: id,
        INPUT_CHANNEL: "release",
      }),
    });
    for (const job of [
      "gate",
      ...Object.values(PUBLISHERS),
      "github-release",
    ]) {
      assert.equal(results.get(job), "skipped", job);
    }
  });
}

test("a failed build or check publishes nothing", () => {
  for (const failed of ["checks", "npm-pack", "image-build"]) {
    const results = simulate(jobs, {
      outputs: planFor("mcp-server"),
      fail: [failed],
    });
    for (const job of [
      "gate",
      "npm-publish",
      "image-publish",
      "github-release",
    ]) {
      assert.equal(
        results.get(job),
        "skipped",
        `${job} after ${failed} failed`,
      );
    }
  }
  const android = simulate(jobs, {
    outputs: planFor("android"),
    fail: ["android-build"],
  });
  assert.equal(android.get("android-publish"), "skipped");
});

test("the GitHub Release waits for every publisher the component selects", () => {
  for (const failed of ["npm-publish", "image-publish"]) {
    const results = simulate(jobs, {
      outputs: planFor("mcp-server"),
      fail: [failed],
    });
    assert.equal(results.get("github-release"), "skipped", failed);
  }
  const image = simulate(jobs, { outputs: planFor("gateway") });
  assert.equal(image.get("npm-publish"), "skipped");
  assert.equal(image.get("github-release"), "success");
});

test("a development image build publishes the image and records no release", () => {
  const results = simulate(jobs, {
    outputs: releasePlan(
      {
        COMMIT,
        INPUT_COMPONENT: "gateway",
        INPUT_CHANNEL: "development",
        INPUT_DRY_RUN: "false",
      },
      manifest,
    ),
  });
  assert.equal(results.get("image-publish"), "success");
  assert.equal(results.get("npm-publish"), "skipped");
  assert.equal(results.get("github-release"), "skipped");
});
