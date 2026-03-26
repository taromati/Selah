#!/usr/bin/env bash
set -euo pipefail

# ── Selah Installer (macOS / Linux) ──

SELAH_HOME="${SELAH_HOME:-$HOME/.selah}"
SELAH_VERSION="${SELAH_VERSION:-latest}"
GITHUB_REPO="taromati/Selah"

# Parse arguments
for arg in "$@"; do
    case "$arg" in
        --help|-h)
            echo "사용법: install.sh"
            exit 0
            ;;
    esac
done

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

info()    { echo -e "  ${CYAN}${1}${NC}"; }
success() { echo -e "  ${GREEN}✓ ${1}${NC}"; }
warn()    { echo -e "  ${YELLOW}⚠ ${1}${NC}"; }
error()   { echo -e "  ${RED}✗ ${1}${NC}"; exit 1; }

echo ""
echo -e "${CYAN}${BOLD}  ╔═══════════════════════════════╗${NC}"
echo -e "${CYAN}${BOLD}  ║      Selah Installer          ║${NC}"
echo -e "${CYAN}${BOLD}  ╚═══════════════════════════════╝${NC}"
echo ""

# ── Cleanup 인프라 ──

INSTALL_SUCCESS=false
CLEANUP_ITEMS=()        # rm -rf 대상 경로 (역순 정리)
CLEANUP_SYMLINKS=()     # 이번 실행에서 새로 만든 심볼릭 링크
CLEANUP_RC_FILE=""      # PATH를 추가한 shell rc 파일
SELAH_HOME_EXISTED=false  # 설치 전 ~/.selah 이미 존재했으면 true

# 설치 전 ~/.selah 존재 여부 기록
if [ -d "${SELAH_HOME}" ]; then
    SELAH_HOME_EXISTED=true
fi

