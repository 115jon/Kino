param(
    [switch]$Force
)

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$resourceDirectory = Join-Path $repoRoot "composeApp\src\desktopMain\resources\win32-x86-64"
$manifestPath = Join-Path $repoRoot "desktop\runtime\windows-mpv-runtime.json"
$requiredFiles = @("libmpv-2.dll", "mpv.dll")

if (-not (Test-Path -LiteralPath $manifestPath)) {
    throw "MPV runtime manifest was not found: $manifestPath"
}

$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if ([string]::IsNullOrWhiteSpace($manifest.url)) { throw "MPV runtime URL is missing." }
if ([string]::IsNullOrWhiteSpace($manifest.sha256) -or $manifest.sha256 -eq "REPLACE_WITH_RELEASE_ASSET_SHA256") {
    throw "MPV runtime SHA-256 is not configured in $manifestPath"
}

if (-not $Force -and ($requiredFiles | ForEach-Object { Test-Path -LiteralPath (Join-Path $resourceDirectory $_) } | Where-Object { -not $_ }).Count -eq 0) {
    Write-Host "Windows MPV runtime is already present."
    exit 0
}

if (-not (Test-Path -LiteralPath $resourceDirectory)) {
    New-Item -ItemType Directory -Path $resourceDirectory -Force | Out-Null
}

$tempDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ("kino-mpv-runtime-" + [guid]::NewGuid().ToString("N"))
$archivePath = Join-Path $tempDirectory "runtime.zip"
$extractDirectory = Join-Path $tempDirectory "extracted"

try {
    New-Item -ItemType Directory -Path $extractDirectory -Force | Out-Null
    Write-Host "Downloading Windows MPV runtime $($manifest.version)..."
    Invoke-WebRequest -Uri $manifest.url -OutFile $archivePath -UseBasicParsing

    $actualHash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
    $expectedHash = ([string]$manifest.sha256).Trim().ToLowerInvariant()
    if ($actualHash -ne $expectedHash) {
        throw "MPV runtime checksum mismatch. Expected $expectedHash, received $actualHash."
    }

    Expand-Archive -LiteralPath $archivePath -DestinationPath $extractDirectory -Force
    foreach ($fileName in $requiredFiles) {
        $source = Get-ChildItem -LiteralPath $extractDirectory -Filter $fileName -Recurse -File | Select-Object -First 1
        if ($null -eq $source) { throw "MPV runtime archive is missing $fileName" }
        Copy-Item -LiteralPath $source.FullName -Destination (Join-Path $resourceDirectory $fileName) -Force
    }
    Write-Host "Windows MPV runtime prepared in $resourceDirectory"
}
finally {
    if (Test-Path -LiteralPath $tempDirectory) {
        Remove-Item -LiteralPath $tempDirectory -Recurse -Force -ErrorAction SilentlyContinue
    }
}
