export interface DiscordConfig {
  enabled: boolean
  token: string | null
  tokenSet: boolean
  serverName: string
}

export interface TelegramConfig {
  enabled: boolean
  token: string | null
  tokenSet: boolean
  botUsername: string
  channelMappings: Record<string, string>
}

export interface LlmConfig {
  provider: string
  apiKey: string | null
  apiKeySet: boolean
  baseUrl: string
  model: string
  cliPath: string
}

export interface EmbeddingConfig {
  provider: string
  apiKey: string | null
  apiKeySet: boolean
  baseUrl: string
  model: string
  dimensions: number
}

export interface AgentConfig {
  channelName: string
  dataDir: string
}

export interface NotificationConfig {
  channel: string
}

export interface SearxngConfig {
  url: string
}

export interface AuthConfig {
  enabled: boolean
}

export interface SetupConfigData {
  discord: DiscordConfig
  telegram: TelegramConfig
  llm: LlmConfig
  embedding: EmbeddingConfig
  agent: AgentConfig
  notification: NotificationConfig
  searxng: SearxngConfig
  auth: AuthConfig
}

export interface SectionExpose {
  getData: () => Record<string, unknown>
  hasChanges: () => boolean
}
