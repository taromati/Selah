# 설정 레퍼런스

모든 설정은 프로젝트 루트의 `config.yml`에서 관리합니다. `selah setup`으로 자동 생성할 수 있습니다.

## Discord

| 키 | 설명 | 기본값 |
|----|------|--------|
| `discord.enabled` | Discord 활성화 | `false` |
| `discord.token` | Discord 봇 토큰 | (필수) |
| `discord.server-name` | Discord 서버 이름 | (필수) |

## Telegram

| 키 | 설명 | 기본값 |
|----|------|--------|
| `telegram.enabled` | Telegram 활성화 | `false` |
| `telegram.token` | Telegram 봇 토큰 | (필수) |
| `telegram.chat-id` | 허용할 Chat ID | (필수) |

## LLM 프로바이더

### OpenAI

| 키 | 설명 | 기본값 |
|----|------|--------|
| `llm.providers.openai.type` | 프로바이더 타입 | `openai` |
| `llm.providers.openai.enabled` | 활성화 | `false` |
| `llm.providers.openai.api-key` | API 키 | - |
| `llm.providers.openai.model` | 모델명 | - |

### vLLM

| 키 | 설명 | 기본값 |
|----|------|--------|
| `llm.providers.vllm.type` | 프로바이더 타입 | `vllm` |
| `llm.providers.vllm.enabled` | 활성화 | `false` |
| `llm.providers.vllm.base-url` | API URL | - |
| `llm.providers.vllm.model` | 모델명 | - |

### Gemini CLI

| 키 | 설명 | 기본값 |
|----|------|--------|
| `llm.providers.gemini.type` | 프로바이더 타입 | `gemini` |
| `llm.providers.gemini.enabled` | 활성화 | `false` |
| `llm.providers.gemini.cli-path` | CLI 경로 | `gemini` |
| `llm.providers.gemini.model` | 모델명 | - |

### Codex (ChatGPT)

| 키 | 설명 | 기본값 |
|----|------|--------|
| `llm.providers.codex.type` | 프로바이더 타입 | `codex` |
| `llm.providers.codex.enabled` | 활성화 | `false` |
| `llm.providers.codex.model` | 모델명 | `gpt-5.4` |
| `llm.providers.codex.refresh-token` | OAuth refresh token | - |
| `llm.providers.codex.account-id` | ChatGPT 계정 ID | - |

### 공통 프로바이더 옵션

| 키 | 설명 | 기본값 |
|----|------|--------|
| `connect-timeout-seconds` | 연결 타임아웃 (초) | `10` |
| `timeout-seconds` | 읽기 타임아웃 (초) | `120` |
| `context-window` | 컨텍스트 윈도우 (토큰) | - |
| `max-tokens` | 최대 출력 토큰 | - |
| `rate-limit-per-minute` | 분당 호출 제한 | - |

## 임베딩

| 키 | 설명 | 기본값 |
|----|------|--------|
| `llm.embedding.provider` | 임베딩 프로바이더 (`openai` / `onnx`) | `onnx` |
| `llm.embedding.base-url` | OpenAI 임베딩 API URL | - |
| `llm.embedding.model` | 임베딩 모델명 | - |
| `llm.embedding.api-key` | API 키 (OpenAI 프로바이더) | - |

> ONNX 내장 모드는 외부 서버 없이 `multilingual-e5-small` 모델을 로컬에서 실행합니다.

## 에이전트 플러그인

| 키 | 설명 | 기본값 |
|----|------|--------|
| `plugins.agent.enabled` | 에이전트 활성화 | `false` |
| `plugins.agent.channel-name` | 메신저 채널명 | `agent` |
| `plugins.agent.data-dir` | 에이전트 데이터 디렉토리 | `./agent-data/` |
| `plugins.agent.llm-provider` | 사용할 LLM 프로바이더 이름 | - |
| `plugins.agent.temperature` | Sampling temperature | `0.7` |

### 웹 검색

| 키 | 설명 | 기본값 |
|----|------|--------|
| `plugins.agent.web-search.provider` | 검색 프로바이더 (`brave` / `searxng`) | `brave` |
| `plugins.agent.web-search.api-key` | Brave Search API 키 | - |
| `plugins.agent.web-search.searxng-url` | SearXNG 인스턴스 URL | - |

### 도구 정책

| 키 | 설명 | 기본값 |
|----|------|--------|
| `plugins.agent.tools.policy-default` | 기본 도구 정책 (`allow` / `ask` / `block`) | `ask` |
| `plugins.agent.tools.policy` | 도구별 정책 맵 | `{}` |

## 웹 인증

| 키 | 설명 | 기본값 |
|----|------|--------|
| `web.auth.enabled` | 웹 UI 인증 활성화 | `true` |