cleanup() {
    # cleanup 도중 실패해도 계속 진행
    set +e

    # /tmp/selah-* 임시 파일은 항상 정리
    rm -f /tmp/selah-*.tar.gz /tmp/selah-*.sh 2>/dev/null || true

    # 성공했으면 나머지 정리 불필요
    if [ "${INSTALL_SUCCESS}" = true ]; then
        return
    fi

    echo ""
    warn "설치 실패 — 생성된 항목을 정리합니다..."

    # shell rc 파일에 추가한 PATH 라인 제거
    if [ -n "${CLEANUP_RC_FILE}" ] && [ -f "${CLEANUP_RC_FILE}" ]; then
        # "# Selah" 라인과 그 다음 export PATH 라인을 제거
        # (빈 줄 + # Selah + export PATH 3줄을 삽입했으므로 역순으로 정리)
        local tmp_rc
        tmp_rc=$(mktemp)
        # 빈 줄 → "# Selah" → "export PATH=...(selah 포함)" 패턴 블록 제거
        awk '
            /^$/ { blank=$0; next }
            /^# Selah$/ { skip=1; blank=""; next }
            skip && /^export PATH=.*selah/ { skip=0; next }
            {
                if (blank != "") { print blank; blank="" }
                skip=0
                print
            }
            END { if (blank != "") print blank }
        ' "${CLEANUP_RC_FILE}" > "${tmp_rc}" && mv "${tmp_rc}" "${CLEANUP_RC_FILE}" || rm -f "${tmp_rc}"
        warn "PATH 제거: ${CLEANUP_RC_FILE}"
    fi

    # 새로 만든 심볼릭 링크 제거 (역순)
    local i
    if [ ${#CLEANUP_SYMLINKS[@]} -gt 0 ]; then
        for (( i=${#CLEANUP_SYMLINKS[@]}-1; i>=0; i-- )); do
            local sl="${CLEANUP_SYMLINKS[$i]}"
            if [ -L "${sl}" ]; then
                rm -f "${sl}" 2>/dev/null || true
                warn "심볼릭 링크 제거: ${sl}"
            fi
        done
    fi

    # 이번 실행에서 새로 생성한 항목 제거 (역순)
    if [ ${#CLEANUP_ITEMS[@]} -gt 0 ]; then
        for (( i=${#CLEANUP_ITEMS[@]}-1; i>=0; i-- )); do
            local item="${CLEANUP_ITEMS[$i]}"
            if [ -e "${item}" ]; then
                rm -rf "${item}" 2>/dev/null || true
                warn "제거: ${item}"
            fi
        done
    fi

    # 첫 설치(신규 생성)인 경우 ~/.selah 전체 제거
    if [ "${SELAH_HOME_EXISTED}" = false ] && [ -d "${SELAH_HOME}" ]; then
        rm -rf "${SELAH_HOME}" 2>/dev/null || true
        warn "설치 디렉토리 제거: ${SELAH_HOME}"
    fi
}

trap cleanup EXIT
trap 'trap - EXIT; cleanup; exit 130' INT
trap 'trap - EXIT; cleanup; exit 143' TERM
trap 'trap - EXIT; cleanup; exit 129' HUP

# ── 사전 무결성 검사 (이전 설치가 꼬인 상태 복구) ──

preflight_check() {
    [ -d "${SELAH_HOME}" ] || return 0
    info "기존 설치 감지. 무결성 확인 중..."
    local fixed=false

    # runtime이 있지만 java가 없거나 실행 불가
    if [ -d "${SELAH_HOME}/runtime" ] && [ ! -x "${SELAH_HOME}/runtime/bin/java" ]; then
        warn "런타임이 손상되었습니다. 제거 후 다시 설치합니다."
        rm -rf "${SELAH_HOME}/runtime"
        fixed=true
    fi

    # 0바이트 JAR 제거
    if [ -d "${SELAH_HOME}/lib" ]; then
        local bad_jars
        bad_jars=$(find "${SELAH_HOME}/lib" -maxdepth 1 -name "selah-*.jar" -empty 2>/dev/null)
        if [ -n "${bad_jars}" ]; then
            echo "${bad_jars}" | xargs rm -f
            warn "손상된 JAR 파일을 제거했습니다."
            fixed=true
        fi
    fi

    # 래퍼 스크립트가 비어있음
    if [ -f "${SELAH_HOME}/bin/selah" ] && [ ! -s "${SELAH_HOME}/bin/selah" ]; then
        rm -f "${SELAH_HOME}/bin/selah"
        warn "손상된 래퍼 스크립트를 제거했습니다."
        fixed=true
    fi

    # 서비스가 등록된 상태에서 재설치 시 경고 (실행 중인지도 확인)
    local service_active=false
    local service_running=false
    case "$(uname -s)" in
        Darwin)
            if [ -f "$HOME/Library/LaunchAgents/me.taromati.selah.plist" ]; then
                service_active=true
                if launchctl list me.taromati.selah &>/dev/null 2>&1; then
                    service_running=true
                fi
            fi
            ;;
        Linux)
            if [ -f "$HOME/.config/systemd/user/selah.service" ]; then
                service_active=true
                if systemctl --user is-active selah &>/dev/null 2>&1; then
                    service_running=true
                fi
            fi
            ;;
    esac
    if [ "${service_active}" = true ]; then
        if [ "${service_running}" = true ]; then
            warn "Selah 서비스가 현재 실행 중입니다. 설치 완료 후 서비스가 자동 재시작됩니다."
            warn "지금 중지하려면 Ctrl+C 후 서비스를 먼저 중지해주세요."
        else
            warn "자동 실행 서비스가 등록되어 있습니다. 설치 완료 후 서비스가 자동 재시작됩니다."
        fi
    fi

    if [ "${fixed}" = true ]; then
        success "손상 항목 정리 완료. 설치를 계속합니다."
    else
        success "무결성 확인 완료"
    fi
}

# ── OS/Arch 감지 ──

detect_platform() {
    local os arch
    os="$(uname -s)"
    arch="$(uname -m)"

    case "$os" in
        Darwin) os="macos" ;;
        Linux)  os="linux" ;;
        *)      error "지원하지 않는 OS: $os (macOS 또는 Linux만 지원)" ;;
    esac

    case "$arch" in
        x86_64|amd64) arch="x64" ;;
        aarch64|arm64) arch="aarch64" ;;
        *)             error "지원하지 않는 아키텍처: $arch" ;;
    esac

    echo "${os}-${arch}"
}

PLATFORM=$(detect_platform)
info "플랫폼: ${PLATFORM}"

# ── Java 런타임 확인 ──

check_java() {
    # 자체 런타임만 사용 (시스템 Java 사용하지 않음)
    if [ -x "${SELAH_HOME}/runtime/bin/java" ]; then
        local bundled_ver
        bundled_ver=$("${SELAH_HOME}/runtime/bin/java" -version 2>&1 | head -1 | awk -F '"' '{print $2}' | cut -d. -f1)
        if [ "${bundled_ver:-0}" -ge 25 ]; then
            success "Selah 런타임 (Java ${bundled_ver}) 발견"
            return 0
        else
            warn "Selah 런타임이 오래됐습니다 (Java ${bundled_ver}). 업데이트합니다."
            return 1
        fi
    fi

    return 1
}

install_runtime() {
    # 기존 runtime 백업 후 삭제
    local runtime_backup=""
    if [ -d "${SELAH_HOME}/runtime" ]; then
        runtime_backup="${SELAH_HOME}/runtime.bak.$$"
        mv "${SELAH_HOME}/runtime" "${runtime_backup}"
    fi

    # runtime 디렉토리가 새로 생성되는 경우 cleanup 대상으로 등록
    CLEANUP_ITEMS+=("${SELAH_HOME}/runtime")

    # 1차: jlink 경량 런타임 다운로드 시도 (~60MB)
    local runtime_name="selah-runtime-${RESOLVED_VERSION}-${PLATFORM}.tar.gz"
    local runtime_url="https://github.com/${GITHUB_REPO}/releases/download/v${RESOLVED_VERSION}/${runtime_name}"

    info "jlink 런타임 다운로드 시도..."
    local downloaded=false
    if command -v curl &>/dev/null; then
        if curl -fsSL "${runtime_url}" -o /tmp/selah-runtime.tar.gz 2>/dev/null; then
            downloaded=true
        fi
    elif command -v wget &>/dev/null; then
        if wget -q "${runtime_url}" -O /tmp/selah-runtime.tar.gz 2>/dev/null; then
            downloaded=true
        fi
    fi

    if [ "${downloaded}" = true ]; then
        mkdir -p "${SELAH_HOME}/runtime"
        if ! tar xzf /tmp/selah-runtime.tar.gz -C "${SELAH_HOME}/runtime" --strip-components=1; then
            warn "런타임 압축 해제 실패. 백업에서 복원합니다."
            rm -rf "${SELAH_HOME}/runtime"
            if [ -n "${runtime_backup}" ] && [ -d "${runtime_backup}" ]; then
                mv "${runtime_backup}" "${SELAH_HOME}/runtime"
            fi
            error "런타임 설치에 실패했습니다."
        fi
        rm -f /tmp/selah-runtime.tar.gz
        # 백업 삭제 (성공)
        [ -n "${runtime_backup}" ] && rm -rf "${runtime_backup}" || true
        success "jlink 런타임 설치 완료 (~60MB)"
        return
    fi

    # 2차: Corretto 25 JDK 전체 다운로드 (~300MB)
    warn "jlink 런타임을 찾을 수 없습니다. Corretto 25 JDK를 설치합니다."
    install_corretto_jdk "${runtime_backup}"
}

install_corretto_jdk() {
    local runtime_backup="${1:-}"
    info "AWS Corretto 25 JDK 다운로드 중..."

    # https://docs.aws.amazon.com/corretto/latest/corretto-25-ug/downloads-list.html
    local jdk_os jdk_arch
    case "$PLATFORM" in
        macos-x64)     jdk_os="macos";  jdk_arch="x64" ;;
        macos-aarch64) jdk_os="macos";  jdk_arch="aarch64" ;;
        linux-x64)     jdk_os="linux";  jdk_arch="x64" ;;
        linux-aarch64) jdk_os="linux";  jdk_arch="aarch64" ;;
    esac

    local jdk_url="https://corretto.aws/downloads/latest/amazon-corretto-25-${jdk_arch}-${jdk_os}-jdk.tar.gz"
    info "URL: ${jdk_url}"

    if command -v curl &>/dev/null; then
        curl -fsSL "${jdk_url}" -o /tmp/selah-jdk.tar.gz
    elif command -v wget &>/dev/null; then
        wget -q "${jdk_url}" -O /tmp/selah-jdk.tar.gz
    else
        # 백업 복원 후 오류
        if [ -n "${runtime_backup}" ] && [ -d "${runtime_backup}" ]; then
            mv "${runtime_backup}" "${SELAH_HOME}/runtime"
        fi
        error "curl 또는 wget이 필요합니다."
    fi

    mkdir -p "${SELAH_HOME}/runtime"
    if ! tar xzf /tmp/selah-jdk.tar.gz -C "${SELAH_HOME}/runtime" --strip-components=1; then
        warn "JDK 압축 해제 실패. 백업에서 복원합니다."
        rm -rf "${SELAH_HOME}/runtime"
        if [ -n "${runtime_backup}" ] && [ -d "${runtime_backup}" ]; then
            mv "${runtime_backup}" "${SELAH_HOME}/runtime"
        fi
        error "JDK 설치에 실패했습니다."
    fi
    rm -f /tmp/selah-jdk.tar.gz

    # macOS: Corretto tar.gz는 Contents/Home 구조일 수 있음
    if [ -d "${SELAH_HOME}/runtime/Contents/Home" ]; then
        local tmp_home
        tmp_home=$(mktemp -d)
        # dotfile 포함하여 이동
        ( shopt -s dotglob; mv "${SELAH_HOME}/runtime/Contents/Home"/* "${tmp_home}/" )
        rm -rf "${SELAH_HOME}/runtime"/*
        ( shopt -s dotglob; mv "${tmp_home}"/* "${SELAH_HOME}/runtime/" )
        rm -rf "${tmp_home}"
    fi

    # 백업 삭제 (성공)
    [ -n "${runtime_backup}" ] && rm -rf "${runtime_backup}" || true

    success "AWS Corretto 25 JDK 설치 완료"
}

# ── 버전 확인 ──

resolve_version() {
    if [ "${SELAH_VERSION}" != "latest" ]; then
        RESOLVED_VERSION="${SELAH_VERSION}"
        return
    fi

    info "최신 버전 확인 중..."
    local api_url="https://api.github.com/repos/${GITHUB_REPO}/releases/latest"
    local tag_name=""

    if command -v curl &>/dev/null; then
        tag_name=$(curl -fsSL "${api_url}" | grep '"tag_name"' | head -1 | sed 's/.*"tag_name".*"v\(.*\)".*/\1/')
    elif command -v wget &>/dev/null; then
        tag_name=$(wget -qO- "${api_url}" | grep '"tag_name"' | head -1 | sed 's/.*"tag_name".*"v\(.*\)".*/\1/')
    else
        error "curl 또는 wget이 필요합니다."
    fi

    if [ -z "${tag_name}" ]; then
        error "최신 버전을 확인할 수 없습니다. SELAH_VERSION을 직접 지정해주세요."
    fi

    RESOLVED_VERSION="${tag_name}"
    info "최신 버전: ${RESOLVED_VERSION}"
}

