<template>
  <div class="embedding-section">
    <el-form label-position="top">
      <el-form-item label="임베딩 방식">
        <el-radio-group v-model="provider" @change="onProviderChange">
          <el-radio value="">내장 ONNX (권장)</el-radio>
          <el-radio value="http">OpenAI API (HTTP)</el-radio>
        </el-radio-group>
      </el-form-item>
    </el-form>

    <!-- ONNX -->
    <el-card v-if="provider === ''" class="provider-card">
      <div class="onnx-status">
        <div v-if="onnxLoading" v-loading="true" style="min-height: 60px" />
        <div v-else-if="onnxStatus">
          <el-icon v-if="onnxStatus.downloaded" color="var(--el-color-success)" :size="18"><SuccessFilled /></el-icon>
          <el-icon v-else color="var(--el-text-color-disabled)" :size="18"><InfoFilled /></el-icon>
          <span v-if="onnxStatus.downloaded">
            모델 다운로드 완료 ({{ onnxStatus.modelSize }})
          </span>
          <span v-else>
            서버 시작 시 자동으로 모델이 다운로드됩니다
          </span>
        </div>
        <p class="onnx-hint">별도 설정이 필요하지 않습니다.</p>
      </div>
    </el-card>

    <!-- HTTP (OpenAI API) -->
    <el-card v-if="provider === 'http'" class="provider-card">
      <el-form label-position="top">
        <el-form-item label="API Key">
          <el-input
            v-model="http.apiKey"
            type="password"
            show-password
            :placeholder="apiKeySet ? '(변경하지 않으면 기존 키 유지)' : 'API Key를 입력하세요'"
            @input="emitChange"
          />
        </el-form-item>

        <el-form-item label="Base URL">
          <el-input
            v-model="http.baseUrl"
            placeholder="https://api.openai.com/v1"
            @input="emitChange"
          />
        </el-form-item>

        <el-form-item label="모델">
          <el-input
            v-model="http.model"
            placeholder="text-embedding-3-small"
            @input="emitChange"
          />
        </el-form-item>

        <el-form-item label="차원">
          <el-input-number
            v-model="http.dimensions"
            :min="0"
            :step="256"
            placeholder="1536"
            @change="emitChange"
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
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import { SuccessFilled, CircleCloseFilled, InfoFilled } from '@element-plus/icons-vue'
import api from '../../api/client'
import type { SetupConfigData } from './types'

const props = defineProps<{
  initialData: SetupConfigData | null
}>()

const emit = defineEmits<{
  change: []
}>()

const provider = ref('')
const apiKeySet = ref(false)

const http = ref({
  apiKey: null as string | null,
  baseUrl: 'https://api.openai.com/v1',
  model: 'text-embedding-3-small',
  dimensions: 1536
})

const onnxLoading = ref(false)
const onnxStatus = ref<{ downloaded: boolean; modelSize: string; modelPath: string } | null>(null)

const testing = ref(false)
const testResult = ref<{ success: boolean; responseTimeMs: number } | null>(null)

watch(() => props.initialData, (data) => {
  if (!data?.embedding) return
  provider.value = data.embedding.provider || ''
  apiKeySet.value = data.embedding.apiKeySet

  if (data.embedding.provider === 'http') {
    http.value = {
      apiKey: null,
      baseUrl: data.embedding.baseUrl || 'https://api.openai.com/v1',
      model: data.embedding.model || 'text-embedding-3-small',
      dimensions: data.embedding.dimensions || 1536
    }
  }
}, { immediate: true })

function onProviderChange() {
  testResult.value = null
  emit('change')
  if (provider.value === '') {
    loadOnnxStatus()
  }
}

function emitChange() {
  emit('change')
}

async function loadOnnxStatus() {
  onnxLoading.value = true
  try {
    const { data } = await api.get('/api/setup/embedding/onnx-status')
    onnxStatus.value = data.data
  } catch {
    // 실패 시 무시
  } finally {
    onnxLoading.value = false
  }
}

async function testConnection() {
  testing.value = true
  testResult.value = null
  try {
    const { data } = await api.post('/api/setup/embedding/test', {
      apiKey: http.value.apiKey,
      baseUrl: http.value.baseUrl,
      model: http.value.model
    })
    testResult.value = data.data
  } catch {
    testResult.value = { success: false, responseTimeMs: 0 }
  } finally {
    testing.value = false
  }
}

function getData(): Record<string, unknown> {
  if (provider.value === 'http') {
    return {
      provider: 'http',
      apiKey: http.value.apiKey,
      baseUrl: http.value.baseUrl,
      model: http.value.model,
      dimensions: http.value.dimensions
    }
  }
  return {
    provider: '',
    apiKey: null,
    baseUrl: '',
    model: '',
    dimensions: 0
  }
}

function hasChanges(): boolean {
  return true
}

onMounted(() => {
  if (provider.value === '') {
    loadOnnxStatus()
  }
})

defineExpose({ getData, hasChanges })
</script>

<style scoped>
.provider-card {
  margin-top: 16px;
}

.onnx-status {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.onnx-status > div {
  display: flex;
  align-items: center;
  gap: 8px;
}

.onnx-hint {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  margin: 4px 0 0;
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
