<template>
  <div>
    <div class="tab-toolbar">
      <el-input
        v-model="localFilter"
        placeholder="스킬 필터..."
        clearable
        size="small"
        style="width: 240px"
      />
      <div class="tab-toolbar-actions">
        <el-button type="primary" size="small" @click="openInstallDialog">설치</el-button>
        <el-button size="small" @click="loadSkills">새로고침</el-button>
      </div>
    </div>

    <div class="skills-cards" v-loading="skillsLoading">
      <div v-if="filteredSkills.length === 0" style="padding: 24px">
        <el-empty :description="localFilter ? '일치하는 스킬 없음' : '등록된 스킬 없음'" />
      </div>
      <el-card
        v-for="skill in filteredSkills"
        :key="skill.name"
        shadow="hover"
        class="skill-card"
        :class="{ 'skill-card--disabled': !skill.active }"
      >
        <template #header>
          <div class="card-header">
            <div class="skill-card-title">
              <span class="skill-name">{{ skill.name }}</span>
              <el-tag
                :type="skillGatingType(skill.gatingStatus)"
                size="small"
                effect="plain"
              >{{ skillGatingLabel(skill.gatingStatus) }}</el-tag>
              <el-tag
                v-if="skill.mcpServer"
                size="small"
                effect="plain"
              >MCP: {{ skill.mcpServer }}</el-tag>
              <el-tag
                v-for="os in formatOs(skill.os)"
                :key="os"
                size="small"
                type="info"
                effect="plain"
              >{{ os }}</el-tag>
            </div>
            <div class="skill-card-actions">
              <el-switch
                :model-value="skill.active"
                size="small"
                @change="(val: boolean) => toggleSkill(skill, val)"
              />
              <el-button size="small" @click="viewSkillContent(skill.name)">내용</el-button>
              <el-popconfirm
                title="이 스킬을 삭제하시겠습니까?"
                confirm-button-text="삭제"
                cancel-button-text="취소"
                @confirm="deleteSkill(skill.name)"
              >
                <template #reference>
                  <el-button type="danger" size="small" text>삭제</el-button>
                </template>
              </el-popconfirm>
            </div>
          </div>
        </template>

        <div class="skill-description">{{ skill.description || '-' }}</div>
        <div v-if="skill.tools && skill.tools.length > 0" class="skill-tools">
          <el-tag
            v-for="tool in skill.tools"
            :key="tool"
            size="small"
            effect="plain"
            class="skill-tool-tag"
          >{{ tool }}</el-tag>
        </div>
        <div v-if="skill.gatingReason" class="skill-reason">{{ skill.gatingReason }}</div>
      </el-card>
    </div>

    <!-- 스킬 설치 다이얼로그 -->
    <el-dialog v-model="showInstallDialog" title="스킬 설치" width="600" @closed="resetInstallDialog">
      <el-tabs v-model="installTab">
        <el-tab-pane label="URL 설치" name="url">
          <el-form label-width="80px" style="margin-top: 12px">
            <el-form-item label="소스">
              <el-input v-model="installUrl" placeholder="GitHub URL 또는 다운로드 URL" />
            </el-form-item>
          </el-form>
          <div style="text-align: right">
            <el-button
              type="primary"
              :loading="installing"
              :disabled="!installUrl"
              @click="installFromUrl"
            >설치</el-button>
          </div>
        </el-tab-pane>
        <el-tab-pane label="ClawHub 검색" name="search">
          <div class="search-bar">
            <el-input
              v-model="hubQuery"
              placeholder="검색어..."
              clearable
              @keyup.enter="searchHub"
            />
            <el-button :loading="hubSearching" @click="searchHub">검색</el-button>
          </div>
          <div class="hub-results" v-loading="hubSearching">
            <div v-if="hubResults.length === 0 && !hubSearching" class="hub-empty">
              {{ hubSearched ? '결과 없음' : '검색어를 입력하세요' }}
            </div>
            <div
              v-for="item in hubResults"
              :key="item.name"
              class="hub-item"
            >
              <div class="hub-item-info">
                <span class="hub-item-name">{{ item.name }}</span>
                <span class="hub-item-desc">{{ item.description }}</span>
              </div>
              <el-button
                size="small"
                type="primary"
                :loading="installingName === item.name"
                @click="installFromHub(item)"
              >설치</el-button>
            </div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-dialog>

    <!-- 스킬 내용 다이얼로그 -->
    <el-dialog
      v-model="showContentDialog"
      :title="`스킬 - ${contentName}`"
      width="900"
      top="5vh"
      @closed="contentValue = ''"
    >
      <el-input
        v-model="contentValue"
        type="textarea"
        :rows="30"
        class="skill-editor"
      />
      <template #footer>
        <el-button @click="showContentDialog = false">닫기</el-button>
        <el-button
          type="primary"
          :loading="contentSaving"
          @click="saveSkillContent"
        >저장</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import api from '@/api/client'