# ── Selah 다운로드 ──

download_selah() {
    info "Selah v${RESOLVED_VERSION} 다운로드 중..."
    mkdir -p "${SELAH_HOME}/lib"

    local jar_name="selah-${RESOLVED_VERSION}.jar"
    local jar_path="${SELAH_HOME}/lib/${jar_name}"
    local download_url="https://github.com/${GITHUB_REPO}/releases/download/v${RESOLVED_VERSION}/${jar_name}"

    # 새로 다운로드하는 JAR만 cleanup 대상으로 등록
    if [ ! -f "${jar_path}" ]; then
        CLEANUP_ITEMS+=("${jar_path}")
    fi

    if command -v curl &>/dev/null; then
        curl -fsSL "${download_url}" -o "${jar_path}"
    elif command -v wget &>/dev/null; then
        wget -q "${download_url}" -O "${jar_path}"
    else
        error "curl 또는 wget이 필요합니다."
    fi

    # 이전 버전 JAR 정리 (-maxdepth 1으로 하위 디렉토리 탐색 방지)
    find "${SELAH_HOME}/lib" -maxdepth 1 -name "selah-*.jar" ! -name "${jar_name}" -delete 2>/dev/null || true

    success "Selah v${RESOLVED_VERSION} 다운로드 완료 (${jar_name})"
}

