<template>
  <div>
    <div style="display: flex; gap: 8px; margin-bottom: 12px">
      <el-popconfirm
        title="모든 세션을 삭제하시겠습니까?"
        confirm-button-text="삭제"
        cancel-button-text="취소"
        @confirm="deleteAllSessions"
      >
        <template #reference>
          <el-button type="danger" size="small">전체 삭제</el-button>
        </template>
      </el-popconfirm>
    </div>

    <div class="sessions-layout">
      <!-- 세션 리스트 -->
      <div class="sessions-sidebar">
        <div v-if="sessions.length === 0" class="sessions-empty">세션 없음</div>
        <div
          v-for="s in sessions"
          :key="s.id"
          class="session-item"
          :class="{ active: selectedSessionId === s.id }"
          @click="selectSession(s.id)"
        >
          <div class="session-title">{{ s.title || '(제목 없음)' }}</div>
          <div class="session-meta">
            <el-tag v-if="s.llmModel" size="small" effect="plain">{{ s.llmModel }}</el-tag>
            <el-tag v-if="s.active" type="success" size="small" effect="plain">active</el-tag>
          </div>
          <div class="session-time">{{ formatDate(s.updatedAt || s.createdAt) }}</div>
        </div>
      </div>

      <!-- 메시지 뷰어 -->
      <div class="session-content">
        <template v-if="selectedSessionId">
          <div class="session-actions">
            <el-button size="small" @click="resetSession">초기화</el-button>
            <el-button size="small" @click="showModelDialog = true">모델 변경</el-button>
            <el-popconfirm
              title="이 세션을 삭제하시겠습니까?"
              confirm-button-text="삭제"
              cancel-button-text="취소"
              @confirm="deleteSession(selectedSessionId!)"
            >
              <template #reference>
                <el-button type="danger" size="small" text>삭제</el-button>
              </template>
            </el-popconfirm>
          </div>

          <!-- 세션 상세 정보 -->
          <div v-if="sessionDetail" class="session-detail">
            <div class="session-detail-row">
              <div class="session-detail-col">
                <span class="detail-label">Channel</span>
                <span class="detail-value">{{ sessionDetail.channelId }}</span>
              </div>
              <div class="session-detail-col">
                <span class="detail-label">LLM Model</span>
                <span class="detail-value">{{ sessionDetail.llmModel || sessionDetail.defaultModel || '-' }}</span>
              </div>
            </div>
            <div class="session-detail-row">
              <div class="session-detail-col">
                <span class="detail-label">Messages</span>
                <span class="detail-value">{{ sessionDetail.messageCount }}</span>
              </div>
              <div class="session-detail-col">
                <span class="detail-label">Compaction</span>
                <span class="detail-value">{{ sessionDetail.compactionCount }}</span>
              </div>
            </div>
            <div v-if="sessionDetail.toolApprovals" class="session-detail-row">
              <div class="session-detail-col" style="flex: 1">
                <span class="detail-label">Tool Approvals</span>
                <span class="detail-value text-truncate">{{ truncate(sessionDetail.toolApprovals, 100) }}</span>
              </div>
            </div>
            <div v-if="sessionDetail.summary" class="session-detail-row">
              <div class="session-detail-col" style="flex: 1">
                <span class="detail-label clickable" @click="showSummary = !showSummary">
                  Summary {{ showSummary ? '▼' : '▶' }}
                </span>
                <pre v-if="showSummary" class="summary-content">{{ sessionDetail.summary }}</pre>
              </div>
            </div>
          </div>

          <div class="messages-container" v-loading="messagesLoading">
            <div v-if="messages.length === 0" class="messages-empty">메시지 없음</div>
            <div
              v-for="msg in messages"
              :key="msg.id"
              class="message-item"
              :class="'message-' + msg.role"
            >
              <div class="message-header">
                <el-tag
                  :type="roleTagType(msg.role)"
                  size="small"
                  effect="dark"
                >{{ msg.role }}</el-tag>
                <span v-if="msg.model" class="message-name">{{ msg.model }}</span>
                <span class="message-time">{{ formatDate(msg.createdAt) }}</span>
                <el-button
                  type="danger"
                  size="small"
                  text
                  class="message-delete"
                  @click="deleteMessage(msg.id)"
                >
                  <el-icon><Delete /></el-icon>
                </el-button>
              </div>
              <div class="message-body">
                <template v-if="msg.role === 'tool'">
                  <span class="tool-label">{{ msg.toolCallId || 'tool' }}</span>
                  <span class="tool-content">{{ truncate(msg.content, 500) }}</span>
                </template>
                <template v-else>
                  <pre v-if="msg.content" class="message-text">{{ msg.content }}</pre>
                  <div v-if="msg.toolCalls" class="tool-calls-section">
                    <span class="tool-calls-toggle" @click="toggleToolCalls(msg.id)">
                      tool_calls {{ expandedToolCalls.has(msg.id) ? '▼' : '▶' }}
                    </span>
                    <pre v-if="expandedToolCalls.has(msg.id)" class="tool-calls-content">{{ msg.toolCalls }}</pre>
                  </div>
                  <span v-if="!msg.content && !msg.toolCalls" class="no-content">(내용 없음)</span>
                </template>
              </div>
            </div>
          </div>
        </template>
        <template v-else>
          <el-empty description="세션을 선택해주세요" />
        </template>
      </div>
    </div>

    <!-- 모델 변경 다이얼로그 -->
    <el-dialog v-model="showModelDialog" title="모델 변경" width="400">
      <el-form label-width="80px">
        <el-form-item label="모델">
          <el-input v-model="newModel" placeholder="모델명 입력" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showModelDialog = false">취소</el-button>
        <el-button type="primary" :loading="modelChanging" @click="changeModel">변경</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete } from '@element-plus/icons-vue'
