# -- Selah Installer (Windows) --

# Ensure scripts can run in future sessions (CurrentUser scope, no admin required)
$currentPolicy = Get-ExecutionPolicy -Scope CurrentUser
if ($currentPolicy -eq "Restricted" -or $currentPolicy -eq "Undefined") {
    Set-ExecutionPolicy -Scope CurrentUser RemoteSigned -Force
}

$ErrorActionPreference = "Stop"

$SelahHome = if ($env:SELAH_HOME) { $env:SELAH_HOME } else { Join-Path $env:USERPROFILE ".selah" }
$SelahVersion = if ($env:SELAH_VERSION) { $env:SELAH_VERSION } else { "latest" }
$GitHubRepo = "taromati/Selah"

# -- Cleanup state tracking --

$CleanupItems = @()          # Paths created in this run (deleted on failure)
$SelahHomeExisted = Test-Path $SelahHome
$script:PathWasModified = $false
$OriginalUserPath = if ($null -eq [Environment]::GetEnvironmentVariable("PATH", "User")) { "" } else { [Environment]::GetEnvironmentVariable("PATH", "User") }
$script:InstallSuccess = $false

function Invoke-Cleanup {
    # Always remove temp files
    Get-ChildItem $env:TEMP -Filter "selah-*" -ErrorAction SilentlyContinue | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue

    if ($script:InstallSuccess) { return }

    # Restore PATH
    if ($script:PathWasModified) {
        [Environment]::SetEnvironmentVariable("PATH", $OriginalUserPath, "User")
        Write-Host "  [<-] PATH restored" -ForegroundColor Yellow
    }

    # Remove newly created items in reverse order
    $arr = [array]$CleanupItems
    [array]::Reverse($arr)
    foreach ($path in $arr) {
        if (Test-Path $path) {
            Remove-Item -Recurse -Force $path -ErrorAction SilentlyContinue
            Write-Host "  [<-] Removed: $path" -ForegroundColor Yellow
        }
    }

    # Remove ~/.selah/ entirely (only if this was a fresh install)
    if (-not $SelahHomeExisted -and (Test-Path $SelahHome)) {
        Remove-Item -Recurse -Force $SelahHome -ErrorAction SilentlyContinue
        Write-Host "  [<-] Selah home directory removed: $SelahHome" -ForegroundColor Yellow
    }
}

function Write-Info($msg)    { Write-Host "  $msg" -ForegroundColor Cyan }
function Write-Success($msg) { Write-Host "  [OK] $msg" -ForegroundColor Green }
function Write-Warn($msg)    { Write-Host "  [!] $msg" -ForegroundColor Yellow }
function Write-Err($msg)     { Write-Host "  [X] $msg" -ForegroundColor Red }

Write-Host ""
Write-Host "  +===============================+" -ForegroundColor Cyan
Write-Host "  |      Selah Installer          |" -ForegroundColor Cyan
Write-Host "  +===============================+" -ForegroundColor Cyan
Write-Host ""

# -- Java runtime check --

function Test-Java {
    # Use bundled runtime only (system Java is not used)
    $bundledJava = Join-Path $SelahHome "runtime\bin\java.exe"
    if (Test-Path $bundledJava) {
        # In PS 5.1, java -version stderr output is converted to ErrorRecord,
        # which may throw when $ErrorActionPreference=Stop -- temporarily relax
        $oldPref = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            $ver = & $bundledJava -version 2>&1 | Out-String
        } finally {
            $ErrorActionPreference = $oldPref
        }
        if ($ver -match '"(\d+)') {
            $major = [int]$Matches[1]
            if ($major -ge 25) {
                Write-Success "Selah runtime (Java $major) found"
                return $true
            } else {
                Write-Warn "Selah runtime is outdated (Java $major). Updating."
                return $false
            }
        }
    }

    return $false
}