# ── 기본 파일 설치 ──

install_defaults() {
    mkdir -p "${SELAH_HOME}/"{data,logs,agent-data,bin}

    # 에이전트 기본 파일 다운로드 (JAR 내 리소스 추출 실패 시 폴백용)
    local agent_dir="${SELAH_HOME}/agent-data"
    for md_file in PERSONA.md GUIDE.md TOOLS.md MEMORY.md; do
        if [ ! -f "${agent_dir}/${md_file}" ]; then
            local md_url="https://raw.githubusercontent.com/${GITHUB_REPO}/main/app/src/main/resources/defaults/agent-data/${md_file}"
            if command -v curl &>/dev/null; then
                curl -fsSL "${md_url}" -o "${agent_dir}/${md_file}" 2>/dev/null || true
            elif command -v wget &>/dev/null; then
                wget -q "${md_url}" -O "${agent_dir}/${md_file}" 2>/dev/null || true
            fi
        fi
    done

    # SELAH_HOME 절대 경로를 하드코딩한 래퍼 스크립트 생성
    local _selah_home="${SELAH_HOME}"
    local wrapper="${SELAH_HOME}/bin/selah"
    if [ ! -f "${wrapper}" ]; then
        CLEANUP_ITEMS+=("${wrapper}")
    fi

    # 래퍼 스크립트 생성 (SELAH_HOME을 설치 시점 값으로 하드코딩)
    cat > "${wrapper}" << WRAPPER
#!/usr/bin/env bash
export SELAH_HOME="${_selah_home}"

# 자체 런타임만 사용
JAVA="\${SELAH_HOME}/runtime/bin/java"
if [ ! -x "\${JAVA}" ]; then
    echo "selah: 런타임을 찾을 수 없습니다. 'selah update' 또는 재설치해주세요." >&2
    exit 1
fi

# JAR 찾기
JAR=\$(ls -t "\${SELAH_HOME}"/lib/selah-*.jar 2>/dev/null | head -1)
if [ -z "\${JAR}" ]; then
    echo "selah: JAR 파일을 찾을 수 없습니다. 재설치해주세요." >&2
    exit 1
fi

cd "\${SELAH_HOME}"

# 서버 모드(_server)일 때 SearXNG 자동 실행
if [ "\$1" = "_server" ] && [ -x "\${SELAH_HOME}/searxng/start.sh" ]; then
    "\${SELAH_HOME}/searxng/start.sh" &
    SEARXNG_PID=\$!

    # Selah 종료 시 SearXNG도 종료
    cleanup_searxng() {
        if [ -n "\${SEARXNG_PID}" ] && kill -0 "\${SEARXNG_PID}" 2>/dev/null; then
            kill "\${SEARXNG_PID}" 2>/dev/null
        elif [ -x "\${SELAH_HOME}/searxng/stop.sh" ]; then
            "\${SELAH_HOME}/searxng/stop.sh" 2>/dev/null
        fi
    }
    trap cleanup_searxng EXIT INT TERM
fi

"\${JAVA}" -jar "\${JAR}" "\$@"
WRAPPER
    chmod +x "${wrapper}"

    # defaults/ 복사 (JAR에서 추출하거나 존재하면 복사)
    if [ -d "${SELAH_HOME}/lib/defaults" ] && [ ! -d "${SELAH_HOME}/defaults" ]; then
        cp -r "${SELAH_HOME}/lib/defaults" "${SELAH_HOME}/defaults"
        CLEANUP_ITEMS+=("${SELAH_HOME}/defaults")
    fi

    # 제거 스크립트 생성
    cat > "${SELAH_HOME}/uninstall.sh" << UNINSTALL
#!/usr/bin/env bash
# Selah 제거 스크립트
set -euo pipefail
SELAH_HOME="\${SELAH_HOME:-${_selah_home}}"

echo "Selah을 완전히 제거합니다."
echo "  경로: \${SELAH_HOME}"
read -rp "  계속하시겠습니까? (y/N) " confirm
[[ "\${confirm}" =~ ^[yY]\$ ]] || { echo "취소되었습니다."; exit 0; }

# 1. 서비스 해제
case "\$(uname -s)" in
    Darwin)
        launchctl bootout "gui/\$(id -u)/me.taromati.selah" 2>/dev/null || true
        rm -f "\$HOME/Library/LaunchAgents/me.taromati.selah.plist"
        echo "  ✓ LaunchAgent 해제"
        ;;
    Linux)
        if [ -f "\$HOME/.config/systemd/user/selah.service" ]; then
            systemctl --user stop selah 2>/dev/null || true
            systemctl --user disable selah 2>/dev/null || true
            rm -f "\$HOME/.config/systemd/user/selah.service"
            systemctl --user daemon-reload 2>/dev/null || true
            echo "  ✓ systemd 서비스 해제"
        fi
        ;;
