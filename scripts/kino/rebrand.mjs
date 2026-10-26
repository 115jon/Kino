#!/usr/bin/env node
import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";
import { pathToFileURL } from "node:url";

export const STRING_FILES =
  /^(composeApp|androidApp)\/src\/[^/]+\/(composeResources|res)\/values[^/]*\/[^/]*strings[^/]*\.xml$/;

export const KEEP_NAMES = [
  /^community_/,
  /^action_support_nuvio$/,
  /^profile_background_member_note$/,
];

const RULES = [
  [/\bNuvio Mobile\b/g, "Kino"],
  [/Nuvio/g, "Kino"],
];

const URLS = /((?:https?|nuvio):\/\/\S+|[\w.-]*nuvio\.tv\S*)/i;
const ELEMENT = /<(string|plurals|string-array)\s+name="([^"]+)"[^>]*>([\s\S]*?)<\/\1>/g;
const TAG = /(<[^>]*>)/;

function rewriteText(text) {
  return text
    .split(URLS)
    .map((part, index) =>
      index % 2 ? part : RULES.reduce((acc, [from, to]) => acc.replace(from, to), part),
    )
    .join("");
}

function rewriteBody(body) {
  return body
    .split(TAG)
    .map((part) => (TAG.test(part) ? part : rewriteText(part)))
    .join("");
}

export function rebrandXml(source) {
  const hits = [];
  const output = source.replace(ELEMENT, (element, kind, name, body, offset) => {
    if (KEEP_NAMES.some((rule) => rule.test(name))) return element;
    const next = rewriteBody(body);
    if (next === body) return element;
    const line = source.slice(0, offset).split("\n").length;
    hits.push({ line, name, text: body.trim().slice(0, 120) });
    const close = `</${kind}>`;
    return element.slice(0, element.length - body.length - close.length) + next + close;
  });
  return { output, hits };
}

function trackedStringFiles() {
  return execFileSync("git", ["ls-files", "-z"], { encoding: "utf8", maxBuffer: 1 << 28 })
    .split("\0")
    .filter((file) => STRING_FILES.test(file));
}

function main() {
  const check = process.argv.includes("--check");
  let files = 0;
  let strings = 0;
  for (const file of trackedStringFiles()) {
    const source = readFileSync(file, "utf8");
    const { output, hits } = rebrandXml(source);
    if (hits.length === 0) continue;
    files += 1;
    strings += hits.length;
    if (check) {
      for (const hit of hits) console.log(`${file}:${hit.line}: ${hit.name}: ${hit.text}`);
    } else {
      writeFileSync(file, output);
    }
  }
  console.log(`${check ? "would change" : "changed"} ${strings} strings in ${files} files`);
  if (check && strings > 0) process.exit(1);
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) main();
