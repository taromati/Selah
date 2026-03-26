# Selah

셀프 호스팅 AI 에이전트 봇. Discord와 Telegram을 지원합니다.

## 주요 기능
- **AI 에이전트** — 도구 호출이 가능한 대화형 AI
- **MCP 연동** — Model Context Protocol로 외부 도구 연결
- **메모리** — 하이브리드 검색 (BM25 + 임베딩) 기반 영구 기억
- **웹 검색** — 실시간 웹 검색 및 페이지 읽기
- **예약 작업** — cron 기반 자동화
- **스킬** — 확장 가능한 커스텀 워크플로우
- **웹 UI** — 브라우저 기반 관리 대시보드

## 빠른 시작

### 자동 설치 (권장)

**macOS / Linux:**
```bash
curl -fsSL https://raw.githubusercontent.com/taromati/Selah/main/install.sh | bash
```

**Windows (PowerShell):**
```powershell
powershell -ExecutionPolicy Bypass -Command "irm https://raw.githubusercontent.com/taromati/Selah/main/install.ps1 | iex"
```

설치 스크립트가 다음을 자동 처리합니다:
- Java 런타임 (Corretto 25) 다운로드
- Selah JAR 다운로드
- 초기 설정 마법사 (`selah setup`) 실행

### 수동 설치

```bash
# 1. 빌드
./gradlew clean build

# 2. 기본 에이전트 데이터 복사
cp -r defaults/agent-data/ agent-data/

# 3. 초기 설정
java -jar build/libs/selah-*.jar setup

# 4. 실행
java -jar build/libs/selah-*.jar
```

## 주요 명령어

| 명령어 | 설명 |
|--------|------|
| `selah` | 서버 시작 |
| `selah setup` | 초기 설정 마법사 |
| `selah doctor` | 설정 검증 |
| `selah enable` | OS 서비스 등록 (자동 시작) |
| `selah disable` | 서비스 해제 |
| `selah update` | 자체 업데이트 |
| `selah --version` | 버전 출력 |

## 기술 스택
- Java 25 + Spring Boot 4
- SQLite (플러그인별 분리)
- JDA 6 (Discord) / Telegram Bot API
- Vue 3 + TypeScript + Element Plus (웹 UI)

## 문서
- [설치 가이드](docs/setup-guide.md)
- [설정 레퍼런스](docs/configuration.md)
