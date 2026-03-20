# ── Selah용 SearXNG 설치 스크립트 (Windows) ──
#
# Agent의 web_search 도구를 위한 SearXNG(메타검색엔진)을 로컬 서비스로 설치합니다.
# install.ps1에서 호출되거나 단독 실행할 수 있습니다.
#
# 순서:
#   1. Python 3.11+ 확인 (없으면 winget으로 설치, winget 없으면 안내)
#   2. uv (Python 패키지 매니저) 확인
#   3. SearXNG 소스를 ~/.selah/searxng/에 tarball 다운로드
#   4. Python venv 생성 + SearXNG 설치
#   5. settings.yml 생성
#   6. start/stop 스크립트 생성

$ErrorActionPreference = "Stop"

$SelahHome = if ($env:SELAH_HOME) { $env:SELAH_HOME } else { Join-Path $env:USERPROFILE ".selah" }
$SearxngDir = Join-Path $SelahHome "searxng"
$SearxngPort = if ($env:SEARXNG_PORT) { $env:SEARXNG_PORT } else { "8888" }
$SearxngArchive = "https://github.com/searxng/searxng/archive/refs/heads/master.zip"

function Write-Info($msg)    { Write-Host "  $msg" -ForegroundColor Cyan }
function Write-Success($msg) { Write-Host "  `u{2713} $msg" -ForegroundColor Green }
function Write-Warn($msg)    { Write-Host "  ! $msg" -ForegroundColor Yellow }
function Write-Err($msg)     { Write-Host "  x $msg" -ForegroundColor Red }

# ── Step 1: Python 3.11+ 확인 ──

function Find-Python {
    # 일반 명령어에서 먼저 탐색
    foreach ($cmd in @("python", "python3")) {
        try {
            $output = & $cmd --version 2>&1 | Out-String
            if ($output -match '(\d+)\.(\d+)') {
                $major = [int]$Matches[1]
                $minor = [int]$Matches[2]
                # major > 3 이면 무조건 통과, major == 3이면 minor >= 11 필요
                if ($major -gt 3 -or ($major -eq 3 -and $minor -ge 11)) {
                    return $cmd
                }
            }
        } catch {}
    }

    # py launcher로 특정 버전 시도 — 실제 python.exe 경로를 resolve하여 반환
    foreach ($ver in @("3.13", "3.12", "3.11")) {
        try {
            $output = & py "-$ver" --version 2>&1 | Out-String
            if ($output -match 'Python') {
                # py launcher가 실제로 실행하는 python.exe 경로를 resolve
                $resolved = & py "-$ver" -c "import sys; print(sys.executable)" 2>&1 | Out-String
                $resolved = $resolved.Trim()
                if ($resolved -and (Test-Path $resolved)) {
                    return $resolved
                }
                # resolve 실패 시 py launcher 자체는 사용하지 않음 (uv와 호환 문제)
            }
        } catch {}
    }

    return $null
}

function Refresh-PathFromRegistry {
    # 현재 세션 PATH를 레지스트리(User + Machine)에서 다시 로드
    $machinePath = [Environment]::GetEnvironmentVariable("PATH", "Machine")
    $userPath = [Environment]::GetEnvironmentVariable("PATH", "User")
    $env:PATH = "$userPath;$machinePath"
}

