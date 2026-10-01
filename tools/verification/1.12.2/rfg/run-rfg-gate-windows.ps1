& {
    $ErrorActionPreference = 'Stop'

    function Convert-WindowsPathToWslMount {
        param([Parameter(Mandatory = $true)][string]$Path)

        $full = [System.IO.Path]::GetFullPath($Path)
        if ($full -notmatch '^([A-Za-z]):\\(.*)$') {
            throw "Unsupported Windows path for WSL mount conversion: $full"
        }

        $drive = $Matches[1].ToLowerInvariant()
        # Use numeric character codes so PowerShell string escaping cannot
        # accidentally turn a single Windows separator into a two-character
        # search string. [char]92='\', [char]47='/'.
        $tail = $Matches[2].Replace([char]92, [char]47)
        return "/mnt/$drive/$tail"
    }

    function ConvertTo-Utf8Base64 {
        param([Parameter(Mandatory = $true)][string]$Value)
        return [System.Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($Value))
    }

    function Invoke-WslProbeWithRetry {
        param(
            [Parameter(Mandatory = $true)][string]$Description,
            [Parameter(Mandatory = $true)][string[]]$Arguments,
            [int]$Attempts = 3
        )

        $lastCode = 1
        for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
            Write-Host "WSL probe: $Description (attempt $attempt/$Attempts)" -ForegroundColor DarkGray
            try {
                $probeOutput = @(& wsl.exe @Arguments 2>&1)
                $lastCode = $LASTEXITCODE
            }
            catch {
                $probeOutput = @($_.Exception.Message)
                $lastCode = 1
            }
            foreach ($line in $probeOutput) {
                Write-Host ([string]$line)
            }
            if ($lastCode -eq 0) {
                return 0
            }
            if ($attempt -lt $Attempts) {
                Start-Sleep -Seconds ([Math]::Min(5, 1 + (2 * $attempt)))
            }
        }
        return $lastCode
    }

    function Write-WslFailureDiagnostics {
        param(
            [Parameter(Mandatory = $true)][string]$Path,
            [Parameter(Mandatory = $true)][string]$Reason
        )

        $lines = New-Object System.Collections.Generic.List[string]
        $lines.Add('Acoustic Shaders RFG Windows/WSL launcher failure')
        $lines.Add("Timestamp: $([DateTimeOffset]::Now.ToString('o'))")
        $lines.Add("Reason: $Reason")
        $lines.Add('')

        $diagnostics = @(
            @{ Label = 'wsl.exe --version'; Args = @('--version') },
            @{ Label = 'wsl.exe --status'; Args = @('--status') },
            @{ Label = 'wsl.exe -l -v'; Args = @('-l', '-v') }
        )

        foreach ($diag in $diagnostics) {
            $lines.Add("=== $($diag.Label) ===")
            $diagArgs = [string[]]$diag.Args
            try {
                $diagOutput = @(& wsl.exe @diagArgs 2>&1)
                $diagCode = $LASTEXITCODE
            }
            catch {
                $diagOutput = @($_.Exception.Message)
                $diagCode = 1
            }
            foreach ($line in $diagOutput) {
                $lines.Add([string]$line)
            }
            $lines.Add("exit_code=$diagCode")
            $lines.Add('')
        }

        [System.IO.File]::WriteAllLines($Path, $lines, (New-Object System.Text.UTF8Encoding($false)))
    }

    # Runtime regression guard for the exact path shape used by the Windows
    # launcher. This executes before any WSL process is started.
    $PathSelfTestInput = 'C:\Users\ExampleUser\Downloads\AcousticShaders-RFG-WSL-RUN.sh'
    $PathSelfTestExpected = '/mnt/c/Users/ExampleUser/Downloads/AcousticShaders-RFG-WSL-RUN.sh'
    $PathSelfTestActual = Convert-WindowsPathToWslMount $PathSelfTestInput
    if ($PathSelfTestActual -ne $PathSelfTestExpected) {
        throw "Windows-to-WSL path conversion self-test failed: expected '$PathSelfTestExpected', got '$PathSelfTestActual'"
    }

    $Downloads = Join-Path $env:USERPROFILE 'Downloads'

    # Never hard-code this repository's own commit or bundle SHA here: doing so would
    # make the launcher stale on the very next commit. Prefer an explicit environment
    # override, otherwise select the newest transferable bundle in Downloads. The
    # bundle filename carries the expected Git short id and the extracted repository
    # is then checked against that id plus a clean working tree.
    $Bundle = $null
    if ($env:ACOUSTIC_RFG_BUNDLE) {
        $Bundle = Get-Item -LiteralPath $env:ACOUSTIC_RFG_BUNDLE -ErrorAction Stop
    }
    else {
        $Bundle = Get-ChildItem -LiteralPath $Downloads -File -Filter 'AcousticShaders-RFG-WSL-Bundle-*.zip' |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
    }

    if (-not $Bundle) {
        throw "No AcousticShaders-RFG-WSL-Bundle-<git-short>.zip found in $Downloads"
    }

    if ($Bundle.Name -notmatch '^AcousticShaders-RFG-WSL-Bundle-([0-9a-fA-F]{7,40})\.zip$') {
        throw "Unexpected RFG bundle filename: $($Bundle.Name)"
    }

    $ExpectedShortHead = $Matches[1].ToLowerInvariant()
    $BundleRoot = [System.IO.Path]::GetFileNameWithoutExtension($Bundle.Name)
    $BundlePath = $Bundle.FullName
    $BundleSha256 = (Get-FileHash -LiteralPath $BundlePath -Algorithm SHA256).Hash.ToLowerInvariant()

    $Result = Join-Path $Downloads 'AcousticShaders-RFG-1.12.2-Result.zip'
    $Failure = Join-Path $Downloads 'AcousticShaders-RFG-FAILED.log'
    $RunLog = Join-Path $Downloads 'AcousticShaders-RFG-WSL-RUN.log'
    $Runner = Join-Path $Downloads 'AcousticShaders-RFG-WSL-RUN.sh'

    Write-Host ''
    Write-Host 'RFG bundle' -ForegroundColor Cyan
    Write-Host "Path:     $BundlePath"
    Write-Host "Git short: $ExpectedShortHead"
    Write-Host "SHA-256:  $BundleSha256"

    Remove-Item -LiteralPath $Result -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $Failure -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $RunLog -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $Runner -Force -ErrorAction SilentlyContinue

    $BundleWsl = Convert-WindowsPathToWslMount $BundlePath
    $DownloadsWsl = Convert-WindowsPathToWslMount $Downloads
    $RunnerWsl = Convert-WindowsPathToWslMount $Runner

    $Template = @'
