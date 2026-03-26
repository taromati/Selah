# 서비스 라이프사이클

Selah의 설치 → 실행 → 관리 → 제거까지의 전체 플로우를 OS별로 정리한 문서.

## CLI 명령어

| 명령 | 설명 |
|------|------|
| `selah` / `selah start` | 서비스 등록 + 시작 (Windows: 관리자 권한 필요) |
| `selah stop` | 서비스 해제 + 프로세스 종료 |
| `selah restart` | 서비스 재시작 |
| `selah setup` | 초기 설정 마법사 (완료 시 서비스 자동 등록+시작) |
| `selah doctor` | 설정 검증 |
| `selah update` | 자체 업데이트 (JAR 교체 + 재시작) |
| `selah --version` | 버전 출력 |
| `_server` | **내부 명령** — 서비스 매니저가 Spring Boot 서버를 실행할 때 사용 |

## 시나리오 1: 신규 설치 → setup → start

```
[사용자]
  │
  ▼
install.ps1 (Win) / install.sh (macOS/Linux)
  ├── Java 런타임 설치 (jlink ~60MB 또는 Corretto 25)
  ├── Selah JAR 다운로드 → ~/.selah/lib/
  ├── NSSM 다운로드 → ~/.selah/bin/nssm.exe (Windows만)
  ├── 래퍼 스크립트 생성 (bin/selah.bat 또는 bin/selah)
  ├── uninstall 스크립트 생성
  ├── PATH 등록
  └── install-searxng 호출
        ├── Python + uv 확인/설치
        ├── SearXNG 소스 다운로드 + venv 설치
        ├── settings.yml + start/stop 스크립트 생성
        └── (Windows + 관리자) NSSM으로 selah-searxng 서비스 등록
  │
  ▼
selah setup (Win: 수동 실행 / macOS·Linux: install.sh가 자동 실행)
  → SetupWizard.run()
       ├── 5단계 위저드: 메신저 → LLM → 설정 → 임베딩 → Agent
       ├── config.yml 생성
       └── ServiceInstaller.install() ← 자동 서비스 등록 + 시작
             │
             ├── [Win] WindowsService.install()
             │     ├── NSSM 서비스 등록 (java -jar selah.jar _server)
             │     ├── SearXNG 서비스 등록 (searxng-run.exe)
             │     ├── nssm start selah
             │     └── health check 검증 (최대 15초)
             │
             ├── [macOS] MacOsService.install()
             │     ├── plist 생성 → ~/Library/LaunchAgents/
             │     └── launchctl bootstrap
             │
             └── [Linux] LinuxService.install()
                   ├── systemd unit 생성 → ~/.config/systemd/user/
                   ├── systemctl enable + restart
                   └── loginctl enable-linger
```

### OS별 분기

| 단계 | Windows | macOS | Linux |
|------|---------|-------|-------|
| 인스톨러 | install.ps1 | install.sh | install.sh |
| SearXNG 서비스 | NSSM 별도 서비스 | 래퍼 스크립트 동반 실행 | 래퍼 스크립트 동반 실행 |
| setup 자동 실행 | 안내만 출력 (수동) | install.sh 끝에서 자동 | install.sh 끝에서 자동 |
| 서비스 매니저 | NSSM | LaunchAgent | systemd user |

### 에러 케이스

| 상황 | 동작 |
|------|------|
| NSSM 다운로드 실패 | 경고 출력, `selah start`에서 레거시 모드 (Startup 폴더) |
| SearXNG 설치 실패 | Selah 본체는 정상 설치, 웹 검색만 비활성화 |
| 관리자 권한 없음 (Win) | NSSM 서비스 등록 스킵, 안내 메시지 출력 |

---

## 시나리오 2: 재부팅 (서비스 자동 시작)

```
[OS 부팅]
  │
  ├── [Windows] NSSM 서비스 매니저
  │     ├── "selah" (SERVICE_AUTO_START)
  │     │     → java -jar selah.jar _server
  │     │     → SpringApplication.run()
  │     │     → SearxngHealthChecker (60초 주기)
  │     │
  │     └── "selah-searxng" (SERVICE_AUTO_START)
  │           → searxng-run.exe (독립 프로세스)
  │
  ├── [macOS] launchd (RunAtLoad=true)
  │     └── bin/selah _server
  │           ├── searxng/start.sh & (SearXNG 백그라운드)
  │           ├── trap cleanup_searxng EXIT
  │           └── java -jar selah.jar _server → Spring Boot
  │
  └── [Linux] systemd (WantedBy=default.target)
        └── bin/selah _server
              └── (macOS와 동일한 래퍼 구조)
```

### SearXNG 시작 메커니즘

| OS | 1차 시작 | 자동 재시작 | 2차 방어 |
|----|----------|------------|---------|
| Windows | NSSM 별도 서비스 | NSSM AppExit=Restart (5초) | SearxngHealthChecker → nssm start |
| macOS | 래퍼 스크립트가 start.sh 실행 | 없음 (자식 프로세스) | SearxngHealthChecker → start.sh 재실행 |
| Linux | 래퍼 스크립트가 start.sh 실행 | 없음 (자식 프로세스) | SearxngHealthChecker → start.sh 재실행 |

---

## 시나리오 3: selah stop → selah start