function Install-Python {
    Write-Info "Python 3.11+을 찾을 수 없습니다."

    # winget 확인
    $hasWinget = $false
    try {
        $null = & winget --version 2>&1
        if ($LASTEXITCODE -eq 0) { $hasWinget = $true }
    } catch {}

    if ($hasWinget) {
        Write-Info "winget으로 Python 3.12 설치 중..."
        & winget install Python.Python.3.12 --accept-package-agreements --accept-source-agreements
        if ($LASTEXITCODE -ne 0) {
            Write-Warn "winget 설치 실패 (exitcode: $LASTEXITCODE). 수동 설치가 필요합니다."
        } else {
            Write-Success "Python 3.12 설치 완료"
            Refresh-PathFromRegistry
            return $true
        }
    }

    # winget 없거나 실패 → 안내 후 대기
    Write-Host ""
    Write-Warn "Python을 자동 설치할 수 없습니다."
    Write-Host ""
    Write-Host "  다음 중 하나로 Python 3.11+을 설치해주세요:" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "  1) Microsoft Store에서 설치 (가장 쉬움)" -ForegroundColor White
    Write-Host "     시작 메뉴에서 'Python' 검색 → Microsoft Store에서 Python 3.12 설치" -ForegroundColor Gray
    Write-Host ""
    Write-Host "  2) 공식 설치 파일 다운로드" -ForegroundColor White
    Write-Host "     https://www.python.org/downloads/" -ForegroundColor Cyan
    Write-Host "     설치 시 'Add Python to PATH' 체크 필수!" -ForegroundColor Yellow
    Write-Host ""
    if (-not $hasWinget) {
        Write-Host "  3) winget 설치 후 재시도" -ForegroundColor White
        Write-Host "     Microsoft Store에서 '앱 설치 관리자'를 업데이트하면 winget을 사용할 수 있습니다." -ForegroundColor Gray
        Write-Host ""
    }

    # 인터렉티브 대기 — 설치 후 Enter로 계속
    Read-Host "  Python 설치가 끝나면 Enter를 눌러주세요"
    Refresh-PathFromRegistry

    if (Find-Python) {
        return $true
    }

    # 한 번 더 기회
    Write-Warn "아직 Python을 찾을 수 없습니다. PATH 등록을 확인해주세요."
    Write-Host "  'Add Python to PATH' 옵션을 켰는지 확인하세요." -ForegroundColor Yellow
    Write-Host "  이미 설치했다면 설치 프로그램을 다시 실행 → 'Modify' → 'Add to PATH' 체크" -ForegroundColor Gray
    Write-Host ""
    Read-Host "  확인 후 Enter를 눌러주세요"
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
        Write-Success "Python 발견: $(Get-PythonVersion $python)"
        $script:PythonCmd = $python
        return $true
    }

    $installed = Install-Python
    if ($installed) {
        $python = Find-Python
        if ($python) {
            Write-Success "Python 확인: $(Get-PythonVersion $python)"
            $script:PythonCmd = $python
            return $true
        }
    }

    Write-Err "Python을 찾을 수 없습니다. 새 터미널에서 다시 실행해주세요."
    return $false
}

# ── Step 2: uv 확인 ──

function Ensure-Uv {
    # PATH에서 찾기
    try {
        $null = & uv --version 2>&1
        if ($LASTEXITCODE -eq 0) {
            $ver = & uv --version 2>&1 | Out-String
            Write-Success "uv 발견: $($ver.Trim())"
            return $true
        }
    } catch {}

    # 일반적인 설치 경로 확인
    $uvCandidates = @(
        "$env:USERPROFILE\.local\bin\uv.exe",
        "$env:USERPROFILE\.cargo\bin\uv.exe",
        "$env:LOCALAPPDATA\uv\uv.exe"
    )
    foreach ($candidate in $uvCandidates) {
        if (Test-Path $candidate) {
            $dir = Split-Path $candidate -Parent
            $env:PATH = "$dir;$env:PATH"
            Write-Success "uv 발견: $candidate"
            return $true
        }
    }

    Write-Info "uv 설치 중..."
    Invoke-RestMethod https://astral.sh/uv/install.ps1 | Invoke-Expression
    if ($LASTEXITCODE -ne 0) {
        throw "uv 설치 실패 (exitcode: $LASTEXITCODE). 수동 설치: https://docs.astral.sh/uv/"
    }

    # 설치 후 PATH 갱신
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
        Write-Success "uv 설치 완료"
        return $true
    } catch {
        throw "uv 설치 후 찾을 수 없습니다. 새 터미널에서 다시 실행해주세요."
    }
}

# ── Step 3: SearXNG 소스 다운로드 ──

function Setup-SearxngSource {
    if (Test-Path (Join-Path $SearxngDir "searx")) {
        Write-Success "SearXNG 소스가 이미 존재합니다"
        return
    }

    Write-Info "SearXNG 다운로드 중..."
    $parentDir = Split-Path $SearxngDir -Parent
    if (-not (Test-Path $parentDir)) { New-Item -ItemType Directory -Force -Path $parentDir | Out-Null }
    if (-not (Test-Path $SearxngDir)) { New-Item -ItemType Directory -Force -Path $SearxngDir | Out-Null }

    $tempZip = Join-Path $env:TEMP "selah-searxng.zip"
    $tempExtract = Join-Path $env:TEMP "selah-searxng-extract"

    $ProgressPreference = 'SilentlyContinue'
    Invoke-WebRequest -Uri $SearxngArchive -OutFile $tempZip -UseBasicParsing

    if (Test-Path $tempExtract) { Remove-Item -Recurse -Force $tempExtract }
    Expand-Archive -Path $tempZip -DestinationPath $tempExtract

    # 내부 디렉토리(searxng-master/)의 내용을 SearxngDir로 이동
    $innerDir = Get-ChildItem -Path $tempExtract -Directory | Select-Object -First 1
    if ($innerDir) {
        Copy-Item -Path (Join-Path $innerDir.FullName "*") -Destination $SearxngDir -Recurse -Force
    }

    Remove-Item -Force $tempZip -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force $tempExtract -ErrorAction SilentlyContinue
    Write-Success "SearXNG 소스 다운로드 완료"
}

