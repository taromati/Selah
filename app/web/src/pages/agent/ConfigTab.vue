<template>
  <div class="config-container" v-loading="configLoading">
    <div class="config-grid">
      <!-- 왼쪽 열 -->
      <el-form label-width="160px" label-position="left">
        <el-collapse v-model="configSections">
          <el-collapse-item title="LLM" name="llm">
            <el-form-item>
              <template #label><span class="cfg-label">Provider <el-tooltip content="LLM 프로바이더 (openai, vllm 등)"><el-icon><QuestionFilled /></el-icon></el-tooltip></span></template>
              <el-select v-model="cfg.llmProvider" style="width: 100%">
                <el-option
                  v-for="p in cfg.availableProviders"
                  :key="p"
                  :label="p"
                  :value="p"
                />
              </el-select>
            </el-form-item>
            <el-form-item>
              <template #label><span class="cfg-label">Context Msgs <el-tooltip content="대화에 포함할 최대 메시지 수"><el-icon><QuestionFilled /></el-icon></el-tooltip></span></template>
              <el-input-number v-model="cfg.maxContextMessages" :min="1" />
            </el-form-item>
            <el-form-item>
              <template #label><span class="cfg-label">Max Tokens <el-tooltip content="LLM 응답 최대 토큰 수"><el-icon><QuestionFilled /></el-icon></el-tooltip></span></template>
              <el-input-number v-model="cfg.maxTokens" :min="1" :step="1000" />
            </el-form-item>
            <el-form-item>
              <template #label><span class="cfg-label">Temperature <el-tooltip content="응답 랜덤성 (0=결정적, 2=창의적)"><el-icon><QuestionFilled /></el-icon></el-tooltip></span></template>
              <el-input-number v-model="cfg.temperature" :min="0" :max="2" :step="0.1" :precision="1" />
            </el-form-item>
          </el-collapse-item>

          <el-collapse-item title="Session" name="session">
            <el-form-item>
              <template #label><span class="cfg-label">contextWindow <el-tooltip :content="`프로바이더 컨텍스트 윈도우 오버라이드 (비우면 프로바이더 기본값: ${providerDefaults.contextWindow})`"><el-icon><QuestionFilled /></el-icon></el-tooltip></span></template>
              <el-input :model-value="String(cfg.session.contextWindow ?? '')" placeholder="auto" @input="(v: string) => cfg.session.contextWindow = v ? Number(v) : null" />
            </el-form-item>
            <el-form-item>
              <template #label><span class="cfg-label">recentKeep <el-tooltip :content="`Compaction 시 원본 유지할 최근 메시지 수 (비우면 프로바이더 기본값: ${providerDefaults.recentKeep})`"><el-icon><QuestionFilled /></el-icon></el-tooltip></span></template>
              <el-input :model-value="String(cfg.session.recentKeep ?? '')" placeholder="auto" @input="(v: string) => cfg.session.recentKeep = v ? Number(v) : null" />
            </el-form-item>
            <el-form-item>
              <template #label><span class="cfg-label">charsPerToken <el-tooltip :content="`토큰당 평균 글자 수 (비우면 프로바이더 기본값: ${providerDefaults.charsPerToken})`"><el-icon><QuestionFilled /></el-icon></el-tooltip></span></template>
              <el-input :model-value="String(cfg.session.charsPerToken ?? '')" placeholder="auto" @input="(v: string) => cfg.session.charsPerToken = v ? Number(v) : null" />
            </el-form-item>
            <el-form-item>
              <template #label><span class="cfg-label">compactionRatio <el-tooltip content="컨텍스트 사용률이 이 비율 초과 시 Compaction 실행"><el-icon><QuestionFilled /></el-icon></el-tooltip></span></template>
              <el-input-number :model-value="cfg.session.compactionRatio" :min="0" :max="1" :step="0.05" :precision="2" @change="(v: number) => cfg.session.compactionRatio = v" />
            </el-form-item>
            <el-form-item>
              <template #label><span class="cfg-label">taskIdleTimeout <el-tooltip content="Task 유휴 타임아웃 (분). 이 시간 동안 입력 없으면 Task 종료"><el-icon><QuestionFilled /></el-icon></el-tooltip></span></template>
              <el-input-number :model-value="cfg.session.taskIdleTimeoutMinutes" @change="(v: number) => cfg.session.taskIdleTimeoutMinutes = v" :min="1" />
            </el-form-item>
            <el-form-item>
              <template #label><span class="cfg-label">sessionIdleTimeout <el-tooltip content="세션 유휴 타임아웃 (분). 0이면 비활성화"><el-icon><QuestionFilled /></el-icon></el-tooltip></span></template>
              <el-input-number :model-value="cfg.session.sessionIdleTimeoutMinutes" @change="(v: number) => cfg.session.sessionIdleTimeoutMinutes = v" :min="0" />
            </el-form-item>
            <el-form-item>
              <template #label><span class="cfg-label">maxInactiveSessions <el-tooltip content="채널당 최대 비활성 세션 수. 초과 시 가장 오래된 것부터 삭제"><el-icon><QuestionFilled /></el-icon></el-tooltip></span></template>
              <el-input-number :model-value="cfg.session.maxInactiveSessions" @change="(v: number) => cfg.session.maxInactiveSessions = v" :min="1" />
            </el-form-item>
          </el-collapse-item>

          <el-collapse-item title="Exec" name="exec">
            <template v-for="(val, key) in cfg.exec" :key="key">
              <el-form-item>
                <template #label><span class="cfg-label">{{ displayLabel(key) }} <el-tooltip :content="execTooltips[String(key)] || String(key)"><el-icon><QuestionFilled /></el-icon></el-tooltip></span></template>
                <el-switch v-if="typeof val === 'boolean'" :model-value="val" @change="(v: boolean) => cfg.exec[key] = v" />
                <el-input-number v-else-if="typeof val === 'number'" :model-value="val" @change="(v: number) => cfg.exec[key] = v" />
                <el-input v-else :model-value="String(val ?? '')" @input="(v: string) => cfg.exec[key] = v" />
              </el-form-item>
            </template>
          </el-collapse-item>

          <el-collapse-item title="File" name="file">
            <template v-for="(val, key) in cfg.file" :key="key">
              <el-form-item>
                <template #label><span class="cfg-label">{{ displayLabel(key) }} <el-tooltip :content="fileTooltips[String(key)] || String(key)"><el-icon><QuestionFilled /></el-icon></el-tooltip></span></template>
                <el-input-number v-if="typeof val === 'number'" :model-value="val" @change="(v: number) => cfg.file[key] = v" />
                <el-input v-else :model-value="String(val ?? '')" @input="(v: string) => cfg.file[key] = v" />
              </el-form-item>
            </template>
          </el-collapse-item>

          <el-collapse-item title="Browser" name="browser">
            <template v-for="(val, key) in cfg.browser" :key="key">
              <el-form-item>
                <template #label><span class="cfg-label">{{ displayLabel(key) }} <el-tooltip :content="browserTooltips[String(key)] || String(key)"><el-icon><QuestionFilled /></el-icon></el-tooltip></span></template>
                <el-switch v-if="typeof val === 'boolean'" :model-value="val" @change="(v: boolean) => cfg.browser[key] = v" />
                <el-input-number v-else-if="typeof val === 'number'" :model-value="val" @change="(v: number) => cfg.browser[key] = v" />
                <el-input v-else :model-value="String(val ?? '')" @input="(v: string) => cfg.browser[key] = v" />
              </el-form-item>
            </template>
          </el-collapse-item>
        </el-collapse>
      </el-form>

      <!-- 오른쪽 열 -->
      <el-form label-width="160px" label-position="left">
        <el-collapse v-model="configSections">
          <el-collapse-item title="Subagent" name="subagent">
            <template v-for="(val, key) in cfg.subagent" :key="key">
              <el-form-item>
                <template #label><span class="cfg-label">{{ displayLabel(key) }} <el-tooltip :content="subagentTooltips[String(key)] || String(key)"><el-icon><QuestionFilled /></el-icon></el-tooltip></span></template>
                <el-input-number v-if="typeof val === 'number'" :model-value="val" @change="(v: number) => cfg.subagent[key] = v" />
                <el-input v-else :model-value="String(val ?? '')" @input="(v: string) => cfg.subagent[key] = v" />
              </el-form-item>
            </template>
          </el-collapse-item>

          <el-collapse-item title="Cron" name="cron">
            <template v-for="(val, key) in cfg.cron" :key="key">
              <el-form-item>
                <template #label><span class="cfg-label">{{ displayLabel(key) }} <el-tooltip :content="cronTooltips[String(key)] || String(key)"><el-icon><QuestionFilled /></el-icon></el-tooltip></span></template>
                <el-input-number v-if="typeof val === 'number'" :model-value="val" @change="(v: number) => cfg.cron[key] = v" />
                <el-input v-else :model-value="String(val ?? '')" @input="(v: string) => cfg.cron[key] = v" />
              </el-form-item>
            </template>
          </el-collapse-item>

          <el-collapse-item title="Routine" name="routine">
            <template v-for="(val, key) in cfg.routine" :key="key">
              <el-form-item>
                <template #label><span class="cfg-label">{{ displayLabel(key) }} <el-tooltip :content="routineTooltips[String(key)] || String(key)"><el-icon><QuestionFilled /></el-icon></el-tooltip></span></template>
                <el-switch v-if="typeof val === 'boolean'" :model-value="val" @change="(v: boolean) => cfg.routine[key] = v" />
                <el-input-number v-else-if="typeof val === 'number'" :model-value="val" @change="(v: number) => cfg.routine[key] = v" />
                <el-input v-else :model-value="String(val ?? '')" @input="(v: string) => cfg.routine[key] = v" />
              </el-form-item>
            </template>
          </el-collapse-item>

          <el-collapse-item title="Suggest" name="suggest">
            <template v-for="(val, key) in cfg.suggest" :key="key">
              <el-form-item>
                <template #label><span class="cfg-label">{{ displayLabel(key) }} <el-tooltip :content="suggestTooltips[String(key)] || String(key)"><el-icon><QuestionFilled /></el-icon></el-tooltip></span></template>
                <el-switch v-if="typeof val === 'boolean'" :model-value="val" @change="(v: boolean) => cfg.suggest[key] = v" />
                <el-input-number v-else-if="typeof val === 'number'" :model-value="val" @change="(v: number) => cfg.suggest[key] = v" />
                <el-input v-else :model-value="String(val ?? '')" @input="(v: string) => cfg.suggest[key] = v" />
              </el-form-item>
            </template>
          </el-collapse-item>

          <el-collapse-item title="Task" name="task">
            <template v-for="(val, key) in cfg.task" :key="key">
              <el-form-item>
                <template #label><span class="cfg-label">{{ displayLabel(key) }} <el-tooltip :content="taskTooltips[String(key)] || String(key)"><el-icon><QuestionFilled /></el-icon></el-tooltip></span></template>
                <el-input-number v-if="typeof val === 'number'" :model-value="val" @change="(v: number) => cfg.task[key] = v" />
                <el-input v-else :model-value="String(val ?? '')" @input="(v: string) => cfg.task[key] = v" />
              </el-form-item>
            </template>
          </el-collapse-item>
        </el-collapse>
      </el-form>
    </div>

    <div class="config-footer">
      <el-button type="primary" :loading="configSaving" @click="saveConfig">저장</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { QuestionFilled } from '@element-plus/icons-vue'
