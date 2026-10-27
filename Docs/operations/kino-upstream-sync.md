# Kino: syncing upstream NuvioMobile

Kino is a GPLv3 fork of NuvioMedia/NuvioMobile. Remotes:

- `origin`: upstream `https://github.com/NuvioMedia/NuvioMobile.git`, branch `cmp-rewrite`. Fetch only, never push.
- `fork`: `https://github.com/115jon/Kino.git`, default branch `main`. Sync branches, tags and releases live here.

## Where we are

Tracking is plain git ancestry:

- `git rev-list --count main..origin/cmp-rewrite`: upstream commits not merged yet.
- `git rev-list --count --no-merges origin/cmp-rewrite..main`: Kino-only commits.
- `git merge-base main origin/cmp-rewrite`: the last upstream commit absorbed.
- `git log kino/pre-sync-<date>..main`: what a given sync brought in.

## Weekly workflow

`.github/workflows/upstream-sync.yml` runs Mondays 03:17 UTC and on demand. It checks out `main`, fetches
upstream `cmp-rewrite` and exits if nothing is new. Otherwise it creates `sync/upstream-<YYYYMMDD>` and tries
the merge:

- Right after the merge it runs `scripts/kino/deleted-paths.mjs apply` from `main`. Any path in
  `scripts/kino/deleted-paths.txt` that upstream modified (a modify/delete conflict) or newly added is removed again, so
  those conflicts resolve as deleted. Only conflicts left after that count as conflicts.
- Clean: it runs `scripts/kino/rebrand.mjs` from `main` (committing any new rebranded strings and deletions), then
  `rebrand.mjs --check` and `verify-release-state.mjs` for android, desktop and ios, pushes the branch and opens
  a PR to `main`. It closes the conflict issue if one is open.
- Conflicts: it aborts and creates or updates the single open issue "Upstream sync needs a manual merge" with the
  commit count behind and the conflicted paths, then exits 0. Merge by hand as below.

Pushes made with `GITHUB_TOKEN` do not trigger other workflows, so run the verify commands on the PR yourself.
The repository must allow GitHub Actions to create pull requests.

## Manual merge

Commits go through the `commit` CLI only (`commit status`, `commit resolve --take`, `commit continue`,
`commit abort-op`, `commit do`). No `git add`, `git commit`, `git checkout`, `git restore` or `git rm`.

1. `git fetch origin` and `git fetch fork`. Stop if `main` is dirty or an earlier `sync/upstream-*` PR is still open.
2. Tag the start: `git tag kino/pre-sync-<YYYYMMDD> main`.
3. Make a scratch worktree: `git worktree add <tmp>/kino-sync -b sync/upstream-<YYYYMMDD> main`, then copy
   `local.properties` into it.
4. `git config rerere.enabled true`, then `git merge --no-ff --no-commit origin/cmp-rewrite`. For a large gap,
   merge in stages at upstream commits that split big files. Check rerere's replays anyway.