function Install-Runtime {
    # Attempt 1: jlink lightweight runtime (~60MB)
    $runtimeName = "selah-runtime-$ResolvedVersion-windows-x64.zip"
    $runtimeUrl = "https://github.com/$GitHubRepo/releases/download/v$ResolvedVersion/$runtimeName"
    $runtimeDir = Join-Path $SelahHome "runtime"

    Write-Info "Attempting jlink runtime download..."
    try {
        $tempZip = Join-Path $env:TEMP "selah-runtime.zip"
        $ProgressPreference = 'SilentlyContinue'
        Invoke-WebRequest -Uri $runtimeUrl -OutFile $tempZip -UseBasicParsing -ErrorAction Stop

        if (Test-Path $runtimeDir) { Remove-Item -Recurse -Force $runtimeDir }
        New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null
        $script:CleanupItems += $runtimeDir

        $tempExtract = Join-Path $env:TEMP "selah-runtime-extract"
        if (Test-Path $tempExtract) { Remove-Item -Recurse -Force $tempExtract }
        Expand-Archive -Path $tempZip -DestinationPath $tempExtract

        $innerDir = Get-ChildItem -Path $tempExtract -Directory | Select-Object -First 1
        if ($innerDir) {
            Copy-Item -Path (Join-Path $innerDir.FullName "*") -Destination $runtimeDir -Recurse -Force
        } else {
            Copy-Item -Path (Join-Path $tempExtract "*") -Destination $runtimeDir -Recurse -Force
        }

        Remove-Item -Force $tempZip
        Remove-Item -Recurse -Force $tempExtract
        Write-Success "jlink runtime installed (~60MB)"
        return
    } catch {
        Write-Warn "jlink runtime not found. Installing Corretto 25 JDK."
    }

    # Attempt 2: Full Corretto 25 JDK (~300MB)
    Install-CorrettoJdk
}

function Install-CorrettoJdk {
    Write-Info "Downloading AWS Corretto 25 JDK..."
    $runtimeDir = Join-Path $SelahHome "runtime"

    # https://docs.aws.amazon.com/corretto/latest/corretto-25-ug/downloads-list.html
    $jdkUrl = "https://corretto.aws/downloads/latest/amazon-corretto-25-x64-windows-jdk.zip"
    Write-Info "URL: $jdkUrl"

    $tempZip = Join-Path $env:TEMP "selah-jdk.zip"
    $tempExtract = Join-Path $env:TEMP "selah-jdk-extract"

    $ProgressPreference = 'SilentlyContinue'
    Invoke-WebRequest -Uri $jdkUrl -OutFile $tempZip -UseBasicParsing

    if (Test-Path $runtimeDir) { Remove-Item -Recurse -Force $runtimeDir }
    New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null
    $script:CleanupItems += $runtimeDir

    if (Test-Path $tempExtract) { Remove-Item -Recurse -Force $tempExtract }
    Expand-Archive -Path $tempZip -DestinationPath $tempExtract

    $innerDir = Get-ChildItem -Path $tempExtract -Directory | Select-Object -First 1
    if ($innerDir) {
        Copy-Item -Path (Join-Path $innerDir.FullName "*") -Destination $runtimeDir -Recurse -Force
    } else {
        Copy-Item -Path (Join-Path $tempExtract "*") -Destination $runtimeDir -Recurse -Force
    }

    Remove-Item -Force $tempZip
    Remove-Item -Recurse -Force $tempExtract

    Write-Success "AWS Corretto 25 JDK installed"
}

# -- Version resolution --

function Resolve-Version {
    if ($SelahVersion -ne "latest") {
        $script:ResolvedVersion = $SelahVersion
        return
    }

    Write-Info "Checking latest version..."
    $apiUrl = "https://api.github.com/repos/$GitHubRepo/releases/latest"
    try {
        $release = Invoke-RestMethod -Uri $apiUrl -UseBasicParsing
        $script:ResolvedVersion = $release.tag_name -replace '^v', ''
        Write-Info "Latest version: $script:ResolvedVersion"
    } catch {
        throw "Cannot determine latest version. Please set SELAH_VERSION explicitly."
    }
}

# -- Selah download --

