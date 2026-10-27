#!/usr/bin/env node
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const LIST_FILE = join(dirname(fileURLToPath(import.meta.url)), "deleted-paths.txt");
const REMOVE_CHUNK = 200;

export function parseDeletedPaths(text) {
  return text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((entry) => {
      const pattern = entry
        .split("*")
        .map((part) => part.replace(/[.+?^${}()|[\]\\]/g, "\\$&"))
        .join("[^/]*");
      return new RegExp(`^${pattern}(/.*)?$`);
    });
}

export function findDeleted(files, rules) {
  return files.filter((file) => rules.some((rule) => rule.test(file)));
}

export function loadDeletedPaths() {
  return parseDeletedPaths(readFileSync(LIST_FILE, "utf8"));
}

function trackedFiles() {
  const files = execFileSync("git", ["ls-files", "-z"], { encoding: "utf8", maxBuffer: 1 << 28 })
    .split("\0")
    .filter(Boolean);
  return [...new Set(files)];
}

export function listTrackedDeleted() {
  return findDeleted(trackedFiles(), loadDeletedPaths());
}

export function applyDeletions() {
  const files = listTrackedDeleted();
  for (let index = 0; index < files.length; index += REMOVE_CHUNK) {
    execFileSync(
      "git",
      ["rm", "-r", "-f", "-q", "--ignore-unmatch", "--", ...files.slice(index, index + REMOVE_CHUNK)],
      { stdio: "inherit" },
    );
  }
  return files;
}

function main() {
  const command = process.argv[2];
  if (command === "apply") {
    const files = applyDeletions();
    for (const file of files) console.log(`deleted ${file}`);
    console.log(`removed ${files.length} Kino-deleted paths`);
  } else if (command === "check") {
    const files = listTrackedDeleted();
    for (const file of files) console.log(`Kino deleted this path: ${file}`);
    console.log(`found ${files.length} Kino-deleted paths`);
    if (files.length > 0) process.exit(1);
  } else {
    console.error("usage: deleted-paths.mjs apply|check");
    process.exit(2);
  }
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) main();
