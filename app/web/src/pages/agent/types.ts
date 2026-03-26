export interface ApiResponse<T = any> {
  code: 'OK' | 'FAIL'
  message?: string
  data?: T
}

export interface Session {
  id: string
  channelId: string
  title: string
  summary: string | null
  compactionCount: number
  toolApprovals: string | null
  llmModel: string | null
  active: boolean
  createdAt: string
  updatedAt: string
}

export interface Message {
  id: string
  sessionId: string
  role: string
  content: string
  toolCallId: string | null
  toolCalls: string | null
  model: string | null
  createdAt: string
}

export interface Task {
  id: string
  title: string
  description: string
  originalRequest: string | null
  status: string
  source: string
  progress: string | null
  retryCount: number
  completedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface Job {
  id: string
  name: string
  channelId: string
  scheduleType: string
  scheduleValue: string
  timezone: string | null
  executionType: string
  payload: string | null
  enabled: boolean
  nextRunAt: string | null
  lastRunAt: string | null
  lastRunStatus: string | null
  lastError: string | null
  consecutiveErrors: number
  createdAt: string
  updatedAt: string
}

export interface ActivityEntry {
  id: string
  activityType: string
  jobName: string
  channelId: string
  executionType: string
  status: string
  resultText: string | null
  toolsUsed: string | null
  errorMessage: string | null
  totalTokens: number | null
  createdAt: string
}

export interface ActivityPage {
  content: ActivityEntry[]
  totalElements: number
  totalPages: number
}

export interface RoutineHistoryEntry {
  id: string
  completedAt: string
  title: string
  summary: string | null
  status: string
  createdAt: string
}

export interface RoutineHistoryPage {
  content: RoutineHistoryEntry[]
  totalElements: number
  totalPages: number
}

export interface McpServer {
  name: string
  transportType: string
  state: string
  connected: boolean
  enabled: boolean
  autoConnect: boolean
  toolCount: number
  tools: string[]
  command: string | null
  url: string | null
  args: string[]
  env: Record<string, string>
  headers: Record<string, string>
  timeoutSeconds: number
  maxRetries: number
  retryCount: number
  error: string | null
  defaultPolicy: string | null
  toolPolicies: Record<string, string>
  authNotifications: AuthNotification[]
}

export interface AuthNotification {
  timestamp: string
  message: string
  url: string | null
  code: string | null
}

export interface Skill {
  name: string
  description: string
  active: boolean
  gatingStatus: string
  gatingReason: string | null
  tools: string[] | null
  mcpServer: string | null
  os: string[] | null
}

export interface SessionDetail {
  id: string
  channelId: string
  title: string
  summary: string | null
  compactionCount: number
  toolApprovals: string | null
  llmModel: string | null
  llmProvider: string
  defaultModel?: string
  active: boolean
  createdAt: string
  updatedAt: string
  messageCount: number
}

export interface AgentConfig {
  provider: string
  session: { maxMessages: number; maxTokens: number; [k: string]: any }
  tools: { enabled: boolean; [k: string]: any }
  execution: Record<string, any>
  file: Record<string, any>
  search: Record<string, any>
  browser: Record<string, any>
  subAgent: Record<string, any>
  cron: Record<string, any>
  routine: Record<string, any>
  suggest: Record<string, any>
  task: Record<string, any>
  [k: string]: any
}

export function formatDate(dateStr: string): string {
  if (!dateStr) return '-'
  try {
    const d = new Date(dateStr)
    return d.toLocaleString('ko-KR', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit'
    })
  } catch {
    return dateStr
  }
}

export function truncate(text: string | null | undefined, max: number): string {
  if (!text) return ''
  return text.length > max ? text.substring(0, max) + '...' : text
}