5. Resolve each conflict by reading both sides (`git diff <base> main -- <path>` for Kino's change,
   `git log <base>..origin/cmp-rewrite -- <path>` for upstream's reason):
   - Upstream rewrote or split the file: take upstream, then port the Kino hunk into wherever the code moved.
   - Branding-only conflict: take upstream; the rebrand script fixes the strings.
   - Anything on the keep-ours list below: take ours.
6. `node scripts/kino/rebrand.mjs`, then `node scripts/kino/rebrand.mjs --check` must report 0.
7. `commit resolve --take <paths>` and `commit continue`. `resolve` only accepts conflicted paths, so ports into
   new upstream files, desktop actuals for new `expect` declarations and type fixes go in separate follow-up
   commits with `commit do`.
8. Push the branch and tag to `fork` and open a PR to `main`. `main` moves only after a human approves.

## Keep ours

- Icons and branding: launcher icons, splash, `app_logo*.png`, `app_logo_wordmark.png`, Android `strings.xml`
  app names, `Theme.Nuvio` colors.
- `desktop/`, `composeApp/src/desktopMain/`, `composeApp/src/desktopTest/`, `release/`.
- Our workflows (`android-release`, `desktop-release`, `platform-checks`, `release-please`, `upstream-sync`), the
  release-please configs and manifests in `.github/release-please/`, and `.github/PULL_REQUEST_TEMPLATE.md` (short
  Summary and Testing template).
- Paths in `scripts/kino/deleted-paths.txt` stay deleted (see below).
- `MPVKit/` stays vendored, including its own `.github/`. `store.json` is kept because upstream rewrites it every
  release.
- Updater: `AppUpdaterRepository` points at `115jon/Kino` releases, tags `android-v*` and `desktop-v*`, and the
  `release-manifest.json` asset.
- Android: one universal APK with no ABI splits, application ID `site.x115jon.kino`.
- Product scope (below): `KinoFeaturePolicy`, `KinoMemberAccess`, and the flags set to `false` in every
  `AppFeaturePolicy` actual.

## Kino-deleted paths

`scripts/kino/deleted-paths.txt` lists what Kino removed from upstream, one path per line: exact files, directories
(everything below), or a `*` within one path segment. Today that covers upstream's contribution process
(`CONTRIBUTING.md`, `.github/ISSUE_TEMPLATE/`, the PR template check, the stale, unlabeled and needs-info issue
workflows, any `FUNDING.yml` and `CODE_OF_CONDUCT.md`), the workflows listed above that Kino dropped,
`scripts/update-store-source.py`, `iosApp/Configuration/Version.xcconfig`, and the orphan gitlinks `libass-android`,
`vendor/TorrServer` and `vendor/quickjs-kt`.

- Delete something else for good: remove it with the normal commit flow and add its path to the list in the same PR.
- `node scripts/kino/deleted-paths.mjs apply` removes listed paths that are tracked (`git rm`, so it also resolves
  modify/delete conflicts mid-merge); `check` lists them and exits 1. `rebrand.mjs` runs the same logic: plain runs
  remove, `--check` fails.
- Keep `LICENSE` and `FORK_NOTICE.md`; they are never listed.

## Take theirs

`gradle/libs.versions.toml`, translations except brand values (rerun the rebrand script), tests, and feature
code. Adapt Kino code to upstream APIs rather than the reverse.

## Product scope

Kino is its own app: a user logs in and watches. After each merge, check that nothing new brings back:

- Support, donation, supporter or membership UI, "Support Nuvio", contributor pages, GitHub, Discord, Reddit,
  community or website promo links. Switch them off rather than delete upstream files:
  `supportersContributorsPageEnabled`, `donationActionsEnabled` and `donationProgressEnabled` are `false` in every
  `AppFeaturePolicy` actual, and `KinoFeaturePolicy` turns off member sync, the member badge and alternate app
  icons. Delete only small files that are pure promo.
- Membership-gated items must be open to everyone or hidden, never locked. Supporter themes and gradient custom
  themes are open through `KinoMemberAccess`; server-gated profile backgrounds and member avatars stay hidden.
- Keep `LICENSE`, `FORK_NOTICE.md` and the licenses page, whose app entry links to the Kino repository.
- Keep functional backends: `ServerDiscovery`, Supabase and `api.nuvio.tv`, `nuvio.tv/link`, party links, and
  the `nuvio://auth/simkl` scheme registered with Simkl.

## Verify

```
./gradlew :composeApp:compileKotlinDesktop --console=plain
./gradlew :composeApp:desktopTest -x :composeApp:compileWindowsMediaSession --console=plain
./gradlew :composeApp:compileAndroidMain --console=plain
./gradlew -Pnuvio.android.distribution=full :composeApp:compileAndroidMain --console=plain
./gradlew :composeApp:compileWindowsMediaSession --console=plain
node --test scripts/versioning.test.mjs
node --test scripts/kino/rebrand.test.mjs scripts/kino/deleted-paths.test.mjs
node scripts/kino/rebrand.mjs --check
node scripts/kino/deleted-paths.mjs check
```

Run `compileWindowsMediaSession` from a shell initialised with the MSVC environment (`vcvars64.bat` or a
Developer PowerShell). If Gradle hangs, `./gradlew --stop` and retry.

## Known traps

- A scratch worktree has no `local.properties`; copy it in before building.
- Some files have mixed CRLF/LF and `core.autocrlf=true`. Editors and `sed -i` can rewrite every line ending.
  Check `git diff --stat` for whole-file churn and restore the original endings line by line.
- `commit resolve` rejects paths that are not conflicted, so ports into new upstream files go in follow-up commits.
- New `expect` declarations in `commonMain` need desktop actuals in `desktopMain`, or the desktop compile fails.
- Orphan gitlinks without `.gitmodules` break `actions/checkout`. If upstream bumps `tools`, `libass-android`,
  `vendor/TorrServer` or `vendor/quickjs-kt`, resolve the modify/delete conflict by keeping them deleted.
- Upstream ignores `scripts/*` and `Docs`. Kino's `.gitignore` re-includes `scripts/kino/` and `Docs/operations/`;
  keep those lines when `.gitignore` conflicts.
