<template>
  <div class="llm-section">
    <el-form label-position="top">
      <el-form-item label="프로바이더">
        <el-radio-group v-model="provider" @change="onProviderChange">
          <el-radio value="openai-codex">OpenAI Codex</el-radio>
          <el-radio value="openai">OpenAI</el-radio>
          <el-radio value="vllm">vLLM</el-radio>
          <el-radio value="gemini-cli">Gemini CLI</el-radio>
        </el-radio-group>
      </el-form-item>
    </el-form>

    <!-- OpenAI -->
    <el-card v-if="provider === 'openai'" class="provider-card">
      <el-form label-position="top">
        <el-form-item label="API Key">
          <el-input
            v-model="openai.apiKey"
            type="password"
            show-password
            :placeholder="apiKeySet ? '(변경하지 않으면 기존 키 유지)' : 'API Key를 입력하세요'"
            @input="emitChange"
          />
        </el-form-item>

        <el-form-item label="모델">
          <el-input
            v-model="openai.model"
            placeholder="gpt-4o"
            @input="emitChange"
          />
        </el-form-item>

        <el-form-item>
          <el-button :loading="testing" @click="testConnection">연결 테스트</el-button>
          <span v-if="testResult" class="test-result">
            <el-icon v-if="testResult.success" color="var(--el-color-success)"><SuccessFilled /></el-icon>
            <el-icon v-else color="var(--el-color-danger)"><CircleCloseFilled /></el-icon>
            <span v-if="testResult.success">성공 ({{ testResult.responseTimeMs }}ms)</span>
            <span v-else class="error-text">연결 실패</span>
          </span>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- vLLM -->
    <el-card v-if="provider === 'vllm'" class="provider-card">
      <el-form label-position="top">
        <el-form-item label="Base URL">
          <el-input
            v-model="vllm.baseUrl"
            placeholder="http://localhost:8000/v1"
            @input="emitChange"
          />
        </el-form-item>

        <el-form-item label="모델">
          <el-input
            v-model="vllm.model"
            placeholder="모델 이름"
            @input="emitChange"
          />
        </el-form-item>

        <el-form-item>
          <el-button :loading="testing" @click="testConnection">연결 테스트</el-button>
          <span v-if="testResult" class="test-result">
            <el-icon v-if="testResult.success" color="var(--el-color-success)"><SuccessFilled /></el-icon>
            <el-icon v-else color="var(--el-color-danger)"><CircleCloseFilled /></el-icon>
            <span v-if="testResult.success">성공 ({{ testResult.responseTimeMs }}ms)</span>
            <span v-else class="error-text">연결 실패</span>
          </span>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- OpenAI Codex -->
    <el-card v-if="provider === 'openai-codex'" class="provider-card">
      <el-form label-position="top">
        <el-alert type="info" :closable="false" style="margin-bottom: 16px">
          ChatGPT 계정 인증이 필요합니다. 터미널에서 <code>codex login</code>을 실행하세요.
        </el-alert>

        <el-form-item label="모델">
          <el-input
            v-model="codex.model"
            placeholder="gpt-5.4"
            @input="emitChange"
          />
        </el-form-item>
      </el-form>
    </el-card>

    <!-- Gemini CLI -->
    <el-card v-if="provider === 'gemini-cli'" class="provider-card">
      <el-form label-position="top">
        <el-form-item label="CLI 경로">
          <el-input
            v-model="gemini.cliPath"
            placeholder="gemini"
            @input="emitChange"
          />
        </el-form-item>

        <el-form-item label="모델">
          <el-input
            v-model="gemini.model"
            placeholder="gemini-2.5-pro"
            @input="emitChange"
          />
        </el-form-item>

        <el-form-item>
          <el-button :loading="testing" @click="testConnection">연결 테스트</el-button>
          <span v-if="testResult" class="test-result">
            <el-icon v-if="testResult.success" color="var(--el-color-success)"><SuccessFilled /></el-icon>
            <el-icon v-else color="var(--el-color-danger)"><CircleCloseFilled /></el-icon>
            <span v-if="testResult.success">CLI 발견됨</span>
            <span v-else class="error-text">CLI를 찾을 수 없습니다</span>
          </span>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { SuccessFilled, CircleCloseFilled } from '@element-plus/icons-vue'
