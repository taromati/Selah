# -- SearXNG installer for Selah (Windows) --
#
# Installs SearXNG (meta-search engine) as a local service for Agent's web_search tool.
# Can be called from install.ps1 or run standalone.
#
# Steps:
#   1. Check Python 3.11+ (install via winget if missing, or show instructions)
#   2. Check uv (Python package manager)
#   3. Download SearXNG source to ~/.selah/searxng/ as tarball
#   4. Create Python venv + install SearXNG
#   5. Generate settings.yml
#   6. Generate start/stop scripts

$ErrorActionPreference = "Stop"

$SelahHome = if ($env:SELAH_HOME) { $env:SELAH_HOME } else { Join-Path $env:USERPROFILE ".selah" }
$SearxngDir = Join-Path $SelahHome "searxng"
$SearxngPort = if ($env:SEARXNG_PORT) { $env:SEARXNG_PORT } else { "8888" }
$SearxngArchive = "https://github.com/searxng/searxng/archive/refs/heads/master.tar.gz"

function Write-Info($msg)    { Write-Host "  $msg" -ForegroundColor Cyan }
function Write-Success($msg) { Write-Host "  [OK] $msg" -ForegroundColor Green }
function Write-Warn($msg)    { Write-Host "  ! $msg" -ForegroundColor Yellow }
function Write-Err($msg)     { Write-Host "  x $msg" -ForegroundColor Red }

# -- Step 1: Check Python 3.11+ --

function Find-Python {
    # Search common command names first
    foreach ($cmd in @("python", "python3")) {
        try {
            $output = & $cmd --version 2>&1 | Out-String
            if ($output -match '(\d+)\.(\d+)') {
                $major = [int]$Matches[1]
                $minor = [int]$Matches[2]
                # major > 3 always passes; major == 3 requires minor >= 11
                if ($major -gt 3 -or ($major -eq 3 -and $minor -ge 11)) {
                    return $cmd
                }
            }
        } catch {}
    }

    # Try py launcher for specific versions -- resolve actual python.exe path
    foreach ($ver in @("3.13", "3.12", "3.11")) {
        try {
            $output = & py "-$ver" --version 2>&1 | Out-String
            if ($output -match 'Python') {
                # Resolve the actual python.exe path that py launcher uses
                $resolved = & py "-$ver" -c "import sys; print(sys.executable)" 2>&1 | Out-String
                $resolved = $resolved.Trim()
                if ($resolved -and (Test-Path $resolved)) {
                    return $resolved
                }
                # Don't use py launcher itself if resolve fails (uv compatibility issues)
            }
        } catch {}
    }

    return $null
}

function Refresh-PathFromRegistry {
    # Reload current session PATH from registry (User + Machine)
    $machinePath = [Environment]::GetEnvironmentVariable("PATH", "Machine")
    $userPath = [Environment]::GetEnvironmentVariable("PATH", "User")
    $env:PATH = "$userPath;$machinePath"
}