function Install-Selah {
    Write-Info "Downloading Selah v$ResolvedVersion..."
    $libDir = Join-Path $SelahHome "lib"

    $libDirExisted = Test-Path $libDir
    New-Item -ItemType Directory -Force -Path $libDir | Out-Null
    if (-not $libDirExisted) { $script:CleanupItems += $libDir }

    $jarName = "selah-$ResolvedVersion.jar"
    $jarPath = Join-Path $libDir $jarName
    $downloadUrl = "https://github.com/$GitHubRepo/releases/download/v$ResolvedVersion/$jarName"
    $ProgressPreference = 'SilentlyContinue'
    Invoke-WebRequest -Uri $downloadUrl -OutFile $jarPath -UseBasicParsing
    $script:CleanupItems += $jarPath

    # Remove old JAR versions
    Get-ChildItem -Path $libDir -Filter "selah-*.jar" | Where-Object { $_.Name -ne $jarName } | Remove-Item -Force

    Write-Success "Selah v$ResolvedVersion downloaded ($jarName)"
}

# -- NSSM installation --

function Install-Nssm {
    $nssmPath = Join-Path $SelahHome "bin\nssm.exe"

    if (Test-Path $nssmPath) {
        Write-Success "NSSM already installed"
        return
    }

    Write-Info "Downloading NSSM (service manager)..."
    $nssmUrl = "https://nssm.cc/release/nssm-2.24.zip"
    $tempZip = Join-Path $env:TEMP "selah-nssm.zip"
    $tempExtract = Join-Path $env:TEMP "selah-nssm-extract"

    $ProgressPreference = 'SilentlyContinue'
    Invoke-WebRequest -Uri $nssmUrl -OutFile $tempZip -UseBasicParsing

    if (Test-Path $tempExtract) { Remove-Item -Recurse -Force $tempExtract }
    Expand-Archive -Path $tempZip -DestinationPath $tempExtract

    $nssmExe = Get-ChildItem -Path $tempExtract -Recurse -Filter "nssm.exe" |
        Where-Object { $_.DirectoryName -match "win64" } |
        Select-Object -First 1

    if (-not $nssmExe) {
        $nssmExe = Get-ChildItem -Path $tempExtract -Recurse -Filter "nssm.exe" |
            Select-Object -First 1
    }

    if ($nssmExe) {
        $binDir = Join-Path $SelahHome "bin"
        New-Item -ItemType Directory -Force -Path $binDir | Out-Null
        Copy-Item -Path $nssmExe.FullName -Destination $nssmPath -Force
        Write-Success "NSSM installed"
    } else {
        Write-Warn "NSSM download failed. 'selah start' will use legacy mode."
    }

    Remove-Item -Force $tempZip -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force $tempExtract -ErrorAction SilentlyContinue
}

# -- Default file installation --

function Install-Defaults {
    foreach ($dir in @("data", "logs", "agent-data", "bin")) {
        $dirPath = Join-Path $SelahHome $dir
        $dirExisted = Test-Path $dirPath
        New-Item -ItemType Directory -Force -Path $dirPath | Out-Null
        if (-not $dirExisted) { $script:CleanupItems += $dirPath }
    }

    # Download agent default files
    $agentDir = Join-Path $SelahHome "agent-data"
    foreach ($mdFile in @("PERSONA.md", "GUIDE.md", "TOOLS.md", "MEMORY.md")) {
        $mdPath = Join-Path $agentDir $mdFile
        if (-not (Test-Path $mdPath)) {
            $mdUrl = "https://raw.githubusercontent.com/$GitHubRepo/main/app/src/main/resources/defaults/agent-data/$mdFile"
            try {
                $ProgressPreference = 'SilentlyContinue'
                Invoke-WebRequest -Uri $mdUrl -OutFile $mdPath -UseBasicParsing -ErrorAction SilentlyContinue
            } catch {}
        }
    }

    # Wrapper script (bat) -- hardcoded install path
    $wrapperPath = Join-Path $SelahHome "bin\selah.bat"
    $wrapperExisted = Test-Path $wrapperPath
    $batContent = @"
@echo off
chcp 65001 >nul
title Selah
if not defined SELAH_HOME set SELAH_HOME=$SelahHome

set JAVA=%SELAH_HOME%\runtime\bin\java.exe
if not exist "%JAVA%" (
    echo selah: Runtime not found. Run 'selah update' or reinstall. 1>&2
    exit /b 1
)

set JAR=
for /f "delims=" %%f in ('dir /b /o-d "%SELAH_HOME%\lib\selah-*.jar" 2^>nul') do (
    set JAR=%SELAH_HOME%\lib\%%f
    goto :found_jar
)
echo selah: JAR file not found. Please reinstall. 1>&2
exit /b 1
:found_jar

cd /d "%SELAH_HOME%"

"%JAVA%" -jar "%JAR%" %*
"@
    [System.IO.File]::WriteAllText($wrapperPath, $batContent, [System.Text.Encoding]::ASCII)
    if (-not $wrapperExisted) { $script:CleanupItems += $wrapperPath }

    # No .ps1 wrapper -- PowerShell resolves .ps1 before .bat, causing ExecutionPolicy errors.
    # selah.bat handles both cmd and PowerShell via PATH.

    # Generate uninstall script
    $uninstallPath = Join-Path $SelahHome "uninstall.ps1"
    $uninstallContent = @"
# Selah uninstall script
`$SelahHome = if (`$env:SELAH_HOME) { `$env:SELAH_HOME } else { Join-Path `$env:USERPROFILE ".selah" }

