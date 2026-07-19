import { execFileSync } from "node:child_process";
import fs from "node:fs";

import {
  determineBump,
  getPlatformTag,
  incrementVersion,
} from "./versioning.mjs";

const platformFiles = {
  android: {
    file: "release/versions/android.properties",
    versionPattern: /^(versionName=)(\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?)$/m,
    buildPattern: /^(versionCode=)(\d+)$/m,
  },
  desktop: {
    file: "release/versions/desktop.properties",
    versionPattern: /^(versionName=)(\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?)$/m,
    buildPattern: /^(versionCode=)(\d+)$/m,
  },
  ios: {
    file: "release/versions/ios.xcconfig",
    versionPattern: /^(MARKETING_VERSION=)(\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?)$/m,
    buildPattern: /^(CURRENT_PROJECT_VERSION=)(\d+)$/m,
  },
};

function runGit(args) {
  return execFileSync("git", args, {
    encoding: "utf8",
    stdio: ["ignore", "pipe", "ignore"],
  }).trim();
}

function hasTag(tag) {
  try {
    runGit(["rev-parse", "--verify", `refs/tags/${tag}`]);
    return true;
  } catch {
    return false;
  }
}

function getBaselineTag(platform, version) {
  const candidates = [getPlatformTag(platform, version), `v${version}`];
  return candidates.find(hasTag) ?? null;
}

function getCommitsSinceCurrentVersion(tag) {
  if (tag) {
    return runGit(["log", `${tag}..HEAD`, "--format=%B%x1e"])
      .split("\x1e")
      .map((commit) => commit.trim())
      .filter(Boolean);
  }

  return runGit(["log", "-n", "100", "--format=%B%x1e"])
    .split("\x1e")
    .map((commit) => commit.trim())
    .filter(Boolean);
}

const platform = process.argv.find((arg) => arg.startsWith("--platform="))?.split("=")[1] ?? "android";
const platformConfig = platformFiles[platform];
if (!platformConfig) throw new Error(`Invalid release platform: ${platform}`);

const content = fs.readFileSync(platformConfig.file, "utf8");
const version = content.match(platformConfig.versionPattern)?.[2];
const build = Number(content.match(platformConfig.buildPattern)?.[2]);
if (!version || !Number.isInteger(build)) throw new Error(`Invalid version file: ${platformConfig.file}`);

const requestedBump = process.argv.find((arg) => arg.startsWith("--bump="))?.split("=")[1];
const currentTag = getPlatformTag(platform, version);
const baselineTag = getBaselineTag(platform, version);
const commits = getCommitsSinceCurrentVersion(baselineTag);
const bump = requestedBump ?? determineBump(commits);
if (!["major", "minor", "patch", "none"].includes(bump)) throw new Error(`Invalid bump type: ${bump}`);

const nextVersion = incrementVersion(version, bump);
console.log(`Current version: ${version}`);
console.log(`Platform: ${platform}`);
console.log(`Current tag: ${currentTag}`);
console.log(`Baseline tag: ${baselineTag ?? "history fallback"}`);
console.log(`Commits considered: ${commits.length}`);
console.log(`Recommended bump: ${bump}`);
console.log(`Next version: ${nextVersion}`);
console.log(`Next tag: ${getPlatformTag(platform, nextVersion)}`);

if (process.argv.includes("--dry-run") || bump === "none") process.exit(0);

const updated = content
  .replace(platformConfig.versionPattern, `$1${nextVersion}`)
  .replace(platformConfig.buildPattern, `$1${build + 1}`);
fs.writeFileSync(platformConfig.file, updated);
console.log(`Updated ${platformConfig.file} to ${nextVersion} (build ${build + 1}).`);
