const bumpOrder = {
  none: 0,
  patch: 1,
  minor: 2,
  major: 3,
};

const patchTypes = new Set([
  "fix",
  "perf",
  "refactor",
  "build",
  "ci",
  "chore",
  "docs",
]);

const platformTagPrefixes = {
  android: "android-v",
  desktop: "desktop-v",
  ios: "ios-v",
};

export function parseConventionalCommit(message) {
  const subject = message.split(/\r?\n/, 1)[0]?.trim() ?? "";
  const match = subject.match(/^([\w-]+)(?:\([^)]*\))?(!)?:\s+.+$/);
  const breakingFooter = /(?:^|\r?\n)\s*BREAKING[- ]CHANGE\s*:/m.test(message);

  return {
    type: match?.[1] ?? null,
    breaking: Boolean(match?.[2] || breakingFooter),
  };
}

export function determineBump(commits) {
  let bump = "none";

  for (const commit of commits) {
    const parsed = parseConventionalCommit(commit);
    if (parsed.breaking) return "major";

    if (parsed.type === "feat" && bumpOrder[bump] < bumpOrder.minor) {
      bump = "minor";
    } else if (parsed.type && patchTypes.has(parsed.type) && bumpOrder[bump] < bumpOrder.patch) {
      bump = "patch";
    }
  }

  return bump;
}

export function incrementVersion(version, bump) {
  const match = version.match(/^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?$/);
  if (!match) throw new Error(`Invalid semantic version: ${version}`);

  const major = Number(match[1]);
  const minor = Number(match[2]);
  const patch = Number(match[3]);

  if (bump === "none") return version;
  if (bump === "major") return `${major + 1}.0.0`;
  if (bump === "minor") return `${major}.${minor + 1}.0`;
  if (bump === "patch") return `${major}.${minor}.${match[4] ? patch : patch + 1}`;
  throw new Error(`Invalid bump type: ${bump}`);
}

export function getPlatformTag(platform, version) {
  const prefix = platformTagPrefixes[platform];
  if (!prefix) throw new Error(`Invalid release platform: ${platform}`);
  return `${prefix}${version}`;
}

export function getPlatformTagPrefix(platform) {
  const prefix = platformTagPrefixes[platform];
  if (!prefix) throw new Error(`Invalid release platform: ${platform}`);
  return prefix;
}

export function compareVersions(left, right) {
  const leftParts = parseVersion(left);
  const rightParts = parseVersion(right);

  for (let index = 0; index < 3; index += 1) {
    if (leftParts.core[index] !== rightParts.core[index]) {
      return leftParts.core[index] > rightParts.core[index] ? 1 : -1;
    }
  }

  if (!leftParts.preRelease && !rightParts.preRelease) return 0;
  if (!leftParts.preRelease) return 1;
  if (!rightParts.preRelease) return -1;

  const size = Math.max(leftParts.preRelease.length, rightParts.preRelease.length);
  for (let index = 0; index < size; index += 1) {
    const leftPart = leftParts.preRelease[index];
    const rightPart = rightParts.preRelease[index];
    if (leftPart === undefined) return -1;
    if (rightPart === undefined) return 1;
    if (leftPart === rightPart) continue;

    const leftNumber = /^\d+$/.test(leftPart) ? Number(leftPart) : null;
    const rightNumber = /^\d+$/.test(rightPart) ? Number(rightPart) : null;
    if (leftNumber !== null && rightNumber !== null) return leftNumber > rightNumber ? 1 : -1;
    if (leftNumber !== null) return -1;
    if (rightNumber !== null) return 1;
    return leftPart > rightPart ? 1 : -1;
  }

  return 0;
}

function parseVersion(version) {
  const match = version.match(/^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?$/);
  if (!match) throw new Error(`Invalid semantic version: ${version}`);
  return {
    core: [Number(match[1]), Number(match[2]), Number(match[3])],
    preRelease: match[4]?.split(".") ?? null,
  };
}
