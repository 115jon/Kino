# Kino

Kino is an independent community fork of [Nuvio Mobile](https://github.com/NuvioMedia/NuvioMobile), extended for desktop use with Compose Multiplatform and a Windows installer.

Kino is not affiliated with, endorsed by, or sponsored by NuvioMedia. The fork maintains its own releases, update channel, branding work, and desktop-specific changes.

## What Changed

- Desktop navigation, media detail layouts, season and episode selection, and player controls.
- Windows desktop packaging with a bundled installer, Start Menu/Desktop shortcuts, and uninstall registration.
- A separate release channel for this fork's mobile and desktop builds.

The shared application code still contains upstream package names and attribution strings where they are part of the inherited codebase. Product branding is being migrated incrementally; the upstream relationship remains disclosed throughout the project.

## Installation

Published installers and Android packages are available from the [Kino releases page](https://github.com/115jon/Kino/releases).

For local Windows installer development:

```powershell
./gradlew.bat :composeApp:createReleaseDistributable
powershell -ExecutionPolicy Bypass -File ./desktop/scripts/build-installer.ps1
```

The installer is produced under `desktop/installer/bin/Release/`.

## Development

```bash
git clone https://github.com/115jon/Kino.git
cd Kino
./gradlew :composeApp:compileKotlinDesktop
```

The project is organized as follows:

- `composeApp/` contains shared Kotlin Multiplatform and Compose Multiplatform code.
- `composeApp/src/commonMain/` contains shared UI, features, repositories, and platform-agnostic logic.
- `composeApp/src/desktopMain/` contains desktop-specific integrations.
- `desktop/` contains the Windows installer and packaging scripts.
- `iosApp/` contains shared version configuration and Apple platform project files.

## Upstream Synchronization

The `desktop` branch is the maintained Kino product branch. Changes from upstream `NuvioMedia/NuvioMobile` are brought in through reviewed synchronization pull requests so desktop and fork-specific changes remain explicit and conflict resolution is auditable.

## License and Disclosure

Kino is distributed under the GNU General Public License v3.0, inherited from the upstream project. See [LICENSE](LICENSE) for the full license text and [FORK_NOTICE.md](FORK_NOTICE.md) for the fork disclosure and attribution details.

Kino is a client-side application for metadata, user-installed extensions, and user-provided sources. Users are responsible for content they access and for complying with applicable law and third-party service terms. Kino does not host, store, or distribute media content.

## Third-Party Components

Kino uses Kotlin Multiplatform, Compose Multiplatform, MPV/libmpv, FFmpeg, Supabase, TMDB, Trakt, IMDb datasets, IntroDB, and other third-party services and libraries. Their names and trademarks belong to their respective owners.