import api from '@/api/client'
import { formatDate, truncate } from './types'
import type { ApiResponse, Session, SessionDetail, Message } from './types'

const sessions = ref<Session[]>([])
const sessionsLoading = ref(false)
const selectedSessionId = ref<string | null>(null)
const sessionDetail = ref<SessionDetail | null>(null)
const messages = ref<Message[]>([])
const messagesLoading = ref(false)
const expandedToolCalls = ref<Set<string>>(new Set())
const showSummary = ref(false)
const showModelDialog = ref(false)
const newModel = ref('')
const modelChanging = ref(false)

async function loadSessions(): Promise<void> {
  sessionsLoading.value = true
  try {
    const { data } = await api.get<ApiResponse<Session[]>>('/agent/api/sessions')
    if (data.code === 'OK' && data.data) {
      sessions.value = data.data
    } else {
      sessions.value = data.data ?? (Array.isArray(data) ? data : [])
    }
  } catch {
    ElMessage.error('세션 목록 로드 실패')
  } finally {
    sessionsLoading.value = false
  }
}

async function selectSession(id: string): Promise<void> {
  selectedSessionId.value = id
  await Promise.all([loadSessionDetail(id), loadMessages(id)])
}

async function loadSessionDetail(sessionId: string): Promise<void> {
  try {
    const { data } = await api.get<ApiResponse<SessionDetail>>(`/agent/api/sessions/${sessionId}`)
    if (data.code === 'OK' && data.data) {
      sessionDetail.value = data.data
    }
  } catch {
    sessionDetail.value = null
  }
}

async function loadMessages(sessionId: string): Promise<void> {
  messagesLoading.value = true
  try {
    const { data } = await api.get<ApiResponse<Message[]>>(`/agent/api/sessions/${sessionId}/messages`)
    if (data.code === 'OK' && data.data) {
      messages.value = data.data
    } else {
      messages.value = data.data ?? (Array.isArray(data) ? data : [])
    }
  } catch {
    ElMessage.error('메시지 로드 실패')
  } finally {
    messagesLoading.value = false
  }
}

async function resetSession(): Promise<void> {
  if (!selectedSessionId.value) return
  try {
    await ElMessageBox.confirm('이 세션을 초기화하시겠습니까?', '세션 초기화', { type: 'warning' })
  } catch {
    return
  }
  try {
    const { data } = await api.post<ApiResponse>(`/agent/api/sessions/${selectedSessionId.value}/reset`)
    if (data.code === 'OK') {
      ElMessage.success('세션이 초기화되었습니다.')
      await loadMessages(selectedSessionId.value!)
      await loadSessions()
    } else {
      ElMessage.error(data.message || '초기화 실패')
    }
  } catch {
    ElMessage.error('세션 초기화에 실패했습니다.')
  }
}

async function changeModel(): Promise<void> {
  if (!selectedSessionId.value || !newModel.value) return
  modelChanging.value = true
  try {
    const { data } = await api.post<ApiResponse>(`/agent/api/sessions/${selectedSessionId.value}/model`, {
      model: newModel.value
    })
    if (data.code === 'OK') {
      ElMessage.success('모델이 변경되었습니다.')
      showModelDialog.value = false
      newModel.value = ''
      await loadSessions()
    } else {
      ElMessage.error(data.message || '모델 변경 실패')
    }
  } catch {
    ElMessage.error('모델 변경에 실패했습니다.')
  } finally {
    modelChanging.value = false
  }
}

async function deleteSession(id: string): Promise<void> {
  try {
    const { data } = await api.delete<ApiResponse>(`/agent/api/sessions/${id}`)
    if (data.code === 'OK') {
      ElMessage.success('세션이 삭제되었습니다.')
      if (selectedSessionId.value === id) {
        selectedSessionId.value = null
        sessionDetail.value = null
        messages.value = []
      }
      await loadSessions()
    } else {
      ElMessage.error(data.message || '삭제 실패')
    }
  } catch {
    ElMessage.error('세션 삭제에 실패했습니다.')
  }
}

