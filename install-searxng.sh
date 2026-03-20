#!/usr/bin/env bash
set -euo pipefail

# ── Selah용 SearXNG 설치 스크립트 ──
#
# Agent의 web_search 도구를 위한 SearXNG(메타검색엔진)을 로컬 서비스로 설치합니다.
# install.sh에서 호출되거나 단독 실행할 수 있습니다.
#
# 순서:
#   1. Python 3.11+ 확인 (없으면 패키지 매니저로 설치)
#   2. uv (Python 패키지 매니저) 확인
#   3. SearXNG 소스를 ~/.selah/searxng/에 clone
#   4. Python venv 생성 + SearXNG 설치
#   5. settings.yml 생성
#   6. start/stop 스크립트 생성

SELAH_HOME="${SELAH_HOME:-$HOME/.selah}"
SEARXNG_DIR="${SELAH_HOME}/searxng"
SEARXNG_PORT="${SEARXNG_PORT:-8888}"
SEARXNG_ARCHIVE="https://github.com/searxng/searxng/archive/refs/heads/master.tar.gz"

# 색상 (install.sh에서 이미 정의된 경우 재사용)
RED="${RED:-\033[0;31m}"
GREEN="${GREEN:-\033[0;32m}"
YELLOW="${YELLOW:-\033[0;33m}"
CYAN="${CYAN:-\033[0;36m}"
BOLD="${BOLD:-\033[1m}"
NC="${NC:-\033[0m}"

info()    { echo -e "  ${CYAN}${1}${NC}"; }
success() { echo -e "  ${GREEN}✓ ${1}${NC}"; }
warn()    { echo -e "  ${YELLOW}⚠ ${1}${NC}"; }
err()     { echo -e "  ${RED}✗ ${1}${NC}"; }
die()     { err "$1"; exit 1; }

# ── 실패 정리 ──
# preflight 이후에 캡처하므로 일단 false로 초기화
SEARXNG_EXISTED=false
VENV_EXISTED=false
INSTALL_SUCCESS=false

cleanup() {
    set +e  # 정리 중 에러가 다시 abort되지 않도록
    if [ "$INSTALL_SUCCESS" = true ]; then return; fi
    err "SearXNG 설치 실패. 정리 중..."
    if [ "$VENV_EXISTED" = false ] && [ -d "${SEARXNG_DIR}/.venv" ]; then
        rm -rf "${SEARXNG_DIR}/.venv"
    fi
    if [ "$SEARXNG_EXISTED" = false ] && [ -d "${SEARXNG_DIR}" ]; then
        rm -rf "${SEARXNG_DIR}"
    fi
}
trap cleanup EXIT
trap 'trap - EXIT; cleanup; exit 130' INT
trap 'trap - EXIT; cleanup; exit 143' TERM
trap 'trap - EXIT; cleanup; exit 129' HUP

# ── 사전 무결성 검사 ──

preflight_searxng() {
    [ -d "${SEARXNG_DIR}" ] || return 0
    local fixed=false

    # searxng dir 있지만 searx/ 없으면 손상으로 간주 → 삭제
    if [ ! -d "${SEARXNG_DIR}/searx" ]; then
        warn "SearXNG 소스가 손상되었습니다 (searx/ 없음). 제거 후 다시 설치합니다."
        rm -rf "${SEARXNG_DIR}"
        fixed=true
    fi

    # venv가 있지만 searxng-run이 없음 (디렉토리가 남아있는 경우만 검사)
    if [ -d "${SEARXNG_DIR}/.venv" ] && \
       [ ! -f "${SEARXNG_DIR}/.venv/bin/searxng-run" ] && \
       [ ! -f "${SEARXNG_DIR}/.venv/Scripts/searxng-run.exe" ]; then
        warn "SearXNG venv가 손상되었습니다. 제거 후 다시 설치합니다."
        rm -rf "${SEARXNG_DIR}/.venv"
        fixed=true
    fi

    if [ "${fixed}" = true ]; then
        success "손상 항목 정리 완료"
    fi
}

# ── OS 감지 ──

