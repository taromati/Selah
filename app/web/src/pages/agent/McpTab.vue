<template>
  <div>
    <div class="tab-toolbar">
      <el-button type="primary" size="small" @click="openAddDialog">추가</el-button>
      <el-button size="small" @click="loadMcpServers">새로고침</el-button>
    </div>

    <div class="mcp-cards" v-loading="mcpLoading">
      <div v-if="mcpServers.length === 0" style="padding: 24px">
        <el-empty description="등록된 MCP 서버 없음" />
      </div>

      <el-card
        v-for="server in mcpServers"
        :key="server.name"
        shadow="hover"
        class="mcp-card"
        :class="{ 'mcp-card--disabled': !server.enabled }"
      >
        <template #header>
          <div class="card-header">
            <div class="mcp-card-title">
              <span class="mcp-name">{{ server.name }}</span>
              <span class="mcp-status" :class="statusClass(server)">
                <span
                  v-if="isTransitioning(server)"
                  class="mcp-status-spinner"
                />
                <span v-else class="mcp-status-dot" />
                {{ statusLabel(server) }}
              </span>
            </div>
            <div class="mcp-card-actions">
              <el-switch
                :model-value="server.enabled"
                size="small"
                active-text="활성"
                inactive-text="비활성"
                @change="(val: boolean) => toggleEnabled(server, val)"
              />
              <el-button
                v-if="server.enabled && !server.connected"
                size="small"
                type="success"
                @click="connectMcp(server.name)"
              >연결</el-button>
              <el-button
                v-if="server.connected"
                size="small"
                @click="disconnectMcp(server.name)"
              >해제</el-button>
              <el-button size="small" @click="openEditDialog(server)">편집</el-button>
              <el-button size="small" @click="viewMcpStderr(server.name)">stderr</el-button>
              <el-popconfirm
                title="이 MCP 서버를 삭제하시겠습니까?"
                confirm-button-text="삭제"
                cancel-button-text="취소"
                @confirm="deleteMcpServer(server.name)"
              >
                <template #reference>
                  <el-button type="danger" size="small" text>삭제</el-button>
                </template>
              </el-popconfirm>
            </div>
          </div>
        </template>

        <!-- 에러 알림 -->
        <el-alert
          v-if="server.error"
          :title="server.error"
          type="error"
          show-icon
          :closable="false"
          class="mcp-alert"
        />

        <!-- 인증 알림 배너 -->
        <el-alert
          v-if="server.authNotifications && server.authNotifications.length > 0"
          :type="isAuthAction(server) ? 'warning' : 'info'"
          show-icon
          :closable="false"
          class="mcp-alert"
        >
          <template #title>
            <div class="auth-notification">
              <span>{{ lastNotification(server).message }}</span>
              <div class="auth-notification-actions">
                <el-link
                  v-if="lastNotification(server).url"
                  :href="lastNotification(server).url!"
                  target="_blank"
                  type="primary"
                >인증 링크</el-link>
                <el-button
                  v-if="lastNotification(server).code"
                  size="small"
                  @click="copyCode(lastNotification(server).code!)"
                >코드 복사</el-button>
                <el-button
                  size="small"
                  @click="clearNotifications(server.name)"
                >닫기</el-button>
              </div>
            </div>
          </template>
        </el-alert>

        <!-- 기본 정보 -->
        <div class="mcp-info">
          <div class="mcp-info-row">
            <span class="mcp-info-label">타입</span>
            <span class="mcp-info-value">{{ server.transportType }}</span>
          </div>
          <div class="mcp-info-row" v-if="server.command">
            <span class="mcp-info-label">명령어</span>
            <span class="mcp-info-value mcp-info-mono">{{ server.command }}{{ server.args.length > 0 ? ' ' + server.args.join(' ') : '' }}</span>
          </div>
          <div class="mcp-info-row" v-if="server.url">
            <span class="mcp-info-label">URL</span>
            <span class="mcp-info-value mcp-info-mono">{{ server.url }}</span>
          </div>
          <div class="mcp-info-row">
            <span class="mcp-info-label">기본 정책</span>
            <el-select
              :model-value="server.defaultPolicy || 'ask'"
              size="small"
              style="width: 100px"
              @change="(val: string) => updateHotConfig(server, 'defaultPolicy', val)"
            >
              <el-option value="allow" label="allow" />
              <el-option value="ask" label="ask" />
              <el-option value="deny" label="deny" />
            </el-select>
            <span class="mcp-info-label" style="margin-left: 16px">자동 연결</span>
            <el-checkbox
              :model-value="server.autoConnect"
              @change="(val: boolean) => updateHotConfig(server, 'autoConnect', val)"
            />
            <span class="mcp-info-label" style="margin-left: 16px">타임아웃</span>
            <span class="mcp-info-value">{{ server.timeoutSeconds }}s</span>
            <span class="mcp-info-label" style="margin-left: 16px">재시도</span>
            <span class="mcp-info-value">{{ server.maxRetries }}회</span>
          </div>
        </div>

        <!-- 도구 요약 + 정책 관리 -->
        <div class="mcp-tools-summary" v-if="server.connected && server.toolCount > 0">
          <div class="mcp-tools-badges">
            <span class="mcp-tools-count">도구 {{ server.toolCount }}개</span>
            <el-tag
              v-if="policyCount(server, 'allow') > 0"
              size="small"
              type="success"
              effect="plain"
            >allow: {{ policyCount(server, 'allow') }}</el-tag>
            <el-tag
              v-if="policyCount(server, 'ask') > 0"
              size="small"
              type="warning"
              effect="plain"
            >ask: {{ policyCount(server, 'ask') }}</el-tag>
            <el-tag
              v-if="policyCount(server, 'deny') > 0"
              size="small"
              type="danger"
              effect="plain"
            >deny: {{ policyCount(server, 'deny') }}</el-tag>
            <el-tag
              v-if="policyCount(server, 'default') > 0"
              size="small"
              type="info"
              effect="plain"
            >default: {{ policyCount(server, 'default') }}</el-tag>
          </div>
          <el-button size="small" @click="openPolicyDialog(server)">정책 관리</el-button>
        </div>
        <div v-else-if="server.enabled && !server.connected" class="mcp-no-tools">
          연결 후 도구가 표시됩니다
        </div>
        <div v-else-if="server.connected && server.toolCount === 0" class="mcp-no-tools">
          도구 없음
        </div>
      </el-card>
    </div>

    <!-- 추가/편집 다이얼로그 -->
    <el-dialog
      v-model="showFormDialog"
      :title="isEditMode ? 'MCP 서버 편집' : 'MCP 서버 추가'"
      width="600"
      @closed="resetForm"
    >
      <el-form :model="form" label-width="120px">
        <el-form-item label="이름">
          <el-input
            v-model="form.name"
            placeholder="서버 이름"
            :disabled="isEditMode"
          />
        </el-form-item>
        <el-form-item label="타입">
          <el-radio-group v-model="form.transportType">
            <el-radio value="stdio">STDIO</el-radio>
            <el-radio value="http">HTTP</el-radio>
            <el-radio value="sse">SSE</el-radio>
            <el-radio value="streamable-http">Streamable HTTP</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.transportType === 'stdio'" label="Command">
          <el-input v-model="form.command" placeholder="실행 명령어" />
        </el-form-item>
        <el-form-item v-if="form.transportType !== 'stdio'" label="URL">
          <el-input v-model="form.url" placeholder="http://..." />
        </el-form-item>
        <el-form-item label="Args">
          <el-input v-model="form.args" placeholder="인자 (쉼표 구분)" />
        </el-form-item>
        <el-form-item label="Env">
          <el-input
            v-model="form.env"
            type="textarea"
            :rows="3"
            placeholder="KEY=VALUE (줄바꿈 구분)"
          />
        </el-form-item>
        <el-form-item v-if="form.transportType !== 'stdio'" label="Headers">
          <el-input
            v-model="form.headers"
            type="textarea"
            :rows="3"
            placeholder="KEY=VALUE (줄바꿈 구분)"
          />
        </el-form-item>
        <el-form-item label="기본 정책">
          <el-select v-model="form.defaultPolicy" style="width: 120px">
            <el-option value="allow" label="allow" />
            <el-option value="ask" label="ask" />
            <el-option value="deny" label="deny" />
          </el-select>
        </el-form-item>
        <el-form-item label="활성화">
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-form-item label="자동 연결">
          <el-switch v-model="form.autoConnect" />
        </el-form-item>
        <el-form-item label="Timeout (sec)">
          <el-input-number v-model="form.timeoutSeconds" :min="1" :step="5" />
        </el-form-item>
        <el-form-item label="Max Retries">
          <el-input-number v-model="form.maxRetries" :min="0" :max="20" />
        </el-form-item>
      </el-form>
      <div v-if="isEditMode && formHasColdChanges" class="cold-change-warning">
        <el-alert
          title="전송 설정(Command, URL, Args, Env, Headers)이 변경되어 저장 시 재연결됩니다."
          type="info"
          show-icon
          :closable="false"
        />
      </div>
      <template #footer>
        <el-button @click="showFormDialog = false">취소</el-button>
        <el-button
          type="primary"
          :loading="formSaving"
          :disabled="!form.name"
          @click="saveForm"
        >{{ isEditMode ? '저장' : '추가' }}</el-button>
      </template>
    </el-dialog>

    <!-- 도구 정책 다이얼로그 -->
    <el-dialog
      v-model="showPolicyDialog"
      :title="`도구 정책 - ${policyServerName}`"
      width="800"
      @closed="resetPolicyDialog"
    >
      <div class="policy-toolbar">
        <el-input
          v-model="policySearch"
          placeholder="도구 검색..."
          size="small"
          clearable
          style="width: 240px"
        />
        <div class="policy-toolbar-right">
          <span class="policy-toolbar-label">전체:</span>
          <el-select
            v-model="policyBulkAction"
            size="small"
            placeholder="일괄 변경"
            style="width: 110px"
            @change="applyBulkPolicy"
          >
            <el-option value="" label="선택..." disabled />
            <el-option value="allow" label="allow" />
            <el-option value="ask" label="ask" />
            <el-option value="deny" label="deny" />
            <el-option value="default" label="default" />
          </el-select>
          <span class="policy-toolbar-label" style="margin-left: 12px">
            기본 정책: <el-tag size="small" effect="plain">{{ policyServerDefault || 'ask' }}</el-tag>
          </span>
        </div>
      </div>
      <div class="policy-table-container">
        <el-table :data="filteredPolicyTools" size="small" max-height="400" stripe>
          <el-table-column prop="name" label="도구명" min-width="260">
            <template #default="{ row }">
              <span class="mcp-info-mono">{{ row.name }}</span>
            </template>
          </el-table-column>
          <el-table-column label="정책" width="140" align="center">
            <template #default="{ row }">
              <el-select
                v-model="row.policy"
                size="small"
                style="width: 110px"
                @change="markPolicyDirty"
              >
                <el-option value="allow" label="allow" />
                <el-option value="ask" label="ask" />
                <el-option value="deny" label="deny" />
                <el-option value="default" label="default" />
              </el-select>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <template #footer>
        <el-button @click="showPolicyDialog = false">취소</el-button>
        <el-button
          type="primary"
          :loading="policySaving"
          :disabled="!policyDirty"
          @click="savePolicies"
        >저장</el-button>
      </template>
    </el-dialog>

    <!-- stderr 다이얼로그 -->
    <el-dialog v-model="showStderrDialog" :title="`stderr - ${stderrServerName}`" width="700">
      <div class="stderr-container">
        <div v-if="stderrLines.length === 0" class="stderr-empty">출력 없음</div>
        <div v-for="(line, i) in stderrLines" :key="i" class="stderr-line">{{ line }}</div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import api from '@/api/client'
