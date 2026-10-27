import { execFileSync } from "node:child_process";
import fs from "node:fs";

import {
  getReleaseFile,
  isReleaseStateAtLeast,
  readReleaseState,
  synchronizeReleaseState,
  writeReleaseState,
} from "./versioning.mjs";

function runGit(args) {
  return execFileSync("git", args, {
    encoding: "utf8",
    stdio: ["ignore", "pipe", "ignore"],
  }).trim();
}

function getArgument(name) {
  return process.argv.find((argument) => argument.startsWith(`--${name}=`))?.split("=").slice(1).join("=");
}

const platform = getArgument("platform") ?? "desktop";
const { file, canonicalFile } = getReleaseFile(platform);
if (!canonicalFile) throw new Error(`Missing canonical version file for ${platform}`);

const content = fs.readFileSync(file, "utf8");
const current = readReleaseState(platform, content);
const nextVersion = fs.readFileSync(canonicalFile, "utf8").trim();
const baseRef = getArgument("base-ref");

if (baseRef) {
  const baseline = readReleaseState(platform, runGit(["show", `${baseRef}:${file}`]));
  const candidate = synchronizeReleaseState(current, nextVersion, baseline);
  if (!isReleaseStateAtLeast(candidate, baseline)) {
    throw new Error(`${platform} release state ${candidate.version} (build ${candidate.build}) is lower than ${baseline.version} (build ${baseline.build})`);
  }
  fs.writeFileSync(file, writeReleaseState(platform, content, candidate));
}
