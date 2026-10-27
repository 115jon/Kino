import { execFileSync } from "node:child_process";
import fs from "node:fs";

import {
  determineBump,
  getPlatformTag,
  getReleaseFile,
  incrementVersion,
  readReleaseState,
  writeReleaseState,
} from "./versioning.mjs";

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
const { file } = getReleaseFile(platform);
const content = fs.readFileSync(file, "utf8");
const { version, build } = readReleaseState(platform, content);

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

fs.writeFileSync(file, writeReleaseState(platform, content, { version: nextVersion, build: build + 1 }));
console.log(`Updated ${file} to ${nextVersion} (build ${build + 1}).`);