async function deleteAllSessions(): Promise<void> {
  try {
    const { data } = await api.delete<ApiResponse>('/agent/api/sessions')
    if (data.code === 'OK') {
      ElMessage.success('전체 세션이 삭제되었습니다.')
      selectedSessionId.value = null
      sessionDetail.value = null
      messages.value = []
      sessions.value = []
    } else {
      ElMessage.error(data.message || '삭제 실패')
    }
  } catch {
    ElMessage.error('전체 세션 삭제에 실패했습니다.')
  }
}

async function deleteMessage(msgId: string): Promise<void> {
  try {
    const { data } = await api.delete<ApiResponse>(`/agent/api/messages/${msgId}`)
    if (data.code === 'OK') {
      ElMessage.success('메시지가 삭제되었습니다.')
      if (selectedSessionId.value) {
        await loadMessages(selectedSessionId.value)
      }
    } else {
      ElMessage.error(data.message || '메시지 삭제 실패')
    }
  } catch {
    ElMessage.error('메시지 삭제에 실패했습니다.')
  }
}

function toggleToolCalls(msgId: string): void {
  if (expandedToolCalls.value.has(msgId)) {
    expandedToolCalls.value.delete(msgId)
  } else {
    expandedToolCalls.value.add(msgId)
  }
}

function roleTagType(role: string): '' | 'success' | 'warning' | 'info' | 'danger' {
  switch (role) {
    case 'user': return ''
    case 'assistant': return 'success'
    case 'system': return 'warning'
    case 'tool': return 'info'
    default: return 'info'
  }
}

onMounted(() => {
  loadSessions()
})
</script>

<style scoped>
.sessions-layout {
  display: flex;
  gap: 16px;
  height: calc(100vh - 240px);
  min-height: 400px;
}

.sessions-sidebar {
  width: 280px;
  min-width: 280px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  overflow-y: auto;
}

.sessions-empty {
  padding: 24px;
  text-align: center;
  color: var(--el-text-color-secondary);
}

.session-item {
  padding: 12px 16px;
  cursor: pointer;
  border-bottom: 1px solid var(--el-border-color-lighter);
  transition: background 0.15s;
}

.session-item:hover {
  background: var(--el-fill-color-light);
}

.session-item.active {
  background: var(--el-color-primary-light-9);
  border-left: 3px solid var(--el-color-primary);
}

.session-title {
  font-size: 14px;
  font-weight: 500;
  margin-bottom: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 2px;
}

.session-count {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.session-time {
  font-size: 11px;
  color: var(--el-text-color-placeholder);
}

.session-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.session-actions {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
  flex-shrink: 0;
}

.messages-container {
  flex: 1;
  overflow-y: auto;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 12px;
}

.messages-empty {
  padding: 24px;
  text-align: center;
  color: var(--el-text-color-secondary);
}

.message-item {
  margin-bottom: 12px;
  padding: 10px 12px;
  border-radius: 6px;
  border: 1px solid var(--el-border-color-lighter);
}

.message-item.message-user {
  background: var(--el-color-primary-light-9);
}

.message-item.message-assistant {
  background: var(--el-color-success-light-9);
}

.message-item.message-system {
  background: var(--el-color-warning-light-9);
}

.message-item.message-tool {
  background: var(--el-fill-color-light);
}

.message-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.message-name {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-weight: 500;
}

.message-time {
  font-size: 11px;
  color: var(--el-text-color-placeholder);
  margin-left: auto;
}

.message-delete {
  padding: 2px 4px;
}

.message-body {
  font-size: 13px;
  line-height: 1.6;
}

.message-text {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  font-size: inherit;
}

.tool-label {
  display: inline-block;
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
  margin-bottom: 4px;
  margin-right: 8px;
}

.tool-content {
  display: block;
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  font-size: 12px;
  color: var(--el-text-color-regular);
  white-space: pre-wrap;
  word-break: break-all;
}

.session-detail {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  padding: 10px 14px;
  margin-bottom: 12px;
  background: var(--el-fill-color-light);
  flex-shrink: 0;
}

.session-detail-row {
  display: flex;
  gap: 24px;
  margin-bottom: 4px;
}

.session-detail-row:last-child {
  margin-bottom: 0;
}

.session-detail-col {
  flex: 1;
  min-width: 0;
}

.detail-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-secondary);
  margin-right: 6px;
}

.detail-label.clickable {
  cursor: pointer;
  user-select: none;
}

.detail-value {
  font-size: 13px;
  color: var(--el-text-color-primary);
}

.text-truncate {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: inline-block;
  max-width: 300px;
  vertical-align: bottom;
}

.summary-content {
  margin: 4px 0 0 0;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  padding: 8px;
  max-height: 200px;
  overflow-y: auto;
}

.tool-calls-section {
  margin-top: 4px;
}

.tool-calls-toggle {
  cursor: pointer;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  user-select: none;
}

.tool-calls-toggle:hover {
  color: var(--el-color-primary);
}

.tool-calls-content {
  margin: 4px 0 0 0;
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  padding: 8px;
  max-height: 300px;
  overflow-y: auto;
}

.no-content {
  color: var(--el-text-color-placeholder);
  font-size: 12px;
  font-style: italic;
}
</style>