detect_os() {
    case "$(uname -s)" in
        Darwin) echo "macos" ;;
        Linux)  echo "linux" ;;
        *)      echo "unknown" ;;
    esac
}

OS=$(detect_os)

# ── Step 1: Python 3.11+ 확인 ──

check_python_version() {
    local cmd="$1"
    if ! command -v "$cmd" &>/dev/null; then
        return 1
    fi
    local ver
    ver=$("$cmd" --version 2>&1 | grep -oE '[0-9]+\.[0-9]+' | head -1)
    local major minor
    major=$(echo "$ver" | cut -d. -f1)
    minor=$(echo "$ver" | cut -d. -f2)
    # major > 3 이면 무조건 통과, major == 3이면 minor >= 11 필요
    if [ "${major:-0}" -gt 3 ]; then
        return 0
    fi
    if [ "${major:-0}" -eq 3 ] && [ "${minor:-0}" -ge 11 ]; then
        return 0
    fi
    return 1
}

find_python() {
    for cmd in python3 python python3.13 python3.12 python3.11; do
        if check_python_version "$cmd"; then
            echo "$cmd"
            return 0
        fi
    done
    return 1
}

install_python() {
    info "Python 3.11+을 찾을 수 없습니다. 설치합니다..."
    local auto_installed=false

    case "$OS" in
        macos)
            if command -v brew &>/dev/null; then
                info "Homebrew로 Python 3.12 설치 중..."
                brew install python@3.12
                auto_installed=true
            fi
            ;;
        linux)
            if command -v apt-get &>/dev/null; then
                info "apt로 Python 3 설치 중..."
                sudo apt-get update -qq
                sudo apt-get install -y -qq python3 python3-venv python3-dev
                auto_installed=true
            elif command -v dnf &>/dev/null; then
                info "dnf로 Python 3 설치 중..."
                sudo dnf install -y python3 python3-devel
                auto_installed=true
            elif command -v pacman &>/dev/null; then
                info "pacman으로 Python 3 설치 중..."
                sudo pacman -S --noconfirm python
                auto_installed=true
            fi
            ;;
    esac

    if [ "$auto_installed" = true ]; then
        return
    fi

    # 자동 설치 실패 → 안내 후 대기
    echo ""
    warn "Python을 자동 설치할 수 없습니다."
    echo ""
    case "$OS" in
        macos)
            info "Homebrew를 먼저 설치해주세요: https://brew.sh"
            info "  /bin/bash -c \"\$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)\""
            info "설치 후 : brew install python@3.12"
            ;;
        linux)
            info "패키지 매니저(apt/dnf/pacman)를 찾을 수 없습니다."
            info "Python 3.11+ 을 수동 설치해주세요: https://www.python.org/downloads/"
            ;;
        *)
            info "지원하지 않는 OS입니다. Python 3.11+을 수동 설치해주세요."
            ;;
    esac
    echo ""

    # 비인터랙티브 환경에서 read 실패 시 명확한 에러 출력
    if ! read -rp "  Python 설치가 끝나면 Enter를 눌러주세요... " 2>/dev/null; then
        die "비인터랙티브 환경에서 Python 설치 대기 불가. Python 3.11+을 먼저 설치한 후 다시 실행해주세요."
    fi

    if find_python >/dev/null 2>&1; then
        return
    fi

    warn "아직 Python을 찾을 수 없습니다. PATH를 확인해주세요."
    if ! read -rp "  확인 후 Enter를 눌러주세요... " 2>/dev/null; then
        die "비인터랙티브 환경에서 Python 설치 대기 불가. Python 3.11+을 먼저 설치한 후 다시 실행해주세요."
    fi

    if ! find_python >/dev/null 2>&1; then
        die "Python 3.11+을 찾을 수 없습니다. 새 터미널에서 다시 실행해주세요."
    fi
}

ensure_python() {
    local python_cmd
    if python_cmd=$(find_python); then
        local ver
        ver=$("$python_cmd" --version 2>&1)
        success "Python 발견: ${ver}"
        PYTHON_CMD="$python_cmd"
        return 0
    fi

    install_python

    if python_cmd=$(find_python); then
        local ver
        ver=$("$python_cmd" --version 2>&1)
        success "Python 설치 완료: ${ver}"
        PYTHON_CMD="$python_cmd"
        return 0
    fi

    die "Python 3.11+ 설치에 실패했습니다. 수동 설치해주세요."
}