esac

# 2. PATH 정리
# symlink
[ -L /usr/local/bin/selah ] && rm -f /usr/local/bin/selah && echo "  ✓ symlink 제거"
# shell rc
for rc in "\$HOME/.zshrc" "\$HOME/.bashrc" "\$HOME/.bash_profile"; do
    if [ -f "\$rc" ] && grep -q "# Selah" "\$rc" 2>/dev/null; then
        sed -i.bak '/# Selah/,/selah/d' "\$rc" && rm -f "\${rc}.bak"
        echo "  ✓ PATH 제거: \$rc"
    fi
done

# 3. SearXNG 중지
if [ -f "\${SELAH_HOME}/searxng/stop.sh" ]; then
    bash "\${SELAH_HOME}/searxng/stop.sh" 2>/dev/null || true
    echo "  ✓ SearXNG 중지"
fi

# 4. 디렉토리 삭제
rm -rf "\${SELAH_HOME}"
echo ""
echo "  ✓ Selah 제거 완료"
UNINSTALL
    chmod +x "${SELAH_HOME}/uninstall.sh"

    success "기본 파일 설치 완료"
}

# ── PATH 등록 ──

setup_path() {
    local bin_dir="${SELAH_HOME}/bin"
    local shell_rc

    # 이미 PATH에 있으면 스킵
    if echo "$PATH" | tr ':' '\n' | grep -qxF "${bin_dir}"; then
        return 0
    fi

    # 심볼릭 링크 방식 (우선)
    # 1차: /usr/local/bin (macOS 기본, Linux root)
    if [ -w /usr/local/bin ]; then
        if ln -sf "${bin_dir}/selah" /usr/local/bin/selah; then
            CLEANUP_SYMLINKS+=("/usr/local/bin/selah")
            success "symlink: /usr/local/bin/selah"
            return 0
        fi
    fi
    # 2차: ~/.local/bin (Ubuntu/Fedora 기본 PATH)
    if mkdir -p "$HOME/.local/bin" 2>/dev/null; then
        if ln -sf "${bin_dir}/selah" "$HOME/.local/bin/selah"; then
            CLEANUP_SYMLINKS+=("$HOME/.local/bin/selah")
            export PATH="$HOME/.local/bin:$PATH"
            success "symlink: ~/.local/bin/selah"
            return 0
        fi
    fi

    # shell rc에 PATH 추가
    if [ -f "$HOME/.zshrc" ]; then
        shell_rc="$HOME/.zshrc"
    elif [ -f "$HOME/.bashrc" ]; then
        shell_rc="$HOME/.bashrc"
    elif [ -f "$HOME/.bash_profile" ]; then
        shell_rc="$HOME/.bash_profile"
    else
        warn "셸 설정 파일을 찾을 수 없습니다. PATH를 수동으로 추가해주세요:"
        info "  export PATH=\"${bin_dir}:\$PATH\""
        return 0
    fi

    if ! grep -qF "selah" "${shell_rc}" 2>/dev/null; then
        echo "" >> "${shell_rc}"
        echo "# Selah" >> "${shell_rc}"
        echo "export PATH=\"${bin_dir}:\$PATH\"" >> "${shell_rc}"
        CLEANUP_RC_FILE="${shell_rc}"
        export PATH="${bin_dir}:$PATH"
        success "PATH 추가: ${shell_rc}"
    fi
}

