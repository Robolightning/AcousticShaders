# Acoustic Shaders - Minecraft 1.12.2 Windows/Forge validation harness
# PowerShell 5.1+ / Windows 10-11. Maintainer verification tool; not part of the public release package.
param(
    [string]$BundleRoot = $PSScriptRoot,
    [switch]$CudaFdtdHardwareGate
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0
Add-Type -AssemblyName System.IO.Compression.FileSystem

$ExpectedMixinBooterSha256 = '71d5f742414bce4b4d570c0a7f1267173555ed1d8a548d40540013c2495cf605'
$MixinBooterFileName  = '!mixinbooter-11.15.jar'
$MixinBooterFileId    = '8701264'
$ForgelinContinuousVersion = '2.4.0.0'
$ForgelinContinuousFileName = 'Forgelin-Continuous-2.4.0.0.jar'
$ForgelinContinuousUrl = 'https://cdn.modrinth.com/data/1mPcAmuy/versions/jZIkQLdu/Forgelin-Continuous-2.4.0.0.jar'
$ForgelinContinuousPage = 'https://modrinth.com/mod/forgelin-continuous/version/2.4.0.0'
$CudaNvrtcWheelName = 'nvidia_cuda_nvrtc_cu12-12.2.128-py3-none-win_amd64.whl'
$CudaNvrtcUrl = 'https://files.pythonhosted.org/packages/24/10/7637ec2b1801cf9b51ff07ea85845ed6fea6cb55c18058e14dddb141cd31/nvidia_cuda_nvrtc_cu12-12.2.128-py3-none-win_amd64.whl'
$CudaNvrtcSha256 = 'ad875796162b2518cfbcf649a2ab6e2046eb3ce5407d27c736a7abc887c6a3de'

$Downloads = Join-Path $env:USERPROFILE 'Downloads'
$MinecraftRoot = Join-Path $env:APPDATA '.minecraft'
$SessionStart = Get-Date
$Stamp = $SessionStart.ToString('yyyyMMdd-HHmmss')
$ReportDir = Join-Path $Downloads ("AcousticShaders-TestLogs-" + $Stamp)
$ReportZip = Join-Path $Downloads ("AcousticShaders-TestReport-" + $Stamp + '.zip')
$Staging = $BundleRoot
$TranscriptStarted = $false
$GameStarted = $false
$GamePid = $null
$GameExitCode = $null
$FatalError = $null
$GameDir = $MinecraftRoot
$ForgeVersionId = $null
$ForgeProfileName = $null
$ForgeProfileFile = $null
$AutoPlayAttempted = $false
$AutoPlaySucceeded = $false

function Write-Step([string]$Text) {
    Write-Host ("`n=== " + $Text + " ===") -ForegroundColor Cyan
}

function Ensure-Directory([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        New-Item -ItemType Directory -Path $Path -Force | Out-Null
    }
}

function Test-ZipFile([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $false }
    try {
        $z = [System.IO.Compression.ZipFile]::OpenRead($Path)
        try { return ($z.Entries.Count -gt 0) }
        finally { $z.Dispose() }
    } catch { return $false }
}

function Get-ForgeBuildNumber([string]$Id) {
    if ($Id -match '14\.23\.5\.(\d+)') { return [int]$Matches[1] }
    if ($Id -match 'forge[^0-9]*(\d+)$') { return [int]$Matches[1] }
    return 0
}

function Copy-LogIfExists([string]$Source, [string]$DestinationName) {
    if (Test-Path -LiteralPath $Source -PathType Leaf) {
        Copy-Item -LiteralPath $Source -Destination (Join-Path $ReportDir $DestinationName) -Force -ErrorAction SilentlyContinue
    }
}

function Save-Text([string]$Path, [string]$Text) {
    [System.IO.File]::WriteAllText($Path, $Text, (New-Object System.Text.UTF8Encoding($false)))
}

function Get-AcousticResourcePackDiagnostics([string]$GameDir) {
    $lines = New-Object System.Collections.Generic.List[string]
    $options = Join-Path $GameDir 'options.txt'
    if (Test-Path -LiteralPath $options -PathType Leaf) {
        try {
            $rp = Get-Content -LiteralPath $options -ErrorAction Stop | Where-Object { $_ -like 'resourcePacks:*' } | Select-Object -First 1
            $lines.Add(('options.txt ' + [string]$rp))
        } catch { $lines.Add(('options.txt read failed: ' + $_.Exception.Message)) }
    } else { $lines.Add('options.txt missing') }
    $dir = Join-Path $GameDir 'resourcepacks'
    if (Test-Path -LiteralPath $dir -PathType Container) {
        Get-ChildItem -LiteralPath $dir -Force -ErrorAction SilentlyContinue | Sort-Object Name | ForEach-Object {
            $marker = if (Test-Path -LiteralPath (Join-Path $_.FullName '.acousticshaders-generated.properties') -PathType Leaf) { ' generated-marker' } else { '' }
            $lines.Add(('resourcepack=' + $_.Name + $marker))
        }
    } else { $lines.Add('resourcepacks directory missing') }
    return ($lines -join "`r`n") + "`r`n"
}

function Repair-AcousticResourcePackSelection([string]$GameDir, [string]$ReportDir) {
    $newName = 'Acoustic Shaders Default Materials'
    $legacyName = 'AcousticShaders-Generated-Materials'
    $options = Join-Path $GameDir 'options.txt'
    if (Test-Path -LiteralPath $options -PathType Leaf) {
        try {
            Copy-Item -LiteralPath $options -Destination (Join-Path $ReportDir 'options-before-acoustic-pack-migration.txt') -Force -ErrorAction SilentlyContinue
            $lines = @(Get-Content -LiteralPath $options -ErrorAction Stop)
            $found = $false
            for ($i=0; $i -lt $lines.Count; $i++) {
                if (-not $lines[$i].StartsWith('resourcePacks:')) { continue }
                $found = $true
                $payload = $lines[$i].Substring('resourcePacks:'.Length)
                $packs = New-Object System.Collections.Generic.List[string]
                try {
                    $parsed = ConvertFrom-Json -InputObject $payload -ErrorAction Stop
                    if ($parsed -is [System.Collections.IEnumerable] -and -not ($parsed -is [string])) {
                        foreach ($item in $parsed) { if ($null -ne $item) { $packs.Add([string]$item) } }
                    } elseif ($null -ne $parsed) {
                        $packs.Add([string]$parsed)
                    }
                } catch {}
                $next = New-Object System.Collections.Generic.List[string]
                $seenNew = $false
                foreach ($raw in $packs) {
                    $v = [string]$raw
                    $base = if ($v.StartsWith('file/')) { $v.Substring(5) } else { $v }
                    $malformedOldNew = $legacyName + ' ' + $newName
                    if ($base -eq $legacyName -or $base.StartsWith($legacyName + '-') -or $base -eq $malformedOldNew) { continue }
                    if ($base -eq $newName) {
                        if (-not $seenNew) { $next.Add($newName); $seenNew = $true }
                        continue
                    }
                    if (-not [string]::IsNullOrWhiteSpace($v)) { $next.Add($v) }
                }
                if (-not $seenNew) { $next.Add($newName) }
                $encoded = New-Object System.Collections.Generic.List[string]
                foreach ($item in $next) {
                    $escaped = ([string]$item).Replace('\','\\').Replace('"','\"')
                    $encoded.Add('"' + $escaped + '"')
                }
                $json = '[' + ([string]::Join(',', $encoded.ToArray())) + ']'
                $lines[$i] = 'resourcePacks:' + $json
                break
            }
            if (-not $found) {
                $json = '["' + $newName.Replace('\','\\').Replace('"','\"') + '"]'
                $lines += 'resourcePacks:' + $json
            }
            Save-Text $options (($lines -join "`r`n") + "`r`n")
        } catch {
            Write-Host ('Resource-pack options migration skipped: ' + $_.Exception.Message) -ForegroundColor DarkYellow
        }
    }
    # Remove only a legacy directory proven to be generated by Acoustic Shaders.
    $legacyDir = Join-Path (Join-Path $GameDir 'resourcepacks') $legacyName
    if ((Test-Path -LiteralPath $legacyDir -PathType Container) -and (Test-Path -LiteralPath (Join-Path $legacyDir '.acousticshaders-generated.properties') -PathType Leaf)) {
        try { Remove-Item -LiteralPath $legacyDir -Recurse -Force; Write-Host 'Removed legacy generated acoustic material resource-pack directory.' -ForegroundColor DarkGray } catch {}
    }
}

function Redact-SensitiveText([string]$Text) {
    if ($null -eq $Text) { return '' }
    $r = $Text
    $r = [regex]::Replace($r, '(?i)(--accessToken\s+)(\S+)', '$1<REDACTED>')
    $r = [regex]::Replace($r, '(?i)(--clientId\s+)(\S+)', '$1<REDACTED>')
    $r = [regex]::Replace($r, '(?i)(--xuid\s+)(\S+)', '$1<REDACTED>')
    $r = [regex]::Replace($r, '(?i)("accessToken"\s*:\s*")(.*?)(")', '$1<REDACTED>$3')
    $r = [regex]::Replace($r, '(?i)(Authorization:\s*Bearer\s+)([^\s"'']+)', '$1<REDACTED>')
    return $r
}

function Copy-TextLogSanitized([string]$Source, [string]$DestinationName) {
    if (Test-Path -LiteralPath $Source -PathType Leaf) {
        try {
            $text = Get-Content -LiteralPath $Source -Raw -ErrorAction Stop
            Save-Text (Join-Path $ReportDir $DestinationName) (Redact-SensitiveText $text)
        } catch {
            Copy-Item -LiteralPath $Source -Destination (Join-Path $ReportDir $DestinationName) -Force -ErrorAction SilentlyContinue
        }
    }
}

function Try-DownloadMixinBooter([string]$Destination) {
    $urls = @(
        'https://www.curseforge.com/minecraft/mc-mods/mixin-booter/download/8701264/file',
        'https://mediafilez.forgecdn.net/files/8701/264/%21mixinbooter-11.15.jar',
        'https://media.forgecdn.net/files/8701/264/%21mixinbooter-11.15.jar'
    )
    foreach ($url in $urls) {
        try {
            Write-Host ("Trying MixinBooter 11.15: " + $url) -ForegroundColor DarkGray
            if (Test-Path -LiteralPath $Destination) { Remove-Item -LiteralPath $Destination -Force }
            Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $Destination -MaximumRedirection 10 -TimeoutSec 90
            if (Test-ZipFile $Destination) { return $true }
        } catch {
            Write-Host ("  failed: " + $_.Exception.Message) -ForegroundColor DarkYellow
        }
        if (Test-Path -LiteralPath $Destination) { Remove-Item -LiteralPath $Destination -Force -ErrorAction SilentlyContinue }
    }
    return $false
}

function Try-DownloadForgelinContinuous([string]$Destination) {
    try {
        Write-Host ("Trying Forgelin-Continuous ${ForgelinContinuousVersion}: " + $ForgelinContinuousUrl) -ForegroundColor DarkGray
        if (Test-Path -LiteralPath $Destination) { Remove-Item -LiteralPath $Destination -Force }
        Invoke-WebRequest -UseBasicParsing -Uri $ForgelinContinuousUrl -OutFile $Destination -MaximumRedirection 10 -TimeoutSec 90
        return (Test-ZipFile $Destination)
    } catch {
        Write-Host ("  failed: " + $_.Exception.Message) -ForegroundColor DarkYellow
        if (Test-Path -LiteralPath $Destination) { Remove-Item -LiteralPath $Destination -Force -ErrorAction SilentlyContinue }
        return $false
    }
}

function Ensure-OptionalCudaNvrtc([string]$AcousticConfigDir) {
    $status = New-Object System.Collections.Generic.List[string]
    try {
        $nvidia = @(Get-CimInstance Win32_VideoController -ErrorAction SilentlyContinue | Where-Object { [string]$_.Name -match '(?i)NVIDIA' })
        if ($nvidia.Count -eq 0) {
            $status.Add('NVIDIA GPU not detected; CUDA/NVRTC bootstrap skipped.')
            return ($status -join "`r`n")
        }
        $status.Add(('NVIDIA GPU detected: ' + (($nvidia | ForEach-Object { [string]$_.Name }) -join ', ')))
        $toolkitNvrtc = @()
        if ($env:CUDA_PATH) {
            $cudaBin = Join-Path $env:CUDA_PATH 'bin'
            if (Test-Path -LiteralPath $cudaBin -PathType Container) {
                $toolkitNvrtc = @(Get-ChildItem -LiteralPath $cudaBin -File -Filter 'nvrtc64_*.dll' -ErrorAction SilentlyContinue)
            }
        }
        if ($toolkitNvrtc.Count -gt 0) {
            $status.Add(('Existing CUDA Toolkit NVRTC: ' + $toolkitNvrtc[0].FullName))
            return ($status -join "`r`n")
        }

        $cudaDir = Join-Path $AcousticConfigDir 'cuda'
        Ensure-Directory $cudaDir
        $existing = @(Get-ChildItem -LiteralPath $cudaDir -File -Filter 'nvrtc64_*.dll' -ErrorAction SilentlyContinue)
        if ($existing.Count -gt 0) {
            $status.Add(('Existing Acoustic Shaders NVRTC: ' + $existing[0].FullName))
            return ($status -join "`r`n")
        }

        $wheel = Join-Path $Downloads $CudaNvrtcWheelName
        $needDownload = $true
        if (Test-Path -LiteralPath $wheel -PathType Leaf) {
            try {
                $h = (Get-FileHash -LiteralPath $wheel -Algorithm SHA256).Hash.ToLowerInvariant()
                if ($h -eq $CudaNvrtcSha256) { $needDownload = $false; $status.Add('Using cached pinned NVIDIA NVRTC wheel from Downloads.') }
                else { Remove-Item -LiteralPath $wheel -Force; $status.Add('Discarded cached NVRTC wheel with wrong SHA-256.') }
            } catch { Remove-Item -LiteralPath $wheel -Force -ErrorAction SilentlyContinue }
        }
        if ($needDownload) {
            $status.Add('Downloading optional NVIDIA NVRTC runtime from the official NVIDIA PyPI package...')
            try { Invoke-WebRequest -UseBasicParsing -Uri $CudaNvrtcUrl -OutFile $wheel -MaximumRedirection 10 -TimeoutSec 180 }
            catch { $status.Add(('NVRTC download failed; CUDA will fall back to OpenCL/CPU: ' + $_.Exception.Message)); return ($status -join "`r`n") }
            $h = (Get-FileHash -LiteralPath $wheel -Algorithm SHA256).Hash.ToLowerInvariant()
            if ($h -ne $CudaNvrtcSha256) { Remove-Item -LiteralPath $wheel -Force -ErrorAction SilentlyContinue; $status.Add('NVRTC SHA-256 mismatch; file rejected. CUDA will fall back safely.'); return ($status -join "`r`n") }
        }

        $tmp = Join-Path $env:TEMP ('AcousticShaders-NVRTC-' + [Guid]::NewGuid().ToString('N'))
        Ensure-Directory $tmp
        try {
            [System.IO.Compression.ZipFile]::ExtractToDirectory($wheel, $tmp)
            $dlls = @(Get-ChildItem -LiteralPath $tmp -Recurse -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -match '(?i)^nvrtc(?:-builtins)?64_.*\.dll$' })
            $main = @($dlls | Where-Object { $_.Name -match '(?i)^nvrtc64_.*\.dll$' })
            if ($main.Count -eq 0) { $status.Add('Pinned NVRTC package contained no nvrtc64 DLL; CUDA bootstrap rejected.'); return ($status -join "`r`n") }
            foreach ($d in $dlls) { Copy-Item -LiteralPath $d.FullName -Destination (Join-Path $cudaDir $d.Name) -Force }
            $status.Add(('Installed optional NVRTC DLLs into ' + $cudaDir + ': ' + (($dlls | ForEach-Object { $_.Name }) -join ', ')))
        } finally { if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue } }
    } catch {
        $status.Add(('CUDA/NVRTC bootstrap failed non-fatally: ' + $_.Exception.Message))
    }
    return ($status -join "`r`n")
}