Write-Host ""
Write-Host "This will completely remove Selah." -ForegroundColor Yellow
Write-Host "  Path: `$SelahHome"
`$confirm = Read-Host "  Continue? (y/N)"
if (`$confirm -ne 'y' -and `$confirm -ne 'Y') { Write-Host "Cancelled."; exit 0 }

# 1. Remove NSSM services
`$nssmExe = Join-Path `$SelahHome "bin\nssm.exe"
if (Test-Path `$nssmExe) {
    `$null = sc.exe query selah-searxng 2>`$null
    if (`$LASTEXITCODE -eq 0) {
        & `$nssmExe stop selah-searxng 2>&1 | Out-Null
        & `$nssmExe remove selah-searxng confirm 2>&1 | Out-Null
        Write-Host "  [OK] SearXNG service removed"
    }
    `$null = sc.exe query selah 2>`$null
    if (`$LASTEXITCODE -eq 0) {
        & `$nssmExe stop selah 2>&1 | Out-Null
        & `$nssmExe remove selah confirm 2>&1 | Out-Null
        Write-Host "  [OK] Selah service removed"
    }
}

# 2. Remove legacy startup entries
`$startupLnk = Join-Path `$env:APPDATA "Microsoft\Windows\Start Menu\Programs\Startup\Selah.lnk"
if (Test-Path `$startupLnk) { Remove-Item -Force `$startupLnk; Write-Host "  [OK] Startup entry removed" }
try { & schtasks /delete /tn "Selah" /f 2>&1 | Out-Null; Write-Host "  [OK] Scheduled task removed" } catch {}

# 3. Terminate running process
try { & taskkill /f /im java.exe /fi "WINDOWTITLE eq Selah*" 2>&1 | Out-Null } catch {}

