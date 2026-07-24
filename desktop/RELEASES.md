# Desktop Releases

Desktop releases use Release Please on the `desktop` branch. Conventional commits determine the next semantic version:

- `fix`, `perf`, `refactor`, `build`, `ci`, `chore`, and `docs` produce a patch release.
- `feat` produces a minor release while the app remains below `1.0.0`.
- A `BREAKING CHANGE` footer or `!` marker produces a major release.

Release Please opens or updates a release PR. Merging that PR updates the canonical version file and the desktop properties file, creates a `desktop-vX.Y.Z` tag and GitHub release, and starts the existing Desktop Release workflow, which adds the installer assets to that release.

The repository requires a `RELEASE_TOKEN` secret with permission to write contents, issues, and pull requests. A personal access token is required because tags and pull requests created with `GITHUB_TOKEN` do not reliably trigger downstream workflows.

The following checks protect version history:

- `verify-release-state.mjs` rejects versions below the latest platform tag.
- Pull request checks reject versions below the base branch.
- Upstream synchronization rejects versions below `upstream/cmp-rewrite` when the parent contains release files.
- `sync-release-version.mjs` increments `versionCode` only after a version increase and never lowers it.

When resolving upstream conflicts, keep the higher release version and version code from the fork. Do not replace the fork's release files with older parent values.