import type { ApiResponse, Skill } from './types'

// ─── Local skills ───

const skills = ref<Skill[]>([])
const skillsLoading = ref(false)
const localFilter = ref('')

const filteredSkills = computed(() => {
  if (!localFilter.value) return skills.value
  const q = localFilter.value.toLowerCase()
  return skills.value.filter(s =>
    s.name.toLowerCase().includes(q) ||
    (s.description && s.description.toLowerCase().includes(q))
  )
})

async function loadSkills(): Promise<void> {
  skillsLoading.value = true
  try {
    const { data } = await api.get<ApiResponse<Skill[]>>('/agent/api/skills')
    if (data.code === 'OK' && data.data) {
      skills.value = data.data
    } else {
      skills.value = data.data ?? (Array.isArray(data) ? data : [])
    }
  } catch {
    ElMessage.error('스킬 목록 로드 실패')
  } finally {
    skillsLoading.value = false
  }
}

async function toggleSkill(skill: Skill, active: boolean): Promise<void> {
  try {
    const action = active ? 'enable' : 'disable'
    const { data } = await api.post<ApiResponse>(`/agent/api/skills/${skill.name}/${action}`)
    if (data.code === 'OK') {
      skill.active = active
      ElMessage.success(`${skill.name} ${active ? '활성화' : '비활성화'} 완료`)
    } else {
      ElMessage.error(data.message || '상태 변경 실패')
    }
  } catch {
    ElMessage.error('스킬 상태 변경에 실패했습니다.')
  }
}

async function deleteSkill(name: string): Promise<void> {
  try {
    const { data } = await api.delete<ApiResponse>(`/agent/api/skills/${name}`)
    if (data.code === 'OK') {
      ElMessage.success('스킬이 삭제되었습니다.')
      await loadSkills()
    } else {
      ElMessage.error(data.message || '삭제 실패')
    }
  } catch {
    ElMessage.error('스킬 삭제에 실패했습니다.')
  }
}

// ─── Install dialog ───

const showInstallDialog = ref(false)
const installTab = ref('url')
const installUrl = ref('')
const installing = ref(false)

const hubQuery = ref('')
const hubSearching = ref(false)
const hubSearched = ref(false)
const hubResults = ref<any[]>([])
const installingName = ref('')

function openInstallDialog(): void {
  showInstallDialog.value = true
}

function resetInstallDialog(): void {
  installUrl.value = ''
  hubQuery.value = ''
  hubResults.value = []
  hubSearched.value = false
  installingName.value = ''
}

async function installFromUrl(): Promise<void> {
  if (!installUrl.value) return
  installing.value = true
  try {
    const { data } = await api.post<ApiResponse>('/agent/api/skills/install', {
      source: 'github',
      url: installUrl.value
    })
    if (data.code === 'OK') {
      ElMessage.success('스킬이 설치되었습니다.')
      showInstallDialog.value = false
      await loadSkills()
    } else {
      ElMessage.error(data.message || '설치 실패')
    }
  } catch {
    ElMessage.error('스킬 설치에 실패했습니다.')
  } finally {
    installing.value = false
  }
}

async function searchHub(): Promise<void> {
  if (!hubQuery.value) return
  hubSearching.value = true
  hubSearched.value = false
  try {
    const { data } = await api.get<ApiResponse>('/agent/api/skills/search', {
      params: { q: hubQuery.value }
    })
    if (data.code === 'OK' && data.data) {
      hubResults.value = data.data
    } else if (Array.isArray(data)) {
      hubResults.value = data
    } else {
      hubResults.value = []
    }
  } catch {
    ElMessage.error('검색 실패')
    hubResults.value = []
  } finally {
    hubSearching.value = false
    hubSearched.value = true
  }
}

