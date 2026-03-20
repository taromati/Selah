<template>
  <div v-loading="loading">
    <el-tabs v-model="activeTab">
      <el-tab-pane label="메신저" name="messenger">
        <MessengerSection
          v-if="loadedTabs.has('messenger')"
          ref="messengerRef"
          :initial-data="configData"
          @change="onSectionChange"
        />
      </el-tab-pane>

      <el-tab-pane label="LLM" name="llm">
        <LlmSection
          v-if="loadedTabs.has('llm')"
          ref="llmRef"
          :initial-data="configData"
          @change="onSectionChange"
        />
      </el-tab-pane>

      <el-tab-pane label="임베딩" name="embedding">
        <EmbeddingSection
          v-if="loadedTabs.has('embedding')"
          ref="embeddingRef"
          :initial-data="configData"
          @change="onSectionChange"
        />
      </el-tab-pane>

      <el-tab-pane label="Agent" name="agent-setup">
        <AgentSetupSection
          v-if="loadedTabs.has('agent-setup')"
          ref="agentRef"
          :initial-data="configData"
          @change="onSectionChange"
        />
      </el-tab-pane>

      <el-tab-pane label="보안" name="auth">
        <div v-if="loadedTabs.has('auth')" class="auth-section">
          <el-card shadow="never">
            <div class="auth-row">
              <div class="auth-label">
                <h4>웹 인증</h4>
                <p>웹 UI 접근 시 메신저(Discord/Telegram)에서 승인이 필요합니다.</p>
              </div>
              <el-switch
                v-model="authEnabled"
                @change="onSectionChange"
              />
            </div>
          </el-card>
        </div>
      </el-tab-pane>

      <el-tab-pane label="진단" name="doctor">
        <DoctorSection v-if="loadedTabs.has('doctor')" ref="doctorRef" />
      </el-tab-pane>
    </el-tabs>

    <!-- 하단 고정 저장 바 -->
    <div class="save-bar" v-if="hasChanges">
      <el-alert type="warning" :closable="false" show-icon>
        변경 사항이 있습니다. 저장 후 서버 재시작이 필요합니다.
      </el-alert>
      <el-button type="primary" :loading="saving" @click="saveAll">
        설정 저장
      </el-button>
    </div>

    <RestartDialog
      v-model:visible="showRestartDialog"
      :service-registered="serviceRegistered"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import { useTabRoute } from '../../composables/useTabRoute'
import api from '../../api/client'
import MessengerSection from './MessengerSection.vue'
import LlmSection from './LlmSection.vue'
import EmbeddingSection from './EmbeddingSection.vue'
import AgentSetupSection from './AgentSetupSection.vue'
import DoctorSection from './DoctorSection.vue'
import RestartDialog from './RestartDialog.vue'
import type { SetupConfigData, SectionExpose } from './types'

const validTabs = ['messenger', 'llm', 'embedding', 'agent-setup', 'auth', 'doctor']
const { activeTab } = useTabRoute('messenger', validTabs)
const loadedTabs = ref(new Set<string>())

const messengerRef = ref<SectionExpose | null>(null)
const llmRef = ref<SectionExpose | null>(null)
const embeddingRef = ref<SectionExpose | null>(null)
const agentRef = ref<SectionExpose | null>(null)
const doctorRef = ref<unknown>(null)

const loading = ref(false)
const saving = ref(false)
const hasChanges = ref(false)
const showRestartDialog = ref(false)
const serviceRegistered = ref(false)

const configData = ref<SetupConfigData | null>(null)
const authEnabled = ref(false)

function onSectionChange() {
  hasChanges.value = true
}

async function loadConfig() {
  loading.value = true
  try {
    const { data } = await api.get('/api/setup/config')
    if (data.data) {
      configData.value = data.data
      authEnabled.value = data.data.auth?.enabled ?? false
    }
  } catch {
    ElMessage.error('설정을 불러오는데 실패했습니다')
  } finally {
    loading.value = false
  }
}

async function saveAll() {
  saving.value = true
  try {
    const payload: Record<string, unknown> = {}

    if (messengerRef.value) {
      const messengerData = messengerRef.value.getData() as Record<string, Record<string, unknown>>
      payload.discord = messengerData.discord ?? { enabled: false }
      payload.telegram = messengerData.telegram ?? { enabled: false }
    } else if (configData.value) {
      payload.discord = configData.value.discord.enabled
        ? { enabled: true, token: null, serverName: configData.value.discord.serverName }
        : { enabled: false }
      payload.telegram = configData.value.telegram.enabled
        ? { enabled: true, token: null, botUsername: configData.value.telegram.botUsername, channelMappings: configData.value.telegram.channelMappings }
        : { enabled: false }
    }

    if (llmRef.value) {
      payload.llm = llmRef.value.getData()
    } else if (configData.value) {
      payload.llm = { provider: configData.value.llm.provider, apiKey: null, baseUrl: configData.value.llm.baseUrl, model: configData.value.llm.model, cliPath: configData.value.llm.cliPath }
    }

    if (embeddingRef.value) {
      payload.embedding = embeddingRef.value.getData()
    } else if (configData.value) {
      payload.embedding = { provider: configData.value.embedding.provider, apiKey: null, baseUrl: configData.value.embedding.baseUrl, model: configData.value.embedding.model, dimensions: configData.value.embedding.dimensions }
    }

    if (agentRef.value) {
      const agentData = agentRef.value.getData()
      payload.agent = agentData.agent
      payload.notification = agentData.notification
      payload.searxng = agentData.searxng
    } else if (configData.value) {
      payload.agent = configData.value.agent
      payload.notification = configData.value.notification
      payload.searxng = configData.value.searxng
    }

    payload.auth = { enabled: authEnabled.value }

    const { data } = await api.post('/api/setup/config', payload)
    if (data.data) {
      hasChanges.value = false
      serviceRegistered.value = data.data.serviceRegistered ?? false
      showRestartDialog.value = true
    }
  } catch (err: unknown) {
    const message = (err as { response?: { data?: { message?: string } } })?.response?.data?.message || '설정 저장에 실패했습니다'
    ElMessage.error(message)
  } finally {
    saving.value = false
  }
}

function loadTab(tab: string) {
  if (!loadedTabs.value.has(tab)) {
    loadedTabs.value.add(tab)
  }
}

watch(activeTab, (tab) => {
  loadTab(tab)
})

onMounted(async () => {
  await loadConfig()
  loadTab(activeTab.value)
  await nextTick()
})
</script>

<style scoped>
.save-bar {
  position: sticky;
  bottom: 0;
  background: var(--el-bg-color);
  padding: 16px 0;
  display: flex;
  align-items: center;
  gap: 16px;
  border-top: 1px solid var(--el-border-color-light);
  z-index: 10;
}

.save-bar .el-alert {
  flex: 1;
}

.auth-section {
  max-width: 560px;
}

.auth-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
}

.auth-label h4 {
  margin: 0 0 6px;
  font-size: 15px;
}

.auth-label p {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.5;
}
</style>
