<template>
  <div>
    <el-tabs v-model="activeTab">
      <el-tab-pane label="Sessions" name="sessions">
        <SessionsTab v-if="loadedTabs.has('sessions')" />
      </el-tab-pane>

      <el-tab-pane label="Tasks" name="tasks">
        <TasksTab v-if="loadedTabs.has('tasks')" ref="tasksRef" />
      </el-tab-pane>

      <el-tab-pane label="History" name="history">
        <HistoryTab v-if="loadedTabs.has('history')" ref="historyRef" />
      </el-tab-pane>

      <el-tab-pane label="Schedules" name="jobs">
        <ScheduledJobsTab v-if="loadedTabs.has('jobs')" ref="jobsRef" />
      </el-tab-pane>

      <el-tab-pane label="Live Logs" name="logs">
        <LiveLogsPanel v-if="loadedTabs.has('logs')" api-url="/agent/api/logs/recent" />
      </el-tab-pane>

      <el-tab-pane label="MCP" name="mcp">
        <McpTab v-if="loadedTabs.has('mcp')" ref="mcpRef" />
      </el-tab-pane>

      <el-tab-pane label="Skills" name="skills">
        <SkillsTab v-if="loadedTabs.has('skills')" ref="skillsRef" />
      </el-tab-pane>

      <el-tab-pane label="Profile" name="profile">
        <ProfileTab v-if="loadedTabs.has('profile')" ref="profileRef" />
      </el-tab-pane>

      <el-tab-pane label="Config" name="config">
        <ConfigTab v-if="loadedTabs.has('config')" ref="configRef" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted, nextTick, type ComponentPublicInstance } from 'vue'
import { useTabRoute } from '@/composables/useTabRoute'
import SessionsTab from './SessionsTab.vue'
import TasksTab from './TasksTab.vue'
import HistoryTab from './HistoryTab.vue'
import ScheduledJobsTab from './ScheduledJobsTab.vue'
import McpTab from './McpTab.vue'
import SkillsTab from './SkillsTab.vue'
import ProfileTab from './ProfileTab.vue'
import ConfigTab from './ConfigTab.vue'
import LiveLogsPanel from '@/components/LiveLogsPanel.vue'

const validTabs = ['sessions', 'tasks', 'history', 'jobs', 'logs', 'mcp', 'skills', 'profile', 'config']
const { activeTab } = useTabRoute('sessions', validTabs)
const loadedTabs = ref(new Set<string>())

const tasksRef = ref<ComponentPublicInstance<{ loadTasks: () => Promise<void> }> | null>(null)
const historyRef = ref<ComponentPublicInstance<{ loadHistory: () => Promise<void> }> | null>(null)
const jobsRef = ref<ComponentPublicInstance<{ loadJobs: () => Promise<void> }> | null>(null)
const mcpRef = ref<ComponentPublicInstance<{ loadMcpServers: () => Promise<void> }> | null>(null)
const skillsRef = ref<ComponentPublicInstance<{ loadSkills: () => Promise<void> }> | null>(null)
const profileRef = ref<ComponentPublicInstance<{ loadProfile: () => Promise<void> }> | null>(null)
const configRef = ref<ComponentPublicInstance<{ loadConfig: () => Promise<void> }> | null>(null)

async function loadTab(tab: string): Promise<void> {
  const firstVisit = !loadedTabs.value.has(tab)
  if (firstVisit) {
    loadedTabs.value.add(tab)
    await nextTick()
  }
  // Sessions 탭은 자체 onMounted에서 로드하므로 제외
  // Live Logs 탭은 LiveLogsPanel 자체에서 처리하므로 제외
  switch (tab) {
    case 'tasks': tasksRef.value?.loadTasks(); break
    case 'history': historyRef.value?.loadHistory(); break
    case 'jobs': jobsRef.value?.loadJobs(); break
    case 'mcp': mcpRef.value?.loadMcpServers(); break
    case 'skills': skillsRef.value?.loadSkills(); break
    case 'profile': profileRef.value?.loadProfile(); break
    case 'config': configRef.value?.loadConfig(); break
  }
}

watch(activeTab, (tab) => loadTab(tab))

onMounted(() => {
  loadedTabs.value.add('sessions')
  const tab = activeTab.value
  if (tab !== 'sessions') {
    loadTab(tab)
  }
})
</script>