#!/usr/bin/env bash
set -euo pipefail

BUNDLE_WSL="$(printf '%s' '__BUNDLE_WSL_B64__' | base64 -d)"
DOWNLOADS_WSL="$(printf '%s' '__DOWNLOADS_WSL_B64__' | base64 -d)"
EXPECTED_SHORT_HEAD="$(printf '%s' '__EXPECTED_SHORT_HEAD_B64__' | base64 -d)"
BUNDLE_ROOT="$(printf '%s' '__BUNDLE_ROOT_B64__' | base64 -d)"
TARGET="$HOME/AcousticShaders-RFG-WSL-CURRENT"

printf '%s\n' '=== Acoustic Shaders RFG gate ==='
rm -rf "$TARGET"
mkdir -p "$TARGET"
unzip -q "$BUNDLE_WSL" -d "$TARGET"

PROJECT="$TARGET/$BUNDLE_ROOT/project"
if [[ ! -d "$PROJECT/.git" ]]; then
    echo "ERROR: extracted Git project missing: $PROJECT" >&2
    exit 1
fi
cd "$PROJECT"

printf '%s\n' 'Project:'
git status --short
git log -1 --oneline

ACTUAL_HEAD="$(git rev-parse HEAD)"
case "$ACTUAL_HEAD" in
    "$EXPECTED_SHORT_HEAD"*) ;;
    *)
        echo "ERROR: bundle filename expects Git $EXPECTED_SHORT_HEAD but project HEAD is $ACTUAL_HEAD" >&2
        exit 1
        ;;
esac
if [[ -n "$(git status --porcelain)" ]]; then
    echo 'ERROR: extracted project is not clean' >&2
    git status --short >&2
    exit 1
fi
printf '[PASS] exact clean Git snapshot: %s\n' "$ACTUAL_HEAD"

printf '\n%s\n' 'Disk:'
df -h "$HOME" || true

printf '\n%s\n' 'Checking exact Kotlin 2.4.0...'
KOTLIN_HOME=''
for candidate in \
    "$HOME/acoustic-kotlin-2.4/extracted/kotlinc" \
    "$HOME/acoustic-kotlin-2.4/kotlinc"
do
    if [[ -x "$candidate/bin/kotlinc" ]]; then
        KOTLIN_HOME="$candidate"
        break
    fi
done