import api from '../../api/client'
import type { SetupConfigData } from './types'

const props = defineProps<{
  initialData: SetupConfigData | null
}>()

const emit = defineEmits<{
  change: []
}>()

const provider = ref('openai')
const apiKeySet = ref(false)

const codex = ref({ model: 'gpt-5.4' })
const openai = ref({ apiKey: null as string | null, model: 'gpt-5.4' })
const vllm = ref({ baseUrl: 'http://localhost:8000/v1', model: '' })
const gemini = ref({ cliPath: 'gemini', model: '' })

const testing = ref(false)
const testResult = ref<{ success: boolean; responseTimeMs: number } | null>(null)

watch(() => props.initialData, (data) => {
  if (!data?.llm) return
  provider.value = data.llm.provider || 'openai'
  apiKeySet.value = data.llm.apiKeySet

  // 현재 프로바이더 설정값 적용
  switch (data.llm.provider) {
    case 'openai-codex':
      codex.value = { model: data.llm.model || 'gpt-5.4' }
      break
    case 'openai':
      openai.value = { apiKey: null, model: data.llm.model || 'gpt-5.4' }
      break
    case 'vllm':
      vllm.value = { baseUrl: data.llm.baseUrl || 'http://localhost:8000/v1', model: data.llm.model || '' }
      break
    case 'gemini-cli':
      gemini.value = { cliPath: data.llm.cliPath || 'gemini', model: data.llm.model || '' }
      break
  }
}, { immediate: true })

function onProviderChange() {
  testResult.value = null
  emit('change')
}

function emitChange() {
  emit('change')
}

async function testConnection() {
  testing.value = true
  testResult.value = null
  try {
    const payload: Record<string, unknown> = { provider: provider.value }

    switch (provider.value) {
      case 'openai-codex':
        payload.model = codex.value.model
        break
      case 'openai':
        payload.apiKey = openai.value.apiKey
        payload.model = openai.value.model
        break
      case 'vllm':
        payload.baseUrl = vllm.value.baseUrl
        payload.model = vllm.value.model
        break
      case 'gemini-cli':
        payload.cliPath = gemini.value.cliPath
        payload.model = gemini.value.model
        break
    }

    const { data } = await api.post('/api/setup/llm/test', payload)
    testResult.value = data.data
  } catch {
    testResult.value = { success: false, responseTimeMs: 0 }
  } finally {
    testing.value = false
  }
}

function getData(): Record<string, unknown> {
  switch (provider.value) {
    case 'openai-codex':
      return {
        provider: 'openai-codex',
        apiKey: null,
        baseUrl: '',
        model: codex.value.model,
        cliPath: null
      }
    case 'openai':
      return {
        provider: 'openai',
        apiKey: openai.value.apiKey,
        baseUrl: '',
        model: openai.value.model,
        cliPath: null
      }
    case 'vllm':
      return {
        provider: 'vllm',
        apiKey: null,
        baseUrl: vllm.value.baseUrl,
        model: vllm.value.model,
        cliPath: null
      }
    case 'gemini-cli':
      return {
        provider: 'gemini-cli',
        apiKey: null,
        baseUrl: '',
        model: gemini.value.model,
        cliPath: gemini.value.cliPath
      }
    default:
      return { provider: provider.value }
  }
}

function hasChanges(): boolean {
  return true
}

defineExpose({ getData, hasChanges })
</script>

<style scoped>
.provider-card {
  margin-top: 16px;
}

.test-result {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-left: 12px;
  font-size: 13px;
}

.error-text {
  color: var(--el-color-danger);
}
</style>
