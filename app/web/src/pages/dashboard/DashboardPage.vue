<template>
  <div v-loading="loading">
    <!-- 상태 카드 영역 -->
    <el-row :gutter="16" class="status-section">
      <el-col :xs="12" :sm="8" :md="6" v-for="item in statusCards" :key="item.title">
        <el-card shadow="hover" class="status-card" @click="navigateToSetup(item.section)">
          <div class="status-body">
            <el-icon :size="24" :color="item.color">
              <component :is="item.icon" />
            </el-icon>
            <div>
              <div class="status-title">{{ item.title }}</div>
              <div class="status-detail" :style="{ color: item.color }">
                {{ item.detail }}
              </div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- Doctor 요약 (실패 항목 있을 때만) -->
    <el-alert
      v-if="doctorFailed > 0"
      :title="`${doctorFailed}개의 설정 문제가 발견되었습니다`"
      type="warning"
      show-icon
      class="doctor-alert"
      :closable="false"
    >
      <template #default>
        <el-button type="warning" link @click="navigate('/setup#doctor')">진단 결과 보기</el-button>
      </template>
    </el-alert>

    <!-- 기능 링크 영역 -->
    <h3 class="section-title">기능</h3>
    <el-row :gutter="16">
      <el-col :xs="24" :sm="12" :md="8" v-for="feature in features" :key="feature.title">
        <el-card shadow="hover" class="feature-card" @click="navigate(feature.route)">
          <div class="card-body">
            <el-icon :size="28" color="var(--el-color-primary)">
              <component :is="feature.icon" />
            </el-icon>
            <div>
              <div class="card-title">{{ feature.title }}</div>
              <div class="card-desc">{{ feature.description }}</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, type Component } from 'vue'
import { useRouter } from 'vue-router'
import { Cpu, ChatDotRound, Promotion, Coin, Search, Setting } from '@element-plus/icons-vue'
import api from '../../api/client'

const router = useRouter()
const loading = ref(false)

interface MessengerStatus {
  configured: boolean
  connected: boolean
  displayName: string | null
}

interface ServiceStatus {
  configured: boolean
  connected: boolean
  displayName: string | null
}

interface DoctorSummary {
  passed: number
  failed: number
  warned: number
}

interface SystemStatus {
  discord: MessengerStatus
  telegram: MessengerStatus
  llm: ServiceStatus
  embedding: ServiceStatus
  searxng: ServiceStatus
  serviceRegistered: boolean
  doctor: DoctorSummary
}

const status = ref<SystemStatus | null>(null)
const doctorFailed = computed(() => status.value?.doctor?.failed ?? 0)

const COLOR_OK = 'var(--el-color-success)'
const COLOR_ERROR = 'var(--el-color-danger)'
const COLOR_NONE = 'var(--el-text-color-disabled)'

interface StatusCard {
  title: string
  detail: string
  color: string
  icon: Component
  section: string
}

const statusCards = computed<StatusCard[]>(() => {
  if (!status.value) return []
  const s = status.value
  return [
    {
      title: 'Discord',
      ...resolveMessengerCard(s.discord),
      icon: ChatDotRound,
      section: 'messenger'
    },
    {
      title: 'Telegram',
      ...resolveMessengerCard(s.telegram),
      icon: Promotion,
      section: 'messenger'
    },
    {
      title: 'LLM',
      ...resolveServiceCard(s.llm),
      icon: Cpu,
      section: 'llm'
    },
    {
      title: '임베딩',
      ...resolveServiceCard(s.embedding),
      icon: Coin,
      section: 'embedding'
    },
    {
      title: 'SearXNG',
      ...resolveServiceCard(s.searxng),
      icon: Search,
      section: 'agent-setup'
    }
  ]
})

function resolveMessengerCard(m: MessengerStatus): { detail: string; color: string } {
  if (!m.configured) return { detail: '미설정', color: COLOR_NONE }
  if (m.connected) return { detail: `연결됨${m.displayName ? ' (' + m.displayName + ')' : ''}`, color: COLOR_OK }
  return { detail: '연결 끊김', color: COLOR_ERROR }
}

function resolveServiceCard(s: ServiceStatus): { detail: string; color: string } {
  if (!s.configured) return { detail: '미설정', color: COLOR_NONE }
  if (s.connected) return { detail: s.displayName || '정상', color: COLOR_OK }
  return { detail: '연결 실패', color: COLOR_ERROR }
}

const features = [
  { title: '설정', description: '시스템 설정 관리', icon: Setting, route: '/setup' },
  { title: 'Agent', description: 'AI Agent 관리', icon: Cpu, route: '/agent' }
]

function navigate(route: string) {
  router.push(route)
}

function navigateToSetup(section: string) {
  router.push(`/setup#${section}`)
}

async function loadStatus() {
  loading.value = true
  try {
    const { data } = await api.get('/api/setup/status')
    if (data.data) {
      status.value = data.data
    }
  } catch {
    // 실패 시 카드 미표시
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadStatus()
})
</script>

<style scoped>
.status-section {
  margin-bottom: 24px;
}

.status-card {
  cursor: pointer;
  margin-bottom: 12px;
}

.status-body {
  display: flex;
  align-items: center;
  gap: 12px;
}

.status-title {
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 2px;
}

.status-detail {
  font-size: 12px;
}

.doctor-alert {
  margin-bottom: 24px;
}

.section-title {
  margin: 0 0 16px;
  font-size: 16px;
  color: var(--el-text-color-primary);
}

.feature-card {
  cursor: pointer;
  margin-bottom: 16px;
}

.card-body {
  display: flex;
  align-items: center;
  gap: 16px;
}

.card-title {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 2px;
}

.card-desc {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