# ── Step 4: venv + 설치 ──

function Install-SearxngVenv {
    $venvDir = Join-Path $SearxngDir ".venv"
    $searxngRun = Join-Path $venvDir "Scripts\searxng-run.exe"

    if (Test-Path $searxngRun) {
        Write-Success "SearXNG venv 이미 설치됨"
        return
    }

    Write-Info "Python venv 생성 및 SearXNG 설치 중..."

    # PythonCmd는 이미 실제 python.exe 경로로 resolve되어 있음 (Find-Python에서 처리)
    & uv venv $venvDir --python $script:PythonCmd
    if ($LASTEXITCODE -ne 0) {
        throw "uv venv 생성 실패 (exitcode: $LASTEXITCODE)"
    }

    $venvPython = Join-Path $venvDir "Scripts\python.exe"
    $requirementsFile = Join-Path $SearxngDir "requirements.txt"

    # SearXNG의 setup.py가 빌드 시 런타임 의존성을 import하므로 먼저 전체 설치
    & uv pip install --python $venvPython setuptools
    if ($LASTEXITCODE -ne 0) { throw "setuptools 설치 실패 (exitcode: $LASTEXITCODE)" }

    & uv pip install --python $venvPython -r $requirementsFile
    if ($LASTEXITCODE -ne 0) { throw "requirements.txt 설치 실패 (exitcode: $LASTEXITCODE)" }

    & uv pip install --python $venvPython --no-deps --no-build-isolation $SearxngDir
    if ($LASTEXITCODE -ne 0) {
        throw "uv pip install 실패 (exitcode: $LASTEXITCODE)"
    }

    if (Test-Path $searxngRun) {
        Write-Success "SearXNG 설치 완료"
    } else {
        throw "SearXNG 설치 실패: searxng-run.exe를 찾을 수 없습니다"
    }
}

# ── Step 5: settings.yml 생성 ──

function Generate-Settings {
    $settingsFile = Join-Path $SearxngDir "settings.yml"

    if (Test-Path $settingsFile) {
        Write-Success "settings.yml 이미 존재 (기존 설정 유지)"
        return
    }

    Write-Info "settings.yml 생성 중..."

    $bytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    $secretKey = ($bytes | ForEach-Object { $_.ToString("x2") }) -join ""

    $content = @"
# Selah용 SearXNG 설정
# 문서: https://docs.searxng.org/admin/settings/index.html

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

# Agent 웹 검색용 엔진
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
    # BOM 없는 UTF-8로 저장 (PS 5.1의 Out-File은 BOM을 붙이므로 WriteAllText 사용)
    [System.IO.File]::WriteAllText($settingsFile, $content, [System.Text.UTF8Encoding]::new($false))

    Write-Success "settings.yml 생성 완료 (포트: $SearxngPort, 바인드: 127.0.0.1)"
}

# ── Step 6: start/stop 스크립트 생성 ──