function Try-AutoClickForgePlay([string]$ForgeVersion, [string]$ProfileName, [int]$TimeoutSeconds = 50) {
    # Best-effort accessibility automation. It deliberately refuses to click Play unless
    # the launcher UI exposes evidence that the selected installation is the detected Forge 1.12.2 profile.
    try {
        Add-Type -AssemblyName UIAutomationClient -ErrorAction Stop
        Add-Type -AssemblyName UIAutomationTypes -ErrorAction Stop
    } catch {
        Write-Host ("Windows UI Automation is unavailable: " + $_.Exception.Message) -ForegroundColor DarkYellow
        return $false
    }
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $root = [System.Windows.Automation.AutomationElement]::RootElement
            $windows = $root.FindAll([System.Windows.Automation.TreeScope]::Children, [System.Windows.Automation.Condition]::TrueCondition)
            foreach ($window in $windows) {
                $title = [string]$window.Current.Name
                if ($title -notmatch '(?i)minecraft\s*launcher') { continue }
                $all = $window.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)
                $versionVisible = $false
                $profileVisible = $false
                $playButton = $null
                foreach ($el in $all) {
                    $name = [string]$el.Current.Name
                    if (-not [string]::IsNullOrWhiteSpace($name)) {
                        if ($name.IndexOf($ForgeVersion, [System.StringComparison]::OrdinalIgnoreCase) -ge 0 -or
                            ($name -match '(?i)1\.12\.2' -and $name -match '(?i)forge')) { $versionVisible = $true }
                        if (-not [string]::IsNullOrWhiteSpace($ProfileName) -and $name.Equals($ProfileName, [System.StringComparison]::OrdinalIgnoreCase)) { $profileVisible = $true }
                    }
                    if ($el.Current.ControlType -eq [System.Windows.Automation.ControlType]::Button -and
                        $name -match '^(?i:Play|Играть|PLAY)$') { $playButton = $el }
                }
                # Exact version evidence is sufficient. Profile-name evidence is accepted only when
                # the profile name itself is specific enough to identify Forge, not a generic "latest" profile.
                $safeProfile = $profileVisible -and $ProfileName -match '(?i)forge'
                if ($null -ne $playButton -and ($versionVisible -or $safeProfile)) {
                    try {
                        $pattern = $playButton.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)
                        $pattern.Invoke()
                        Write-Host 'AUTO PLAY: Windows UI Automation invoked the Forge Play button.' -ForegroundColor Green
                        return $true
                    } catch {
                        Write-Host ("Found the Forge Play button but could not invoke it: " + $_.Exception.Message) -ForegroundColor DarkYellow
                    }
                }
            }
        } catch {}
        Start-Sleep -Seconds 2
    }
    return $false
}

