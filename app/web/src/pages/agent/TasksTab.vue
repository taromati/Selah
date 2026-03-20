<template>
  <div>
    <!-- Routine 상태바 -->
    <div class="routine-status-bar">
      <span class="status-info">
        주기: {{ routineConfig.intervalMs ? Math.round(routineConfig.intervalMs / 60000) + '분' : '-' }}
        | 알림: {{ routineConfig.activeStartHour ?? '-' }}:00 ~ {{ routineConfig.activeEndHour ?? '-' }}:00 KST
      </span>
      <div class="routine-actions">
        <el-switch
          :model-value="routineConfig.enabled"
          :loading="routineToggling"
          @change="(val: boolean) => toggleRoutine(val)"
        />
        <el-button type="primary" size="small" :loading="routineRunning" @click="runRoutine">수동 실행</el-button>
      </div>
    </div>

    <!-- Task 목록 -->
    <div class="tab-toolbar">
      <el-button type="primary" size="small" @click="showTaskDialog = true">추가</el-button>
    </div>

    <el-table :data="tasks" stripe style="width: 100%" v-loading="tasksLoading">
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="expand-detail">
            <div v-if="row.description" class="expand-row">
              <span class="expand-label">설명</span>
              <pre class="expand-value">{{ row.description }}</pre>
            </div>
            <div v-if="row.originalRequest" class="expand-row">
              <span class="expand-label">원래 요청</span>
              <pre class="expand-value">{{ row.originalRequest }}</pre>
            </div>
            <div v-if="row.progress" class="expand-row">
              <span class="expand-label">진행 상황</span>
              <pre class="expand-value">{{ row.progress }}</pre>
            </div>
            <div class="expand-row">
              <span class="expand-label">재시도</span>
              <span>{{ row.retryCount }}회</span>
            </div>
            <div v-if="!row.description && !row.originalRequest && !row.progress" class="expand-empty">
              상세 정보 없음
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="상태" width="150">
        <template #default="{ row }">
          <el-tag :type="taskStatusType(row.status)" size="small" effect="dark">
            {{ statusLabel(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="title" label="제목" min-width="240" />
      <el-table-column label="소스" width="100">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ row.source }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="생성일" width="110" align="center">
        <template #default="{ row }">
          <div class="date-cell">
            <span class="date-date">{{ formatDatePart(row.createdAt) }}</span>
            <span class="date-time">{{ formatTimePart(row.createdAt) }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="완료일" width="110" align="center">
        <template #default="{ row }">
          <template v-if="row.completedAt">
            <div class="date-cell">
              <span class="date-date">{{ formatDatePart(row.completedAt) }}</span>
              <span class="date-time">{{ formatTimePart(row.completedAt) }}</span>
            </div>
          </template>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column label="작업" width="180" align="center">
        <template #default="{ row }">
          <el-dropdown trigger="click" @command="(cmd: string) => changeTaskStatus(row.id, cmd)">
            <el-button size="small">
              상태 변경
              <el-icon class="el-icon--right"><ArrowDown /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="PENDING">PENDING</el-dropdown-item>
                <el-dropdown-item command="IN_PROGRESS">IN_PROGRESS</el-dropdown-item>
                <el-dropdown-item command="COMPLETED">COMPLETED</el-dropdown-item>
                <el-dropdown-item command="FAILED">FAILED</el-dropdown-item>
                <el-dropdown-item command="WAITING_APPROVAL">WAITING_APPROVAL</el-dropdown-item>
                <el-dropdown-item command="CANCELLED">CANCELLED</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-popconfirm
            title="삭제하시겠습니까?"
            confirm-button-text="삭제"
            cancel-button-text="취소"
            @confirm="deleteTask(row.id)"
          >
            <template #reference>
              <el-button type="danger" size="small" text>삭제</el-button>
            </template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>

    <!-- Routine 실행 기록 -->
    <el-card shadow="never" class="routine-history">
      <template #header>
        <div class="card-header">
          <span class="card-title">루틴 실행 기록</span>
          <div class="search-bar">
            <el-input
              v-model="logSearchQuery"
              placeholder="검색..."
              clearable
              size="small"
              style="width: 200px"
              @clear="loadRoutineLog"
              @keyup.enter="loadRoutineLog"
            />
            <el-button size="small" @click="loadRoutineLog">검색</el-button>
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
        <el-table-column label="상태" width="100">
          <template #default="{ row }">
            <el-tag :type="logStatusType(row.status)" size="small" effect="dark">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="결과" min-width="300">
          <template #default="{ row }">
            <span v-if="row.resultText" class="result-preview">{{ truncate(row.resultText, 120) }}</span>
            <span v-else class="no-data">-</span>
          </template>
        </el-table-column>
        <el-table-column label="도구" width="160">
          <template #default="{ row }">
            <span class="tools-text">{{ row.toolsUsed || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="토큰" width="80" align="right">
          <template #default="{ row }">{{ row.totalTokens ?? '-' }}</template>
        </el-table-column>
      </el-table>

      <div class="pagination-bar" v-if="logTotalPages > 1">
        <el-pagination
          v-model:current-page="logCurrentPage"
          :page-size="logPageSize"
          :total="logTotalElements"
          layout="prev, pager, next"
          @current-change="loadRoutineLog"
        />
      </div>
    </el-card>

    <!-- Task 추가 다이얼로그 -->
    <el-dialog v-model="showTaskDialog" title="할 일 추가" width="480" @closed="resetTaskForm">
      <el-form :model="taskForm" label-width="80px">
        <el-form-item label="제목">
          <el-input v-model="taskForm.title" placeholder="할 일 제목" />
        </el-form-item>
        <el-form-item label="설명">
          <el-input
            v-model="taskForm.description"
            type="textarea"
            :rows="3"
            placeholder="설명 (선택)"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showTaskDialog = false">취소</el-button>
        <el-button
          type="primary"
          :loading="taskAdding"
          :disabled="!taskForm.title"
          @click="addTask"
        >추가</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowDown } from '@element-plus/icons-vue'
import api from '@/api/client'
import { truncate } from './types'
import type { ApiResponse, Task, ActivityEntry, ActivityPage } from './types'

// ── Tasks ──
const tasks = ref<Task[]>([])
const tasksLoading = ref(false)
const showTaskDialog = ref(false)
const taskAdding = ref(false)
const taskForm = reactive({
  title: '',
  description: ''
})

// ── Routine Status ──
const routineConfig = reactive({
  enabled: false,
  intervalMs: 0,
  activeStartHour: 7,
  activeEndHour: 1
})
const routineRunning = ref(false)
const routineToggling = ref(false)

// ── Routine Log ──
const logItems = ref<ActivityEntry[]>([])
const logLoading = ref(false)
const logSearchQuery = ref('')
const logCurrentPage = ref(1)
const logPageSize = 15
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
    const ss = String(d.getSeconds()).padStart(2, '0')
    return `${hh}:${mi}:${ss}`
  } catch {
    return ''
  }
}

function statusLabel(status: string): string {
  switch (status) {
    case 'PENDING': return 'PENDING'
    case 'IN_PROGRESS': return 'IN PROGRESS'
    case 'COMPLETED': return 'COMPLETED'
    case 'FAILED': return 'FAILED'
    case 'WAITING_APPROVAL': return 'WAITING'
    case 'CANCELLED': return 'CANCELLED'
    default: return status
  }
}

function taskStatusType(status: string): '' | 'success' | 'warning' | 'info' | 'danger' {
  switch (status) {
    case 'PENDING': return 'warning'
    case 'IN_PROGRESS': return ''
    case 'COMPLETED': return 'success'
    case 'FAILED': return 'danger'
    case 'WAITING_APPROVAL': return 'info'
    case 'CANCELLED': return 'info'
    default: return 'info'
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

// ── Tasks API ──
async function loadTaskList(): Promise<void> {
  tasksLoading.value = true
  try {
    const { data } = await api.get<ApiResponse<Task[]>>('/agent/api/tasks')
    if (data.code === 'OK' && data.data) {
      tasks.value = data.data
    } else {
      tasks.value = data.data ?? (Array.isArray(data) ? data : [])
    }
  } catch {
    ElMessage.error('할 일 목록 로드 실패')
  } finally {
    tasksLoading.value = false
  }
}

async function addTask(): Promise<void> {
  if (!taskForm.title) {
    ElMessage.warning('제목을 입력해주세요.')
    return
  }
  taskAdding.value = true
  try {
    const { data } = await api.post<ApiResponse>('/agent/api/tasks', {
      title: taskForm.title,
      description: taskForm.description
    })
    if (data.code === 'OK') {
      ElMessage.success('할 일이 추가되었습니다.')
      showTaskDialog.value = false
      await loadTaskList()
    } else {
      ElMessage.error(data.message || '추가 실패')
    }
  } catch {
    ElMessage.error('할 일 추가에 실패했습니다.')
  } finally {
    taskAdding.value = false
  }
}

async function changeTaskStatus(id: string, status: string): Promise<void> {
  try {
    const { data } = await api.put<ApiResponse>(`/agent/api/tasks/${id}/status`, { status })
    if (data.code === 'OK') {
      ElMessage.success('상태가 변경되었습니다.')
      await loadTaskList()
    } else {
      ElMessage.error(data.message || '상태 변경 실패')
    }
  } catch {
    ElMessage.error('상태 변경에 실패했습니다.')
  }
}

async function deleteTask(id: string): Promise<void> {
  try {
    const { data } = await api.delete<ApiResponse>(`/agent/api/tasks/${id}`)
    if (data.code === 'OK') {
      ElMessage.success('할 일이 삭제되었습니다.')
      await loadTaskList()
    } else {
      ElMessage.error(data.message || '삭제 실패')
    }
  } catch {
    ElMessage.error('할 일 삭제에 실패했습니다.')
  }
}

function resetTaskForm(): void {
  taskForm.title = ''
  taskForm.description = ''
}

// ── Routine Status API ──
async function loadRoutineStatus(): Promise<void> {
  try {
    const { data } = await api.get<ApiResponse<Record<string, any>>>('/agent/api/config')
    const rt = (data.code === 'OK' && data.data) ? (data.data as any).routine : null
    if (rt) {
      routineConfig.enabled = rt.enabled ?? false
      routineConfig.intervalMs = rt.intervalMs ?? 0
      routineConfig.activeStartHour = rt.activeStartHour ?? 7
      routineConfig.activeEndHour = rt.activeEndHour ?? 1
    }
  } catch { /* ignore */ }
}

async function toggleRoutine(enabled: boolean): Promise<void> {
  routineToggling.value = true
  try {
    const { data } = await api.post<ApiResponse>('/agent/api/config', {
      routine: { enabled }
    })
    if (data.code === 'OK') {
      routineConfig.enabled = enabled
      ElMessage.success(enabled ? '루틴이 활성화되었습니다.' : '루틴이 비활성화되었습니다.')
    } else {
      ElMessage.error(data.message || '설정 변경 실패')
    }
  } catch {
    ElMessage.error('루틴 설정 변경에 실패했습니다.')
  } finally {
    routineToggling.value = false
  }
}

async function runRoutine(): Promise<void> {
  routineRunning.value = true
  try {
    const { data } = await api.post<ApiResponse<{ status: string; message: string }>>('/agent/api/routine/run')
    if (data.code === 'OK' && data.data) {
      if (data.data.status === 'error') {
        ElMessage.error(data.data.message || '루틴 실행 실패')
      } else {
        ElMessage.info(data.data.message || '루틴이 실행되었습니다.')
        await loadRoutineLog()
      }
    } else {
      ElMessage.error('루틴 실행 실패')
    }
  } catch {
    ElMessage.error('루틴 실행에 실패했습니다.')
  } finally {
    routineRunning.value = false
  }
}

// ── Routine Log API ──
async function loadRoutineLog(): Promise<void> {
  logLoading.value = true
  try {
    const params: Record<string, any> = {
      page: logCurrentPage.value - 1,
      size: logPageSize,
      type: 'routine'
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
    ElMessage.error('루틴 로그 로드 실패')
  } finally {
    logLoading.value = false
  }
}

// ── 통합 로드 ──
async function loadTasks(): Promise<void> {
  await Promise.all([loadTaskList(), loadRoutineStatus(), loadRoutineLog()])
}

defineExpose({ loadTasks })
</script>

<style scoped>
.routine-status-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  margin-bottom: 16px;
  background: var(--el-fill-color-lighter);
}

.routine-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.status-info {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.tab-toolbar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  margin-bottom: 12px;
}

.routine-history {
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

.no-data {
  color: var(--el-text-color-placeholder);
}

.expand-detail {
  padding: 8px 16px;
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
  min-width: 70px;
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

.error-text {
  color: var(--el-color-danger);
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
</style>
