<template>
  <div class="profile-container" v-loading="loading">
    <el-collapse v-model="openSections">
      <el-collapse-item title="PERSONA.md" name="persona">
        <el-input
          v-model="personaContent"
          type="textarea"
          :autosize="{ minRows: 6, maxRows: 30 }"
          placeholder="PERSONA.md 내용이 없습니다."
        />
        <div class="section-footer">
          <el-button type="primary" :loading="personaSaving" @click="savePersona">저장</el-button>
        </div>
      </el-collapse-item>

      <el-collapse-item title="GUIDE.md" name="guide">
        <el-input
          v-model="guideContent"
          type="textarea"
          :autosize="{ minRows: 6, maxRows: 30 }"
          placeholder="GUIDE.md 내용이 없습니다."
        />
        <div class="section-footer">
          <el-button type="primary" :loading="guideSaving" @click="saveGuide">저장</el-button>
        </div>
      </el-collapse-item>

      <el-collapse-item title="TOOLS.md" name="tools">
        <el-input
          v-model="toolsContent"
          type="textarea"
          :autosize="{ minRows: 6, maxRows: 30 }"
          placeholder="TOOLS.md 내용이 없습니다."
        />
        <div class="section-footer">
          <el-button type="primary" :loading="toolsSaving" @click="saveTools">저장</el-button>
        </div>
      </el-collapse-item>

      <el-collapse-item title="MEMORY.md" name="memory">
        <el-input
          v-model="memoryContent"
          type="textarea"
          :autosize="{ minRows: 6, maxRows: 30 }"
          placeholder="MEMORY.md 내용이 없습니다."
        />
        <div class="section-footer">
          <el-button type="primary" :loading="memorySaving" @click="saveMemory">저장</el-button>
        </div>
      </el-collapse-item>

      <el-collapse-item title="USER.md" name="user">
        <el-input
          v-model="userContent"
          type="textarea"
          :autosize="{ minRows: 6, maxRows: 30 }"
          placeholder="USER.md 내용이 없습니다. 회고를 통해 자동 축적됩니다."
        />
        <div class="section-footer">
          <el-button type="primary" :loading="userSaving" @click="saveUser">저장</el-button>
        </div>
      </el-collapse-item>
    </el-collapse>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '@/api/client'
import type { ApiResponse } from './types'

const loading = ref(false)
const openSections = ref<string[]>(['persona', 'guide', 'tools', 'memory', 'user'])

const personaContent = ref('')
const personaSaving = ref(false)

const guideContent = ref('')
const guideSaving = ref(false)

const toolsContent = ref('')
const toolsSaving = ref(false)

const memoryContent = ref('')
const memorySaving = ref(false)

const userContent = ref('')
const userSaving = ref(false)

async function loadProfile(): Promise<void> {
  loading.value = true
  try {
    const [personaRes, guideRes, toolsRes, memoryRes, userRes] = await Promise.all([
      api.get<ApiResponse<{ content: string }>>('/agent/api/persona/file'),
      api.get<ApiResponse<{ content: string }>>('/agent/api/guide'),
      api.get<ApiResponse<{ content: string }>>('/agent/api/tools-md'),
      api.get<ApiResponse<{ content: string }>>('/agent/api/memory-md'),
      api.get<ApiResponse<{ content: string }>>('/agent/api/user-md')
    ])
    personaContent.value = personaRes.data.data?.content ?? ''
    guideContent.value = guideRes.data.data?.content ?? ''
    toolsContent.value = toolsRes.data.data?.content ?? ''
    memoryContent.value = memoryRes.data.data?.content ?? ''
    userContent.value = userRes.data.data?.content ?? ''
  } catch {
    ElMessage.error('프로필 로드 실패')
  } finally {
    loading.value = false
  }
}

async function savePersona(): Promise<void> {
  personaSaving.value = true
  try {
    const { data } = await api.put<ApiResponse>('/agent/api/persona/file', { content: personaContent.value })
    if (data.code === 'OK') {
      ElMessage.success('PERSONA.md 저장 완료')
    } else {
      ElMessage.error(data.message || 'PERSONA.md 저장 실패')
    }
  } catch {
    ElMessage.error('PERSONA.md 저장에 실패했습니다.')
  } finally {
    personaSaving.value = false
  }
}

async function saveGuide(): Promise<void> {
  guideSaving.value = true
  try {
    const { data } = await api.put<ApiResponse>('/agent/api/guide', { content: guideContent.value })
    if (data.code === 'OK') {
      ElMessage.success('GUIDE.md 저장 완료')
    } else {
      ElMessage.error(data.message || 'GUIDE.md 저장 실패')
    }
  } catch {
    ElMessage.error('GUIDE.md 저장에 실패했습니다.')
  } finally {
    guideSaving.value = false
  }
}

async function saveTools(): Promise<void> {
  toolsSaving.value = true
  try {
    const { data } = await api.put<ApiResponse>('/agent/api/tools-md', { content: toolsContent.value })
    if (data.code === 'OK') {
      ElMessage.success('TOOLS.md 저장 완료')
    } else {
      ElMessage.error(data.message || 'TOOLS.md 저장 실패')
    }
  } catch {
    ElMessage.error('TOOLS.md 저장에 실패했습니다.')
  } finally {
    toolsSaving.value = false
  }
}

async function saveMemory(): Promise<void> {
  memorySaving.value = true
  try {
    const { data } = await api.put<ApiResponse>('/agent/api/memory-md', { content: memoryContent.value })
    if (data.code === 'OK') {
      ElMessage.success('MEMORY.md 저장 완료')
    } else {
      ElMessage.error(data.message || 'MEMORY.md 저장 실패')
    }
  } catch {
    ElMessage.error('MEMORY.md 저장에 실패했습니다.')
  } finally {
    memorySaving.value = false
  }
}

async function saveUser(): Promise<void> {
  userSaving.value = true
  try {
    const { data } = await api.put<ApiResponse>('/agent/api/user-md', { content: userContent.value })
    if (data.code === 'OK') {
      ElMessage.success('USER.md 저장 완료')
    } else {
      ElMessage.error(data.message || 'USER.md 저장 실패')
    }
  } catch {
    ElMessage.error('USER.md 저장에 실패했습니다.')
  } finally {
    userSaving.value = false
  }
}

defineExpose({ loadProfile })
</script>

<style scoped>
.profile-container {
  padding: 0;
}

.section-footer {
  margin-top: 12px;
}

.profile-container :deep(.el-collapse-item__header) {
  font-size: 15px;
  font-weight: 600;
}

.profile-container :deep(.el-collapse-item__content) {
  padding-top: 12px;
}

.profile-container :deep(.el-textarea__inner) {
  font-family: 'SF Mono', 'Fira Code', 'Consolas', monospace;
  font-size: 13px;
  line-height: 1.6;
}
</style>