Ensure-Directory $ReportDir
try {
    Start-Transcript -Path (Join-Path $ReportDir 'powershell-transcript.txt') -Force | Out-Null
    $TranscriptStarted = $true

    Write-Step '1/8 - Verify extracted all-in-one release bundle'
    if (-not (Test-Path -LiteralPath $MinecraftRoot -PathType Container)) {
        throw "Minecraft directory not found: $MinecraftRoot"
    }
    if (-not (Test-Path -LiteralPath $BundleRoot -PathType Container)) {
        throw "Bundle directory not found: $BundleRoot"
    }
    $Checksums = Join-Path $BundleRoot 'SHA256SUMS.txt'
    if (-not (Test-Path -LiteralPath $Checksums -PathType Leaf)) { throw "SHA256SUMS.txt is missing from bundle." }
    $checksumMap = @{}
    foreach ($line in (Get-Content -LiteralPath $Checksums -ErrorAction Stop)) {
        if ($line -match '^([0-9a-fA-F]{64})\s+\*?(.+)$') { $checksumMap[$Matches[2].Trim()] = $Matches[1].ToLowerInvariant() }
    }
    $ModCandidates = @(Get-ChildItem -LiteralPath $BundleRoot -File -ErrorAction Stop |
        Where-Object { $_.Name -match '(?i)^acoustic-shaders-mc1122-.*\.jar$' } |
        Sort-Object Name)
    if ($ModCandidates.Count -ne 1) { throw "Expected exactly one Acoustic Shaders mod JAR in bundle, found $($ModCandidates.Count)." }
    $ModJar = $ModCandidates[0]
    $RequiredBundleFiles = @($ModJar.Name, 'AcousticShaders-Reference-Hybrid.zip')
    foreach ($name in $RequiredBundleFiles) {
        $file = Join-Path $BundleRoot $name
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Bundle file missing: $name" }
        if (-not $checksumMap.ContainsKey($name)) { throw "No SHA-256 entry for $name" }
        $actual = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actual -ne $checksumMap[$name]) { throw "SHA-256 mismatch for $name" }
        Write-Host ("Verified: " + $name) -ForegroundColor DarkGray
    }
    $modHash = (Get-FileHash -LiteralPath $ModJar.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $ReferencePack = Get-Item -LiteralPath (Join-Path $BundleRoot 'AcousticShaders-Reference-Hybrid.zip')

    Write-Step '2/8 - Detect installed Minecraft 1.12.2 Forge profile'
    $ProfileFiles = @( @(
        (Join-Path $MinecraftRoot 'launcher_profiles.json'),
        (Join-Path $MinecraftRoot 'launcher_profiles_microsoft_store.json')
    ) | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } )

    $ProfileMatches = @()
    foreach ($pf in $ProfileFiles) {
        try {
            $profileJson = Get-Content -LiteralPath $pf -Raw | ConvertFrom-Json
            if ($null -ne $profileJson.profiles) {
                foreach ($prop in $profileJson.profiles.PSObject.Properties) {
                    $p = $prop.Value
                    $vid = [string]$p.lastVersionId
                    if ($vid -match '(?i)1\.12\.2' -and $vid -match '(?i)forge') {
                        $gd = $null
                        if ($null -ne $p.PSObject.Properties['gameDir']) { $gd = [string]$p.gameDir }
                        $ProfileMatches += [pscustomobject]@{
                            Key = [string]$prop.Name
                            VersionId = $vid
                            GameDir = $gd
                            File = $pf
                            Build = (Get-ForgeBuildNumber $vid)
                        }
                    }
                }
            }
        } catch {
            Write-Host "Could not parse launcher profile file $pf : $($_.Exception.Message)" -ForegroundColor DarkYellow
        }
    }

    $VersionMatches = @()
    $VersionsDir = Join-Path $MinecraftRoot 'versions'
    if (Test-Path -LiteralPath $VersionsDir -PathType Container) {
        $VersionMatches = @(Get-ChildItem -LiteralPath $VersionsDir -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '(?i)1\.12\.2' -and $_.Name -match '(?i)forge' } |
            ForEach-Object {
                [pscustomobject]@{ VersionId = $_.Name; Build = (Get-ForgeBuildNumber $_.Name); Path = $_.FullName }
            })
    }

    Write-Host ("Forge candidates: launcher profiles={0}, installed versions={1}" -f @($ProfileMatches).Count, @($VersionMatches).Count) -ForegroundColor DarkGray

    if (@($ProfileMatches).Count -gt 0) {
        $bestProfile = $ProfileMatches | Sort-Object Build -Descending | Select-Object -First 1
        $ForgeVersionId = $bestProfile.VersionId
        $ForgeProfileName = $bestProfile.Key
        $ForgeProfileFile = $bestProfile.File
        if (-not [string]::IsNullOrWhiteSpace($bestProfile.GameDir)) {
            $GameDir = [Environment]::ExpandEnvironmentVariables($bestProfile.GameDir)
        }
    } elseif (@($VersionMatches).Count -gt 0) {
        $bestVersion = $VersionMatches | Sort-Object Build -Descending | Select-Object -First 1
        $ForgeVersionId = $bestVersion.VersionId
        $GameDir = $MinecraftRoot
    } else {
        throw 'No installed Minecraft 1.12.2 Forge version/profile was detected.'
    }

    Ensure-Directory $GameDir
    Write-Host "Forge version: $ForgeVersionId" -ForegroundColor Green
    if ($ForgeProfileName) { Write-Host "Launcher profile: $ForgeProfileName" -ForegroundColor Green }
    Write-Host "Game directory: $GameDir" -ForegroundColor Green

    # Back up launcher profile files and, where possible, preselect the matching Forge profile.
    foreach ($pf in $ProfileFiles) {
        Copy-Item -LiteralPath $pf -Destination (Join-Path $ReportDir ((Split-Path $pf -Leaf) + '.before')) -Force
    }
    if ($ForgeProfileName -and $ForgeProfileFile) {
        try {
            $rawProfiles = Get-Content -LiteralPath $ForgeProfileFile -Raw
            if ($rawProfiles -match '"selectedProfile"\s*:\s*"[^"]*"') {
                $safeProfileName = $ForgeProfileName.Replace('\','\\').Replace('"','\"')
                $replacement = '"selectedProfile": "' + $safeProfileName + '"'
                $newProfiles = [regex]::Replace($rawProfiles, '"selectedProfile"\s*:\s*"[^"]*"', $replacement, 1)
                Save-Text $ForgeProfileFile $newProfiles
                Write-Host 'Forge profile preselected in launcher profile JSON (where supported by the launcher).' -ForegroundColor Green
            }
        } catch {
            Write-Host "Profile preselection skipped: $($_.Exception.Message)" -ForegroundColor DarkYellow
        }
    }

    Write-Step '3/8 - Install mod, shaderpacks, first-test config'
    $ModsDir = Join-Path $GameDir 'mods'
    $AcousticConfigDir = Join-Path $GameDir 'config\acousticshaders'
    $ShaderpacksDir = Join-Path $AcousticConfigDir 'shaderpacks'
    $BackupModsDir = Join-Path $ReportDir 'backup-mods'
    $BackupConfigDir = Join-Path $ReportDir 'backup-config'
    Ensure-Directory $ModsDir
    Ensure-Directory $ShaderpacksDir
    Ensure-Directory $BackupModsDir
    Ensure-Directory $BackupConfigDir

    # Repair the generated material pack selection *before* Minecraft starts.  This prevents the 1.12.2
    # ResourcePackRepository from bootstrapping a stale RC12/RC13 generated-pack Entry that later becomes a ghost.
    Repair-AcousticResourcePackSelection $GameDir $ReportDir
    Save-Text (Join-Path $ReportDir 'resourcepacks-before-launch.txt') (Get-AcousticResourcePackDiagnostics $GameDir)

    # Move only previous Acoustic Shaders JARs out of the way; unrelated mods are untouched.
    Get-ChildItem -LiteralPath $ModsDir -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '(?i)^acoustic[-_ ]?shaders.*\.jar$' } |
        ForEach-Object { Move-Item -LiteralPath $_.FullName -Destination (Join-Path $BackupModsDir $_.Name) -Force }

    Copy-Item -LiteralPath $ModJar.FullName -Destination (Join-Path $ModsDir $ModJar.Name) -Force
    # Remove deprecated official split packs; RC15 folds their purpose into shader-local presets.
    foreach ($deprecated in @('AcousticShaders-Performance.zip','AcousticShaders-Cinematic.zip')) {
        $oldPack = Join-Path $ShaderpacksDir $deprecated
        if (Test-Path -LiteralPath $oldPack -PathType Leaf) { Move-Item -LiteralPath $oldPack -Destination (Join-Path $BackupConfigDir $deprecated) -Force }
    }
    Copy-Item -LiteralPath $ReferencePack.FullName -Destination (Join-Path $ShaderpacksDir 'AcousticShaders-Reference-Hybrid.zip') -Force

    $RuntimeConfig = Join-Path $AcousticConfigDir 'runtime.properties'
    if (Test-Path -LiteralPath $RuntimeConfig -PathType Leaf) {
        Copy-Item -LiteralPath $RuntimeConfig -Destination (Join-Path $BackupConfigDir 'runtime.properties.before') -Force
    }
    $runtimeProfile = if ($CudaFdtdHardwareGate) { 'MAXIMUM' } else { 'HIGH' }
    $runtimeText = @"
