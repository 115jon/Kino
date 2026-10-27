import assert from "node:assert/strict";
import test from "node:test";

import { findDeleted, loadDeletedPaths, parseDeletedPaths } from "./deleted-paths.mjs";

test("matches exact files and everything below a listed directory", () => {
  const rules = parseDeletedPaths("CONTRIBUTING.md\n.github/ISSUE_TEMPLATE\n");
  assert.deepEqual(
    findDeleted(
      [
        "CONTRIBUTING.md",
        ".github/ISSUE_TEMPLATE/bug_report.yml",
        ".github/ISSUE_TEMPLATE",
        "docs/CONTRIBUTING.md",
        ".github/ISSUE_TEMPLATE_NOTES.md",
      ],
      rules,
    ),
    ["CONTRIBUTING.md", ".github/ISSUE_TEMPLATE/bug_report.yml", ".github/ISSUE_TEMPLATE"],
  );
});

test("expands star within one path segment and ignores blank lines", () => {
  const rules = parseDeletedPaths("\n.github/workflows/pr-full-debug-build*.yml\r\n\n");
  assert.deepEqual(
    findDeleted(
      [
        ".github/workflows/pr-full-debug-build.yml",
        ".github/workflows/pr-full-debug-build-comment.yml",
        ".github/workflows/release-please.yml",
        ".github/workflows/sub/pr-full-debug-build.yml",
      ],
      rules,
    ),
    [".github/workflows/pr-full-debug-build.yml", ".github/workflows/pr-full-debug-build-comment.yml"],
  );
});

test("treats regex characters in entries literally", () => {
  const rules = parseDeletedPaths("scripts/update-store-source.py");
  assert.deepEqual(findDeleted(["scripts/update-store-source.py", "scripts/update-store-sourceXpy"], rules), [
    "scripts/update-store-source.py",
  ]);
});

test("the shipped list removes upstream community files but keeps Kino workflows and licences", () => {
  const rules = loadDeletedPaths();
  const kept = [
    "LICENSE",
    "FORK_NOTICE.md",
    ".github/PULL_REQUEST_TEMPLATE.md",
    ".github/workflows/upstream-sync.yml",
    ".github/workflows/release-please.yml",
    ".github/workflows/android-release.yml",
    "MPVKit/.github/FUNDING.yml",
  ];
  assert.deepEqual(findDeleted(kept, rules), []);
  assert.deepEqual(
    findDeleted(
      [
        ".github/workflows/pr-template-check.yml",
        ".github/workflows/close-stale-issues.yml",
        ".github/workflows/close-unlabeled-issues.yml",
        ".github/workflows/stale-needs-info.yml",
        ".github/workflows/triage-needs-info.yml",
        ".github/ISSUE_TEMPLATE/config.yml",
        "CONTRIBUTING.md",
      ],
      rules,
    ).length,
    7,
  );
});