import type { ApiResponse, McpServer } from './types'

// ─── State ───

const mcpServers = ref<McpServer[]>([])
const mcpLoading = ref(false)

// Form dialog (add/edit)
const showFormDialog = ref(false)
const isEditMode = ref(false)
const formSaving = ref(false)
const formOriginal = ref<McpServer | null>(null)
const form = reactive({
  name: '',
  transportType: 'stdio',
  command: '',
  url: '',
  args: '',
  env: '',
  headers: '',
  defaultPolicy: 'ask',
  enabled: true,
  autoConnect: true,
  timeoutSeconds: 30,
  maxRetries: 3
})

// Policy dialog
const showPolicyDialog = ref(false)
const policyServerName = ref('')
const policyServerDefault = ref('')
const policySearch = ref('')
const policyBulkAction = ref('')
const policyDirty = ref(false)
const policySaving = ref(false)
const policyTools = ref<{ name: string; policy: string }[]>([])

const filteredPolicyTools = computed(() => {
  if (!policySearch.value) return policyTools.value
  const q = policySearch.value.toLowerCase()
  return policyTools.value.filter(t => t.name.toLowerCase().includes(q))
})

// Stderr dialog
const showStderrDialog = ref(false)
const stderrServerName = ref('')
const stderrLines = ref<string[]>([])