import api from '@/api/client'
import type { ApiResponse } from './types'

const configLoading = ref(false)
const configSaving = ref(false)
const configSections = ref<string[]>(['llm', 'session'])

const cfg = reactive<Record<string, any>>({
  llmProvider: '',
  availableProviders: [],
  maxContextMessages: 50,
  maxTokens: 4096,
  temperature: 0.7,
  session: {},
  exec: {},
  file: {},
  browser: {},
  subagent: {},
  cron: {},
  routine: {},
  suggest: {},
  task: {},
  providerCapabilities: {}
})

const providerDefaults = computed(() => {
  const caps = cfg.providerCapabilities?.[cfg.llmProvider] || {}
  return {
    contextWindow: caps.contextWindow ?? '?',
    recentKeep: caps.recentKeep ?? '?',
    charsPerToken: caps.charsPerToken ?? '?'
  }
})

// ─── Tooltips ───

const execTooltips: Record<string, string> = {
  security: '보안 모드 (allowlist: 허용 명령만, blocklist: 차단 목록 외 모두 허용)',
  timeoutSeconds: '명령 실행 타임아웃 (초)',
  outputLimitKb: '명령 출력 최대 크기 (KB)',
  allowlist: '허용된 명령어 목록 (쉼표 구분)',
  blockedPatterns: '차단할 위험 패턴 목록'
}

