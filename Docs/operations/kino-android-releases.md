# Kino: Android releases

Android has its own release-please component next to desktop. Commits that touch Android code cut Android releases;
commits that only touch desktop code do not.

## How a release happens

1. Conventional commits land on `main`. `.github/workflows/release-please.yml` runs both components in turn:
   `.github/release-please/android-config.json` and `.github/release-please/desktop-config.json`, each with its own
   manifest.
2. Each component ignores commits whose files all sit under the other platforms' paths (`exclude-paths`). Shared
   code (`composeApp/src/commonMain`, `fullCommonMain`) counts for both.
3. Release-please opens `release-please--branches--main--components--android`. The workflow then runs
   `sync-release-version.mjs --platform=android` on that branch, which raises `versionCode` in
   `release/versions/android.properties` whenever the version rises.
4. Merging that PR updates `CHANGELOG-android.md`, `release/versions/android.version` and the `versionName` between
   the `x-release-please` markers in `android.properties`, then creates the `android-vX.Y.Z` tag and GitHub release.
5. The tag starts `.github/workflows/android-release.yml`. It verifies the release state, builds
   `:androidApp:assembleFullRelease`, checks the signature with `apksigner`, and attaches
   `Kino-Android-X.Y.Z.apk` and `release-manifest.json` to the release, named `Kino Android vX.Y.Z`.

`android-release.yml` can also be started by hand with an existing `android-v*` tag.

## First release

The manifest starts at `0.2.24` with `versionCode` 96 and no tag, so release-please counts commits after
`bootstrap-sha` in `android-config.json`. The first `feat` commit after it proposes `0.3.0`. Do not hand-edit the
versions; set `release-as` in the config if a specific first version is wanted.

`node scripts/bump-version.mjs --platform=android` and `--dry-run` still work for local checks. Use
`node scripts/verify-release-state.mjs --platform=android` to confirm the three version files agree.

## Application identity

- Application ID: `site.x115jon.kino` (debug: `site.x115jon.kino.debug`). Upstream Nuvio uses `com.nuvio.app`, so the
  two apps install side by side and never share a Play Store listing.
- Kotlin packages stay `com.nuvio.app`, and the Gradle namespace stays `com.nuvio.android`. They are internal and
  renaming them gains nothing.
- App name `Kino` (`app_name` in the Android and Compose resources) and the Kino launcher icons.

## Secrets

All secrets live in `115jon/Kino` repository secrets (`gh secret list -R 115jon/Kino`):

| Secret | Used for |
| --- | --- |
| `RELEASE_TOKEN` | release-please PRs, tags, releases |
| `KINO_RELEASE_KEYSTORE_BASE64` | base64 of the release keystore |
| `KINO_RELEASE_STORE_PASSWORD` | keystore password |
| `KINO_RELEASE_KEY_ALIAS` | key alias in the keystore |
| `KINO_RELEASE_KEY_PASSWORD` | key password |
| `KINO_SUPABASE_URL`, `KINO_SUPABASE_ANON_KEY` | runtime config, required |
| `KINO_INTRODB_API_URL` | runtime config, optional |

To create or rotate the signing key (keep the keystore: losing it means installs can never upgrade):

```
keytool -genkeypair -v -keystore kino-release.keystore -alias kino -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 kino-release.keystore | gh secret set KINO_RELEASE_KEYSTORE_BASE64 -R 115jon/Kino
gh secret set KINO_RELEASE_STORE_PASSWORD -R 115jon/Kino
gh secret set KINO_RELEASE_KEY_ALIAS -R 115jon/Kino
gh secret set KINO_RELEASE_KEY_PASSWORD -R 115jon/Kino
```

The release job fails before building when a required secret is empty, and fails after building when the APK is not
signed.

## Local release build

`./gradlew -Pkino.release.platform=android :androidApp:assembleFullRelease` produces an unsigned APK without
`NUVIO_RELEASE_*` in `local.properties`. Set those four properties (store file, store password, key alias, key
password) to sign locally.