function Install-Python {
    Write-Info "Python 3.11+ not found."

    # Check for winget
    $hasWinget = $false
    try {
        $null = & winget --version 2>&1
        if ($LASTEXITCODE -eq 0) { $hasWinget = $true }
    } catch {}

    if ($hasWinget) {
        Write-Info "Installing Python 3.12 via winget..."
        & winget install Python.Python.3.12 --accept-package-agreements --accept-source-agreements
        if ($LASTEXITCODE -ne 0) {
            Write-Warn "winget install failed (exitcode: $LASTEXITCODE). Manual installation required."
        } else {
            Write-Success "Python 3.12 installed"
            Refresh-PathFromRegistry
            return $true
        }
    }

    # winget unavailable or failed -- show instructions and wait
    Write-Host ""
    Write-Warn "Cannot install Python automatically."
    Write-Host ""
    Write-Host "  Install Python 3.11+ using one of the following:" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "  1) Microsoft Store (easiest)" -ForegroundColor White
    Write-Host "     Search 'Python' in Start Menu -> install Python 3.12 from Microsoft Store" -ForegroundColor Gray
    Write-Host ""
    Write-Host "  2) Official installer" -ForegroundColor White
    Write-Host "     https://www.python.org/downloads/" -ForegroundColor Cyan
    Write-Host "     Make sure to check 'Add Python to PATH' during installation!" -ForegroundColor Yellow
    Write-Host ""
    if (-not $hasWinget) {
        Write-Host "  3) Install winget and retry" -ForegroundColor White
        Write-Host "     Update 'App Installer' from Microsoft Store to enable winget." -ForegroundColor Gray
        Write-Host ""
    }

    # Interactive wait -- press Enter to continue after installation
    Read-Host "  Press Enter after Python installation is complete"
    Refresh-PathFromRegistry

    if (Find-Python) {
        return $true
    }

    # Give one more chance
    Write-Warn "Python still not found. Please check PATH registration."
    Write-Host "  Make sure 'Add Python to PATH' was checked during installation." -ForegroundColor Yellow
    Write-Host "  If already installed, rerun the installer -> 'Modify' -> check 'Add to PATH'" -ForegroundColor Gray
    Write-Host ""
    Read-Host "  Press Enter after confirming"
    Refresh-PathFromRegistry

    if (Find-Python) {
        return $true
    }

    return $false
}

function Get-PythonVersion($cmd) {
    try {
        $output = & $cmd --version 2>&1 | Out-String
        return $output.Trim()
    } catch { return $cmd }
}

function Ensure-Python {
    $python = Find-Python
    if ($python) {
        Write-Success "Python found: $(Get-PythonVersion $python)"
        $script:PythonCmd = $python
        return $true
    }

    $installed = Install-Python
    if ($installed) {
        $python = Find-Python
        if ($python) {
            Write-Success "Python confirmed: $(Get-PythonVersion $python)"
            $script:PythonCmd = $python
            return $true
        }
    }

    Write-Err "Python not found. Please rerun in a new terminal."
    return $false
}

# -- Step 2: Check uv --

function Ensure-Uv {
    # Try PATH first
    try {
        $null = & uv --version 2>&1
        if ($LASTEXITCODE -eq 0) {
            $ver = & uv --version 2>&1 | Out-String
            Write-Success "uv found: $($ver.Trim())"
            return $true
        }
    } catch {}

    # Check common install locations
    $uvCandidates = @(
        "$env:USERPROFILE\.local\bin\uv.exe",
        "$env:USERPROFILE\.cargo\bin\uv.exe",
        "$env:LOCALAPPDATA\uv\uv.exe"
    )
    foreach ($candidate in $uvCandidates) {
        if (Test-Path $candidate) {
            $dir = Split-Path $candidate -Parent
            $env:PATH = "$dir;$env:PATH"
            Write-Success "uv found: $candidate"
            return $true
        }
    }

    Write-Info "Installing uv..."
    Invoke-RestMethod https://astral.sh/uv/install.ps1 | Invoke-Expression
    if ($LASTEXITCODE -ne 0) {
        throw "uv installation failed (exitcode: $LASTEXITCODE). Manual install: https://docs.astral.sh/uv/"
    }

    # Refresh PATH after install
    foreach ($candidate in $uvCandidates) {
        if (Test-Path $candidate) {
            $dir = Split-Path $candidate -Parent
            $env:PATH = "$dir;$env:PATH"
            break
        }
    }

    try {
        $null = & uv --version 2>&1
        if ($LASTEXITCODE -ne 0) { throw "exitcode $LASTEXITCODE" }
        Write-Success "uv installed"
        return $true
    } catch {
        throw "uv not found after installation. Please rerun in a new terminal."
    }
}

# -- Step 3: Download SearXNG source --