const fileTooltips: Record<string, string> = {
  maxFileSizeKb: '읽기/쓰기 가능한 최대 파일 크기 (KB)',
  maxSearchResults: '파일 검색 최대 결과 수',
  maxSearchDepth: '파일 검색 최대 디렉토리 깊이'
}

const browserTooltips: Record<string, string> = {
  headless: 'Headless 모드 (화면 없이 실행)',
  timeoutSeconds: '페이지 로딩 타임아웃 (초)',
  maxContentLength: '페이지 콘텐츠 최대 글자 수',
  autoCloseMinutes: '미사용 시 자동 닫힘 (분)'
}

const subagentTooltips: Record<string, string> = {
  maxConcurrent: '동시 실행 가능한 최대 서브에이전트 수',
  timeoutSeconds: '서브에이전트 실행 타임아웃 (초)',
  excludedTools: '서브에이전트에서 사용 불가한 도구 목록'
}

const cronTooltips: Record<string, string> = {
  checkIntervalMs: 'Cron 잡 체크 주기 (밀리초)',
  agentTurnTimeoutSeconds: 'Cron 실행 시 에이전트 턴 타임아웃 (초)',
  excludedTools: 'Cron 실행 시 사용 불가한 도구 목록'
}

const routineTooltips: Record<string, string> = {
  enabled: '주기적 자율 점검 활성화',
  intervalMs: '점검 주기 (밀리초, 기본 30분)',
  activeStartHour: '활성 시작 시간 (KST)',
  activeEndHour: '활성 종료 시간 (KST, 자정 넘김 가능)',
  quickCheckMinutes: '작업 없을 때 시간 예산 (분)',
  activeWorkMinutes: '활성 작업 있을 때 시간 예산 (분)',
  excludedTools: 'Routine에서 사용 불가한 도구 목록'
}

