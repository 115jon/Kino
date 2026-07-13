# Nuvio Windows Installer

The Nuvio installer follows the desktop bootstrapper layout used by Ralph Meet.
It installs the Compose desktop distributable into `%LOCALAPPDATA%\Nuvio` and
keeps a stable `Update.exe` launcher at the install root.

The installer creates:

- `%LOCALAPPDATA%\Nuvio\Update.exe`
- `%LOCALAPPDATA%\Nuvio\current.json`
- `%LOCALAPPDATA%\Nuvio\app.ico`
- `%LOCALAPPDATA%\Nuvio\app-<version>\Nuvio.exe`
- A desktop shortcut and a Start Menu shortcut
- A per-user uninstall entry at `HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\Nuvio`

Build from the repository root with PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File .\desktop\scripts\build-installer.ps1
```

The script first runs `:composeApp:createReleaseDistributable`, embeds the
resulting desktop app directory as `payload.zip`, and then builds
`desktop\installer\NuvioSetup.exe`. Generated payload and build output are
ignored by Git.
