import assert from "node:assert/strict";
import test from "node:test";

import {
  compareVersions,
  determineBump,
  getPlatformTag,
  incrementVersion,
} from "./versioning.mjs";
import { createReleaseManifest } from "./release-manifest.mjs";

test("detects a breaking change footer in the full commit message", () => {
  assert.equal(
    determineBump([
      "fix(player): preserve subtitle styles\n\nBREAKING CHANGE: player settings were renamed",
    ]),
    "major",
  );
});

test("detects the hyphenated breaking change footer", () => {
  assert.equal(
    determineBump(["fix: update api\n\nBREAKING-CHANGE: response fields changed"]),
    "major",
  );
});

test("detects a breaking change marker in a commit subject", () => {
  assert.equal(determineBump(["fix(player)!: change player settings"]), "major");
});

test("does not treat ordinary mentions of breaking as breaking changes", () => {
  assert.equal(determineBump(["docs: explain breaking changes in releases"]), "patch");
  assert.equal(
    determineBump(["fix: update docs\n\nThis is a BREAKING CHANGE example only"]),
    "patch",
  );
});

test("uses the highest bump across all commits", () => {
  assert.equal(
    determineBump(["fix: patch", "feat: feature", "chore: maintenance"]),
    "minor",
  );
});

test("increments stable and prerelease versions without producing invalid numbers", () => {
  assert.equal(incrementVersion("0.2.4", "patch"), "0.2.5");
  assert.equal(incrementVersion("0.2.4-beta.1", "patch"), "0.2.4");
  assert.equal(incrementVersion("0.2.4-beta.1", "minor"), "0.3.0");
});

test("uses platform-specific release tags", () => {
  assert.equal(getPlatformTag("android", "0.3.0"), "android-v0.3.0");
  assert.equal(getPlatformTag("desktop", "0.2.5"), "desktop-v0.2.5");
  assert.equal(getPlatformTag("ios", "0.2.5"), "ios-v0.2.5");
});

test("compares semantic versions including prereleases", () => {
  assert.equal(compareVersions("0.3.0", "0.2.99"), 1);
  assert.equal(compareVersions("0.3.0-beta.2", "0.3.0-beta.10"), -1);
  assert.equal(compareVersions("0.3.0", "0.3.0-rc.1"), 1);
});

test("creates a platform-specific release manifest", () => {
  assert.deepEqual(
    createReleaseManifest({
      platform: "android",
      version: "0.3.0",
      versionCode: 97,
      assets: [{ name: "Kino-Android-0.3.0.apk", sizeBytes: 123, sha256: "abc" }],
    }),
    {
      schemaVersion: 1,
      product: "kino",
      platform: "android",
      channel: "stable",
      version: "0.3.0",
      versionCode: 97,
      mandatory: false,
      assets: [{ name: "Kino-Android-0.3.0.apk", sizeBytes: 123, sha256: "abc" }],
    },
  );
});