# ── Step 2: uv 확인 ──

ensure_uv() {
    if command -v uv &>/dev/null; then
        success "uv 발견: $(uv --version)"
        return 0
    fi

    # PATH에 없지만 설치된 경우
    local uv_candidates=(
        "$HOME/.local/bin/uv"
        "$HOME/.cargo/bin/uv"
    )
    for candidate in "${uv_candidates[@]}"; do
        if [ -x "$candidate" ]; then
            export PATH="$(dirname "$candidate"):$PATH"
            success "uv 발견: $(uv --version)"
            return 0
        fi
    done

    info "uv 설치 중..."
    if command -v curl &>/dev/null; then
        curl -LsSf https://astral.sh/uv/install.sh | sh
    elif command -v wget &>/dev/null; then
        wget -qO- https://astral.sh/uv/install.sh | sh
    else
        die "uv 설치에 curl 또는 wget이 필요합니다."
    fi

    # 현재 세션 PATH에 추가
    if [ -x "$HOME/.local/bin/uv" ]; then
        export PATH="$HOME/.local/bin:$PATH"
    elif [ -x "$HOME/.cargo/bin/uv" ]; then
        export PATH="$HOME/.cargo/bin:$PATH"
    fi

    if ! command -v uv &>/dev/null; then
        die "uv 설치에 실패했습니다. 수동 설치: https://docs.astral.sh/uv/"
    fi

    success "uv 설치 완료: $(uv --version)"
}

# ── Step 3: SearXNG 소스 다운로드 ──

setup_searxng_source() {
    if [ -d "${SEARXNG_DIR}/searx" ]; then
        success "SearXNG 소스가 이미 존재합니다"
        return 0
    fi

    info "SearXNG 다운로드 중..."
    mkdir -p "${SEARXNG_DIR}"

    local tmp_archive="/tmp/selah-searxng.tar.gz"
    if command -v curl &>/dev/null; then
        curl -fsSL "${SEARXNG_ARCHIVE}" -o "${tmp_archive}"
    elif command -v wget &>/dev/null; then
        wget -q "${SEARXNG_ARCHIVE}" -O "${tmp_archive}"
    else
        die "curl 또는 wget이 필요합니다."
    fi

    tar xzf "${tmp_archive}" -C "${SEARXNG_DIR}" --strip-components=1
    rm -f "${tmp_archive}"
    success "SearXNG 소스 다운로드 완료"
}

# ── Step 4: venv 생성 + 설치 ──

install_searxng_venv() {
    local venv_dir="${SEARXNG_DIR}/.venv"

    if [ -f "${venv_dir}/bin/searxng-run" ] || [ -f "${venv_dir}/Scripts/searxng-run.exe" ]; then
        success "SearXNG venv 이미 설치됨"
        return 0
    fi

    info "Python venv 생성 및 SearXNG 설치 중..."

    uv venv "${venv_dir}" --python "${PYTHON_CMD}"
    # SearXNG의 setup.py가 빌드 시 런타임 의존성을 import하므로 먼저 전체 설치
    uv pip install --python "${venv_dir}/bin/python" setuptools
    uv pip install --python "${venv_dir}/bin/python" -r "${SEARXNG_DIR}/requirements.txt"
    uv pip install --python "${venv_dir}/bin/python" --no-deps --no-build-isolation "${SEARXNG_DIR}"

    if [ -f "${venv_dir}/bin/searxng-run" ]; then
        success "SearXNG 설치 완료"
    else
        die "SearXNG 설치 실패: searxng-run을 찾을 수 없습니다"
    fi
}

# ── Step 5: settings.yml 생성 ──

