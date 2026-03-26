<template>
  <div class="messenger-section">
    <!-- Discord -->
    <el-card class="section-card">
      <template #header>
        <div class="card-header">
          <span>Discord</span>
          <el-switch
            v-model="discord.enabled"
            :before-change="() => beforeToggle('discord')"
            @change="emitChange"
          />
        </div>
      </template>

      <el-form v-if="discord.enabled" label-position="top">
        <el-form-item label="봇 토큰">
          <div class="token-row">
            <el-input
              v-model="discord.token"
              type="password"
              show-password
              :placeholder="discord.tokenSet ? '(변경하지 않으면 기존 토큰 유지)' : '봇 토큰을 입력하세요'"
              @input="emitChange"
            />
            <el-button
              :loading="discordValidating"
              :disabled="!discord.token"
              @click="validateDiscord"
            >
              검증
            </el-button>
          </div>
          <div v-if="discordValidation" class="validation-result">
            <el-icon v-if="discordValidation.valid" color="var(--el-color-success)"><SuccessFilled /></el-icon>
            <el-icon v-else color="var(--el-color-danger)"><CircleCloseFilled /></el-icon>
            <span v-if="discordValidation.valid">{{ discordValidation.botName }}</span>
            <span v-else class="error-text">유효하지 않은 토큰입니다</span>
          </div>
        </el-form-item>

        <el-form-item label="서버 이름">
          <el-input
            v-model="discord.serverName"
            placeholder="서버 이름"
            @input="emitChange"
          />
        </el-form-item>
      </el-form>

      <div v-else class="disabled-notice">
        비활성화됨
      </div>
    </el-card>

    <!-- Telegram -->
    <el-card class="section-card">
      <template #header>
        <div class="card-header">
          <span>Telegram</span>
          <el-switch
            v-model="telegram.enabled"
            :before-change="() => beforeToggle('telegram')"
            @change="emitChange"
          />
        </div>
      </template>

      <el-form v-if="telegram.enabled" label-position="top">
        <el-form-item label="봇 토큰">
          <div class="token-row">
            <el-input
              v-model="telegram.token"
              type="password"
              show-password
              :placeholder="telegram.tokenSet ? '(변경하지 않으면 기존 토큰 유지)' : '봇 토큰을 입력하세요'"
              @input="emitChange"
            />
            <el-button
              :loading="telegramValidating"
              :disabled="!telegram.token"
              @click="validateTelegram"
            >
              검증
            </el-button>
          </div>
          <div v-if="telegramValidation" class="validation-result">
            <el-icon v-if="telegramValidation.valid" color="var(--el-color-success)"><SuccessFilled /></el-icon>
            <el-icon v-else color="var(--el-color-danger)"><CircleCloseFilled /></el-icon>
            <span v-if="telegramValidation.valid">@{{ telegramValidation.username }}</span>
            <span v-else class="error-text">유효하지 않은 토큰입니다</span>
          </div>
        </el-form-item>

        <el-form-item label="봇 사용자명">
          <el-input
            v-model="telegram.botUsername"
            placeholder="봇 사용자명 (@없이)"
            @input="emitChange"
          />
        </el-form-item>

        <el-divider content-position="left">Chat ID 매핑</el-divider>

        <el-form-item label="Agent 채널 Chat ID">
          <div class="token-row">
            <el-input
              v-model="telegram.channelMappings.agent"
              placeholder="예: -1001234567890"
              @input="emitChange"
            />
            <el-button
              :loading="detecting"
              :disabled="!telegram.token && !telegram.tokenSet"
              @click="startDetection('agent')"
            >
              자동 감지
            </el-button>
          </div>
        </el-form-item>

        <el-form-item label="System 채널 Chat ID">
          <div class="token-row">
            <el-input
              v-model="telegram.channelMappings.system"
              placeholder="예: -1001234567890"
              @input="emitChange"
            />
            <el-button
              size="small"
              link
              @click="copyChatId"
            >
              Agent와 동일하게
            </el-button>
          </div>
        </el-form-item>
      </el-form>

      <div v-else class="disabled-notice">
        비활성화됨
      </div>
    </el-card>

    <!-- Chat ID 감지 다이얼로그 -->
    <el-dialog v-model="detectDialogVisible" title="Chat ID 자동 감지" width="420px" :close-on-click-modal="false">
      <div v-if="detectState === 'waiting'" class="detect-status">
        <el-icon class="is-loading" :size="24"><Loading /></el-icon>
        <p>봇에게 메시지를 보내주세요...</p>
        <p class="detect-hint">감지 대상 채팅방에서 아무 메시지나 보내면 자동으로 감지됩니다. (최대 120초)</p>
      </div>
      <div v-else-if="detectState === 'detected'" class="detect-status">
        <el-icon :size="24" color="var(--el-color-success)"><SuccessFilled /></el-icon>
        <p>감지 완료</p>
        <p class="detect-result">Chat ID: <code>{{ detectedChatId }}</code></p>
        <p v-if="detectedChatTitle" class="detect-result">채팅: {{ detectedChatTitle }}</p>
      </div>
      <div v-else-if="detectState === 'timeout'" class="detect-status">
        <el-icon :size="24" color="var(--el-color-danger)"><CircleCloseFilled /></el-icon>
        <p>감지 실패 (타임아웃)</p>
        <p class="detect-hint">직접 Chat ID를 입력해 주세요.</p>
      </div>
      <div v-else-if="detectState === 'error'" class="detect-status">
        <el-icon :size="24" color="var(--el-color-danger)"><CircleCloseFilled /></el-icon>
        <p>{{ detectError }}</p>
      </div>
      <template #footer>
        <el-button @click="detectDialogVisible = false">닫기</el-button>
        <el-button
          v-if="detectState === 'detected'"
          type="primary"
          @click="applyDetectedChatId"
        >
          이 Chat ID 사용
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onUnmounted } from 'vue'
import { ElMessageBox } from 'element-plus'
import { SuccessFilled, CircleCloseFilled, Loading } from '@element-plus/icons-vue'
import api from '../../api/client'
import type { SetupConfigData } from './types'

