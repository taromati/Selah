<template>
  <el-dialog
    :model-value="visible"
    title="설정 저장 완료"
    :close-on-click-modal="false"
    width="420px"
    @update:model-value="$emit('update:visible', $event)"
  >
    <p>설정이 저장되었습니다. 변경 사항을 적용하려면 서버를 재시작해야 합니다.</p>

    <el-alert
      v-if="!serviceRegistered"
      type="info"
      :closable="false"
      show-icon
      class="service-alert"
    >
      <template #default>
        서비스가 등록되어 있지 않습니다. 수동으로 재시작해주세요.
        <br>
        <code>selah</code> (또는 서비스 등록: <code>selah enable</code>)
      </template>
    </el-alert>

    <div v-if="restarting" class="restart-status">
      <el-icon class="is-loading" :size="20"><Loading /></el-icon>
      <span>재시작 중... 서버 복구를 대기합니다.</span>
    </div>

    <div v-if="restartError" class="restart-error">
      <el-alert type="error" :closable="false" show-icon>
        {{ restartError }}
      </el-alert>
    </div>

    <template #footer>
      <el-button @click="close">나중에</el-button>
      <el-button
        type="primary"
        :loading="restarting"
        :disabled="!serviceRegistered"
        @click="doRestart"
      >
        지금 재시작
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, onUnmounted } from 'vue'
import { Loading } from '@element-plus/icons-vue'
import api from '../../api/client'

defineProps<{
  visible: boolean
  serviceRegistered: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
}>()

const restarting = ref(false)
const restartError = ref('')
let healthPollTimer: ReturnType<typeof setInterval> | null = null

function close() {
  emit('update:visible', false)
}

async function doRestart() {
  restarting.value = true
  restartError.value = ''

  try {
    await api.post('/api/setup/restart')
  } catch {
    // 서버가 종료되면서 연결이 끊길 수 있음 -- 정상
  }

  // 2초 대기 후 health check 폴링 시작
  setTimeout(() => {
    startHealthPolling()
  }, 2000)
}

function startHealthPolling() {
  if (healthPollTimer) clearInterval(healthPollTimer)

  const startTime = Date.now()
  healthPollTimer = setInterval(async () => {
    if (Date.now() - startTime > 60000) {
      stopHealthPolling()
      restarting.value = false
      restartError.value = '서버가 응답하지 않습니다. 수동 확인이 필요합니다.'
      return
    }

    try {
      await api.get('/api/system/health')
      // 성공 -- 서버 복구됨
      stopHealthPolling()
      location.reload()
    } catch {
      // 아직 서버 미기동 -- 계속 폴링
    }
  }, 2000)
}

function stopHealthPolling() {
  if (healthPollTimer) {
    clearInterval(healthPollTimer)
    healthPollTimer = null
  }
}

onUnmounted(() => {
  stopHealthPolling()
})
</script>

<style scoped>
.service-alert {
  margin-top: 12px;
}

.service-alert code {
  background: var(--el-fill-color-light);
  padding: 1px 4px;
  border-radius: 3px;
  font-size: 13px;
}

.restart-status {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 16px;
  color: var(--el-text-color-regular);
}

.restart-error {
  margin-top: 16px;
}
</style>