// Auto-polling
let pollTimer: ReturnType<typeof setInterval> | null = null

// ─── Status helpers ───

function isTransitioning(server: McpServer): boolean {
  return server.state === 'CONNECTING' || server.state === 'RECONNECTING'
}

function statusClass(server: McpServer): string {
  if (!server.enabled) return 'status--disabled'
  switch (server.state) {
    case 'CONNECTED': return 'status--connected'
    case 'CONNECTING':
    case 'RECONNECTING': return 'status--transitioning'
    case 'FAILED': return 'status--failed'
    default: return 'status--disconnected'
  }
}

function statusLabel(server: McpServer): string {
  if (!server.enabled) return '비활성'
  if (server.state === 'RECONNECTING' && server.retryCount > 0) {
    return `RECONNECTING (${server.retryCount}/${server.maxRetries})`
  }
  return server.state
}

// ─── Notification helpers ───

function lastNotification(server: McpServer) {
  return server.authNotifications[server.authNotifications.length - 1]
}

function isAuthAction(server: McpServer): boolean {
  const n = lastNotification(server)
  if (n.code) return true
  if (!n.url) return false
  const text = (n.message + ' ' + n.url).toLowerCase()
  return /auth|login|oauth|device|verify|consent|microsoft/.test(text)
}

// ─── Policy count helpers ───