async function installFromHub(item: any): Promise<void> {
  installingName.value = item.name
  try {
    const url = item.url || item.html_url || item.clone_url
    if (!url) {
      ElMessage.error('설치 URL을 찾을 수 없습니다.')
      return
    }
    const { data } = await api.post<ApiResponse>('/agent/api/skills/install', {
      source: 'github',
      url
    })
    if (data.code === 'OK') {
      ElMessage.success(`${item.name} 설치 완료`)
      await loadSkills()
    } else {
      ElMessage.error(data.message || '설치 실패')
    }
  } catch {
    ElMessage.error('스킬 설치에 실패했습니다.')
  } finally {
    installingName.value = ''
  }
}

// ─── Content dialog ───

const showContentDialog = ref(false)
const contentName = ref('')
const contentValue = ref('')
const contentSaving = ref(false)

async function viewSkillContent(name: string): Promise<void> {
  contentName.value = name
  contentValue.value = ''
  showContentDialog.value = true
  try {
    const { data } = await api.get<ApiResponse<{ content: string }>>(`/agent/api/skills/${name}/content`)
    if (data.code === 'OK' && data.data) {
      contentValue.value = data.data.content
    } else if ((data as any).content) {
      contentValue.value = (data as any).content
    }
  } catch {
    ElMessage.error('스킬 내용 로드 실패')
  }
}

async function saveSkillContent(): Promise<void> {
  contentSaving.value = true
  try {
    const { data } = await api.put<ApiResponse>(`/agent/api/skills/${contentName.value}/content`, {
      content: contentValue.value
    })
    if (data.code === 'OK') {
      ElMessage.success('스킬 내용이 저장되었습니다.')
      showContentDialog.value = false
    } else {
      ElMessage.error(data.message || '저장 실패')
    }
  } catch {
    ElMessage.error('스킬 내용 저장에 실패했습니다.')
  } finally {
    contentSaving.value = false
  }
}

// ─── Helpers ───

const osLabels: Record<string, string> = {
  darwin: 'macOS',
  linux: 'Linux',
  win32: 'Windows',
  windows: 'Windows'
}

function formatOs(os: string[] | null): string[] {
  if (!os || os.length === 0) return []
  return os.map(o => osLabels[o] || o)
}

function skillGatingType(status: string): '' | 'success' | 'warning' | 'info' | 'danger' {
  switch (status) {
    case 'ACTIVE': return 'success'
    case 'INACTIVE': return 'info'
    case 'INSTALL_REQUIRED': return 'warning'
    case 'SKIP': return 'danger'
    default: return 'info'
  }
}

function skillGatingLabel(status: string): string {
  switch (status) {
    case 'ACTIVE': return '활성'
    case 'INACTIVE': return '비활성'
    case 'INSTALL_REQUIRED': return '설치 필요'
    case 'SKIP': return '건너뜀'
    default: return status
  }
}

defineExpose({ loadSkills })
</script>

<style scoped>
.tab-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 12px;
}

.tab-toolbar-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.skills-cards {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.skill-card {
  box-sizing: border-box;
  transition: opacity 0.2s;
}

.skill-card--disabled {
  opacity: 0.55;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.skill-card-title {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  min-width: 0;
  overflow: hidden;
}

.skill-name {
  font-weight: 600;
  font-size: 15px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.skill-card-actions {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
  align-items: center;
  margin-left: auto;
}

.skill-description {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.skill-tools {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 6px;
}

.skill-tool-tag {
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  font-size: 11px;
}

.skill-reason {
  font-size: 12px;
  color: var(--el-color-warning);
  margin-top: 6px;
}

/* Install dialog */
.search-bar {
  display: flex;
  gap: 8px;
  margin-top: 12px;
  margin-bottom: 12px;
}

.hub-results {
  min-height: 120px;
  max-height: 360px;
  overflow-y: auto;
}

.hub-empty {
  text-align: center;
  color: var(--el-text-color-placeholder);
  padding: 40px 0;
}

.hub-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  gap: 12px;
}

.hub-item:last-child {
  border-bottom: none;
}

.hub-item-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.hub-item-name {
  font-weight: 600;
  font-size: 14px;
}

.hub-item-desc {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* Content dialog */
.skill-editor :deep(textarea) {
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.6;
}
</style>
