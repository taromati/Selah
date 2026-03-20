# ── Selah Installer (Windows) ──

$ErrorActionPreference = "Stop"

$SelahHome = if ($env:SELAH_HOME) { $env:SELAH_HOME } else { Join-Path $env:USERPROFILE ".selah" }
$SelahVersion = if ($env:SELAH_VERSION) { $env:SELAH_VERSION } else { "latest" }
$GitHubRepo = "taromati/Selah"

# ── 클린업 상태 추적 ──

$CleanupItems = @()          # 이번 실행에서 새로 생성한 경로 (실패 시 삭제)
$SelahHomeExisted = Test-Path $SelahHome
$script:PathWasModified = $false
$OriginalUserPath = if ($null -eq [Environment]::GetEnvironmentVariable("PATH", "User")) { "" } else { [Environment]::GetEnvironmentVariable("PATH", "User") }
$script:InstallSuccess = $false

function Invoke-Cleanup {
    # 임시 파일은 항상 삭제
    Get-ChildItem $env:TEMP -Filter "selah-*" -ErrorAction SilentlyContinue | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue

    if ($script:InstallSuccess) { return }

    # PATH 복원
    if ($script:PathWasModified) {
        [Environment]::SetEnvironmentVariable("PATH", $OriginalUserPath, "User")
        Write-Host "  ↩ PATH 복원 완료" -ForegroundColor Yellow
    }

    # 신규 생성 항목 역순 삭제
    $arr = [array]$CleanupItems
    [array]::Reverse($arr)
    foreach ($path in $arr) {
        if (Test-Path $path) {
            Remove-Item -Recurse -Force $path -ErrorAction SilentlyContinue
            Write-Host "  ↩ 삭제됨: $path" -ForegroundColor Yellow
        }
    }

    # ~/.selah/ 전체 삭제 (최초 설치였던 경우에만)
    if (-not $SelahHomeExisted -and (Test-Path $SelahHome)) {
        Remove-Item -Recurse -Force $SelahHome -ErrorAction SilentlyContinue
        Write-Host "  ↩ Selah 홈 디렉터리 삭제됨: $SelahHome" -ForegroundColor Yellow
    }
}

function Write-Info($msg)    { Write-Host "  $msg" -ForegroundColor Cyan }
function Write-Success($msg) { Write-Host "  ✓ $msg" -ForegroundColor Green }
function Write-Warn($msg)    { Write-Host "  ⚠ $msg" -ForegroundColor Yellow }
function Write-Err($msg)     { Write-Host "  ✗ $msg" -ForegroundColor Red }

Write-Host ""
Write-Host "  ╔═══════════════════════════════╗" -ForegroundColor Cyan
Write-Host "  ║      Selah Installer          ║" -ForegroundColor Cyan
Write-Host "  ╚═══════════════════════════════╝" -ForegroundColor Cyan
Write-Host ""

# ── Java 런타임 확인 ──

function Test-Java {
    # 자체 런타임만 사용 (시스템 Java 사용하지 않음)
    $bundledJava = Join-Path $SelahHome "runtime\bin\java.exe"
    if (Test-Path $bundledJava) {
        # PS 5.1에서 java -version의 stderr 출력이 ErrorRecord로 변환되어
        # $ErrorActionPreference=Stop 시 터질 수 있으므로 임시 완화
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
                Write-Success "Selah 런타임 (Java $major) 발견"
                return $true
            } else {
                Write-Warn "Selah 런타임이 오래됐습니다 (Java $major). 업데이트합니다."
                return $false
            }
        }
    }

    return $false
}

function Install-Runtime {
    # 1차: jlink 경량 런타임 다운로드 시도 (~60MB)
    $runtimeName = "selah-runtime-$ResolvedVersion-windows-x64.zip"
    $runtimeUrl = "https://github.com/$GitHubRepo/releases/download/v$ResolvedVersion/$runtimeName"
    $runtimeDir = Join-Path $SelahHome "runtime"

    Write-Info "jlink 런타임 다운로드 시도..."
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
        Write-Success "jlink 런타임 설치 완료 (~60MB)"
        return
    } catch {
        Write-Warn "jlink 런타임을 찾을 수 없습니다. Corretto 25 JDK를 설치합니다."
    }

    # 2차: Corretto 25 JDK 전체 다운로드 (~300MB)
    Install-CorrettoJdk
}

