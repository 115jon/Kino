import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";

export function createReleaseManifest({
  platform,
  version,
  versionCode,
  channel = "stable",
  mandatory = false,
  minimumSupportedVersionCode,
  assets,
}) {
  if (!platform || !version || !Array.isArray(assets) || assets.length === 0) {
    throw new Error("A platform, version, and at least one asset are required.");
  }

  const manifest = {
    schemaVersion: 1,
    product: "kino",
    platform,
    channel,
    version,
    ...(Number.isInteger(versionCode) ? { versionCode } : {}),
    ...(Number.isInteger(minimumSupportedVersionCode) ? { minimumSupportedVersionCode } : {}),
    mandatory,
    assets,
  };

  return manifest;
}

function getArgument(name) {
  return process.argv.find((argument) => argument.startsWith(`--${name}=`))?.split("=").slice(1).join("=");
}

function getArguments(name) {
  return process.argv
    .filter((argument) => argument.startsWith(`--${name}=`))
    .map((argument) => argument.split("=").slice(1).join("="));
}

function parseOptionalInteger(value, name) {
  if (value === undefined) return undefined;
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < 0) throw new Error(`Invalid ${name}: ${value}`);
  return parsed;
}

function getFileAsset(filePath) {
  const contents = fs.readFileSync(filePath);
  return {
    name: path.basename(filePath),
    sizeBytes: contents.byteLength,
    sha256: crypto.createHash("sha256").update(contents).digest("hex"),
  };
}

function main() {
  const platform = getArgument("platform");
  const version = getArgument("version");
  const output = getArgument("output") ?? "release-manifest.json";
  const assets = getArguments("asset").map(getFileAsset);
  const versionCode = parseOptionalInteger(getArgument("version-code"), "version code");
  const minimumSupportedVersionCode = parseOptionalInteger(
    getArgument("minimum-supported-version-code"),
    "minimum supported version code",
  );
  const mandatory = getArgument("mandatory") === "true";

  const manifest = createReleaseManifest({
    platform,
    version,
    versionCode,
    channel: getArgument("channel") ?? "stable",
    mandatory,
    minimumSupportedVersionCode,
    assets,
  });

  fs.writeFileSync(output, `${JSON.stringify(manifest, null, 2)}\n`);
  console.log(`Wrote ${output}`);
}

if (process.argv[1]?.endsWith("release-manifest.mjs")) main();
