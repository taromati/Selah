<template>
  <div>
    <div class="tab-toolbar">
      <el-popconfirm
        title="3개월 이전 기록을 삭제하시겠습니까?"
        confirm-button-text="삭제"
        cancel-button-text="취소"
        @confirm="deleteOldHistory"
      >
        <template #reference>
          <el-button type="danger" size="small" text>3개월 이전 삭제</el-button>
        </template>
      </el-popconfirm>
    </div>

    <el-table :data="items" stripe style="width: 100%" v-loading="loading">
      <el-table-column label="시간" width="110" align="center">
        <template #default="{ row }">
          <div class="date-cell">
            <span class="date-date">{{ formatDatePart(row.completedAt) }}</span>
            <span class="date-time">{{ formatTimePart(row.completedAt) }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="상태" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'COMPLETED' ? 'success' : 'danger'" size="small" effect="dark">
            {{ row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="title" label="제목" min-width="200" />
      <el-table-column label="요약" min-width="300">
        <template #default="{ row }">
          <span v-if="row.summary" class="summary-text">{{ truncate(row.summary, 120) }}</span>
          <span v-else class="no-data">-</span>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination-bar" v-if="totalPages > 1">
      <el-pagination
        v-model:current-page="currentPage"
        :page-size="pageSize"
        :total="totalElements"
        layout="prev, pager, next"
        @current-change="loadHistory"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '@/api/client'
import { truncate } from './types'
import type { ApiResponse, RoutineHistoryPage } from './types'

const items = ref<any[]>([])
const loading = ref(false)
const currentPage = ref(1)
const pageSize = 20
const totalElements = ref(0)
const totalPages = ref(0)

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

async function loadHistory(): Promise<void> {
  loading.value = true
  try {
    const params = { page: currentPage.value - 1, size: pageSize }
    const { data } = await api.get<ApiResponse<RoutineHistoryPage>>('/agent/api/routine-history', { params })
    if (data.code === 'OK' && data.data) {
      items.value = data.data.content
      totalElements.value = data.data.totalElements
      totalPages.value = data.data.totalPages
    } else if ((data as any).content) {
      items.value = (data as any).content
      totalElements.value = (data as any).totalElements
      totalPages.value = (data as any).totalPages
    }
  } catch {
    ElMessage.error('루틴 이력 로드 실패')
  } finally {
    loading.value = false
  }
}

async function deleteOldHistory(): Promise<void> {
  try {
    const { data } = await api.delete<ApiResponse>('/agent/api/routine-history', {
      params: { olderThanMonths: 3 }
    })
    if (data.code === 'OK') {
      ElMessage.success('이전 기록이 삭제되었습니다.')
      await loadHistory()
    } else {
      ElMessage.error(data.message || '삭제 실패')
    }
  } catch {
    ElMessage.error('기록 삭제에 실패했습니다.')
  }
}

defineExpose({ loadHistory })
</script>

<style scoped>
.tab-toolbar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  margin-bottom: 12px;
}

.pagination-bar {
  display: flex;
  justify-content: center;
  margin-top: 16px;
}

.summary-text {
  font-size: 13px;
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
</style>