function Install-CorrettoJdk {
    Write-Info "AWS Corretto 25 JDK 다운로드 중..."
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

    Write-Success "AWS Corretto 25 JDK 설치 완료"
}

# ── 버전 확인 ──

function Resolve-Version {
    if ($SelahVersion -ne "latest") {
        $script:ResolvedVersion = $SelahVersion
        return
    }

    Write-Info "최신 버전 확인 중..."
    $apiUrl = "https://api.github.com/repos/$GitHubRepo/releases/latest"
    try {
        $release = Invoke-RestMethod -Uri $apiUrl -UseBasicParsing
        $script:ResolvedVersion = $release.tag_name -replace '^v', ''
        Write-Info "최신 버전: $script:ResolvedVersion"
    } catch {
        throw "최신 버전을 확인할 수 없습니다. SELAH_VERSION을 직접 지정해주세요."
    }
}

# ── Selah 다운로드 ──

function Install-Selah {
    Write-Info "Selah v$ResolvedVersion 다운로드 중..."
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

    # 이전 버전 JAR 정리
    Get-ChildItem -Path $libDir -Filter "selah-*.jar" | Where-Object { $_.Name -ne $jarName } | Remove-Item -Force

    Write-Success "Selah v$ResolvedVersion 다운로드 완료 ($jarName)"
}

# ── 기본 파일 설치 ──

function Install-Defaults {
    foreach ($dir in @("data", "logs", "agent-data", "bin")) {
        $dirPath = Join-Path $SelahHome $dir
        $dirExisted = Test-Path $dirPath
        New-Item -ItemType Directory -Force -Path $dirPath | Out-Null
        if (-not $dirExisted) { $script:CleanupItems += $dirPath }
    }

    # 에이전트 기본 파일 다운로드
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

    # 래퍼 스크립트 (bat) — 설치 경로 하드코딩
    $wrapperPath = Join-Path $SelahHome "bin\selah.bat"
    $wrapperExisted = Test-Path $wrapperPath
    $batContent = @"
@echo off
title Selah
if not defined SELAH_HOME set SELAH_HOME=$SelahHome

set JAVA=%SELAH_HOME%\runtime\bin\java.exe
if not exist "%JAVA%" (
    echo selah: 런타임을 찾을 수 없습니다. 'selah update' 또는 재설치해주세요. 1>&2
    exit /b 1
)

set JAR=
for /f "delims=" %%f in ('dir /b /o-d "%SELAH_HOME%\lib\selah-*.jar" 2^>nul') do (
    set JAR=%SELAH_HOME%\lib\%%f
    goto :found_jar
)
echo selah: JAR 파일을 찾을 수 없습니다. 재설치해주세요. 1>&2
exit /b 1
:found_jar

cd /d "%SELAH_HOME%"

rem SearXNG 자동 시작 (서버 모드 = 인자 없을 때)
if "%~1"=="" if exist "%SELAH_HOME%\searxng\start.bat" (
    start /min "SearXNG" "%SELAH_HOME%\searxng\start.bat"
)

"%JAVA%" -jar "%JAR%" %*

rem SearXNG 자동 종료 (서버 모드)
if "%~1"=="" if exist "%SELAH_HOME%\searxng\stop.bat" (
    call "%SELAH_HOME%\searxng\stop.bat" >nul 2>&1
)
"@
    [System.IO.File]::WriteAllText($wrapperPath, $batContent, [System.Text.Encoding]::ASCII)
    if (-not $wrapperExisted) { $script:CleanupItems += $wrapperPath }

    # PowerShell 래퍼 — 설치 경로 하드코딩, UTF-8 without BOM
    $ps1WrapperPath = Join-Path $SelahHome "bin\selah.ps1"
    $ps1WrapperExisted = Test-Path $ps1WrapperPath
    $ps1Content = @"
`$SelahHome = if (`$env:SELAH_HOME) { `$env:SELAH_HOME } else { '$SelahHome' }

`$java = Join-Path `$SelahHome "runtime\bin\java.exe"
if (-not (Test-Path `$java)) {
    Write-Error "selah: 런타임을 찾을 수 없습니다. 'selah update' 또는 재설치해주세요."
    exit 1
}

