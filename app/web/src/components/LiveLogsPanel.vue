<template>
  <div class="live-logs-panel">
    <div class="logs-toolbar">
      <el-switch v-model="liveLogs.autoScroll.value" active-text="Auto-scroll" size="small" />
      <el-button size="small" @click="liveLogs.active.value ? liveLogs.stop() : liveLogs.start()">
        {{ liveLogs.active.value ? '중지' : '시작' }}
      </el-button>
    </div>
    <div class="logs-container" :ref="(el) => liveLogs.setContainer(el as HTMLElement)">
      <div
        v-for="(line, i) in liveLogs.logs.value"
        :key="i"
        class="log-line"
        :class="'log-' + line.level.toLowerCase()"
      >{{ line.text }}</div>
      <div v-if="liveLogs.logs.value.length === 0" class="log-empty">로그 없음</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import { useLiveLogs } from '../composables/useLiveLogs'

const props = defineProps<{
  apiUrl: string
  lines?: number
}>()

const liveLogs = useLiveLogs(props.apiUrl, props.lines ?? 200)

onMounted(() => {
  liveLogs.start()
})
</script>

<style scoped>
.live-logs-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.logs-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}

.logs-container {
  flex: 1;
  background: #0d1117;
  border: 1px solid #30363d;
  border-radius: 6px;
  padding: 12px;
  overflow-y: auto;
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.5;
  min-height: 400px;
  max-height: 600px;
}

.log-line {
  white-space: pre-wrap;
  word-break: break-all;
}

.log-error { color: #f85149; }
.log-warn { color: #d29922; }
.log-info { color: #8b949e; }
.log-debug { color: #6e7681; }
.log-empty { color: #484f58; text-align: center; padding: 20px; }
</style>