# 4. Clean up PATH
`$currentPath = [Environment]::GetEnvironmentVariable("PATH", "User")
if (`$currentPath) {
    `$binDir = Join-Path `$SelahHome "bin"
    `$newPath = (`$currentPath -split ";" | Where-Object { `$_ -ne `$binDir }) -join ";"
    if (`$newPath -ne `$currentPath) {
        [Environment]::SetEnvironmentVariable("PATH", `$newPath, "User")
        Write-Host "  [OK] PATH entry removed"
    }
}

# 5. Remove directory
Remove-Item -Recurse -Force `$SelahHome
Write-Host ""
Write-Host "  [OK] Selah uninstalled" -ForegroundColor Green
"@
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($uninstallPath, $uninstallContent, $utf8NoBom)

    Write-Success "Default files installed"
}

# -- PATH registration --

function Set-SelahPath {
    $binDir = Join-Path $SelahHome "bin"
    $currentPath = [Environment]::GetEnvironmentVariable("PATH", "User")

    if ($currentPath -split ";" | Where-Object { $_ -eq $binDir }) {
        return
    }

    [Environment]::SetEnvironmentVariable("PATH", "$binDir;$currentPath", "User")
    $env:PATH = "$binDir;$env:PATH"
    $script:PathWasModified = $true
    Write-Success "Added to PATH: $binDir"
}

# -- Pre-flight integrity check (recover from broken previous install) --

function Test-Preflight {
    if (-not (Test-Path $SelahHome)) { return }
    Write-Info "Existing installation detected. Checking integrity..."
    $fixed = $false

    # Runtime directory exists but java.exe is missing
    $runtimeDir = Join-Path $SelahHome "runtime"
    $javaExe = Join-Path $runtimeDir "bin\java.exe"
    if ((Test-Path $runtimeDir) -and -not (Test-Path $javaExe)) {
        Write-Warn "Runtime is corrupted. Removing and reinstalling."
        Remove-Item -Recurse -Force $runtimeDir
        $fixed = $true
    }

    # Remove zero-byte JARs
    $libDir = Join-Path $SelahHome "lib"
    if (Test-Path $libDir) {
        Get-ChildItem $libDir -Filter "selah-*.jar" | Where-Object { $_.Length -eq 0 } | ForEach-Object {
            Remove-Item -Force $_.FullName
            Write-Warn "Corrupted JAR removed: $($_.Name)"
            $fixed = $true
        }
    }

    # Remove empty wrapper scripts
    foreach ($wrapper in @("bin\selah.bat", "bin\selah.ps1")) {
        $p = Join-Path $SelahHome $wrapper
        if ((Test-Path $p) -and (Get-Item $p).Length -eq 0) {
            Remove-Item -Force $p
            Write-Warn "Corrupted wrapper script removed: $wrapper"
            $fixed = $true
        }
    }

    # Warn if scheduled task exists during reinstall
    try {
        $task = & schtasks /query /tn "Selah" 2>&1
        if ($LASTEXITCODE -eq 0) {
            Write-Warn "A scheduled task (schtasks) is registered. It will restart automatically after installation."
        }
    } catch {}

    if ($fixed) {
        Write-Success "Corrupted items cleaned up. Continuing installation."
    } else {
        Write-Success "Integrity check passed"
    }
}

# -- Run --

Write-Info "Install path: $SelahHome"
Write-Host ""

Test-Preflight

try {
    Resolve-Version

    if (-not (Test-Java)) {
        Install-Runtime
    }
    Install-Selah
    Install-Nssm
    Install-Defaults
    Set-SelahPath

    $script:InstallSuccess = $true
} catch {
    Write-Err "Installation failed: $_"
} finally {
    Invoke-Cleanup
}

if (-not $script:InstallSuccess) { exit 1 }

# -- SearXNG installation (failure does not roll back main install) --

Write-Host ""
$installerDir = if ($MyInvocation.MyCommand.Path) { Split-Path -Parent $MyInvocation.MyCommand.Path } else { $null }
$searxngScript = if ($installerDir) { Join-Path $installerDir "install-searxng.ps1" } else { $null }

try {
    $env:SELAH_HOME = $SelahHome
    if ($searxngScript -and (Test-Path $searxngScript)) {
        & powershell -ExecutionPolicy Bypass -File $searxngScript
    } else {
        Write-Info "Downloading SearXNG installer script..."
        $tempScript = Join-Path $env:TEMP "selah-install-searxng.ps1"
        try {
            # PS 5.1's Invoke-WebRequest decodes UTF-8 using system encoding.
            # Use WebClient.DownloadString to explicitly specify UTF-8.
            $wc = New-Object System.Net.WebClient
            $wc.Encoding = [System.Text.Encoding]::UTF8
            $ts = Get-Date -Format "yyyyMMddHHmmss"
            $content = $wc.DownloadString("https://raw.githubusercontent.com/$GitHubRepo/main/install-searxng.ps1?t=$ts")
            [System.IO.File]::WriteAllText($tempScript, $content, [System.Text.UTF8Encoding]::new($false))
            & powershell -ExecutionPolicy Bypass -File $tempScript
            Remove-Item -Force $tempScript -ErrorAction SilentlyContinue
        } catch {
            throw "Failed to download installer script."
        }
    }
} catch {
    Write-Host ""
    Write-Warn "SearXNG installation failed. Web search will be disabled."
    Write-Info "  Reinstall: Invoke-WebRequest https://raw.githubusercontent.com/$GitHubRepo/main/install-searxng.ps1 -OutFile install-searxng.ps1; .\install-searxng.ps1"
}

Write-Host ""
Write-Success "Selah installation complete!"
Write-Host ""
Write-Info "To uninstall: & '$SelahHome\uninstall.ps1'"
Write-Host ""
Write-Info "Next step:"
Write-Info "  selah setup    -- Initial setup (Discord/LLM config + auto-start service)"
Write-Host ""
