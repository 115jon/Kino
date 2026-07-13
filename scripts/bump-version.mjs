import { execFileSync } from "node:child_process";
import fs from "node:fs";

const versionFile = "iosApp/Configuration/Version.xcconfig";
const versionPattern = /^(MARKETING_VERSION=)(\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?)$/m;
const buildPattern = /^(CURRENT_PROJECT_VERSION=)(\d+)$/m;

function runGit(args) {
  return execFileSync("git", args, {
    encoding: "utf8",
    stdio: ["ignore", "pipe", "ignore"],
  }).trim();
}

function getCommitsSinceCurrentVersion(version) {
  try {
    return runGit(["log", `v${version}..HEAD`, "--format=%s"])
      .split("\n")
      .filter(Boolean);
  } catch {
    return runGit(["log", "-n", "100", "--format=%s"])
      .split("\n")
      .filter(Boolean);
  }
}

function determineBump(commits) {
  let bump = "none";
  for (const subject of commits) {
    const isBreaking =
      subject.includes("BREAKING CHANGE") ||
      /^[\w-]+(?:\([^)]*\))?!:/.test(subject);
    if (isBreaking) return "major";

    const type = subject.match(/^([\w-]+)(?:\([^)]*\))?:/)?.[1];
    if (type === "feat") bump = "minor";
    else if (["fix", "perf", "refactor", "build", "ci", "chore", "docs"].includes(type) && bump === "none") {
      bump = "patch";
    }
  }
  return bump;
}

function increment(version, bump) {
  const [major, minor, patch] = version.split(".").map(Number);
  if (bump === "major") return `${major + 1}.0.0`;
  if (bump === "minor") return `${major}.${minor + 1}.0`;
  return `${major}.${minor}.${patch + 1}`;
}

const content = fs.readFileSync(versionFile, "utf8");
const version = content.match(versionPattern)?.[2];
const build = Number(content.match(buildPattern)?.[2]);
if (!version || !Number.isInteger(build)) throw new Error(`Invalid version file: ${versionFile}`);

const requestedBump = process.argv.find((arg) => arg.startsWith("--bump="))?.split("=")[1];
const commits = getCommitsSinceCurrentVersion(version);
const bump = requestedBump ?? determineBump(commits);
if (!["major", "minor", "patch", "none"].includes(bump)) throw new Error(`Invalid bump type: ${bump}`);

const nextVersion = bump === "none" ? version : increment(version, bump);
console.log(`Current version: ${version}`);
console.log(`Commits considered: ${commits.length}`);
console.log(`Recommended bump: ${bump}`);
console.log(`Next version: ${nextVersion}`);

if (process.argv.includes("--dry-run") || bump === "none") process.exit(0);

const updated = content
  .replace(versionPattern, `$1${nextVersion}`)
  .replace(buildPattern, `$1${build + 1}`);
fs.writeFileSync(versionFile, updated);
console.log(`Updated ${versionFile} to ${nextVersion} (Android build ${build + 1}).`);
