# Kino Windows Installer

The Kino installer follows the desktop bootstrapper layout used by Ralph Meet.
It installs the Compose desktop distributable into `%LOCALAPPDATA%\Kino` and
keeps a stable `Update.exe` launcher at the install root.

The installer creates:

- `%LOCALAPPDATA%\Kino\Update.exe`
- `%LOCALAPPDATA%\Kino\current.json`
- `%LOCALAPPDATA%\Kino\app.ico`
- `%LOCALAPPDATA%\Kino\app-<version>\Kino.exe`
- A desktop shortcut and a Start Menu shortcut
- A per-user uninstall entry at `HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\Kino`

Build from the repository root with PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File .\desktop\scripts\build-installer.ps1
```

The script first runs `:composeApp:createReleaseDistributable`, embeds the
resulting desktop app directory as `payload.zip`, and then builds
`desktop\installer\KinoSetup.exe`. Generated payload and build output are
ignored by Git.
