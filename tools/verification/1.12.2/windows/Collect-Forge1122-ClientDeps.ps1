param([string]$Downloads = (Join-Path $env:USERPROFILE 'Downloads'))
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0
Add-Type -AssemblyName System.IO.Compression.FileSystem

$ForgeVersion = '1.12.2-14.23.5.2864'
$ForgeUniversalSha1 = 'd0ab8e116da0e50c6e6099791f97772a08469626'
$MixinSha256 = '71d5f742414bce4b4d570c0a7f1267173555ed1d8a548d40540013c2495cf605'
$ForgelinVersion = '2.4.0.0'
$ForgelinUrl = 'https://cdn.modrinth.com/data/1mPcAmuy/versions/jZIkQLdu/Forgelin-Continuous-2.4.0.0.jar'
$Minecraft = Join-Path $env:APPDATA '.minecraft'
$Stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$Name = "AcousticShaders-MC1122-FORGE-DEPS-$Stamp"
$Work = Join-Path $env:TEMP $Name
$Stage = Join-Path $Work $Name
$Archive = Join-Path $Downloads ($Name + '.zip')

function Ensure-Dir([string]$Path) { if (-not (Test-Path -LiteralPath $Path)) { New-Item -ItemType Directory -Path $Path -Force | Out-Null } }
function Get-Sha1([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA1).Hash.ToLowerInvariant() }
function Get-Sha256([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Test-Zip([string]$Path) {
    try { $z=[IO.Compression.ZipFile]::OpenRead($Path); try { return ($z.Entries.Count -gt 0) } finally { $z.Dispose() } } catch { return $false }
}
function Copy-Or-DownloadLibrary($Meta,[string]$Dest) {
    $expected=[string]$Meta.sha1
    $url=[string]$Meta.url
    if (Test-Path -LiteralPath $Dest -PathType Leaf) {
        if ((Get-Sha1 $Dest) -eq $expected) { return }
        Remove-Item -LiteralPath $Dest -Force
    }
    $relative=[string]$Meta.path
    $existing=Join-Path $Minecraft ('libraries\' + $relative.Replace('/','\'))
    if (Test-Path -LiteralPath $existing -PathType Leaf) {
        if ((Get-Sha1 $existing) -eq $expected) { Copy-Item -LiteralPath $existing -Destination $Dest -Force; return }
    }
    if ([string]::IsNullOrWhiteSpace($url)) { throw "No download URL for $relative" }
    Write-Host "Downloading $relative" -ForegroundColor DarkGray
    Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $Dest -MaximumRedirection 10 -TimeoutSec 180
    $actual=Get-Sha1 $Dest
    if ($actual -ne $expected) { Remove-Item -LiteralPath $Dest -Force -ErrorAction SilentlyContinue; throw "SHA-1 mismatch for $relative expected=$expected actual=$actual" }
}

Remove-Item -LiteralPath $Work -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $Archive -Force -ErrorAction SilentlyContinue
Ensure-Dir $Stage
$ForgeTmp=Join-Path $Work 'forge'
Ensure-Dir $ForgeTmp

$Installer=$null
$Universal=$null
$directInstaller=Join-Path $Downloads 'forge-1.12.2-14.23.5.2864-installer.jar'
$directUniversal=Join-Path $Downloads 'forge-1.12.2-14.23.5.2864-universal.jar'
if ((Test-Path -LiteralPath $directInstaller) -and (Test-Path -LiteralPath $directUniversal)) {
    $Installer=$directInstaller; $Universal=$directUniversal
} else {
    $ForgeTar=Join-Path $Downloads 'forge.tar.xz'
    if (-not (Test-Path -LiteralPath $ForgeTar)) { throw "Need forge.tar.xz or the Forge 2864 installer+universal in Downloads." }
    $Tar=Get-Command tar.exe -ErrorAction SilentlyContinue
    if ($null -eq $Tar) { throw 'Windows tar.exe is required to extract forge.tar.xz.' }
    & $Tar.Source -xf $ForgeTar -C $ForgeTmp 'forge-1.12.2-14.23.5.2864-installer.jar' 'forge-1.12.2-14.23.5.2864-universal.jar'
    if ($LASTEXITCODE -ne 0) { throw "tar.exe failed extracting Forge bundle, rc=$LASTEXITCODE" }
    $Installer=Join-Path $ForgeTmp 'forge-1.12.2-14.23.5.2864-installer.jar'
    $Universal=Join-Path $ForgeTmp 'forge-1.12.2-14.23.5.2864-universal.jar'
}
if ((Get-Sha1 $Universal) -ne $ForgeUniversalSha1) { throw 'Forge 14.23.5.2864 universal SHA-1 mismatch.' }

$zip=[IO.Compression.ZipFile]::OpenRead($Installer)
try {
    $entry=$zip.GetEntry('version.json')
    if ($null -eq $entry) { throw 'Forge installer version.json missing.' }
    $reader=New-Object IO.StreamReader($entry.Open())
    try { $version=($reader.ReadToEnd() | ConvertFrom-Json) } finally { $reader.Dispose() }
} finally { $zip.Dispose() }
if ([string]$version.id -ne '1.12.2-forge-14.23.5.2864') { throw 'Unexpected Forge version.json id.' }

$LibraryRoot=Join-Path $Stage 'libraries'
Ensure-Dir $LibraryRoot
$Count=0
foreach ($lib in $version.libraries) {
    $meta=$lib.downloads.artifact
    if ($null -eq $meta -or [string]::IsNullOrWhiteSpace([string]$meta.path)) { continue }
    $rel=[string]$meta.path
    $dest=Join-Path $LibraryRoot ($rel.Replace('/','\'))
    Ensure-Dir (Split-Path -Parent $dest)
    if ([string]$lib.name -eq 'net.minecraftforge:forge:1.12.2-14.23.5.2864') {
        Copy-Item -LiteralPath $Universal -Destination $dest -Force
        if ((Get-Sha1 $dest) -ne [string]$meta.sha1) { throw 'Staged Forge universal SHA-1 mismatch.' }
    } else {
        Copy-Or-DownloadLibrary $meta $dest
    }
    $Count++
}

$Mods=Join-Path $Stage 'mods'
Ensure-Dir $Mods
$MixinDest=Join-Path $Mods '!mixinbooter-11.15.jar'
$MixinCandidates=@(
    (Join-Path $Downloads '!mixinbooter-11.15.jar'),
    (Join-Path $Minecraft 'mods\!mixinbooter-11.15.jar')
)
$MixinSource=$MixinCandidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
if ($MixinSource -and (Get-Sha256 $MixinSource) -eq $MixinSha256) { Copy-Item -LiteralPath $MixinSource -Destination $MixinDest -Force }
else {
    $urls=@(
      'https://www.curseforge.com/minecraft/mc-mods/mixin-booter/download/8701264/file',
      'https://mediafilez.forgecdn.net/files/8701/264/%21mixinbooter-11.15.jar',
      'https://media.forgecdn.net/files/8701/264/%21mixinbooter-11.15.jar'
    )
    $ok=$false
    foreach ($url in $urls) {
        try {
            Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $MixinDest -MaximumRedirection 10 -TimeoutSec 120
            if ((Test-Zip $MixinDest) -and (Get-Sha256 $MixinDest) -eq $MixinSha256) { $ok=$true; break }
        } catch {}
        Remove-Item -LiteralPath $MixinDest -Force -ErrorAction SilentlyContinue
    }
    if (-not $ok) { throw 'Could not obtain exact MixinBooter 11.15.' }
}

$ForgelinDest=Join-Path $Mods "Forgelin-Continuous-$ForgelinVersion.jar"
$ForgelinCandidates=@(
    (Join-Path $Downloads "Forgelin-Continuous-$ForgelinVersion.jar"),
    (Join-Path $Minecraft "mods\Forgelin-Continuous-$ForgelinVersion.jar")
)
$ForgelinSource=$ForgelinCandidates | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
if ($ForgelinSource) { Copy-Item -LiteralPath $ForgelinSource -Destination $ForgelinDest -Force }
else { Invoke-WebRequest -UseBasicParsing -Uri $ForgelinUrl -OutFile $ForgelinDest -MaximumRedirection 10 -TimeoutSec 180 }
if (-not (Test-Zip $ForgelinDest)) { throw 'Forgelin-Continuous download is not a valid JAR.' }

$Info=Join-Path $Stage 'INFO.txt'
@"
Minecraft=1.12.2
Forge=14.23.5.2864
ForgeLibraries=$Count
MixinBooter=11.15
MixinBooterSHA256=$(Get-Sha256 $MixinDest)
ForgelinContinuous=$ForgelinVersion
ForgelinContinuousSHA256=$(Get-Sha256 $ForgelinDest)
No launcher accounts, profiles, saves, logs or user configuration are included.
"@ | Set-Content -LiteralPath $Info -Encoding UTF8

$Manifest=Join-Path $Stage 'SHA256SUMS.txt'
$lines=New-Object Collections.Generic.List[string]
Get-ChildItem -LiteralPath $Stage -Recurse -File | Where-Object { $_.FullName -ne $Manifest } | Sort-Object FullName | ForEach-Object {
    $rel=$_.FullName.Substring($Stage.Length + 1).Replace('\','/')
    $lines.Add("$(Get-Sha256 $_.FullName)  $rel")
}
$lines | Set-Content -LiteralPath $Manifest -Encoding ASCII
Compress-Archive -LiteralPath $Stage -DestinationPath $Archive -CompressionLevel Optimal
$ArchiveHash=Get-Sha256 $Archive
Write-Host ''
Write-Host '[PASS] Forge 1.12.2 incremental dependency bundle created' -ForegroundColor Green
Write-Host $Archive -ForegroundColor Cyan
Write-Host "SHA-256: $ArchiveHash"
Write-Host "Forge libraries: $Count"
Write-Host 'Send this ZIP back to the development chat.' -ForegroundColor Cyan
Remove-Item -LiteralPath $Work -Recurse -Force -ErrorAction SilentlyContinue
