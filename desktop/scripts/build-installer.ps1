param(
    [switch]$Release,
    [string]$Configuration = "Release"
)

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$gradlePath = Join-Path $repoRoot "gradlew.bat"
$installerProject = Join-Path $repoRoot "desktop\installer\KinoSetup.csproj"
$runtimeScript = Join-Path $repoRoot "desktop\scripts\prepare-windows-mpv-runtime.ps1"
$payloadZip = Join-Path $repoRoot "desktop\installer\Assets\payload.zip"
$versionFile = Join-Path $repoRoot "release\versions\desktop.properties"
$distributableRoot = Join-Path $repoRoot "composeApp\build\compose\binaries\main-release\app"
$localEnvFile = Join-Path $repoRoot ".env.kino.local"

if (-not (Test-Path -LiteralPath $gradlePath)) { throw "Gradle wrapper not found: $gradlePath" }
if (-not (Test-Path -LiteralPath $installerProject)) { throw "Installer project not found: $installerProject" }
if (-not (Test-Path -LiteralPath $runtimeScript)) { throw "MPV runtime preparation script not found: $runtimeScript" }
if (-not (Test-Path -LiteralPath $versionFile)) { throw "Version file not found: $versionFile" }

if (Test-Path -LiteralPath $localEnvFile) {
    $runtimeEnvMap = @{
        "KINO_SUPABASE_URL" = "NUVIO_SUPABASE_URL"
        "KINO_SUPABASE_ANON_KEY" = "NUVIO_SUPABASE_ANON_KEY"
        "KINO_SUPABASE_FALLBACK_URL" = "NUVIO_SUPABASE_FALLBACK_URL"
        "KINO_INTRODB_API_URL" = "NUVIO_INTRODB_API_URL"
    }
    foreach ($line in Get-Content -LiteralPath $localEnvFile) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$') {
            $sourceKey = $matches[1]
            $targetKey = $runtimeEnvMap[$sourceKey]
            if ($null -ne $targetKey) {
                $value = $matches[2].Trim().Trim('"').Trim("'")
                if (-not [string]::IsNullOrWhiteSpace($value)) {
                    Set-Item -Path "Env:$targetKey" -Value $value
                }
            }
        }
    }
}

$versionLine = Get-Content -LiteralPath $versionFile | Where-Object { $_ -match '^\s*versionName\s*=' } | Select-Object -First 1
if ($null -eq $versionLine -or $versionLine -notmatch '=\s*([^\s#]+)') { throw "versionName is missing from $versionFile" }
$version = $matches[1]

Write-Host "Building Kino desktop distributable $version..."
& powershell -ExecutionPolicy Bypass -File $runtimeScript
if ($LASTEXITCODE -ne 0) { throw "Windows MPV runtime preparation failed with exit code $LASTEXITCODE." }
Push-Location $repoRoot
try {
    & $gradlePath "-Pkino.release.platform=desktop" ":composeApp:createReleaseDistributable"
    if ($LASTEXITCODE -ne 0) { throw "Desktop distributable build failed with exit code $LASTEXITCODE." }
}
finally {
    Pop-Location
}

$payloadExecutable = Get-ChildItem -LiteralPath $distributableRoot -Filter "Kino.exe" -Recurse -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if ($null -eq $payloadExecutable) {
    throw "Kino.exe was not found under $distributableRoot"
}

$payloadDirectory = $payloadExecutable.Directory.FullName
$payloadAssetsDirectory = Split-Path -Parent $payloadZip
if (-not (Test-Path -LiteralPath $payloadAssetsDirectory)) {
    New-Item -ItemType Directory -Path $payloadAssetsDirectory | Out-Null
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
if (Test-Path -LiteralPath $payloadZip) { Remove-Item -LiteralPath $payloadZip -Force }
[System.IO.Compression.ZipFile]::CreateFromDirectory(
    $payloadDirectory,
    $payloadZip,
    [System.IO.Compression.CompressionLevel]::Optimal,
    $false
)

Write-Host "Packaging payload from $payloadDirectory"
& dotnet build $installerProject --configuration $Configuration `
    "/p:Version=$version" `
    "/p:AssemblyVersion=$version.0" `
    "/p:FileVersion=$version.0" `
    "/p:InformationalVersion=$version"
if ($LASTEXITCODE -ne 0) { throw "Kino installer build failed with exit code $LASTEXITCODE." }

$installerOutput = Join-Path $repoRoot "desktop\installer\bin\$Configuration\net48\KinoSetup.exe"
if (-not (Test-Path -LiteralPath $installerOutput)) {
    throw "Installer output was not found: $installerOutput"
}

$finalOutput = Join-Path $repoRoot "desktop\installer\bin\$Configuration\KinoSetup-$version.exe"
Copy-Item -LiteralPath $installerOutput -Destination $finalOutput -Force
Write-Host "Installer created: $finalOutput"
