# 설치 가이드

## 요구 사항
- Discord 봇 토큰 또는 Telegram 봇 토큰
- LLM API (OpenAI, vLLM, Gemini CLI 중 하나)

> Java 런타임은 설치 스크립트가 자동으로 준비합니다. 수동 설치 시 Amazon Corretto 25를 권장합니다.

## 자동 설치

```bash
curl -fsSL https://raw.githubusercontent.com/taromati/Selah/main/install.sh | bash
```

설치가 끝나면 자동으로 `selah setup`이 실행됩니다.

## 초기 설정 (selah setup)

5단계 마법사가 `config.yml`을 생성합니다:

1. **메신저 선택** — Discord / Telegram / 둘 다
   - Discord: 봇 토큰 입력 → API 검증
   - Telegram: 봇 토큰 입력 → getMe 검증 → Chat ID 자동 감지
2. **LLM 프로바이더** — OpenAI / vLLM / Gemini CLI 선택
3. **LLM 설정** — API 키, 엔드포인트, 모델명
4. **임베딩 설정** — OpenAI API 또는 ONNX 내장 (기본값)
5. **에이전트 설정** — 채널명, 데이터 디렉토리, SearXNG URL

## 설정 검증

```bash
selah doctor
```

토큰, LLM 연결, 포트, 서비스 상태를 자동 검증합니다.

## 실행

```bash
# 직접 실행
selah

# OS 서비스로 등록 (로그인 시 자동 시작)
selah enable
```

## Discord 봇 만들기

1. [Discord Developer Portal](https://discord.com/developers/applications)에서 애플리케이션 생성
2. Bot 설정에서 봇 생성
3. Privileged Gateway Intents에서 **Message Content Intent** 활성화
4. 봇 토큰 복사 → `selah setup`에서 입력
5. 서버에 봇 초대 (권한: 메시지 보내기, 메시지 기록 읽기, 반응 추가)

## Telegram 봇 만들기

1. [@BotFather](https://t.me/BotFather)에게 `/newbot` 명령
2. 봇 이름과 사용자명 설정
3. 발급된 토큰 복사 → `selah setup`에서 입력
4. 봇에게 아무 메시지를 보내면 Chat ID가 자동 감지됩니다

## SearXNG (웹 검색)

SearXNG는 설치 시 자동으로 함께 설치됩니다. 설치에 실패한 경우 수동으로 재설치할 수 있습니다:

```bash
curl -fsSL https://raw.githubusercontent.com/taromati/Selah/main/install-searxng.sh | bash
```

SearXNG가 설치되지 않으면 Agent의 웹 검색 기능이 비활성화됩니다.