```
[selah stop]
  → SelahApplication.main(["stop"])
  → ServiceInstaller.uninstall()
       │
       ├── [Win] WindowsService.uninstall()
       │     ├── 레거시 항목 정리 (Startup, schtasks)
       │     ├── nssm stop + remove selah-searxng
       │     ├── nssm stop + remove selah
       │     └── 종료 검증 (2초 후 health check)
       │
       ├── [macOS] MacOsService.uninstall()
       │     ├── launchctl bootout → SearXNG도 trap으로 종료
       │     └── plist 삭제
       │
       └── [Linux] LinuxService.uninstall()
             ├── systemctl stop + disable → SearXNG도 trap으로 종료
             └── service 파일 삭제 + daemon-reload

[selah start]
  → (시나리오 1의 ServiceInstaller.install()과 동일)
```

---

## 시나리오 4: 웹 UI 설정 변경 → 재시작

```
[웹 UI]
  │
  ├── POST /api/setup/config
  │     → SetupConfigService.saveConfig()
  │     → config.yml 원자적 교체 (tmp → move)
  │     → ConfigSaveResult(restartRequired, serviceRegistered)
  │
  └── POST /api/setup/restart
        → ServiceRestarter.restart(async=true)
             ├── 200ms 지연 (웹 응답 먼저 반환)
             │
             ├── [Win] NSSM 등록시: nssm restart selah
             │         레거시: restart.bat → System.exit(0) → 3초 후 재시작
             ├── [macOS] launchctl kickstart -k gui/{uid}/me.taromati.selah
             └── [Linux] systemctl --user restart selah
```

> SearXNG는 config 변경과 무관 (설정 파일이 별도). Selah 재시작 시:
> - Windows: SearXNG는 독립 서비스이므로 영향 없음
> - macOS/Linux: Selah 종료 → trap → SearXNG 종료 → 서비스 매니저 재시작 → SearXNG도 재시작

---

## 시나리오 5: SearXNG 크래시

```
[SearXNG 프로세스 죽음]
  │
  ├── [Windows] 1차: NSSM AppExit=Restart (5초 후 자동 재시작)
  │
  ├── [macOS/Linux] 1차: 자동 재시작 없음
  │     (SearXNG는 래퍼의 자식 프로세스, 서비스 매니저가 관리하지 않음)
  │
  └── [전 OS] 2차: SearxngHealthChecker (@Scheduled 60초 주기)
        ├── SearXNG URL 테스트 실패 감지
        ├── [Win] WindowsService.tryStartSearxng() → nssm start
        ├── [macOS/Linux] searxng/start.sh 재실행
        ├── 5초 대기 후 재확인
        └── 실패 시 경고 로그 (다음 주기에 재시도)
```

---

## 시나리오 6: selah update

```
[selah update]
  → SelfUpdater.run()
       ├── GitHub API에서 최신 버전 확인
       ├── 최신이면 "이미 최신" → 종료
       ├── JAR 다운로드 → lib/selah-{version}.jar
       │
       ├── (실행 중이면) stopRunningSelah()
       │     ├── [Win NSSM] nssm stop selah
       │     ├── [Win 레거시] taskkill (WINDOWTITLE 필터)
       │     ├── [macOS] launchctl kill SIGTERM gui/{uid}/...
       │     └── [Linux] systemctl --user stop selah
       │
       ├── 3초 대기 (JAR 잠금 해제)
       ├── 이전 JAR 삭제
       │
       └── (실행 중이었으면) 재시작
             ├── [Win NSSM] nssm start selah
             ├── [Win 레거시] cmd /c start selah.bat
             ├── [macOS] launchctl kickstart -k
             └── [Linux] systemctl --user restart selah
```

---

## 시나리오 7: uninstall

```
[Windows: uninstall.ps1]
  ├── NSSM 서비스 해제 (selah-searxng → selah)
  ├── 레거시 항목 정리 (Startup, schtasks)
  ├── 잔여 프로세스 종료 (taskkill)
  ├── PATH 정리
  └── rm -rf ~/.selah/

[macOS: uninstall.sh]
  ├── launchctl bootout + plist 삭제
  ├── symlink/shell rc PATH 정리
  ├── SearXNG 중지 (stop.sh)
  └── rm -rf ~/.selah/

[Linux: uninstall.sh]
  ├── systemctl stop + disable + unit 삭제 + daemon-reload
  ├── symlink/shell rc PATH 정리
  ├── SearXNG 중지 (stop.sh)
  └── rm -rf ~/.selah/
```

---

## 파일 구조

```
~/.selah/
├── bin/
│   ├── selah.bat (Win) / selah (Unix)    # CLI 래퍼 스크립트
│   └── nssm.exe (Win만)                  # 서비스 매니저
├── lib/
│   └── selah-{version}.jar               # 애플리케이션
├── runtime/                              # 번들 Java 런타임
├── config.yml                            # 설정 파일
├── agent-data/                           # Agent 데이터
├── memory-data/                          # 메모리 데이터
├── logs/
│   ├── nssm-stdout.log (Win)
│   ├── nssm-stderr.log (Win)
│   ├── searxng-stdout.log (Win)
│   └── searxng-stderr.log (Win)
├── searxng/
│   ├── .venv/                            # SearXNG Python 환경
│   ├── settings.yml
│   ├── start.bat / start.sh
│   └── stop.bat / stop.sh
└── uninstall.ps1 (Win) / uninstall.sh
```

### 서비스 파일 위치

| OS | 서비스 | 파일 |
|----|--------|------|
| Windows | selah | NSSM 레지스트리 (`nssm install selah`) |
| Windows | selah-searxng | NSSM 레지스트리 (`nssm install selah-searxng`) |
| macOS | me.taromati.selah | `~/Library/LaunchAgents/me.taromati.selah.plist` |
| Linux | selah | `~/.config/systemd/user/selah.service` |