function policyCount(server: McpServer, policy: string): number {
  if (policy === 'default') {
    return server.tools.filter(t => !server.toolPolicies[t]).length
  }
  return server.tools.filter(t => server.toolPolicies[t] === policy).length
}

// ─── Load servers ───

async function loadMcpServers(): Promise<void> {
  mcpLoading.value = true
  try {
    const { data } = await api.get<ApiResponse<McpServer[]>>('/agent/api/mcp/servers')
    if (data.code === 'OK' && data.data) {
      mcpServers.value = data.data.map(s => ({
        ...s,
        headers: s.headers || {},
        toolPolicies: s.toolPolicies || {},
        authNotifications: s.authNotifications || [],
        retryCount: s.retryCount || 0
      }))
    } else {
      mcpServers.value = data.data ?? (Array.isArray(data) ? data as unknown as McpServer[] : [])
    }
    updatePolling()
  } catch {
    ElMessage.error('MCP 서버 목록 로드 실패')
  } finally {
    mcpLoading.value = false
  }
}

// ─── Auto-polling ───

function updatePolling(): void {
  const hasTransitioning = mcpServers.value.some(s => s.enabled && isTransitioning(s))
  if (hasTransitioning && !pollTimer) {
    pollTimer = setInterval(loadMcpServers, 5000)
  } else if (!hasTransitioning && pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

// ─── Connect / Disconnect ───

async function connectMcp(name: string): Promise<void> {
  try {
    const { data } = await api.post<ApiResponse>(`/agent/api/mcp/servers/connect?name=${encodeURIComponent(name)}`)
    if (data.code === 'OK') {
      ElMessage.success(`${name} 연결 요청 완료`)
      await loadMcpServers()
    } else {
      ElMessage.error(data.message || '연결 실패')
    }
  } catch {
    ElMessage.error('MCP 서버 연결에 실패했습니다.')
  }
}

async function disconnectMcp(name: string): Promise<void> {
  try {
    const { data } = await api.post<ApiResponse>(`/agent/api/mcp/servers/disconnect?name=${encodeURIComponent(name)}`)
    if (data.code === 'OK') {
      ElMessage.success(`${name} 연결 해제 완료`)
      await loadMcpServers()
    } else {
      ElMessage.error(data.message || '연결 해제 실패')
    }
  } catch {
    ElMessage.error('MCP 서버 연결 해제에 실패했습니다.')
  }
}

// ─── Delete ───

async function deleteMcpServer(name: string): Promise<void> {
  try {
    const { data } = await api.delete<ApiResponse>(`/agent/api/mcp/servers?name=${encodeURIComponent(name)}`)
    if (data.code === 'OK') {
      ElMessage.success('MCP 서버가 삭제되었습니다.')
      await loadMcpServers()
    } else {
      ElMessage.error(data.message || '삭제 실패')
    }
  } catch {
    ElMessage.error('MCP 서버 삭제에 실패했습니다.')
  }
}

// ─── Inline hot config updates ───

async function updateHotConfig(server: McpServer, field: string, value: any): Promise<void> {
  const body: Record<string, any> = {
    name: server.name,
    transportType: server.transportType,
    command: server.command,
    url: server.url,
    args: server.args,
    env: server.env,
    headers: server.headers,
    timeoutSeconds: server.timeoutSeconds,
    maxRetries: server.maxRetries,
    autoConnect: server.autoConnect,
    enabled: server.enabled,
    defaultPolicy: server.defaultPolicy,
    toolPolicies: server.toolPolicies,
    [field]: value
  }
  try {
    const { data } = await api.put<ApiResponse>('/agent/api/mcp/servers', body)
    if (data.code === 'OK') {
      await loadMcpServers()
    } else {
      ElMessage.error(data.message || '업데이트 실패')
    }
  } catch {
    ElMessage.error('설정 업데이트에 실패했습니다.')
  }
}

async function toggleEnabled(server: McpServer, enabled: boolean): Promise<void> {
  await updateHotConfig(server, 'enabled', enabled)
}

// ─── Auth notifications ───

async function clearNotifications(name: string): Promise<void> {
  try {
    await api.post<ApiResponse>(`/agent/api/mcp/servers/notifications/clear?name=${encodeURIComponent(name)}`)
    await loadMcpServers()
  } catch {
    ElMessage.error('알림 삭제 실패')
  }
}

function copyCode(code: string): void {
  navigator.clipboard.writeText(code)
  ElMessage.success('코드가 복사되었습니다.')
}

// ─── Add/Edit dialog ───

function openAddDialog(): void {
  isEditMode.value = false
  formOriginal.value = null
  showFormDialog.value = true
}

function openEditDialog(server: McpServer): void {
  isEditMode.value = true
  formOriginal.value = server
  form.name = server.name
  form.transportType = server.transportType
  form.command = server.command || ''
  form.url = server.url || ''
  form.args = server.args.join(', ')
  form.env = Object.entries(server.env).map(([k, v]) => `${k}=${v}`).join('\n')
  form.headers = Object.entries(server.headers).map(([k, v]) => `${k}=${v}`).join('\n')
  form.defaultPolicy = server.defaultPolicy || 'ask'
  form.enabled = server.enabled
  form.autoConnect = server.autoConnect
  form.timeoutSeconds = server.timeoutSeconds
  form.maxRetries = server.maxRetries
  showFormDialog.value = true
}

const formHasColdChanges = computed(() => {
  if (!isEditMode.value || !formOriginal.value) return false
  const orig = formOriginal.value
  if (form.transportType !== orig.transportType) return true
  if (form.command !== (orig.command || '')) return true
  if (form.url !== (orig.url || '')) return true
  if (form.args !== orig.args.join(', ')) return true
  const origEnv = Object.entries(orig.env).map(([k, v]) => `${k}=${v}`).join('\n')
  if (form.env !== origEnv) return true
  const origHeaders = Object.entries(orig.headers).map(([k, v]) => `${k}=${v}`).join('\n')
  if (form.headers !== origHeaders) return true
  return false
})

function resetForm(): void {
  form.name = ''
  form.transportType = 'stdio'
  form.command = ''
  form.url = ''
  form.args = ''
  form.env = ''
  form.headers = ''
  form.defaultPolicy = 'ask'
  form.enabled = true
  form.autoConnect = true
  form.timeoutSeconds = 30
  form.maxRetries = 3
  formOriginal.value = null
}

function buildRequestBody(): Record<string, any> {
  const body: Record<string, any> = {
    name: form.name,
    transportType: form.transportType,
    timeoutSeconds: form.timeoutSeconds,
    maxRetries: form.maxRetries,
    autoConnect: form.autoConnect,
    enabled: form.enabled,
    defaultPolicy: form.defaultPolicy
  }
  if (form.transportType === 'stdio') {
    body.command = form.command
  } else {
    body.url = form.url
  }
  if (form.args) {
    body.args = form.args.split(',').map((s: string) => s.trim()).filter(Boolean)
  }
  if (form.env) {
    body.env = parseKeyValue(form.env)
  }
  if (form.headers && form.transportType !== 'stdio') {
    body.headers = parseKeyValue(form.headers)
  }
  // Preserve existing toolPolicies when editing
  if (isEditMode.value && formOriginal.value) {
    body.toolPolicies = formOriginal.value.toolPolicies
  }
  return body
}

function parseKeyValue(text: string): Record<string, string> {
  const result: Record<string, string> = {}
  text.split('\n').forEach((line: string) => {
    const idx = line.indexOf('=')
    if (idx > 0) {
      result[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
    }
  })
  return result
}

async function saveForm(): Promise<void> {
  if (!form.name) {
    ElMessage.warning('서버 이름을 입력해주세요.')
    return
  }
  formSaving.value = true
  try {
    const body = buildRequestBody()
    const endpoint = isEditMode.value ? 'put' : 'post'
    const { data } = await api[endpoint]<ApiResponse>('/agent/api/mcp/servers', body)
    if (data.code === 'OK') {
      ElMessage.success(isEditMode.value ? '서버 설정이 저장되었습니다.' : 'MCP 서버가 추가되었습니다.')
      showFormDialog.value = false
      await loadMcpServers()
    } else {
      ElMessage.error(data.message || '저장 실패')
    }
  } catch {
    ElMessage.error('MCP 서버 저장에 실패했습니다.')
  } finally {
    formSaving.value = false
  }
}

// ─── Policy dialog ───

function openPolicyDialog(server: McpServer): void {
  policyServerName.value = server.name
  policyServerDefault.value = server.defaultPolicy || 'ask'
  policySearch.value = ''
  policyBulkAction.value = ''
  policyDirty.value = false
  policyTools.value = server.tools.map(t => ({
    name: t,
    policy: server.toolPolicies[t] || 'default'
  }))
  showPolicyDialog.value = true
}

function markPolicyDirty(): void {
  policyDirty.value = true
}

function applyBulkPolicy(val: string): void {
  if (!val) return
  const targets = filteredPolicyTools.value
  targets.forEach(t => { t.policy = val })
  policyDirty.value = true
  policyBulkAction.value = ''
}

function resetPolicyDialog(): void {
  policyTools.value = []
  policySearch.value = ''
  policyDirty.value = false
}

async function savePolicies(): Promise<void> {
  policySaving.value = true
  try {
    const server = mcpServers.value.find(s => s.name === policyServerName.value)
    if (!server) return

    const newPolicies: Record<string, string> = {}
    policyTools.value.forEach(t => {
      if (t.policy !== 'default') {
        newPolicies[t.name] = t.policy
      }
    })

    const body: Record<string, any> = {
      name: server.name,
      transportType: server.transportType,
      command: server.command,
      url: server.url,
      args: server.args,
      env: server.env,
      headers: server.headers,
      timeoutSeconds: server.timeoutSeconds,
      maxRetries: server.maxRetries,
      autoConnect: server.autoConnect,
      enabled: server.enabled,
      defaultPolicy: server.defaultPolicy,
      toolPolicies: newPolicies
    }

    const { data } = await api.put<ApiResponse>('/agent/api/mcp/servers', body)
    if (data.code === 'OK') {
      ElMessage.success('도구 정책이 저장되었습니다.')
      showPolicyDialog.value = false
      await loadMcpServers()
    } else {
      ElMessage.error(data.message || '정책 저장 실패')
    }
  } catch {
    ElMessage.error('도구 정책 저장에 실패했습니다.')
  } finally {
    policySaving.value = false
  }
}

// ─── Stderr ───

async function viewMcpStderr(name: string): Promise<void> {
  stderrServerName.value = name
  stderrLines.value = []
  showStderrDialog.value = true
  try {
    const { data } = await api.get<ApiResponse<string[]>>(`/agent/api/mcp/servers/stderr?name=${encodeURIComponent(name)}`)
    if (data.code === 'OK' && data.data) {
      stderrLines.value = data.data
    } else if (Array.isArray(data)) {
      stderrLines.value = data
    }
  } catch {
    ElMessage.error('stderr 로드 실패')
  }
}

// ─── Lifecycle ───

onMounted(() => {
  loadMcpServers()
})

onUnmounted(() => {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
})

defineExpose({ loadMcpServers })
</script>

<style scoped>
.tab-toolbar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  margin-bottom: 12px;
}

.mcp-cards {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.mcp-card {
  box-sizing: border-box;
  transition: opacity 0.2s;
}

.mcp-card--disabled {
  opacity: 0.55;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.mcp-card-title {
  display: flex;
  align-items: center;
  gap: 10px;
}

.mcp-name {
  font-weight: 600;
  font-size: 15px;
}

.mcp-status {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  font-weight: 500;
}

.mcp-status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}

.status--connected .mcp-status-dot { background: var(--el-color-success); }
.status--connected { color: var(--el-color-success); }

.status--disconnected .mcp-status-dot { background: var(--el-text-color-placeholder); }
.status--disconnected { color: var(--el-text-color-placeholder); }

.status--failed .mcp-status-dot { background: var(--el-color-danger); }
.status--failed { color: var(--el-color-danger); }

.status--transitioning { color: var(--el-color-warning); }
.status--disabled { color: var(--el-text-color-placeholder); }
.status--disabled .mcp-status-dot { background: var(--el-text-color-placeholder); }

.mcp-status-spinner {
  width: 10px;
  height: 10px;
  border: 2px solid var(--el-color-warning-light-5);
  border-top-color: var(--el-color-warning);
  border-radius: 50%;
  display: inline-block;
  animation: mcp-spin 0.8s linear infinite;
}

@keyframes mcp-spin {
  to { transform: rotate(360deg); }
}

.mcp-card-actions {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
  align-items: center;
}

.mcp-alert {
  margin-bottom: 12px;
}

.auth-notification {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.auth-notification-actions {
  display: flex;
  gap: 6px;
  align-items: center;
}

.mcp-info {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 12px;
}

.mcp-info-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.mcp-info-label {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  flex-shrink: 0;
}

.mcp-info-value {
  font-size: 13px;
}

.mcp-info-mono {
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  font-size: 12px;
  background: var(--el-fill-color-light);
  padding: 2px 6px;
  border-radius: 3px;
  word-break: break-all;
}

.mcp-tools-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding-top: 8px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.mcp-tools-badges {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.mcp-tools-count {
  font-size: 13px;
  font-weight: 500;
  margin-right: 4px;
}

.mcp-no-tools {
  color: var(--el-text-color-placeholder);
  font-size: 13px;
  padding-top: 8px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.cold-change-warning {
  margin-top: 12px;
}

/* Policy dialog */
.policy-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.policy-toolbar-right {
  display: flex;
  align-items: center;
  gap: 6px;
}

.policy-toolbar-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.policy-table-container {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
}

/* Stderr */
.stderr-container {
  background: #0d1117;
  border: 1px solid #30363d;
  border-radius: 6px;
  padding: 12px;
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.5;
  max-height: 400px;
  overflow-y: auto;
  color: #c9d1d9;
}

.stderr-empty {
  color: #484f58;
  text-align: center;
  padding: 20px;
}

.stderr-line {
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