if [[ -z "$KOTLIN_HOME" ]]; then
    KOTLIN_ZIP="$DOWNLOADS_WSL/kotlin-compiler-2.4.0.zip"
    KOTLIN_EXPECTED='ba1b9e6eb6ddc3275079224f2e9ea4a2b02eef7d59ce2d38404f04b22613c20a'
    if [[ ! -f "$KOTLIN_ZIP" ]]; then
        echo 'ERROR: exact Kotlin 2.4.0 not found in WSL or Windows Downloads.' >&2
        echo "Expected either an existing WSL installation or: $KOTLIN_ZIP" >&2
        exit 1
    fi
    KOTLIN_ACTUAL="$(sha256sum "$KOTLIN_ZIP" | awk '{print $1}')"
    if [[ "$KOTLIN_ACTUAL" != "$KOTLIN_EXPECTED" ]]; then
        echo 'ERROR: Kotlin 2.4.0 ZIP SHA-256 mismatch' >&2
        exit 1
    fi
    rm -rf "$HOME/acoustic-kotlin-2.4/extracted"
    mkdir -p "$HOME/acoustic-kotlin-2.4/extracted"
    unzip -q "$KOTLIN_ZIP" -d "$HOME/acoustic-kotlin-2.4/extracted"
    KOTLIN_HOME="$HOME/acoustic-kotlin-2.4/extracted/kotlinc"
fi

"$KOTLIN_HOME/bin/kotlinc" -version 2>&1 | grep -F 'kotlinc-jvm 2.4.0'
export ACOUSTIC_KOTLIN_HOME="$KOTLIN_HOME"
export ACOUSTIC_WINDOWS_DOWNLOADS="$DOWNLOADS_WSL"

printf '\n%s\n' '=== Starting real RetroFuturaGradle release gate ==='
bash tools/verification/1.12.2/rfg/bootstrap-wsl.sh
'@

    $WslScript = $Template
    $WslScript = $WslScript.Replace('__BUNDLE_WSL_B64__', (ConvertTo-Utf8Base64 $BundleWsl))
    $WslScript = $WslScript.Replace('__DOWNLOADS_WSL_B64__', (ConvertTo-Utf8Base64 $DownloadsWsl))
    $WslScript = $WslScript.Replace('__EXPECTED_SHORT_HEAD_B64__', (ConvertTo-Utf8Base64 $ExpectedShortHead))
    $WslScript = $WslScript.Replace('__BUNDLE_ROOT_B64__', (ConvertTo-Utf8Base64 $BundleRoot))

    $Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Runner, $WslScript.Replace("`r`n", "`n"), $Utf8NoBom)

    Write-Host ''
    Write-Host 'Checking generated Bash syntax...' -ForegroundColor Cyan
    $SyntaxExitCode = Invoke-WslProbeWithRetry `
        -Description 'generated Bash syntax check' `
        -Arguments @('bash', '-n', $RunnerWsl) `
        -Attempts 3
    if ($SyntaxExitCode -ne 0) {
        $Reason = "Generated Bash runner could not be syntax-checked through WSL after retries: exit code $SyntaxExitCode"
        Write-WslFailureDiagnostics -Path $Failure -Reason $Reason
        Write-Host ''
        Write-Host '[FAIL] WSL service/runtime failed before RFG started' -ForegroundColor Yellow
        Write-Host $Failure
        throw $Reason
    }

    Write-Host ''
    Write-Host 'Starting real RFG release gate in WSL...' -ForegroundColor Cyan
    Write-Host "Runner: $Runner"
    Write-Host ''

    & wsl.exe bash $RunnerWsl 2>&1 | Tee-Object -FilePath $RunLog
    $RfgExitCode = $LASTEXITCODE

    Write-Host ''
    Write-Host "WSL/RFG exit code: $RfgExitCode"

    $Reported = $false
    if (Test-Path -LiteralPath $Result) {
        $Reported = $true
        Write-Host ''
        Write-Host '[PASS] RFG result produced' -ForegroundColor Green
        Write-Host $Result
        $ResultHash = (Get-FileHash -LiteralPath $Result -Algorithm SHA256).Hash.ToLowerInvariant()
        Write-Host "SHA-256: $ResultHash"
    }
    if ((-not $Reported) -and (Test-Path -LiteralPath $Failure)) {
        $Reported = $true
        Write-Host ''
        Write-Host '[FAIL] RFG produced a diagnostic log' -ForegroundColor Yellow
        Write-Host $Failure
    }
    if (-not $Reported) {
        Write-Host ''
        Write-Host 'RFG result/failure bundle was not produced.' -ForegroundColor Yellow
        Write-Host 'Full WSL log:'
        Write-Host $RunLog
    }

    Write-Host ''
    Write-Host 'Send back whichever file was produced:' -ForegroundColor Cyan
    Write-Host '  AcousticShaders-RFG-1.12.2-Result.zip'
    Write-Host '  AcousticShaders-RFG-FAILED.log'
    Write-Host '  AcousticShaders-RFG-WSL-RUN.log (only if neither file above exists)'

    if ($RfgExitCode -ne 0) {
        exit $RfgExitCode
    }
}
