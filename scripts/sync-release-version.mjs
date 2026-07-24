import { execFileSync } from "node:child_process";
import fs from "node:fs";

import { isReleaseStateAtLeast, synchronizeReleaseState } from "./versioning.mjs";

const platformFiles = {
  android: "release/versions/android.properties",
  desktop: "release/versions/desktop.properties",
  ios: "release/versions/ios.xcconfig",
};

const versionFiles = {
  android: "release/versions/android.version",
  desktop: "release/versions/desktop.version",
  ios: "release/versions/ios.version",
};

const versionPatterns = {
  android: /^(versionName=)(\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?)$/m,
  desktop: /^(versionName=)(\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?)$/m,
  ios: /^(MARKETING_VERSION=)(\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?)$/m,
};

const buildPatterns = {
  android: /^(versionCode=)(\d+)$/m,
  desktop: /^(versionCode=)(\d+)$/m,
  ios: /^(CURRENT_PROJECT_VERSION=)(\d+)$/m,
};

function runGit(args) {
  return execFileSync("git", args, {
    encoding: "utf8",
    stdio: ["ignore", "pipe", "ignore"],
  }).trim();
}

function getArgument(name) {
  return process.argv.find((argument) => argument.startsWith(`--${name}=`))?.split("=").slice(1).join("=");
}

function readVersion(content, platform, file) {
  const version = content.match(versionPatterns[platform])?.[2];
  const build = Number(content.match(buildPatterns[platform])?.[2]);
  if (!version || !Number.isInteger(build)) throw new Error(`Invalid version file: ${file}`);
  return { version, build };
}

const platform = getArgument("platform") ?? "desktop";
const file = platformFiles[platform];
if (!file) throw new Error(`Invalid release platform: ${platform}`);

const content = fs.readFileSync(file, "utf8");
const current = readVersion(content, platform, file);
const versionFile = versionFiles[platform];
const nextVersion = fs.readFileSync(versionFile, "utf8").trim();
const baseRef = getArgument("base-ref");

if (baseRef) {
  const baselineContent = runGit(["show", `${baseRef}:${file}`]);
  const baseline = readVersion(baselineContent, platform, file);
  const candidate = synchronizeReleaseState(current, nextVersion, baseline);
  if (!isReleaseStateAtLeast(candidate, baseline)) {
    throw new Error(`${platform} release state ${candidate.version} (build ${candidate.build}) is lower than ${baseline.version} (build ${baseline.build})`);
  }
  const updated = content
    .replace(versionPatterns[platform], `$1${nextVersion}`)
    .replace(buildPatterns[platform], `$1${candidate.build}`);
  fs.writeFileSync(file, updated);
}