function Setup-SearxngSource {
    # Clean up leftover temp dir from previous run
    $tempExtract = Join-Path $env:USERPROFILE ".selah-tmp"
    if (Test-Path $tempExtract) { Remove-Item -Recurse -Force $tempExtract -ErrorAction SilentlyContinue }

    if (-not (Test-Path (Join-Path $SearxngDir "searx"))) {
        Write-Info "Downloading SearXNG..."
        $parentDir = Split-Path $SearxngDir -Parent
        if (-not (Test-Path $parentDir)) { New-Item -ItemType Directory -Force -Path $parentDir | Out-Null }
        if (-not (Test-Path $SearxngDir)) { New-Item -ItemType Directory -Force -Path $SearxngDir | Out-Null }

        $tempTar = Join-Path $env:TEMP "selah-searxng.tar.gz"

        Write-Info "This may take a few minutes depending on your network..."
        Invoke-WebRequest -Uri $SearxngArchive -OutFile $tempTar -UseBasicParsing

        # Extract to short temp path first (Windows bsdtar fails on long paths)
        New-Item -ItemType Directory -Force -Path $tempExtract | Out-Null
        # tar may warn about Windows-incompatible paths (e.g. utils/templates/etc/apache2)
        # -- these are unused files, so temporarily relax ErrorAction and verify essential files
        $oldPref = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try { & tar xzf $tempTar -C $tempExtract --strip-components=1 2>&1 | Out-Null } catch {}
        $ErrorActionPreference = $oldPref
        if (-not (Test-Path (Join-Path $tempExtract "searx"))) {
            throw "tar extraction failed: searx/ directory not found"
        }

        # Move to final location via robocopy
        & robocopy $tempExtract $SearxngDir /E /NFL /NDL /NJH /NJS /NC /NS /NP | Out-Null

        Remove-Item -Force $tempTar -ErrorAction SilentlyContinue
        Remove-Item -Recurse -Force $tempExtract -ErrorAction SilentlyContinue
        Write-Success "SearXNG source downloaded"
    } else {
        Write-Success "SearXNG source already exists"
    }

    # Always ensure version_frozen.py exists (tarball has no .git, so version.py fails on git commands)
    $frozenPath = Join-Path $SearxngDir "searx\version_frozen.py"
    if (-not (Test-Path $frozenPath)) {
        $frozenContent = "VERSION_STRING = `"2025.12.29`"`nVERSION_TAG = `"2025.12.29`"`nDOCKER_TAG = `"latest`"`nGIT_URL = `"https://github.com/searxng/searxng`"`nGIT_BRANCH = `"master`"`n"
        [System.IO.File]::WriteAllText($frozenPath, $frozenContent, [System.Text.UTF8Encoding]::new($false))
        Write-Success "version_frozen.py generated"
    }
}

# -- Step 4: venv + installation --

