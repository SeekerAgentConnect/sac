/**
 * Nx Release version actions for every SAC component (SEE-187).
 *
 * Nx decides the version from Conventional Commits and the `<component>-v<version>` tags.
 * This writes that version into the manifests the rest of the repository already reads.
 * Publishing is not a version action: `.github/workflows/release.yml` does it, and it is not cached.
 */
const { VersionActions } = require("nx/release");

class SacVersionActions extends VersionActions {
  validManifestFilenames = null;
  excludeManifestsFromFormatting = true;

  async readCurrentVersionFromSourceManifest() {
    const { readFileSync } = require("node:fs");
    const { join } = require("node:path");
    const manifest = JSON.parse(
      readFileSync(join(process.cwd(), "release/components.json"), "utf8"),
    );
    const component = manifest.components.find(
      (entry) => entry.id === this.projectGraphNode.name,
    );
    if (!component) return null;
    return {
      currentVersion: component.version,
      manifestPath: "release/components.json",
    };
  }

  async readCurrentVersionFromRegistry() {
    return null;
  }

  async readCurrentVersionOfDependency() {
    return { currentVersion: null, dependencyCollection: null };
  }

  async updateProjectDependencies() {
    return [];
  }

  async updateProjectVersion(tree, newVersion) {
    const { syncProjectVersion } = await import("./version-sync.mjs");
    const io = {
      read: (path) => {
        const value = tree.read(path);
        if (value == null) {
          throw new Error(`${path} is not in the release tree`);
        }
        return value.toString();
      },
      write: (path, contents) => tree.write(path, contents),
    };
    const result = syncProjectVersion(
      io,
      this.projectGraphNode.name,
      newVersion,
    );
    if (result.unchanged) {
      return [
        `${this.projectGraphNode.name} is already ${newVersion}; versionCode was not advanced`,
      ];
    }
    const code =
      result.versionCode == null ? "" : ` (versionCode ${result.versionCode})`;
    return [
      `${result.from} -> ${result.to}${code}`,
      ...result.changed.map((file) => `wrote ${file}`),
    ];
  }
}

module.exports = SacVersionActions;