const suggestTooltips: Record<string, string> = {
  enabled: '자율 제안 기능 활성화',
  cooldownHours: '제안 간 최소 대기 시간 (시간)',
  dailyLimit: '일일 최대 제안 횟수',
  activeStartHour: '제안 활성 시작 시간 (KST)',
  activeEndHour: '제안 활성 종료 시간 (KST)'
}

const taskTooltips: Record<string, string> = {
  maxRetry: 'Task 작업 최대 재시도 횟수',
  approvalTimeoutHours: '사용자 승인 대기 타임아웃 (시간)',
  reminderCount: '리마인더 발송 횟수'
}

// 긴 키 이름 → 짧은 표시 이름
const labelOverrides: Record<string, string> = {
  agentTurnTimeoutSeconds: 'turnTimeout(s)',
  maxInactiveSessions: 'maxInactiveSess',
  sessionIdleTimeout: 'sessIdleTimeout',
  quickCheckMinutes: 'quickCheck(min)',
  activeWorkMinutes: 'activeWork(min)',
  approvalTimeoutHours: 'approvalTimeout(h)'
}

function displayLabel(key: string | number): string {
  const k = String(key)
  return labelOverrides[k] ?? k
}

// ─── Load / Save ───

async function loadConfig(): Promise<void> {
  configLoading.value = true
  try {
    const { data } = await api.get<ApiResponse<Record<string, any>>>('/agent/api/config')
    const raw = data.code === 'OK' && data.data ? data.data : data as any
    if (raw) {
      cfg.llmProvider = raw.llmProvider ?? ''
      cfg.availableProviders = raw.availableProviders ?? []
      cfg.maxContextMessages = raw.maxContextMessages ?? 50
      cfg.maxTokens = raw.maxTokens ?? 4096
      cfg.temperature = raw.temperature ?? 0.7
      cfg.session = raw.session ?? {}
      cfg.exec = raw.exec ?? {}
      cfg.file = raw.file ?? {}
      cfg.browser = raw.browser ?? {}
      cfg.subagent = raw.subagent ?? {}
      cfg.cron = raw.cron ?? {}
      cfg.routine = raw.routine ?? {}
      cfg.suggest = raw.suggest ?? {}
      cfg.task = raw.task ?? {}
      cfg.providerCapabilities = raw.providerCapabilities ?? {}
    }
  } catch {
    ElMessage.error('설정 로드 실패')
  } finally {
    configLoading.value = false
  }
}

async function saveConfig(): Promise<void> {
  configSaving.value = true
  try {
    const { data } = await api.post<ApiResponse>('/agent/api/config', {
      llmProvider: cfg.llmProvider,
      maxContextMessages: cfg.maxContextMessages,
      maxTokens: cfg.maxTokens,
      temperature: cfg.temperature,
      session: cfg.session,
      exec: cfg.exec,
      file: cfg.file,
      browser: cfg.browser,
      subagent: cfg.subagent,
      cron: cfg.cron,
      routine: cfg.routine,
      suggest: cfg.suggest,
      task: cfg.task
    })
    if (data.code === 'OK') {
      ElMessage.success('설정이 저장되었습니다.')
    } else {
      ElMessage.error(data.message || '설정 저장 실패')
    }
  } catch {
    ElMessage.error('설정 저장에 실패했습니다.')
  } finally {
    configSaving.value = false
  }
}

defineExpose({ loadConfig })
</script>

<style scoped>
.config-container {
  padding: 0;
}

.config-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 24px;
  align-items: start;
}

.config-footer {
  margin-top: 24px;
}

.config-container :deep(.el-collapse-item__header) {
  font-size: 15px;
  font-weight: 600;
}

.config-container :deep(.el-collapse-item__content) {
  padding-top: 12px;
}

.config-container :deep(.el-form-item__label) {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.cfg-label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.cfg-label .el-icon {
  font-size: 14px;
  color: var(--el-text-color-placeholder);
  cursor: help;
  flex-shrink: 0;
}
</style>