generate_settings() {
    local settings_file="${SEARXNG_DIR}/settings.yml"

    if [ -f "${settings_file}" ]; then
        success "settings.yml 이미 존재 (기존 설정 유지)"
        return 0
    fi

    info "settings.yml 생성 중..."

    local secret_key
    if command -v openssl &>/dev/null; then
        secret_key=$(openssl rand -hex 32)
    elif command -v python3 &>/dev/null; then
        secret_key=$(python3 -c "import secrets; print(secrets.token_hex(32))")
    else
        secret_key=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')
    fi

    cat > "${settings_file}" << SETTINGS_EOF
# Selah용 SearXNG 설정
# 문서: https://docs.searxng.org/admin/settings/index.html

use_default_settings: true

general:
  instance_name: "Selah SearXNG"
  enable_metrics: false

server:
  port: ${SEARXNG_PORT}
  bind_address: "127.0.0.1"
  secret_key: "${secret_key}"
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
SETTINGS_EOF

    success "settings.yml 생성 완료 (포트: ${SEARXNG_PORT}, 바인드: 127.0.0.1)"
}

# ── Step 6: start/stop 스크립트 생성 ──

create_start_script() {
    local start_script="${SEARXNG_DIR}/start.sh"

    cat > "${start_script}" << 'START_EOF'
#!/usr/bin/env bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VENV_DIR="${SCRIPT_DIR}/.venv"
SETTINGS="${SCRIPT_DIR}/settings.yml"
PID_FILE="${SCRIPT_DIR}/searxng.pid"

if [ ! -f "${VENV_DIR}/bin/searxng-run" ]; then
    echo "오류: SearXNG가 설치되지 않았습니다. install-searxng.sh를 먼저 실행해주세요." >&2
    exit 1
fi

# 이미 실행 중이면 스킵
if [ -f "${PID_FILE}" ]; then
    old_pid=$(cat "${PID_FILE}")
    if kill -0 "${old_pid}" 2>/dev/null; then
        exit 0
    fi
    rm -f "${PID_FILE}"
fi

# PID 파일 생성 (exec로 프로세스 교체되기 전에 현재 PID 기록)
echo $$ > "${PID_FILE}"

export SEARXNG_SETTINGS_PATH="${SETTINGS}"
exec "${VENV_DIR}/bin/searxng-run"
START_EOF
    chmod +x "${start_script}"

    local stop_script="${SEARXNG_DIR}/stop.sh"
    cat > "${stop_script}" << 'STOP_EOF'
#!/usr/bin/env bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PID_FILE="${SCRIPT_DIR}/searxng.pid"
VENV_DIR="${SCRIPT_DIR}/.venv"

if [ -f "${PID_FILE}" ]; then
    pid=$(cat "${PID_FILE}")
    if kill -0 "$pid" 2>/dev/null; then
        kill "$pid"
        echo "SearXNG 종료 (PID: $pid)"
    else
        echo "SearXNG 미실행 (오래된 PID 파일)"
    fi
    rm -f "${PID_FILE}"
else
    # PID 파일 없으면 venv 경로 기준으로 searxng-run 프로세스 탐색
    pid=$(pgrep -f "${VENV_DIR}/bin/searxng-run" 2>/dev/null | head -1)
    if [ -n "$pid" ]; then
        kill "$pid"
        echo "SearXNG 종료 (PID: $pid)"
    else
        echo "SearXNG 미실행"
    fi
fi
STOP_EOF
    chmod +x "${stop_script}"

    success "start/stop 스크립트 생성 완료"
}

# ── 실행 ──

echo ""
echo -e "${CYAN}${BOLD}  ── SearXNG 설치 ──${NC}"
echo ""

# preflight 먼저 실행 (손상 항목 정리)
preflight_searxng

# preflight 정리 후의 실제 상태를 캡처
[ -d "${SEARXNG_DIR}" ]       && SEARXNG_EXISTED=true || SEARXNG_EXISTED=false
[ -d "${SEARXNG_DIR}/.venv" ] && VENV_EXISTED=true    || VENV_EXISTED=false

ensure_python
ensure_uv
setup_searxng_source
install_searxng_venv
generate_settings
create_start_script

INSTALL_SUCCESS=true

echo ""
success "SearXNG 설치 완료!"
info "위치: ${SEARXNG_DIR}"
info "시작: ${SEARXNG_DIR}/start.sh"
info "포트: ${SEARXNG_PORT} (localhost 전용)"
echo ""
