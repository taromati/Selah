<template>
  <div>
    <div class="tab-toolbar">
      <el-button size="small" @click="loadJobs">새로고침</el-button>
    </div>

    <el-table :data="jobs" stripe style="width: 100%" v-loading="jobsLoading">
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="expand-detail">
            <div class="detail-grid">
              <span class="detail-label">ID</span>
              <code class="detail-value selectable">{{ row.id }}</code>
              <span class="detail-label">Timezone</span>
              <span class="detail-value">{{ row.timezone || 'Asia/Seoul' }}</span>
              <span class="detail-label">최근 실행</span>
              <span class="detail-value">{{ row.lastRunAt ? formatDate(row.lastRunAt) : '-' }}</span>
              <span class="detail-label">생성일</span>
              <span class="detail-value">{{ row.createdAt ? formatDate(row.createdAt) : '-' }}</span>
            </div>
            <div v-if="row.payload" class="detail-section">
              <span class="detail-label">Payload</span>
              <pre class="detail-pre">{{ row.payload }}</pre>
            </div>
            <div v-if="row.lastError" class="detail-section">
              <span class="detail-label">에러 상세</span>
              <pre class="detail-pre error-text">{{ row.lastError }}</pre>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="이름" min-width="160">
        <template #default="{ row }">
          <span class="job-name">{{ row.name }}</span>
        </template>
      </el-table-column>
      <el-table-column label="스케줄" width="160">
        <template #default="{ row }">
          <code class="schedule-code">{{ row.scheduleType }}:{{ row.scheduleValue }}</code>
        </template>
      </el-table-column>
      <el-table-column label="타입" width="110">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ row.executionType }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="활성" width="70" align="center">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small" effect="dark">
            {{ row.enabled ? 'ON' : 'OFF' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="다음 실행" width="110" align="center">
        <template #default="{ row }">
          <template v-if="row.nextRunAt">
            <div class="date-cell">
              <span class="date-date">{{ formatDatePart(row.nextRunAt) }}</span>
              <span class="date-time">{{ formatTimePart(row.nextRunAt) }}</span>
            </div>
          </template>
          <span v-else class="no-data">-</span>
        </template>
      </el-table-column>
      <el-table-column label="최근 상태" width="90" align="center">
        <template #default="{ row }">
          <el-tag
            v-if="row.lastRunStatus"
            :type="row.lastRunStatus === 'success' ? 'success' : 'danger'"
            size="small"
            effect="dark"
          >{{ row.lastRunStatus }}</el-tag>
          <span v-else class="no-data">-</span>
        </template>
      </el-table-column>
      <el-table-column label="오류" width="60" align="center">
        <template #default="{ row }">
          <el-badge
            v-if="row.consecutiveErrors > 0"
            :value="row.consecutiveErrors"
            type="danger"
          />
          <span v-else class="no-data">0</span>
        </template>
      </el-table-column>
      <el-table-column label="" width="70" align="center">
        <template #default="{ row }">
          <el-popconfirm
            title="이 작업을 삭제하시겠습니까?"
            confirm-button-text="삭제"
            cancel-button-text="취소"
            @confirm="deleteJob(row.id)"
          >
            <template #reference>
              <el-button type="danger" size="small" text>삭제</el-button>
            </template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>

    <!-- Cron 실행 기록 -->
    <el-card shadow="never" class="cron-history">
      <template #header>
        <div class="card-header">
          <span class="card-title">Cron 실행 기록</span>
          <div class="search-bar">
            <el-input
              v-model="logSearchQuery"
              placeholder="검색..."
              clearable
              size="small"
              style="width: 200px"
              @clear="onLogSearch"
              @keyup.enter="onLogSearch"
            />
            <el-button size="small" @click="onLogSearch">검색</el-button>
          </div>
        </div>
      </template>

      <el-table :data="logItems" stripe style="width: 100%" v-loading="logLoading">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expand-detail">
              <div v-if="row.resultText" class="expand-row">
                <span class="expand-label">결과</span>
                <pre class="expand-value">{{ row.resultText }}</pre>
              </div>
              <div v-if="row.errorMessage" class="expand-row">
                <span class="expand-label">에러</span>
                <pre class="expand-value error-text">{{ row.errorMessage }}</pre>
              </div>
              <div v-if="!row.resultText && !row.errorMessage" class="expand-empty">상세 정보 없음</div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="시간" width="110" align="center">
          <template #default="{ row }">
            <div class="date-cell">
              <span class="date-date">{{ formatDatePart(row.createdAt) }}</span>
              <span class="date-time">{{ formatTimePart(row.createdAt) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="작업명" prop="jobName" min-width="160" />
        <el-table-column label="상태" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="logStatusType(row.status)" size="small" effect="dark">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="결과" min-width="240">
          <template #default="{ row }">
            <span v-if="row.resultText" class="result-preview">{{ truncate(row.resultText, 100) }}</span>
            <span v-else class="no-data">-</span>
          </template>
        </el-table-column>
        <el-table-column label="도구" width="140">
          <template #default="{ row }">
            <span class="tools-text">{{ row.toolsUsed || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="토큰" width="70" align="right">
          <template #default="{ row }">{{ row.totalTokens ?? '-' }}</template>
        </el-table-column>
      </el-table>

      <div class="pagination-bar" v-if="logTotalPages > 1">
        <el-pagination
          v-model:current-page="logCurrentPage"
          :page-size="logPageSize"
          :total="logTotalElements"
          layout="prev, pager, next"
          @current-change="loadCronLog"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '@/api/client'
import { formatDate, truncate } from './types'
import type { ApiResponse, Job, ActivityEntry, ActivityPage } from './types'

// ── Jobs ──
const jobs = ref<Job[]>([])
const jobsLoading = ref(false)

// ── Cron Log ──
const logItems = ref<ActivityEntry[]>([])
const logLoading = ref(false)
const logSearchQuery = ref('')
const logCurrentPage = ref(1)
const logPageSize = 20
const logTotalElements = ref(0)
const logTotalPages = ref(0)

function formatDatePart(dateStr: string): string {
  if (!dateStr) return '-'
  try {
    const d = new Date(dateStr)
    const y = d.getFullYear()
    const mm = String(d.getMonth() + 1).padStart(2, '0')
    const dd = String(d.getDate()).padStart(2, '0')
    return `${y}-${mm}-${dd}`
  } catch {
    return dateStr
  }
}

function formatTimePart(dateStr: string): string {
  if (!dateStr) return ''
  try {
    const d = new Date(dateStr)
    const hh = String(d.getHours()).padStart(2, '0')
    const mi = String(d.getMinutes()).padStart(2, '0')
    return `${hh}:${mi}`
  } catch {
    return ''
  }
}

function logStatusType(status: string): '' | 'success' | 'warning' | 'info' | 'danger' {
  switch (status) {
    case 'success': return 'success'
    case 'error': return 'danger'
    case 'skipped': return 'info'
    default: return 'info'
  }
}

// ── Jobs API ──
async function loadJobList(): Promise<void> {
  jobsLoading.value = true
  try {
    const { data } = await api.get<ApiResponse<Job[]>>('/agent/api/jobs')
    if (data.code === 'OK' && data.data) {
      jobs.value = data.data
    } else {
      jobs.value = data.data ?? (Array.isArray(data) ? data : [])
    }
  } catch {
    ElMessage.error('예약 작업 로드 실패')
  } finally {
    jobsLoading.value = false
  }
}

async function deleteJob(id: string): Promise<void> {
  try {
    const { data } = await api.delete<ApiResponse>(`/agent/api/jobs/${id}`)
    if (data.code === 'OK') {
      ElMessage.success('작업이 삭제되었습니다.')
      await loadJobList()
    } else {
      ElMessage.error(data.message || '삭제 실패')
    }
  } catch {
    ElMessage.error('작업 삭제에 실패했습니다.')
  }
}

// ── Cron Log API ──
function onLogSearch(): void {
  logCurrentPage.value = 1
  loadCronLog()
}

async function loadCronLog(): Promise<void> {
  logLoading.value = true
  try {
    const params: Record<string, any> = {
      page: logCurrentPage.value - 1,
      size: logPageSize,
      type: 'cron'
    }
    if (logSearchQuery.value) params.search = logSearchQuery.value

    const { data } = await api.get<ApiResponse<ActivityPage>>('/agent/api/activity-log', { params })
    if (data.code === 'OK' && data.data) {
      logItems.value = data.data.content
      logTotalElements.value = data.data.totalElements
      logTotalPages.value = data.data.totalPages
    } else if ((data as any).content) {
      logItems.value = (data as any).content
      logTotalElements.value = (data as any).totalElements
      logTotalPages.value = (data as any).totalPages
    }
  } catch {
    ElMessage.error('Cron 로그 로드 실패')
  } finally {
    logLoading.value = false
  }
}

// ── 통합 로드 ──
async function loadJobs(): Promise<void> {
  await Promise.all([loadJobList(), loadCronLog()])
}

defineExpose({ loadJobs })
</script>

<style scoped>
.tab-toolbar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  margin-bottom: 12px;
}

.job-name {
  font-size: 13px;
  word-break: break-all;
}

.schedule-code {
  font-size: 12px;
  color: var(--el-text-color-regular);
}

.no-data {
  color: var(--el-text-color-placeholder);
}

.date-cell {
  display: flex;
  flex-direction: column;
  line-height: 1.4;
}

.date-date {
  font-size: 13px;
}

.date-time {
  font-size: 11px;
  color: var(--el-text-color-secondary);
}

.expand-detail {
  padding: 12px 16px;
}

.detail-grid {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 4px 12px;
  font-size: 13px;
  margin-bottom: 8px;
}

.detail-label {
  font-weight: 600;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.detail-value {
  font-size: 13px;
}

.selectable {
  user-select: all;
  font-size: 12px;
}

.detail-section {
  margin-top: 8px;
}

.detail-pre {
  margin: 4px 0 0 0;
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
  background: var(--el-fill-color-lighter);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  padding: 8px 12px;
  max-height: 200px;
  overflow: auto;
}

.error-text {
  color: var(--el-color-danger);
}

.cron-history {
  margin-top: 24px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-title {
  font-size: 15px;
  font-weight: 600;
}

.search-bar {
  display: flex;
  gap: 8px;
}

.pagination-bar {
  display: flex;
  justify-content: center;
  margin-top: 16px;
}

.result-preview {
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.tools-text {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.expand-row {
  margin-bottom: 8px;
}

.expand-row:last-child {
  margin-bottom: 0;
}

.expand-label {
  display: inline-block;
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
  margin-right: 8px;
}

.expand-value {
  margin: 4px 0 0 0;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  background: var(--el-fill-color-lighter);
  border-radius: 4px;
  padding: 8px 12px;
}

.expand-empty {
  color: var(--el-text-color-placeholder);
  font-size: 13px;
}
</style>