function Create-Scripts {
    # start.bat — 한국어 포함이므로 UTF-8 (BOM 없음) 출력
    $startScript = Join-Path $SearxngDir "start.bat"
    $startBatContent = "@echo off`r`n" +
        "chcp 65001 >nul`r`n" +
        "set SCRIPT_DIR=%~dp0`r`n" +
        "set VENV_DIR=%SCRIPT_DIR%.venv`r`n" +
        "set SETTINGS=%SCRIPT_DIR%settings.yml`r`n" +
        "`r`n" +
        "if not exist `"%VENV_DIR%\Scripts\searxng-run.exe`" (`r`n" +
        "    echo 오류: SearXNG가 설치되지 않았습니다. install-searxng.ps1을 먼저 실행해주세요. 1>&2`r`n" +
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
        "    echo SearXNG 종료 (PID: %%a)`r`n" +
        "    goto :done`r`n" +
        ")`r`n" +
        "echo SearXNG 미실행`r`n" +
        ":done`r`n"
    [System.IO.File]::WriteAllText($stopScript, $stopBatContent, [System.Text.UTF8Encoding]::new($false))

    # start.ps1 (PowerShell 버전)
    $startPs1 = Join-Path $SearxngDir "start.ps1"
    $startPs1Content = @'
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$VenvDir = Join-Path $ScriptDir ".venv"
$Settings = Join-Path $ScriptDir "settings.yml"
$SearxngRun = Join-Path $VenvDir "Scripts\searxng-run.exe"

if (-not (Test-Path $SearxngRun)) {
    Write-Error "SearXNG가 설치되지 않았습니다. install-searxng.ps1을 먼저 실행해주세요."
    exit 1
}

$env:SEARXNG_SETTINGS_PATH = $Settings
& $SearxngRun
'@
    [System.IO.File]::WriteAllText($startPs1, $startPs1Content, [System.Text.UTF8Encoding]::new($false))

    Write-Success "start/stop 스크립트 생성 완료"
}

# ── 사전 무결성 검사 ──

function Test-SearxngPreflight {
    if (-not (Test-Path $SearxngDir)) { return }
    $fixed = $false

    # searxng dir 있지만 searx/ 없으면 손상으로 간주 → 삭제
    $searxSubdir = Join-Path $SearxngDir "searx"
    if (-not (Test-Path $searxSubdir)) {
        Write-Warn "SearXNG 소스가 손상되었습니다 (searx\ 없음). 제거 후 다시 설치합니다."
        Remove-Item -Recurse -Force $SearxngDir
        $fixed = $true
    }

    # venv가 있지만 searxng-run이 없음 (디렉토리가 남아있는 경우만 검사)
    $venvDir = Join-Path $SearxngDir ".venv"
    $searxngRun = Join-Path $venvDir "Scripts\searxng-run.exe"
    if ((Test-Path $venvDir) -and -not (Test-Path $searxngRun)) {
        Write-Warn "SearXNG venv가 손상되었습니다. 제거 후 다시 설치합니다."
        Remove-Item -Recurse -Force $venvDir
        $fixed = $true
    }

    if ($fixed) {
        Write-Success "손상 항목 정리 완료"
    }
}

# ── 실패 정리 ──

function Invoke-SearxngCleanup {
    if ($InstallSuccess) { return }
    Write-Err "SearXNG 설치 실패. 정리 중..."
    if (-not $VenvExisted -and (Test-Path (Join-Path $SearxngDir ".venv"))) {
        Remove-Item -Recurse -Force (Join-Path $SearxngDir ".venv") -ErrorAction SilentlyContinue
        Write-Info "venv 제거 완료"
    }
    if (-not $SearxngExisted -and (Test-Path $SearxngDir)) {
        Remove-Item -Recurse -Force $SearxngDir -ErrorAction SilentlyContinue
        Write-Info "searxng 디렉토리 제거 완료"
    }
}

# ── 실행 ──

Write-Host ""
Write-Host "  -- SearXNG 설치 --" -ForegroundColor Cyan
Write-Host ""

# preflight 먼저 실행 (손상 항목 정리)
Test-SearxngPreflight

# preflight 정리 후의 실제 상태를 캡처
$SearxngExisted = Test-Path $SearxngDir
$VenvExisted = Test-Path (Join-Path $SearxngDir ".venv")
$InstallSuccess = $false

try {
    $pythonOk = Ensure-Python
    if (-not $pythonOk) {
        # Python 설치 안내 출력 후 종료 — 사용자가 설치 후 재실행
        throw "Python 3.11+을 찾을 수 없습니다. 설치 후 다시 실행해주세요."
    }

    Ensure-Uv
    Setup-SearxngSource
    Install-SearxngVenv
    Generate-Settings
    Create-Scripts

    $InstallSuccess = $true
} catch {
    Write-Err "SearXNG 설치 실패: $_"
} finally {
    Invoke-SearxngCleanup
}

if (-not $InstallSuccess) { exit 1 }

Write-Host ""
Write-Success "SearXNG 설치 완료!"
Write-Info "위치: $SearxngDir"
Write-Info "시작: $SearxngDir\start.bat (또는 start.ps1)"
Write-Info "포트: $SearxngPort (localhost 전용)"
Write-Host ""