# Acoustic Shaders real-client test
# The official Reference Acoustic Shader owns its quality presets and stage controls.
shaderpack=AcousticShaders-Reference-Hybrid.zip
shaderpack.stack=AcousticShaders-Reference-Hybrid.zip
profile=$runtimeProfile
snapshot.horizontalRadius=18
snapshot.verticalRadius=10
snapshot.intervalTicks=10
room.maxProbeDistance=24.0
effects.enabled=true
debug=true
"@
    if ($CudaFdtdHardwareGate) {
        # Isolate the still-unproven hardware path: rays intentionally remain on CPU so a
        # successful CUDA ray trace cannot be mistaken for CUDA FDTD execution.
        $runtimeText += @"
option.COMPUTE_BACKEND=CUDA
option.RAY_COMPUTE_BACKEND=CPU_PARALLEL
"@
    }
    [System.IO.File]::WriteAllText($RuntimeConfig, $runtimeText, [System.Text.Encoding]::ASCII)

    if ($CudaFdtdHardwareGate) {
        Write-Host 'CUDA FDTD hardware gate enabled: MAXIMUM + COMPUTE_BACKEND=CUDA + CPU_PARALLEL rays.' -ForegroundColor Yellow
        Write-Host 'After the world loads, stay in a normal AIR scene long enough for capture to converge, trigger at least one spatial sound (TNT is ideal), then keep playing for ~30 seconds before exiting.' -ForegroundColor Yellow
    }

    Write-Step '4/8 - Ensure MixinBooter 11.15 + Forgelin-Continuous 2.4.0.0'
    $ExistingMixin = Get-ChildItem -LiteralPath $ModsDir -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '(?i)mixinbooter.*11\.15.*\.jar$' } | Select-Object -First 1

    if ($null -eq $ExistingMixin) {
        $DownloadedMixin = Get-ChildItem -LiteralPath $Downloads -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '(?i)mixinbooter.*11\.15.*\.jar$' } |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1

        if ($null -eq $DownloadedMixin) {
            $MixinDownloadPath = Join-Path $Downloads $MixinBooterFileName
            if (-not (Try-DownloadMixinBooter $MixinDownloadPath)) {
                Start-Process 'https://www.curseforge.com/minecraft/mc-mods/mixin-booter/files/8701264' -ErrorAction SilentlyContinue
                throw "MixinBooter 11.15 could not be downloaded automatically. Download file ID $MixinBooterFileId to Downloads and run this block again."
            }
            $DownloadedMixin = Get-Item -LiteralPath $MixinDownloadPath
        }

        if (-not (Test-ZipFile $DownloadedMixin.FullName)) { throw 'Downloaded MixinBooter file is not a valid JAR/ZIP.' }

        # Remove only other MixinBooter versions to avoid loading two bootstrappers.
        Get-ChildItem -LiteralPath $ModsDir -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '(?i)mixinbooter.*\.jar$' } |
            ForEach-Object { Move-Item -LiteralPath $_.FullName -Destination (Join-Path $BackupModsDir $_.Name) -Force }

        Copy-Item -LiteralPath $DownloadedMixin.FullName -Destination (Join-Path $ModsDir $MixinBooterFileName) -Force
        $ExistingMixin = Get-Item -LiteralPath (Join-Path $ModsDir $MixinBooterFileName)
    }
    $mixinHash = ''
    try { $mixinHash = (Get-FileHash -LiteralPath $ExistingMixin.FullName -Algorithm SHA256).Hash.ToLowerInvariant() } catch {}
    Write-Host "MixinBooter: $($ExistingMixin.FullName)" -ForegroundColor Green
    if ($mixinHash) {
        Write-Host "MixinBooter SHA-256: $mixinHash" -ForegroundColor DarkGray
        if ($mixinHash -ne $ExpectedMixinBooterSha256) {
            Write-Host 'Warning: MixinBooter 11.15 hash differs from the file used in the first real test. Continuing, but the report will record it.' -ForegroundColor Yellow
        }
    }

    # Forgelin-Continuous is the Kotlin runtime/library layer for the 1.12.2 frontend.
    # The original Shadowfacts Forgelin must not be loaded alongside it because both ship Kotlin libraries.
    $OldForgelin = @(Get-ChildItem -LiteralPath $ModsDir -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '(?i)forgelin.*\.jar$' -and $_.Name -notmatch '(?i)forgelin[-_ ]?continuous' })
    foreach ($old in $OldForgelin) {
        Write-Host ("Moving incompatible legacy Forgelin out of mods: " + $old.Name) -ForegroundColor Yellow
        Move-Item -LiteralPath $old.FullName -Destination (Join-Path $BackupModsDir $old.Name) -Force
    }

    $ExistingForgelin = Get-ChildItem -LiteralPath $ModsDir -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match ('(?i)^Forgelin-Continuous-' + [regex]::Escape($ForgelinContinuousVersion) + '\.jar$') } |
        Select-Object -First 1

    if ($null -eq $ExistingForgelin) {
        # Remove other Continuous versions first so Forge never sees two copies.
        Get-ChildItem -LiteralPath $ModsDir -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '(?i)^Forgelin-Continuous-.*\.jar$' } |
            ForEach-Object { Move-Item -LiteralPath $_.FullName -Destination (Join-Path $BackupModsDir $_.Name) -Force }

        $DownloadedForgelin = Get-ChildItem -LiteralPath $Downloads -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match ('(?i)^Forgelin-Continuous-' + [regex]::Escape($ForgelinContinuousVersion) + '\.jar$') } |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($null -eq $DownloadedForgelin) {
            $ForgelinDownloadPath = Join-Path $Downloads $ForgelinContinuousFileName
            if (-not (Try-DownloadForgelinContinuous $ForgelinDownloadPath)) {
                Start-Process $ForgelinContinuousPage -ErrorAction SilentlyContinue
                throw "Forgelin-Continuous $ForgelinContinuousVersion could not be downloaded automatically. Download it to Downloads and run this block again."
            }
            $DownloadedForgelin = Get-Item -LiteralPath $ForgelinDownloadPath
        }
        if (-not (Test-ZipFile $DownloadedForgelin.FullName)) { throw 'Downloaded Forgelin-Continuous file is not a valid JAR/ZIP.' }
        Copy-Item -LiteralPath $DownloadedForgelin.FullName -Destination (Join-Path $ModsDir $ForgelinContinuousFileName) -Force
        $ExistingForgelin = Get-Item -LiteralPath (Join-Path $ModsDir $ForgelinContinuousFileName)
    }

    $forgelinHash = ''
    try { $forgelinHash = (Get-FileHash -LiteralPath $ExistingForgelin.FullName -Algorithm SHA256).Hash.ToLowerInvariant() } catch {}
    Write-Host "Forgelin-Continuous: $($ExistingForgelin.FullName)" -ForegroundColor Green
    if ($forgelinHash) { Write-Host "Forgelin-Continuous SHA-256: $forgelinHash" -ForegroundColor DarkGray }
    Save-Text (Join-Path $ReportDir 'forgelin-continuous.txt') (("version={0}`r`npath={1}`r`nsha256={2}`r`n" -f $ForgelinContinuousVersion, $ExistingForgelin.FullName, $forgelinHash))

    Write-Host ''
    Write-Host 'Optional CUDA/NVRTC bootstrap:' -ForegroundColor Cyan
    $CudaNvrtcStatus = Ensure-OptionalCudaNvrtc $AcousticConfigDir
    Write-Host $CudaNvrtcStatus -ForegroundColor DarkGray
    Save-Text (Join-Path $ReportDir 'cuda-nvrtc-bootstrap.txt') ($CudaNvrtcStatus + "`r`n")

    # Record the exact optional native compiler payload actually visible to the mod.
    # This contains filenames, sizes and hashes only; no arbitrary user files are collected.
    try {
        $cudaNativeLines = New-Object System.Collections.Generic.List[string]
        $cudaNativeDir = Join-Path $AcousticConfigDir 'cuda'
        if (Test-Path -LiteralPath $cudaNativeDir -PathType Container) {
            Get-ChildItem -LiteralPath $cudaNativeDir -File -ErrorAction SilentlyContinue | Sort-Object Name | ForEach-Object {
                $h = ''
                try { $h = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() } catch {}
                $cudaNativeLines.Add(("config\cuda\{0}`t{1}`t{2}" -f $_.Name, $_.Length, $h))
            }
        }
        if ($env:CUDA_PATH) {
            $cudaBin = Join-Path $env:CUDA_PATH 'bin'
            if (Test-Path -LiteralPath $cudaBin -PathType Container) {
                Get-ChildItem -LiteralPath $cudaBin -File -ErrorAction SilentlyContinue |
                    Where-Object { $_.Name -match '(?i)^nvrtc(?:-builtins)?64_.*\.dll$' } | Sort-Object Name | ForEach-Object {
                        $h = ''
                        try { $h = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() } catch {}
                        $cudaNativeLines.Add(("CUDA_PATH\bin\{0}`t{1}`t{2}" -f $_.Name, $_.Length, $h))
                    }
            }
        }
        if ($cudaNativeLines.Count -eq 0) { $cudaNativeLines.Add('No NVRTC native files found in Acoustic Shaders config or CUDA_PATH.') }
        Save-Text (Join-Path $ReportDir 'cuda-native-files.txt') (($cudaNativeLines -join "`r`n") + "`r`n")
    } catch { Save-Text (Join-Path $ReportDir 'cuda-native-files.txt') (('CUDA native inventory failed: ' + $_.Exception.Message) + "`r`n") }

    Write-Step '5/8 - Save pre-launch diagnostics'
    $modListing = Get-ChildItem -LiteralPath $ModsDir -File -ErrorAction SilentlyContinue | Sort-Object Name | ForEach-Object {
        $h = ''
        try { $h = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash } catch {}
        "{0}`t{1}`t{2}" -f $_.Name, $_.Length, $h
    }
    Save-Text (Join-Path $ReportDir 'mods-before-launch.txt') (($modListing -join "`r`n") + "`r`n")
    Copy-Item -LiteralPath $RuntimeConfig -Destination (Join-Path $ReportDir 'runtime.properties') -Force

    try {
        $gpuInfo = @(Get-CimInstance Win32_VideoController -ErrorAction SilentlyContinue | ForEach-Object {
            "Name=$($_.Name)`tDriverVersion=$($_.DriverVersion)`tAdapterRAM=$($_.AdapterRAM)"
        })
        Save-Text (Join-Path $ReportDir 'gpu-before-launch.txt') (($gpuInfo -join "`r`n") + "`r`n")
    } catch {}
    try {
        $cpuInfo = @(Get-CimInstance Win32_Processor -ErrorAction SilentlyContinue | ForEach-Object {
            "Name=$($_.Name)`tCores=$($_.NumberOfCores)`tLogicalProcessors=$($_.NumberOfLogicalProcessors)`tMaxClockMHz=$($_.MaxClockSpeed)"
        })
        Save-Text (Join-Path $ReportDir 'cpu-before-launch.txt') (($cpuInfo -join "`r`n") + "`r`n")
    } catch {}
    try {
        $smi = Get-Command nvidia-smi.exe -ErrorAction SilentlyContinue
        if ($null -ne $smi) {
            $smiText = & $smi.Source --query-gpu=name,driver_version,pstate,memory.total,memory.used,utilization.gpu --format=csv,noheader,nounits 2>&1 | Out-String
            Save-Text (Join-Path $ReportDir 'nvidia-smi-before-launch.txt') $smiText
        } else {
            Save-Text (Join-Path $ReportDir 'nvidia-smi-before-launch.txt') "nvidia-smi.exe not found`r`n"
        }
    } catch { Save-Text (Join-Path $ReportDir 'nvidia-smi-before-launch.txt') ("nvidia-smi failed: " + $_.Exception.Message + "`r`n") }

    $info = @()
    $info += "SessionStart=$($SessionStart.ToString('o'))"
    $info += "Windows=$([Environment]::OSVersion.VersionString)"
    $info += "MinecraftRoot=$MinecraftRoot"
    $info += "GameDir=$GameDir"
    $info += "ForgeVersionId=$ForgeVersionId"
    $info += "ForgeProfileName=$ForgeProfileName"
    $info += "ForgeProfileFile=$ForgeProfileFile"
    $info += "BundleRoot=$BundleRoot"
    $info += "ModSHA256=$modHash"
    $info += "MixinBooterSHA256=$mixinHash"
    Save-Text (Join-Path $ReportDir 'environment.txt') (($info -join "`r`n") + "`r`n")

    Write-Step '6/8 - Launch Minecraft Launcher'
    $BeforeJava = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -in @('java.exe','javaw.exe') } |
        ForEach-Object { [int]$_.ProcessId })

    # Never use minecraft:// here. On machines where that URI is not registered,
    # Windows redirects it to Microsoft Store, which is not what we want.
    $LauncherStarted = $false
    $LauncherDescription = $null

    # 1) Prefer an actual launcher executable.
    $launcherCandidates = @()
    if (${env:ProgramFiles(x86)}) { $launcherCandidates += (Join-Path ${env:ProgramFiles(x86)} 'Minecraft Launcher\MinecraftLauncher.exe') }
    if ($env:ProgramFiles) { $launcherCandidates += (Join-Path $env:ProgramFiles 'Minecraft Launcher\MinecraftLauncher.exe') }
    if ($env:LOCALAPPDATA) {
        $launcherCandidates += (Join-Path $env:LOCALAPPDATA 'Programs\Minecraft Launcher\MinecraftLauncher.exe')
        $launcherCandidates += (Join-Path $env:LOCALAPPDATA 'Minecraft Launcher\MinecraftLauncher.exe')
    }

    # Installed-app registry entries sometimes reveal a non-standard path.
    $uninstallRoots = @(
        'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*',
        'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*',
        'HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*'
    )
    foreach ($root in $uninstallRoots) {
        try {
            Get-ItemProperty $root -ErrorAction SilentlyContinue |
                Where-Object { $null -ne $_.PSObject.Properties['DisplayName'] -and [string]$_.DisplayName -match '(?i)^Minecraft Launcher$' } |
                ForEach-Object {
                    if ($_.InstallLocation) {
                        $launcherCandidates += (Join-Path ([string]$_.InstallLocation) 'MinecraftLauncher.exe')
                    }
                    $icon = [string]$_.DisplayIcon
                    if ($icon -match '^\"([^\"]+\.exe)\"') { $launcherCandidates += $Matches[1] }
                    elseif ($icon -match '^([^,]+\.exe)') { $launcherCandidates += $Matches[1].Trim() }
                }
        } catch {}
    }

    $LauncherExe = @($launcherCandidates | Where-Object {
        -not [string]::IsNullOrWhiteSpace($_) -and (Test-Path -LiteralPath $_ -PathType Leaf)
    } | Select-Object -Unique | Select-Object -First 1)

    if (@($LauncherExe).Count -gt 0) {
        $LauncherPath = [string]$LauncherExe[0]
        Write-Host "Launcher EXE: $LauncherPath" -ForegroundColor Green
        try {
            Start-Process -FilePath $LauncherPath | Out-Null
            $LauncherStarted = $true
            $LauncherDescription = $LauncherPath
        } catch {
            Write-Host "Could not start launcher EXE: $($_.Exception.Message)" -ForegroundColor DarkYellow
        }
    }

    # 2) If the EXE is hidden behind a Start Menu / Store registration, use the
    # installed Start-app registration itself. This opens the installed launcher;
    # unlike minecraft:// it does NOT search the Microsoft Store when absent.
    if (-not $LauncherStarted) {
        try {
            $startApps = @(Get-StartApps -ErrorAction Stop | Where-Object {
                $null -ne $_.PSObject.Properties['Name'] -and [string]$_.Name -match '(?i)^Minecraft Launcher$'
            })
            if ($startApps.Count -gt 0) {
                $app = $startApps[0]
                $appId = [string]$app.AppID
                Write-Host "Launcher Start-app: $($app.Name) [$appId]" -ForegroundColor Green
                Start-Process -FilePath 'explorer.exe' -ArgumentList ('shell:AppsFolder\' + $appId) | Out-Null
                $LauncherStarted = $true
                $LauncherDescription = 'StartApps:' + $appId
            }
        } catch {
            Write-Host "Installed Start-app launcher detection failed: $($_.Exception.Message)" -ForegroundColor DarkYellow
        }
    }

    # 3) Last local-only fallback: resolve an existing Start Menu shortcut.
    if (-not $LauncherStarted) {
        try {
            $shortcutRoots = @(
                (Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs'),
                (Join-Path $env:ProgramData 'Microsoft\Windows\Start Menu\Programs'),
                (Join-Path $env:USERPROFILE 'Desktop'),
                (Join-Path $env:PUBLIC 'Desktop')
            ) | Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Container) }

            $shortcut = @($shortcutRoots | ForEach-Object {
                Get-ChildItem -LiteralPath $_ -Recurse -File -Filter '*.lnk' -ErrorAction SilentlyContinue
            } | Where-Object { $_.BaseName -match '(?i)^Minecraft Launcher$' } | Select-Object -First 1)

            if ($shortcut.Count -gt 0) {
                $shortcutPath = $shortcut[0].FullName
                Write-Host "Launcher shortcut: $shortcutPath" -ForegroundColor Green
                Start-Process -FilePath $shortcutPath | Out-Null
                $LauncherStarted = $true
                $LauncherDescription = $shortcutPath
            }
        } catch {
            Write-Host "Start Menu shortcut detection failed: $($_.Exception.Message)" -ForegroundColor DarkYellow
        }
    }

    Save-Text (Join-Path $ReportDir 'launcher-detection.txt') ((@(
        "LauncherStarted=$LauncherStarted",
        "LauncherDescription=$LauncherDescription",
        "ForgeVersionId=$ForgeVersionId"
    ) -join "`r`n") + "`r`n")

    Write-Host ''
    if ($LauncherStarted) {
        Write-Host 'Minecraft Launcher was opened.' -ForegroundColor Green
        Write-Host 'Trying safe best-effort Windows UI Automation for the detected Forge profile...' -ForegroundColor Cyan
        $AutoPlayAttempted = $true
        $AutoPlaySucceeded = Try-AutoClickForgePlay $ForgeVersionId $ForgeProfileName 50
        if (-not $AutoPlaySucceeded) {
            Write-Host 'Automatic Play could not be verified/invoked on this Launcher build.' -ForegroundColor Yellow
            Write-Host ('MANUAL FALLBACK: click Play ONCE for Forge 1.12.2: ' + $ForgeVersionId) -ForegroundColor Yellow
        }
    } else {
        Write-Host 'Minecraft Launcher could not be located automatically.' -ForegroundColor Yellow
        Write-Host 'Open your normal Minecraft Launcher manually now.' -ForegroundColor Yellow
        Write-Host ('Then click Play ONCE for Forge 1.12.2: ' + $ForgeVersionId) -ForegroundColor Yellow
    }
    Write-Host 'This console is waiting for the Forge java/javaw process, not frozen.' -ForegroundColor Cyan
    Write-Host 'Leave it open. After Minecraft closes, logs will be packed automatically.' -ForegroundColor Cyan

    Write-Step '7/8 - Wait for the Forge 1.12.2 game process'
    $Deadline = (Get-Date).AddMinutes(20)
    $NextStatus = Get-Date
    $GameCim = $null
    while ((Get-Date) -lt $Deadline -and $null -eq $GameCim) {
        $candidates = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -in @('java.exe','javaw.exe') -and $BeforeJava -notcontains [int]$_.ProcessId } |
            ForEach-Object {
                $cmd = [string]$_.CommandLine
                $score = 0
                if ($cmd -match '(?i)net\.minecraft\.launchwrapper\.Launch') { $score += 100 }
                if ($cmd -match '(?i)1\.12\.2') { $score += 30 }
                if ($cmd -match '(?i)forge') { $score += 30 }
                if ($cmd -match '(?i)--gameDir') { $score += 10 }
                if ($cmd -match '(?i)minecraft') { $score += 10 }
                [pscustomobject]@{ Process = $_; Score = $score; CommandLine = $cmd }
            } | Where-Object { $_.Score -ge 60 } | Sort-Object Score -Descending)

        $bestGame = $candidates | Select-Object -First 1
        if ($null -ne $bestGame) { $GameCim = $bestGame.Process; break }

        if ((Get-Date) -ge $NextStatus) {
            $remaining = [math]::Max(0, [int][math]::Ceiling(($Deadline - (Get-Date)).TotalMinutes))
            $launcherProc = @(Get-Process -ErrorAction SilentlyContinue | Where-Object {
                $_.ProcessName -match '(?i)minecraft.*launcher|minecraftlauncher'
            })
            Write-Host ("Waiting for Forge 1.12.2... {0} min left; launcher-processes={1}" -f $remaining, $launcherProc.Count) -ForegroundColor DarkGray
            $NextStatus = (Get-Date).AddSeconds(15)
        }
        Start-Sleep -Seconds 2
    }

    if ($null -eq $GameCim) {
        throw 'No Forge 1.12.2 Java game process was detected within 20 minutes. The installer/configuration step completed; only the game launch was not detected.'
    }

    $GameStarted = $true
    $GamePid = [int]$GameCim.ProcessId
    Save-Text (Join-Path $ReportDir 'game-command-line.txt') ((Redact-SensitiveText ([string]$GameCim.CommandLine)) + "`r`n")
    Write-Host "Detected Minecraft Java process PID=$GamePid" -ForegroundColor Green
    Write-Host 'Play normally. Close Minecraft when you are done; log collection will continue automatically.' -ForegroundColor Green

    $GameProcess = [System.Diagnostics.Process]::GetProcessById($GamePid)
    $GameProcess.WaitForExit()
    try { $GameExitCode = $GameProcess.ExitCode } catch { $GameExitCode = $null }
    Write-Host "Minecraft process exited. ExitCode=$GameExitCode"

} catch {
    $FatalError = $_.Exception.ToString()
    Write-Host "`nERROR: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host $_.Exception.ToString() -ForegroundColor DarkRed
} finally {
    Write-Step '8/8 - Collect logs (success and crash paths)'
    try {
        Ensure-Directory $ReportDir
        if (Test-Path -LiteralPath $GameDir -PathType Container) {
            Copy-TextLogSanitized (Join-Path $GameDir 'logs\latest.log') 'latest.log'
            Copy-TextLogSanitized (Join-Path $GameDir 'logs\debug.log') 'debug.log'
            Copy-TextLogSanitized (Join-Path $GameDir 'fml-client-latest.log') 'fml-client-latest.log'
            Copy-TextLogSanitized (Join-Path $GameDir 'logs\fml-client-latest.log') 'logs-fml-client-latest.log'

            $crashDir = Join-Path $GameDir 'crash-reports'
            if (Test-Path -LiteralPath $crashDir -PathType Container) {
                Ensure-Directory (Join-Path $ReportDir 'crash-reports')
                Get-ChildItem -LiteralPath $crashDir -File -ErrorAction SilentlyContinue |
                    Where-Object { $_.LastWriteTime -ge $SessionStart.AddMinutes(-1) } |
                    ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $ReportDir 'crash-reports') -Force -ErrorAction SilentlyContinue }
            }

            foreach ($root in @($GameDir, $MinecraftRoot)) {
                Get-ChildItem -LiteralPath $root -File -Filter 'hs_err_pid*.log' -ErrorAction SilentlyContinue |
                    Where-Object { $_.LastWriteTime -ge $SessionStart.AddMinutes(-1) } |
                    ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $ReportDir -Force -ErrorAction SilentlyContinue }
            }

            $acousticDir = Join-Path $GameDir 'config\acousticshaders'
            if (Test-Path -LiteralPath $acousticDir -PathType Container) {
                $acousticCopy = Join-Path $ReportDir 'acousticshaders-config-after'
                if (Test-Path -LiteralPath $acousticCopy) { Remove-Item -LiteralPath $acousticCopy -Recurse -Force }
                Copy-Item -LiteralPath $acousticDir -Destination $acousticCopy -Recurse -Force -ErrorAction SilentlyContinue
            }
        }

        Copy-TextLogSanitized (Join-Path $MinecraftRoot 'launcher_log.txt') 'launcher_log.txt'
        Copy-TextLogSanitized (Join-Path $MinecraftRoot 'launcher_cef_log.txt') 'launcher_cef_log.txt'

        if (Test-Path -LiteralPath (Join-Path $GameDir 'mods') -PathType Container) {
            $modsAfter = Get-ChildItem -LiteralPath (Join-Path $GameDir 'mods') -File -ErrorAction SilentlyContinue | Sort-Object Name | ForEach-Object {
                $h = ''
                try { $h = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash } catch {}
                "{0}`t{1}`t{2}" -f $_.Name, $_.Length, $h
            }
            Save-Text (Join-Path $ReportDir 'mods-after-test.txt') (($modsAfter -join "`r`n") + "`r`n")
        }
        if (Test-Path -LiteralPath $GameDir -PathType Container) {
            Save-Text (Join-Path $ReportDir 'resourcepacks-after-test.txt') (Get-AcousticResourcePackDiagnostics $GameDir)
        }

        $crashCount = 0
        if (Test-Path -LiteralPath (Join-Path $ReportDir 'crash-reports') -PathType Container) {
            $crashCount = @(Get-ChildItem -LiteralPath (Join-Path $ReportDir 'crash-reports') -File -ErrorAction SilentlyContinue).Count
        }
        $hsCount = @(Get-ChildItem -LiteralPath $ReportDir -File -Filter 'hs_err_pid*.log' -ErrorAction SilentlyContinue).Count
        $hardFailure = $false
        $failureSignature = ''
        foreach ($logName in @('latest.log','debug.log','fml-client-latest.log','logs-fml-client-latest.log')) {
            $lp = Join-Path $ReportDir $logName
            if (Test-Path -LiteralPath $lp -PathType Leaf) {
                try {
                    foreach ($line in (Get-Content -LiteralPath $lp -ErrorAction Stop)) {
                        if ($line -match '(?i)(MixinApplyError|InvalidMixinException|Mixin prepare for mod acousticshaders failed|\[FATAL\].*acousticshaders|Exception in thread.*Launch)') {
                            $hardFailure = $true
                            if ([string]::IsNullOrWhiteSpace($failureSignature)) { $failureSignature = $line.Trim() }
                            break
                        }
                    }
                } catch {}
            }
            if ($hardFailure) { break }
        }
        $cudaFdtdGatePassed = $false
        $cudaFdtdEvidence = ''
        if ($CudaFdtdHardwareGate) {
            foreach ($logName in @('latest.log','debug.log','fml-client-latest.log','logs-fml-client-latest.log')) {
                $lp = Join-Path $ReportDir $logName
                if (-not (Test-Path -LiteralPath $lp -PathType Leaf)) { continue }
                try {
                    foreach ($line in (Get-Content -LiteralPath $lp -ErrorAction Stop)) {
                        if ($line -notmatch 'waveCompute=\{([^}]*)\}') { continue }
                        $wave = $Matches[1]
                        if ($wave -match '(?:^|;)cuda avail=true solves=([1-9][0-9]*) failures=0 .*?self-test=pass') {
                            $cudaFdtdGatePassed = $true
                            $cudaFdtdEvidence = $line.Trim()
                        }
                    }
                } catch {}
            }
        }

        $likelySuccess = ($GameStarted -and -not $hardFailure -and $crashCount -eq 0 -and $hsCount -eq 0 -and ($null -eq $GameExitCode -or $GameExitCode -eq 0))
        if ($CudaFdtdHardwareGate -and -not $cudaFdtdGatePassed) { $likelySuccess = $false }

        $resultLines = @()
        $resultLines += "GameStarted=$GameStarted"
        $resultLines += "GamePid=$GamePid"
        $resultLines += "GameExitCode=$GameExitCode"
        $resultLines += "CrashReports=$crashCount"
        $resultLines += "HsErrLogs=$hsCount"
        $resultLines += "HardFailureInLogs=$hardFailure"
        $resultLines += "LikelySuccess=$likelySuccess"
        $resultLines += "CudaFdtdHardwareGateRequested=$([bool]$CudaFdtdHardwareGate)"
        $resultLines += "CudaFdtdHardwareGatePassed=$cudaFdtdGatePassed"
        if (-not [string]::IsNullOrWhiteSpace($cudaFdtdEvidence)) { $resultLines += "CudaFdtdEvidence=$cudaFdtdEvidence" }
        if (-not [string]::IsNullOrWhiteSpace($failureSignature)) { $resultLines += "FailureSignature=$failureSignature" }
        $resultLines += "AutoPlayAttempted=$AutoPlayAttempted"
        $resultLines += "AutoPlaySucceeded=$AutoPlaySucceeded"
        $resultLines += "ForgeVersionId=$ForgeVersionId"
        $resultLines += "ForgeProfileName=$ForgeProfileName"
        $resultLines += "GameDir=$GameDir"
        if ($FatalError) { $resultLines += "PowerShellError=$FatalError" }
        Save-Text (Join-Path $ReportDir 'RESULT.txt') (($resultLines -join "`r`n") + "`r`n")

    } catch {
        Write-Host "Log collection error: $($_.Exception.Message)" -ForegroundColor Red
    }

    if ($TranscriptStarted) {
        try { Stop-Transcript | Out-Null } catch {}
    }

    try {
        if (Test-Path -LiteralPath $ReportZip) { Remove-Item -LiteralPath $ReportZip -Force }
        Compress-Archive -Path (Join-Path $ReportDir '*') -DestinationPath $ReportZip -CompressionLevel Optimal -Force
        Write-Host "`n============================================================" -ForegroundColor Cyan
        Write-Host "Acoustic Shaders test session finished; RESULT.txt contains the classified outcome." -ForegroundColor Cyan
        Write-Host "Report folder: $ReportDir"
        Write-Host "Report ZIP:    $ReportZip" -ForegroundColor Green
        Write-Host 'Send me that ZIP whether the test succeeded or crashed.' -ForegroundColor Green
        Write-Host "============================================================`n" -ForegroundColor Cyan
        Start-Process explorer.exe -ArgumentList ("/select,`"" + $ReportZip + "`"") -ErrorAction SilentlyContinue
    } catch {
        Write-Host "Could not create report ZIP: $($_.Exception.Message)" -ForegroundColor Red
        Write-Host "Raw report folder: $ReportDir"
    }
}