`$jar = Get-ChildItem -Path (Join-Path `$SelahHome "lib") -Filter "selah-*.jar" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not `$jar) {
    Write-Error "selah: JAR 파일을 찾을 수 없습니다. 재설치해주세요."
    exit 1
}
`$jar = `$jar.FullName

Set-Location `$SelahHome
& `$java -jar `$jar @args
"@
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($ps1WrapperPath, $ps1Content, $utf8NoBom)
    if (-not $ps1WrapperExisted) { $script:CleanupItems += $ps1WrapperPath }

    # 제거 스크립트 생성
    $uninstallPath = Join-Path $SelahHome "uninstall.ps1"
    $uninstallContent = @"
# Selah 제거 스크립트
`$SelahHome = if (`$env:SELAH_HOME) { `$env:SELAH_HOME } else { Join-Path `$env:USERPROFILE ".selah" }

Write-Host ""
Write-Host "Selah을 완전히 제거합니다." -ForegroundColor Yellow
Write-Host "  경로: `$SelahHome"
`$confirm = Read-Host "  계속하시겠습니까? (y/N)"
if (`$confirm -ne 'y' -and `$confirm -ne 'Y') { Write-Host "취소되었습니다."; exit 0 }

# 1. 서비스 해제
`$startupLnk = Join-Path `$env:APPDATA "Microsoft\Windows\Start Menu\Programs\Startup\Selah.lnk"
if (Test-Path `$startupLnk) { Remove-Item -Force `$startupLnk; Write-Host "  ✓ 시작 프로그램 해제" }
try { & schtasks /delete /tn "Selah" /f 2>&1 | Out-Null; Write-Host "  ✓ 예약 작업 해제" } catch {}

# 2. 실행 중인 프로세스 종료
try { & taskkill /f /im java.exe /fi "WINDOWTITLE eq Selah*" 2>&1 | Out-Null } catch {}

# 3. PATH 정리
`$currentPath = [Environment]::GetEnvironmentVariable("PATH", "User")
if (`$currentPath) {
    `$binDir = Join-Path `$SelahHome "bin"
    `$newPath = (`$currentPath -split ";" | Where-Object { `$_ -ne `$binDir }) -join ";"
    if (`$newPath -ne `$currentPath) {
        [Environment]::SetEnvironmentVariable("PATH", `$newPath, "User")
        Write-Host "  ✓ PATH 제거"
    }
}

# 4. SearXNG 중지
`$stopBat = Join-Path `$SelahHome "searxng\stop.bat"
if (Test-Path `$stopBat) { & `$stopBat 2>&1 | Out-Null; Write-Host "  ✓ SearXNG 중지" }