function Install-SearxngVenv {
    $venvDir = Join-Path $SearxngDir ".venv"
    $searxngRun = Join-Path $venvDir "Scripts\searxng-run.exe"

    if (Test-Path $searxngRun) {
        Write-Success "SearXNG venv already installed"
        return
    }

    Write-Info "Creating Python venv and installing SearXNG..."

    # PythonCmd is already resolved to actual python.exe path (handled in Find-Python)
    & uv venv $venvDir --python $script:PythonCmd
    if ($LASTEXITCODE -ne 0) {
        throw "uv venv creation failed (exitcode: $LASTEXITCODE)"
    }

    $venvPython = Join-Path $venvDir "Scripts\python.exe"
    $requirementsFile = Join-Path $SearxngDir "requirements.txt"

    # SearXNG's setup.py imports runtime dependencies during build -- install everything first
    & uv pip install --python $venvPython setuptools
    if ($LASTEXITCODE -ne 0) { throw "setuptools installation failed (exitcode: $LASTEXITCODE)" }

    & uv pip install --python $venvPython -r $requirementsFile
    if ($LASTEXITCODE -ne 0) { throw "requirements.txt installation failed (exitcode: $LASTEXITCODE)" }

    & uv pip install --python $venvPython --no-deps --no-build-isolation $SearxngDir
    if ($LASTEXITCODE -ne 0) {
        throw "uv pip install failed (exitcode: $LASTEXITCODE)"
    }

    if (Test-Path $searxngRun) {
        Write-Success "SearXNG installed"
    } else {
        throw "SearXNG installation failed: searxng-run.exe not found"
    }

    # Windows compatibility patch: valkeydb.py uses Unix-only 'pwd' module
    $valkeyPath = Join-Path $venvDir "Lib\site-packages\searx\valkeydb.py"
    if (Test-Path $valkeyPath) {
        $content = [System.IO.File]::ReadAllText($valkeyPath)
        # Patch 'import pwd' to guard against ImportError on Windows
        $content = $content -replace 'import pwd', "try:`r`n    import pwd`r`nexcept ImportError:`r`n    pwd = None"
        # Patch pwd.getpwuid usage to check for None first
        $content = $content -replace '_pw = pwd\.getpwuid\(os\.getuid\(\)\)\s*\r?\n\s*logger\.exception\(\"\[%s \(%s\)\] can''t connect valkey DB \.\.\.\", _pw\.pw_name, _pw\.pw_uid\)', "if pwd:`r`n            _pw = pwd.getpwuid(os.getuid())`r`n            logger.exception(`"[%s (%s)] can't connect valkey DB ...`", _pw.pw_name, _pw.pw_uid)`r`n        else:`r`n            logger.exception(`"can't connect valkey DB ...`")"
        [System.IO.File]::WriteAllText($valkeyPath, $content, [System.Text.UTF8Encoding]::new($false))
        Write-Success "Windows compatibility patch applied (valkeydb.py)"
    }
}

# -- Step 5: Generate settings.yml --

function Generate-Settings {
    $settingsFile = Join-Path $SearxngDir "settings.yml"

    if (Test-Path $settingsFile) {
        Write-Success "settings.yml already exists (keeping existing config)"
        return
    }

    Write-Info "Generating settings.yml..."

    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RNGCryptoServiceProvider]::new()
    $rng.GetBytes($bytes)
    $rng.Dispose()
    $secretKey = ($bytes | ForEach-Object { $_.ToString("x2") }) -join ""

    $content = @"
# SearXNG settings for Selah
# Docs: https://docs.searxng.org/admin/settings/index.html

use_default_settings: true

general:
  instance_name: "Selah SearXNG"
  enable_metrics: false

server:
  port: $SearxngPort
  bind_address: "127.0.0.1"
  secret_key: "$secretKey"
  limiter: false
  public_instance: false
  image_proxy: false

search:
  safe_search: 0
  default_lang: "auto"
  formats:
    - html
    - json

# Engines for Agent web search
engines:
  - name: google
    engine: google
    shortcut: go
    disabled: false

  - name: bing
    engine: bing
    shortcut: bi
    disabled: false

  - name: duckduckgo
    engine: duckduckgo
    shortcut: ddg
    disabled: false

  - name: wikipedia
    engine: wikipedia
    shortcut: wp
    disabled: false

  - name: wikidata
    engine: wikidata
    shortcut: wd
    disabled: true

  - name: brave
    engine: brave
    shortcut: br
    disabled: false
"@
    # Save as UTF-8 without BOM (PS 5.1's Out-File adds BOM, so use WriteAllText)
    [System.IO.File]::WriteAllText($settingsFile, $content, [System.Text.UTF8Encoding]::new($false))

    Write-Success "settings.yml generated (port: $SearxngPort, bind: 127.0.0.1)"
}

# -- Step 6: Generate start/stop scripts --

function Create-Scripts {
    # start.bat -- output as UTF-8 (no BOM)
    $startScript = Join-Path $SearxngDir "start.bat"
    $startBatContent = "@echo off`r`n" +
        "chcp 65001 >nul`r`n" +
        "set SCRIPT_DIR=%~dp0`r`n" +
        "set VENV_DIR=%SCRIPT_DIR%.venv`r`n" +
        "set SETTINGS=%SCRIPT_DIR%settings.yml`r`n" +
        "`r`n" +
        "if not exist `"%VENV_DIR%\Scripts\searxng-run.exe`" (`r`n" +
        "    echo Error: SearXNG is not installed. Please run install-searxng.ps1 first. 1>&2`r`n" +
        "    exit /b 1`r`n" +
        ")`r`n" +
        "`r`n" +
        "set SEARXNG_SETTINGS_PATH=%SETTINGS%`r`n" +
        "`"%VENV_DIR%\Scripts\searxng-run.exe`"`r`n"
    [System.IO.File]::WriteAllText($startScript, $startBatContent, [System.Text.UTF8Encoding]::new($false))

    # stop.bat
    $stopScript = Join-Path $SearxngDir "stop.bat"
    $stopBatContent = "@echo off`r`n" +
        "for /f `"tokens=2`" %%a in ('tasklist /fi `"imagename eq searxng-run.exe`" /fo list ^| find `"PID:`"') do (`r`n" +
        "    taskkill /pid %%a /f`r`n" +
        "    echo SearXNG stopped (PID: %%a)`r`n" +
        "    goto :done`r`n" +
        ")`r`n" +
        "echo SearXNG is not running`r`n" +
        ":done`r`n"
    [System.IO.File]::WriteAllText($stopScript, $stopBatContent, [System.Text.UTF8Encoding]::new($false))

    # start.ps1 (PowerShell version)
    $startPs1 = Join-Path $SearxngDir "start.ps1"
    $startPs1Content = @'
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$VenvDir = Join-Path $ScriptDir ".venv"
$Settings = Join-Path $ScriptDir "settings.yml"
$SearxngRun = Join-Path $VenvDir "Scripts\searxng-run.exe"

if (-not (Test-Path $SearxngRun)) {
    Write-Error "SearXNG is not installed. Please run install-searxng.ps1 first."
    exit 1
}

$env:SEARXNG_SETTINGS_PATH = $Settings
& $SearxngRun
'@
    [System.IO.File]::WriteAllText($startPs1, $startPs1Content, [System.Text.UTF8Encoding]::new($false))

    Write-Success "start/stop scripts generated"
}

# -- Pre-flight integrity check --

function Test-SearxngPreflight {
    if (-not (Test-Path $SearxngDir)) { return }
    $fixed = $false

    # searxng dir exists but searx/ is missing -- treat as corrupted and remove
    $searxSubdir = Join-Path $SearxngDir "searx"
    if (-not (Test-Path $searxSubdir)) {
        Write-Warn "SearXNG source is corrupted (searx\ missing). Removing and reinstalling."
        Remove-Item -Recurse -Force $SearxngDir
        $fixed = $true
    }

    # venv exists but searxng-run is missing (only check if directory is still present)
    $venvDir = Join-Path $SearxngDir ".venv"
    $searxngRun = Join-Path $venvDir "Scripts\searxng-run.exe"
    if ((Test-Path $venvDir) -and -not (Test-Path $searxngRun)) {
        Write-Warn "SearXNG venv is corrupted. Removing and reinstalling."
        Remove-Item -Recurse -Force $venvDir
        $fixed = $true
    }

    if ($fixed) {
        Write-Success "Corrupted items cleaned up"
    }
}

# -- Failure cleanup --

function Invoke-SearxngCleanup {
    if ($InstallSuccess) { return }
    Write-Err "SearXNG installation failed. Cleaning up..."
    if (-not $VenvExisted -and (Test-Path (Join-Path $SearxngDir ".venv"))) {
        Remove-Item -Recurse -Force (Join-Path $SearxngDir ".venv") -ErrorAction SilentlyContinue
        Write-Info "venv removed"
    }
    if (-not $SearxngExisted -and (Test-Path $SearxngDir)) {
        Remove-Item -Recurse -Force $SearxngDir -ErrorAction SilentlyContinue
        Write-Info "searxng directory removed"
    }
}

# -- Run --

Write-Host ""
Write-Host "  -- SearXNG Installation --" -ForegroundColor Cyan
Write-Host ""

# Run preflight first (clean up corrupted items)
Test-SearxngPreflight

# Capture actual state after preflight cleanup
$SearxngExisted = Test-Path $SearxngDir
$VenvExisted = Test-Path (Join-Path $SearxngDir ".venv")
$InstallSuccess = $false

try {
    $pythonOk = Ensure-Python
    if (-not $pythonOk) {
        # Show Python installation instructions and exit -- user reinstalls after setup
        throw "Python 3.11+ not found. Please install and rerun."
    }

    Ensure-Uv
    Setup-SearxngSource
    Install-SearxngVenv
    Generate-Settings
    Create-Scripts

    $InstallSuccess = $true
} catch {
    Write-Err "SearXNG installation failed: $_"
} finally {
    Invoke-SearxngCleanup
}

if (-not $InstallSuccess) { exit 1 }

# -- Step 7: Register NSSM service (if NSSM available) --

$nssmExe = Join-Path $SelahHome "bin\nssm.exe"
$searxngRun = Join-Path $SearxngDir ".venv\Scripts\searxng-run.exe"

if ((Test-Path $nssmExe) -and (Test-Path $searxngRun)) {
    Write-Info "Registering SearXNG as Windows service..."

    # Check admin
    $isAdmin = $false
    try {
        $null = & net session 2>&1
        if ($LASTEXITCODE -eq 0) { $isAdmin = $true }
    } catch {}

    if ($isAdmin) {
        $settingsPath = Join-Path $SearxngDir "settings.yml"
        $logsDir = Join-Path $SelahHome "logs"
        if (-not (Test-Path $logsDir)) { New-Item -ItemType Directory -Force -Path $logsDir | Out-Null }

        # Remove existing service
        & $nssmExe stop selah-searxng 2>&1 | Out-Null
        & $nssmExe remove selah-searxng confirm 2>&1 | Out-Null

        & $nssmExe install selah-searxng $searxngRun
        & $nssmExe set selah-searxng AppDirectory $SearxngDir
        & $nssmExe set selah-searxng AppEnvironmentExtra "SEARXNG_SETTINGS_PATH=$settingsPath"
        & $nssmExe set selah-searxng DisplayName "Selah SearXNG"
        & $nssmExe set selah-searxng Description "SearXNG meta-search engine for Selah"
        & $nssmExe set selah-searxng AppStdout (Join-Path $logsDir "searxng-stdout.log")
        & $nssmExe set selah-searxng AppStderr (Join-Path $logsDir "searxng-stderr.log")
        & $nssmExe set selah-searxng AppStdoutCreationDisposition 4
        & $nssmExe set selah-searxng AppStderrCreationDisposition 4
        & $nssmExe set selah-searxng AppExit Default Restart
        & $nssmExe set selah-searxng AppRestartDelay 5000
        & $nssmExe set selah-searxng Start SERVICE_AUTO_START

        & $nssmExe start selah-searxng

        Write-Success "SearXNG registered as Windows service (selah-searxng)"
    } else {
        Write-Warn "Admin privileges required for service registration."
        Write-Info "Run 'selah start' as administrator to register services."
    }
} else {
    if (-not (Test-Path $nssmExe)) {
        Write-Info "NSSM not found. SearXNG will start with Selah (legacy mode)."
    }
}

Write-Host ""
Write-Success "SearXNG installation complete!"
Write-Info "Location: $SearxngDir"
Write-Info "Port: $SearxngPort (localhost only)"
Write-Host ""
Write-Host "  To reinstall SearXNG later:" -ForegroundColor Yellow
Write-Host "  powershell -c `"irm https://raw.githubusercontent.com/taromati/Selah/main/install-searxng.ps1 | iex`"" -ForegroundColor Cyan
Write-Host ""
