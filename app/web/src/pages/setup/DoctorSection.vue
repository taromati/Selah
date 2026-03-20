<template>
  <div class="doctor-section">
    <el-button type="primary" :loading="running" @click="runDoctor">
      진단 실행
    </el-button>

    <div v-if="result" class="result-area">
      <el-card class="result-card">
        <div v-for="item in result.items" :key="item.name" class="check-item">
          <el-icon :size="18" :color="statusColor(item.status)">
            <SuccessFilled v-if="item.status === 'PASS'" />
            <CircleCloseFilled v-else-if="item.status === 'FAIL'" />
            <WarningFilled v-else />
          </el-icon>
          <span class="check-message">{{ item.message }}</span>
        </div>

        <el-divider />

        <div class="summary">
          <span class="summary-item pass">통과: {{ result.passed }}</span>
          <span class="summary-item fail">실패: {{ result.failed }}</span>
          <span class="summary-item warn">경고: {{ result.warned }}</span>
        </div>
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { SuccessFilled, CircleCloseFilled, WarningFilled } from '@element-plus/icons-vue'
import api from '../../api/client'

interface CheckItem {
  name: string
  status: 'PASS' | 'FAIL' | 'WARN'
  message: string
}

interface DoctorResult {
  items: CheckItem[]
  passed: number
  failed: number
  warned: number
}

const running = ref(false)
const result = ref<DoctorResult | null>(null)

function statusColor(status: string): string {
  switch (status) {
    case 'PASS': return 'var(--el-color-success)'
    case 'FAIL': return 'var(--el-color-danger)'
    case 'WARN': return 'var(--el-color-warning)'
    default: return ''
  }
}

async function runDoctor() {
  running.value = true
  result.value = null
  try {
    const { data } = await api.post('/api/setup/doctor')
    result.value = data.data
  } catch {
    result.value = null
  } finally {
    running.value = false
  }
}
</script>

<style scoped>
.result-area {
  margin-top: 20px;
}

.result-card {
  max-width: 600px;
}

.check-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
}

.check-message {
  font-size: 14px;
}

.summary {
  display: flex;
  gap: 24px;
  font-size: 14px;
  font-weight: 600;
}

.summary-item.pass {
  color: var(--el-color-success);
}

.summary-item.fail {
  color: var(--el-color-danger);
}

.summary-item.warn {
  color: var(--el-color-warning);
}
</style>
