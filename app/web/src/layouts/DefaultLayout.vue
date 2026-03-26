<template>
  <el-container class="layout-container">
    <el-header class="layout-header">
      <div class="header-content">
        <router-link to="/" class="logo">Selah</router-link>

        <!-- Desktop nav -->
        <el-menu
          :default-active="activeMenu"
          mode="horizontal"
          :ellipsis="false"
          class="nav-menu desktop-nav"
          router
        >
          <el-menu-item index="/setup">
            <el-icon><Setting /></el-icon>
            <span>설정</span>
          </el-menu-item>
          <el-menu-item v-if="plugins.agent" index="/agent">
            <el-icon><Cpu /></el-icon>
            <span>Agent</span>
          </el-menu-item>
        </el-menu>

        <!-- Mobile hamburger -->
        <el-button class="mobile-menu-btn" text @click="menuOpen = true">
          <el-icon :size="22"><Menu /></el-icon>
        </el-button>

        <div class="header-right">
          <el-button text @click="handleLogout">
            <el-icon><SwitchButton /></el-icon>
          </el-button>
        </div>
      </div>
    </el-header>

    <!-- Mobile drawer -->
    <el-drawer v-model="menuOpen" direction="ltr" size="260px" :with-header="false">
      <el-menu
        :default-active="activeMenu"
        mode="vertical"
        router
        @select="menuOpen = false"
      >
        <el-menu-item index="/setup">
          <el-icon><Setting /></el-icon>
          <span>설정</span>
        </el-menu-item>
        <el-menu-item v-if="plugins.agent" index="/agent">
          <el-icon><Cpu /></el-icon>
          <span>Agent</span>
        </el-menu-item>
      </el-menu>
    </el-drawer>

    <el-main class="layout-main">
      <router-view />
    </el-main>
  </el-container>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Cpu, SwitchButton, Menu, Setting } from '@element-plus/icons-vue'
import { useAuth } from '../composables/useAuth'
import api from '../api/client'

const route = useRoute()
const router = useRouter()
const { logout } = useAuth()

interface PluginStatus {
  agent: boolean
}

const plugins = ref<PluginStatus>({
  agent: false
})

const menuOpen = ref(false)

const activeMenu = computed(() => {
  const path = route.path
  if (path.startsWith('/setup')) return '/setup'
  if (path.startsWith('/agent')) return '/agent'
  return '/'
})

async function loadPluginStatus() {
  try {
    const { data } = await api.get('/api/system/plugins')
    if (data.data) {
      plugins.value = data.data
    }
  } catch {
    // 실패 시 기본값 유지
  }
}

async function handleLogout() {
  await logout()
  router.push('/login')
}

onMounted(() => {
  loadPluginStatus()
})
</script>

<style scoped>
.layout-container {
  min-height: 100vh;
}

.layout-header {
  border-bottom: 1px solid var(--el-border-color-light);
  padding: 0 20px;
  height: 56px;
  display: flex;
  align-items: center;
}

.header-content {
  display: flex;
  align-items: center;
  width: 100%;
  max-width: 1400px;
  margin: 0 auto;
}

.logo {
  color: var(--el-text-color-primary);
  font-size: 18px;
  font-weight: 700;
  text-decoration: none;
  margin-right: 24px;
  white-space: nowrap;
}

.nav-menu {
  flex: 1;
  border-bottom: none !important;
}

.nav-menu .el-menu-item {
  height: 56px;
  line-height: 56px;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-left: auto;
  white-space: nowrap;
}

.layout-main {
  max-width: 1400px;
  margin: 0 auto;
  width: 100%;
  padding: 20px;
}

/* Mobile hamburger button — hidden on desktop */
.mobile-menu-btn {
  display: none;
}

@media (max-width: 768px) {
  .desktop-nav {
    display: none !important;
  }

  .mobile-menu-btn {
    display: inline-flex;
    margin-left: auto;
  }

  .layout-main {
    max-width: 100%;
    padding: 12px;
  }

  .layout-header {
    padding: 0 12px;
  }
}
</style>