const props = defineProps<{
  initialData: SetupConfigData | null
}>()

const emit = defineEmits<{
  change: []
}>()

const discord = ref({
  enabled: false,
  token: null as string | null,
  tokenSet: false,
  serverName: ''
})

const telegram = ref({
  enabled: false,
  token: null as string | null,
  tokenSet: false,
  botUsername: '',
  channelMappings: { agent: '', system: '' } as Record<string, string>
})

// Validation state
const discordValidating = ref(false)
const discordValidation = ref<{ valid: boolean; botName?: string } | null>(null)

const telegramValidating = ref(false)
const telegramValidation = ref<{ valid: boolean; username?: string; firstName?: string } | null>(null)

// Chat detection state
const detecting = ref(false)
const detectDialogVisible = ref(false)
const detectState = ref<'waiting' | 'detected' | 'timeout' | 'error'>('waiting')
const detectError = ref('')
const detectedChatId = ref('')
const detectedChatTitle = ref('')
const detectTarget = ref<'agent' | 'system'>('agent')
let detectPollTimer: ReturnType<typeof setInterval> | null = null

watch(() => props.initialData, (data) => {
  if (!data) return
  discord.value = {
    enabled: data.discord.enabled,
    token: null,
    tokenSet: data.discord.tokenSet,
    serverName: data.discord.serverName || ''
  }
  telegram.value = {
    enabled: data.telegram.enabled,
    token: null,
    tokenSet: data.telegram.tokenSet,
    botUsername: data.telegram.botUsername || '',
    channelMappings: { agent: '', system: '', ...data.telegram.channelMappings }
  }
}, { immediate: true })

function emitChange() {
  emit('change')
}

async function beforeToggle(platform: 'discord' | 'telegram'): Promise<boolean> {
  const other = platform === 'discord' ? telegram : discord
  const current = platform === 'discord' ? discord : telegram

  // 비활성화하려는 경우, 다른 메신저도 비활성화면 차단
  if (current.value.enabled && !other.value.enabled) {
    await ElMessageBox.alert('최소 1개의 메신저가 필요합니다.', '경고', { type: 'warning' })
    return false
  }
  return true
}

