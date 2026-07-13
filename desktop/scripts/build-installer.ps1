param(
    [switch]$Release,
    [string]$Configuration = "Release"
)

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$gradlePath = Join-Path $repoRoot "gradlew.bat"
$installerProject = Join-Path $repoRoot "desktop\installer\NuvioSetup.csproj"
$payloadZip = Join-Path $repoRoot "desktop\installer\Assets\payload.zip"
$versionFile = Join-Path $repoRoot "iosApp\Configuration\Version.xcconfig"
$distributableRoot = Join-Path $repoRoot "composeApp\build\compose\binaries\main-release\app"

if (-not (Test-Path -LiteralPath $gradlePath)) { throw "Gradle wrapper not found: $gradlePath" }
if (-not (Test-Path -LiteralPath $installerProject)) { throw "Installer project not found: $installerProject" }
if (-not (Test-Path -LiteralPath $versionFile)) { throw "Version file not found: $versionFile" }

$versionLine = Get-Content -LiteralPath $versionFile | Where-Object { $_ -match '^\s*MARKETING_VERSION\s*=' } | Select-Object -First 1
if ($null -eq $versionLine -or $versionLine -notmatch '=\s*([^\s#]+)') { throw "MARKETING_VERSION is missing from $versionFile" }
$version = $matches[1]

Write-Host "Building Nuvio desktop distributable $version..."
Push-Location $repoRoot
try {
    & $gradlePath ":composeApp:createReleaseDistributable"
    if ($LASTEXITCODE -ne 0) { throw "Desktop distributable build failed with exit code $LASTEXITCODE." }
}
finally {
    Pop-Location
}

$payloadExecutable = Get-ChildItem -LiteralPath $distributableRoot -Filter "Nuvio.exe" -Recurse -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if ($null -eq $payloadExecutable) {
    throw "Nuvio.exe was not found under $distributableRoot"
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
if ($LASTEXITCODE -ne 0) { throw "Nuvio installer build failed with exit code $LASTEXITCODE." }

$installerOutput = Join-Path $repoRoot "desktop\installer\bin\$Configuration\net48\NuvioSetup.exe"
if (-not (Test-Path -LiteralPath $installerOutput)) {
    throw "Installer output was not found: $installerOutput"
}

$finalOutput = Join-Path $repoRoot "desktop\installer\bin\$Configuration\NuvioSetup-$version.exe"
Copy-Item -LiteralPath $installerOutput -Destination $finalOutput -Force
Write-Host "Installer created: $finalOutput"
