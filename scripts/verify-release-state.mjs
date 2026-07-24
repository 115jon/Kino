import { execFileSync } from "node:child_process";
import fs from "node:fs";

import {
  compareVersions,
  getPlatformTag,
  getPlatformTagPrefix,
  isReleaseStateAtLeast,
} from "./versioning.mjs";

const platformFiles = {
  android: {
    file: "release/versions/android.properties",
    versionPattern: /^(versionName=)(\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?)$/m,
    buildPattern: /^(versionCode=)(\d+)$/m,
  },
  desktop: {
    file: "release/versions/desktop.properties",
    canonicalFile: "release/versions/desktop.version",
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

function getArgument(name) {
  return process.argv.find((argument) => argument.startsWith(`--${name}=`))?.split("=").slice(1).join("=");
}

function readVersion(platform) {
  const config = platformFiles[platform];
  if (!config) throw new Error(`Invalid release platform: ${platform}`);
  const content = fs.readFileSync(config.file, "utf8");
  return readVersionContent(platform, content, config.file);
}

function readVersionContent(platform, content, file) {
  const config = platformFiles[platform];
  const version = content.match(config.versionPattern)?.[2];
  const build = Number(content.match(config.buildPattern)?.[2]);
  if (!version || !Number.isInteger(build)) throw new Error(`Invalid version file: ${file}`);
  return { version, build };
}

function readVersionAtRef(platform, ref, allowMissing) {
  const config = platformFiles[platform];
  try {
    const content = runGit(["show", `${ref}:${config.file}`]);
    return readVersionContent(platform, content, `${ref}:${config.file}`);
  } catch {
    if (!allowMissing) throw new Error(`Missing release file at ${ref}:${config.file}`);
    return null;
  }
}

function getLatestTaggedVersion(platform) {
  const prefix = getPlatformTagPrefix(platform);
  let tags = [];
  try {
    tags = runGit(["tag", "--list", `${prefix}*`])
      .split("\n")
      .map((tag) => tag.trim())
      .filter(Boolean)
      .map((tag) => tag.slice(prefix.length));
  } catch {
    return null;
  }

  return tags.reduce((latest, tag) => {
    if (!latest || compareVersions(tag, latest) > 0) return tag;
    return latest;
  }, null);
}

function verify() {
  const platform = getArgument("platform") ?? "android";
  const current = readVersion(platform);
  const canonicalFile = platformFiles[platform].canonicalFile;
  if (canonicalFile) {
    if (!fs.existsSync(canonicalFile)) throw new Error(`Missing canonical version file: ${canonicalFile}`);
    const canonicalVersion = fs.readFileSync(canonicalFile, "utf8").trim();
    if (compareVersions(canonicalVersion, current.version) !== 0) {
      throw new Error(`${canonicalFile} ${canonicalVersion} does not match ${current.version}`);
    }
  }
  const latestTaggedVersion = getLatestTaggedVersion(platform);
  if (latestTaggedVersion && compareVersions(current.version, latestTaggedVersion) < 0) {
    throw new Error(
      `${platform} version ${current.version} is lower than the latest tag ${getPlatformTag(platform, latestTaggedVersion)}`,
    );
  }

  const baselineRef = getArgument("baseline-ref");
  const allowMissingBaseline = process.argv.includes("--allow-missing-baseline");
  const baseline = baselineRef ? readVersionAtRef(platform, baselineRef, allowMissingBaseline) : null;
  if (baseline && !isReleaseStateAtLeast(current, baseline)) {
    throw new Error(
      `${platform} ${current.version} (build ${current.build}) is lower than ${baselineRef} ${baseline.version} (build ${baseline.build})`,
    );
  }

  const requestedTag = getArgument("tag");
  if (requestedTag && requestedTag !== getPlatformTag(platform, current.version)) {
    throw new Error(`Tag ${requestedTag} does not match ${getPlatformTag(platform, current.version)}`);
  }

  console.log(`Verified ${platform} ${current.version} (build ${current.build}).`);
}

verify();
