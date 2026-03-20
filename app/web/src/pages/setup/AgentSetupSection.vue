<template>
  <div class="agent-setup-section">
    <!-- Agent 설정 -->
    <el-card class="section-card">
      <template #header>
        <span>Agent</span>
      </template>

      <el-form label-position="top">
        <el-form-item label="채널 이름">
          <el-input
            v-model="agent.channelName"
            placeholder="agent"
            @input="emitChange"
          />
        </el-form-item>

        <el-form-item label="데이터 디렉토리">
          <el-input
            v-model="agent.dataDir"
            placeholder="./agent-data/"
            @input="emitChange"
          />
          <div class="form-hint">
            경로를 변경하면 기존 데이터는 자동으로 이동되지 않습니다.
          </div>
        </el-form-item>

        <el-form-item label="알림 채널">
          <el-input
            v-model="notification.channel"
            placeholder="system"
            @input="emitChange"
          />
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 웹 검색 (SearXNG) -->
    <el-card class="section-card">
      <template #header>
        <div class="card-header">
          <span>웹 검색 (SearXNG)</span>
          <el-switch
            v-model="searxngEnabled"
            @change="onSearxngToggle"
          />
        </div>
      </template>

      <el-form v-if="searxngEnabled" label-position="top">
        <el-form-item label="URL">
          <div class="token-row">
            <el-input
              v-model="searxng.url"
              placeholder="http://localhost:8888"
              @input="emitChange"
            />
            <el-button :loading="testing" @click="testSearxng">연결 테스트</el-button>
          </div>
          <div v-if="testResult" class="test-result-line">
            <el-icon v-if="testResult.success" color="var(--el-color-success)"><SuccessFilled /></el-icon>
            <el-icon v-else color="var(--el-color-danger)"><CircleCloseFilled /></el-icon>
            <span v-if="testResult.success">연결 성공 ({{ testResult.responseTimeMs }}ms)</span>
            <span v-else class="error-text">연결 실패</span>
          </div>
        </el-form-item>
      </el-form>

      <div v-else class="disabled-notice">
        비활성화됨 -- URL을 비우면 웹 검색 기능이 비활성화됩니다.
      </div>
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

const agent = ref({
  channelName: 'agent',
  dataDir: './agent-data/'
})

const notification = ref({
  channel: 'system'
})

const searxng = ref({
  url: 'http://localhost:8888'
})

const searxngEnabled = ref(false)

const testing = ref(false)
const testResult = ref<{ success: boolean; responseTimeMs: number } | null>(null)

watch(() => props.initialData, (data) => {
  if (!data) return
  agent.value = {
    channelName: data.agent?.channelName || 'agent',
    dataDir: data.agent?.dataDir || './agent-data/'
  }
  notification.value = {
    channel: data.notification?.channel || 'system'
  }
  searxng.value = {
    url: data.searxng?.url || ''
  }
  searxngEnabled.value = !!data.searxng?.url
}, { immediate: true })

function emitChange() {
  emit('change')
}

function onSearxngToggle(val: boolean | string | number) {
  if (!val) {
    searxng.value.url = ''
  } else if (!searxng.value.url) {
    searxng.value.url = 'http://localhost:8888'
  }
  testResult.value = null
  emitChange()
}

async function testSearxng() {
  testing.value = true
  testResult.value = null
  try {
    const { data } = await api.post('/api/setup/searxng/test', { url: searxng.value.url })
    testResult.value = data.data
  } catch {
    testResult.value = { success: false, responseTimeMs: 0 }
  } finally {
    testing.value = false
  }
}

function getData(): Record<string, unknown> {
  return {
    agent: { ...agent.value },
    notification: { ...notification.value },
    searxng: { url: searxngEnabled.value ? searxng.value.url : '' }
  }
}

function hasChanges(): boolean {
  return true
}

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

.form-hint {
  color: var(--el-color-warning);
  font-size: 12px;
  margin-top: 4px;
}

.test-result-line {
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
</style>
