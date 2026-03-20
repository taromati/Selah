<template>
  <div class="login-container">
    <el-card class="login-card" shadow="always">
      <template #header>
        <div class="login-header">
          <h2>Selah</h2>
          <p>메신저에서 승인이 필요합니다.</p>
        </div>
      </template>

      <!-- Honeypot -->
      <div class="hp-field" aria-hidden="true">
        <label for="website">Website</label>
        <input type="text" id="website" ref="honeypotRef" autocomplete="off" tabindex="-1">
      </div>

      <el-button
        type="primary"
        size="large"
        :loading="loading"
        :disabled="polling"
        style="width: 100%"
        @click="requestLogin"
      >
        {{ polling ? '메신저에서 승인해주세요...' : '로그인 요청' }}
      </el-button>

      <el-result
        v-if="statusMessage"
        :icon="statusIcon"
        :title="statusMessage"
        style="padding: 16px 0 0"
      />

      <el-button
        v-if="showRetry"
        style="width: 100%; margin-top: 12px"
        @click="resetAndRetry"
      >
        다시 시도
      </el-button>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuth } from '../../composables/useAuth'
import api from '../../api/client'

const route = useRoute()
const router = useRouter()
const { setAuthenticated } = useAuth()

const loading = ref(false)
const polling = ref(false)
const statusMessage = ref('')
const statusType = ref<'success' | 'error' | 'warning' | ''>('')
const showRetry = ref(false)
const honeypotRef = ref<HTMLInputElement>()

const statusIcon = computed(() => {
  switch (statusType.value) {
    case 'success': return 'success'
    case 'error': return 'error'
    case 'warning': return 'warning'
    default: return 'info'
  }
})

const pageLoadTs = Date.now()
let challengeToken = ''
let currentToken = ''
let pollTimer: ReturnType<typeof setInterval> | null = null

async function fetchChallenge() {
  try {
    const { data } = await api.get('/api/auth/challenge')
    if (data.data?.token) {
      challengeToken = data.data.token
    }
  } catch {
    // 무시
  }
}

function collectFingerprint() {
  const fp: Record<string, unknown> = {}

  try {
    const canvas = document.createElement('canvas')
    canvas.width = 200
    canvas.height = 50
    const ctx = canvas.getContext('2d')!
    ctx.textBaseline = 'top'
    ctx.font = '14px Arial'
    ctx.fillStyle = '#f60'
    ctx.fillRect(0, 0, 200, 50)
    ctx.fillStyle = '#069'
    ctx.fillText('Selah FP', 2, 15)
    ctx.fillStyle = 'rgba(102,204,0,0.7)'
    ctx.fillText('Selah FP', 4, 17)
    const data = canvas.toDataURL()
    let hash = 0
    for (let i = 0; i < data.length; i++) {
      hash = ((hash << 5) - hash) + data.charCodeAt(i)
      hash |= 0
    }
    fp.canvasFingerprint = hash.toString(16)
  } catch { fp.canvasFingerprint = null }

  fp.screenResolution = `${screen.width}x${screen.height}`

  try {
    fp.timezone = Intl.DateTimeFormat().resolvedOptions().timeZone
  } catch { fp.timezone = null }

  fp.platform = navigator.platform || null

  try {
    const gl = document.createElement('canvas').getContext('webgl')
    if (gl) {
      const ext = gl.getExtension('WEBGL_debug_renderer_info')
      fp.webglRenderer = ext ? gl.getParameter(ext.UNMASKED_RENDERER_WEBGL) : null
    }
  } catch { fp.webglRenderer = null }

  fp.touchCapable = ('ontouchstart' in window) || (navigator.maxTouchPoints > 0)

  return fp
}

async function requestLogin() {
  loading.value = true
  showRetry.value = false
  statusMessage.value = ''

  const fp = collectFingerprint()
  const honeypotValue = honeypotRef.value?.value || ''

  try {
    const { data } = await api.post('/api/auth/request', {
      ...fp,
      challengeToken,
      honeypotValue,
      pageLoadTimestamp: pageLoadTs
    })

    if (data.code === 'OK' && data.data?.token) {
      currentToken = data.data.token
      loading.value = false
      polling.value = true
      statusMessage.value = ''
      startPolling()
    } else {
      statusMessage.value = data.message || '요청 실패'
      statusType.value = 'error'
      showRetry.value = true
      loading.value = false
    }
  } catch {
    statusMessage.value = '네트워크 오류'
    statusType.value = 'error'
    showRetry.value = true
    loading.value = false
  }
}

function startPolling() {
  pollTimer = setInterval(async () => {
    try {
      const { data } = await api.get('/api/auth/status', {
        params: { token: currentToken }
      })

      if (!data.data) return
      const status = data.data.status

      if (status === 'approved') {
        stopPolling()
        statusMessage.value = '승인됨! 이동 중...'
        statusType.value = 'success'
        setAuthenticated(true)
        const redirect = (route.query.redirect as string) || '/'
        setTimeout(() => router.push(redirect), 500)
      } else if (status === 'denied') {
        stopPolling()
        statusMessage.value = '거부되었습니다.'
        statusType.value = 'error'
        showRetry.value = true
      } else if (status === 'expired' || status === 'not_found') {
        stopPolling()
        statusMessage.value = '요청이 만료되었습니다.'
        statusType.value = 'warning'
        showRetry.value = true
      }
    } catch {
      // 네트워크 에러 시 폴링 계속
    }
  }, 2000)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
  polling.value = false
}

function resetAndRetry() {
  stopPolling()
  statusMessage.value = ''
  showRetry.value = false
  fetchChallenge()
}

fetchChallenge()

onUnmounted(() => {
  stopPolling()
})
</script>

<style scoped>
.login-container {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  background-color: var(--el-bg-color-page);
}

.login-card {
  max-width: 400px;
  width: 100%;
}

.login-header {
  text-align: center;
}

.login-header h2 {
  margin: 0 0 4px;
  font-size: 24px;
}

.login-header p {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 14px;
}

.hp-field {
  position: absolute;
  left: -9999px;
  opacity: 0;
  height: 0;
  width: 0;
  overflow: hidden;
}
</style>