# 5. 디렉토리 삭제
Remove-Item -Recurse -Force `$SelahHome
Write-Host ""
Write-Host "  ✓ Selah 제거 완료" -ForegroundColor Green
"@
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($uninstallPath, $uninstallContent, $utf8NoBom)

    Write-Success "기본 파일 설치 완료"
}

# ── PATH 등록 ──

function Set-SelahPath {
    $binDir = Join-Path $SelahHome "bin"
    $currentPath = [Environment]::GetEnvironmentVariable("PATH", "User")

    if ($currentPath -split ";" | Where-Object { $_ -eq $binDir }) {
        return
    }

    [Environment]::SetEnvironmentVariable("PATH", "$binDir;$currentPath", "User")
    $env:PATH = "$binDir;$env:PATH"
    $script:PathWasModified = $true
    Write-Success "PATH에 추가됨: $binDir"
}

# ── 사전 무결성 검사 (이전 설치가 꼬인 상태 복구) ──

function Test-Preflight {
    if (-not (Test-Path $SelahHome)) { return }
    Write-Info "기존 설치 감지. 무결성 확인 중..."
    $fixed = $false

    # runtime이 있지만 java.exe가 없음
    $runtimeDir = Join-Path $SelahHome "runtime"
    $javaExe = Join-Path $runtimeDir "bin\java.exe"
    if ((Test-Path $runtimeDir) -and -not (Test-Path $javaExe)) {
        Write-Warn "런타임이 손상되었습니다. 제거 후 다시 설치합니다."
        Remove-Item -Recurse -Force $runtimeDir
        $fixed = $true
    }

    # 0바이트 JAR 제거
    $libDir = Join-Path $SelahHome "lib"
    if (Test-Path $libDir) {
        Get-ChildItem $libDir -Filter "selah-*.jar" | Where-Object { $_.Length -eq 0 } | ForEach-Object {
            Remove-Item -Force $_.FullName
            Write-Warn "손상된 JAR 파일 제거: $($_.Name)"
            $fixed = $true
        }
    }

    # 래퍼 스크립트가 비어있음
    foreach ($wrapper in @("bin\selah.bat", "bin\selah.ps1")) {
        $p = Join-Path $SelahHome $wrapper
        if ((Test-Path $p) -and (Get-Item $p).Length -eq 0) {
            Remove-Item -Force $p
            Write-Warn "손상된 래퍼 스크립트 제거: $wrapper"
            $fixed = $true
        }
    }

    # 서비스가 등록된 상태에서 재설치 시 경고
    try {
        $task = & schtasks /query /tn "Selah" 2>&1
        if ($LASTEXITCODE -eq 0) {
            Write-Warn "자동 실행 서비스(schtasks)가 등록되어 있습니다. 설치 완료 후 서비스가 자동 재시작됩니다."
        }
    } catch {}

    if ($fixed) {
        Write-Success "손상 항목 정리 완료. 설치를 계속합니다."
    } else {
        Write-Success "무결성 확인 완료"
    }
}

# ── 실행 ──

Write-Info "설치 경로: $SelahHome"
Write-Host ""

Test-Preflight

try {
    Resolve-Version

    if (-not (Test-Java)) {
        Install-Runtime
    }
    Install-Selah
    Install-Defaults
    Set-SelahPath

    $script:InstallSuccess = $true
} catch {
    Write-Err "설치 실패: $_"
} finally {
    Invoke-Cleanup
}

if (-not $script:InstallSuccess) { exit 1 }

# ── SearXNG 설치 (실패해도 메인 설치 롤백 없음) ──

Write-Host ""
$installerDir = if ($MyInvocation.MyCommand.Path) { Split-Path -Parent $MyInvocation.MyCommand.Path } else { $null }
$searxngScript = if ($installerDir) { Join-Path $installerDir "install-searxng.ps1" } else { $null }

try {
    $env:SELAH_HOME = $SelahHome
    if ($searxngScript -and (Test-Path $searxngScript)) {
        & powershell -ExecutionPolicy Bypass -File $searxngScript
    } else {
        Write-Info "SearXNG 설치 스크립트 다운로드 중..."
        $tempScript = Join-Path $env:TEMP "selah-install-searxng.ps1"
        try {
            $ProgressPreference = 'SilentlyContinue'
            Invoke-WebRequest -Uri "https://raw.githubusercontent.com/$GitHubRepo/main/install-searxng.ps1" `
                -OutFile $tempScript -UseBasicParsing
            & powershell -ExecutionPolicy Bypass -File $tempScript
            Remove-Item -Force $tempScript -ErrorAction SilentlyContinue
        } catch {
            throw "스크립트를 다운로드할 수 없습니다."
        }
    }
} catch {
    Write-Host ""
    Write-Warn "SearXNG 설치에 실패했습니다. 웹 검색 기능이 비활성화됩니다."
    Write-Info "  재설치: Invoke-WebRequest https://raw.githubusercontent.com/$GitHubRepo/main/install-searxng.ps1 -OutFile install-searxng.ps1; .\install-searxng.ps1"
}

Write-Host ""
Write-Success "Selah 설치 완료!"
Write-Host ""
Write-Info "제거하려면: powershell -File `"$SelahHome\uninstall.ps1`""
Write-Host ""
Write-Info "다음 명령으로 초기 설정을 진행하세요:"
Write-Info "  selah setup"
Write-Host ""