# ── 실행 ──

info "설치 경로: ${SELAH_HOME}"
echo ""

preflight_check
resolve_version

if ! check_java; then
    install_runtime
fi
download_selah
install_defaults
setup_path

# Selah 본체 설치 완료 — SearXNG 실패로 롤백되지 않도록 먼저 표시
INSTALL_SUCCESS=true

# ── SearXNG 설치 (Agent web search) ──

echo ""
INSTALLER_DIR="$(cd "$(dirname "$0")" && pwd)"
(
    set +eo pipefail
    if [ -f "${INSTALLER_DIR}/install-searxng.sh" ]; then
        export SELAH_HOME RED GREEN YELLOW CYAN BOLD NC
        bash "${INSTALLER_DIR}/install-searxng.sh"
    else
        info "SearXNG 설치 스크립트 다운로드 중..."
        local_script="/tmp/selah-install-searxng.sh"
        if command -v curl &>/dev/null; then
            curl -fsSL "https://raw.githubusercontent.com/${GITHUB_REPO}/main/install-searxng.sh" \
                -o "${local_script}" 2>/dev/null || true
        elif command -v wget &>/dev/null; then
            wget -q "https://raw.githubusercontent.com/${GITHUB_REPO}/main/install-searxng.sh" \
                -O "${local_script}" 2>/dev/null || true
        fi
        if [ -f "${local_script}" ] && [ -s "${local_script}" ]; then
            export SELAH_HOME RED GREEN YELLOW CYAN BOLD NC
            bash "${local_script}"
            rm -f "${local_script}"
        else
            false  # 서브셸 실패로 전파
        fi
    fi
) || {
    echo ""
    warn "SearXNG 설치에 실패했습니다. 웹 검색 기능이 비활성화됩니다."
    info "  재설치: curl -fsSL https://raw.githubusercontent.com/${GITHUB_REPO}/main/install-searxng.sh | SELAH_HOME=\"${SELAH_HOME}\" bash"
}

echo ""
success "Selah 설치 완료!"
echo ""
# PATH 적용 안내 (symlink이 아닌 shell rc 방식인 경우)
if [ -n "${CLEANUP_RC_FILE}" ]; then
    warn "PATH를 적용하려면 새 터미널을 열거나 다음을 실행하세요:"
    info "  source ${CLEANUP_RC_FILE}"
    echo ""
fi
info "제거하려면: ${SELAH_HOME}/uninstall.sh"
echo ""
info "초기 설정을 시작합니다..."
echo ""

# curl | bash 시 stdin이 파이프에 물려있으므로 터미널로 복원
if [ ! -t 0 ] && [ -e /dev/tty ]; then
    exec < /dev/tty
fi

cd "${SELAH_HOME}"
set +e
"${SELAH_HOME}/bin/selah" setup