async function validateDiscord() {
  if (!discord.value.token) return
  discordValidating.value = true
  discordValidation.value = null
  try {
    const { data } = await api.post('/api/setup/messenger/discord/validate', { token: discord.value.token })
    discordValidation.value = data.data
  } catch {
    discordValidation.value = { valid: false }
  } finally {
    discordValidating.value = false
  }
}

async function validateTelegram() {
  if (!telegram.value.token) return
  telegramValidating.value = true
  telegramValidation.value = null
  try {
    const { data } = await api.post('/api/setup/messenger/telegram/validate', { token: telegram.value.token })
    telegramValidation.value = data.data
    if (data.data?.valid && data.data?.username) {
      telegram.value.botUsername = data.data.username
      emitChange()
    }
  } catch {
    telegramValidation.value = { valid: false }
  } finally {
    telegramValidating.value = false
  }
}

async function startDetection(target: 'agent' | 'system') {
  detectTarget.value = target
  detectState.value = 'waiting'
  detectError.value = ''
  detectedChatId.value = ''
  detectedChatTitle.value = ''
  detectDialogVisible.value = true
  detecting.value = true

  const token = telegram.value.token
  if (!token && !telegram.value.tokenSet) {
    detectState.value = 'error'
    detectError.value = '토큰이 설정되지 않았습니다'
    detecting.value = false
    return
  }

  try {
    await api.post('/api/setup/messenger/telegram/detect-chat', { token: token || null })
    startDetectionPolling()
  } catch (err: unknown) {
    detectState.value = 'error'
    detectError.value = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || '감지 시작에 실패했습니다'
    detecting.value = false
  }
}

function startDetectionPolling() {
  if (detectPollTimer) clearInterval(detectPollTimer)

  const startTime = Date.now()
  detectPollTimer = setInterval(async () => {
    if (Date.now() - startTime > 125000) {
      stopDetectionPolling()
      detectState.value = 'timeout'
      detecting.value = false
      return
    }

    try {
      const { data } = await api.get('/api/setup/messenger/telegram/detect-chat/status')
      const status = data.data
      if (status.state === 'detected') {
        stopDetectionPolling()
        detectState.value = 'detected'
        detectedChatId.value = status.chatId
        detectedChatTitle.value = status.chatTitle || ''
        detecting.value = false
      } else if (status.state === 'timeout') {
        stopDetectionPolling()
        detectState.value = 'timeout'
        detecting.value = false
      }
    } catch {
      // 폴링 에러 무시, 계속 시도
    }
  }, 2000)
}

function stopDetectionPolling() {
  if (detectPollTimer) {
    clearInterval(detectPollTimer)
    detectPollTimer = null
  }
}

function applyDetectedChatId() {
  telegram.value.channelMappings[detectTarget.value] = detectedChatId.value
  detectDialogVisible.value = false
  emitChange()
}

function copyChatId() {
  telegram.value.channelMappings.system = telegram.value.channelMappings.agent
  emitChange()
}

function getData(): Record<string, unknown> {
  return {
    discord: {
      enabled: discord.value.enabled,
      token: discord.value.token,
      tokenSet: discord.value.tokenSet,
      serverName: discord.value.serverName
    },
    telegram: {
      enabled: telegram.value.enabled,
      token: telegram.value.token,
      tokenSet: telegram.value.tokenSet,
      botUsername: telegram.value.botUsername,
      channelMappings: telegram.value.channelMappings
    }
  }
}

function hasChanges(): boolean {
  // 간단한 비교 -- 폼 변경 감지는 parent의 hasChanges에 위임
  return true
}

onUnmounted(() => {
  stopDetectionPolling()
})

defineExpose({ getData, hasChanges })
</script>

<style scoped>
.section-card {
  margin-bottom: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.token-row {
  display: flex;
  gap: 8px;
  width: 100%;
}

.token-row .el-input {
  flex: 1;
}

.validation-result {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  font-size: 13px;
}

.error-text {
  color: var(--el-color-danger);
}

.disabled-notice {
  color: var(--el-text-color-disabled);
  font-size: 13px;
}

.detect-status {
  text-align: center;
  padding: 20px 0;
}

.detect-status p {
  margin: 8px 0;
}

.detect-hint {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.detect-result {
  font-size: 14px;
}

.detect-result code {
  background: var(--el-fill-color-light);
  padding: 2px 6px;
  border-radius: 4px;
}
</style>
