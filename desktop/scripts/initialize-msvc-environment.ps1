$vswhere = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"
if (-not (Test-Path -LiteralPath $vswhere)) {
    throw "Visual Studio Installer vswhere.exe was not found."
}

$installationPath = (& $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath | Select-Object -First 1).Trim()
if ([string]::IsNullOrWhiteSpace($installationPath)) {
    throw "A Visual Studio installation with the x64 C++ toolchain was not found."
}

$vsDevCmd = Join-Path $installationPath "Common7\Tools\VsDevCmd.bat"
if (-not (Test-Path -LiteralPath $vsDevCmd)) {
    throw "VsDevCmd.bat was not found: $vsDevCmd"
}

$environment = & cmd.exe /d /s /c "`"$vsDevCmd`" -arch=x64 && set"
foreach ($line in $environment) {
    $separator = $line.IndexOf('=')
    if ($separator -gt 0) {
        $name = $line.Substring(0, $separator)
        $value = $line.Substring($separator + 1)
        Set-Item -Path "Env:$name" -Value $value
        if (-not [string]::IsNullOrWhiteSpace($env:GITHUB_ENV)) {
            "$name=$value" | Out-File -FilePath $env:GITHUB_ENV -Encoding utf8 -Append
        }
    }
}

if (-not (Get-Command cl.exe -ErrorAction SilentlyContinue)) {
    throw "MSVC environment initialization did not expose cl.exe."
}
