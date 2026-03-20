import { createRouter, createWebHistory } from 'vue-router'
import { useAuth } from '../composables/useAuth'
import DefaultLayout from '../layouts/DefaultLayout.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('../pages/login/LoginPage.vue'),
      meta: { public: true }
    },
    {
      path: '/',
      component: DefaultLayout,
      children: [
        {
          path: '',
          name: 'dashboard',
          component: () => import('../pages/dashboard/DashboardPage.vue')
        },
        {
          path: 'setup',
          name: 'setup',
          component: () => import('../pages/setup/SetupPage.vue')
        },
        {
          path: 'agent',
          name: 'agent',
          component: () => import('../pages/agent/AgentPage.vue')
        }
      ]
    }
  ]
})

router.beforeEach(async (to) => {
  if (to.meta.public) return true

  const { authenticated, checking, checkAuth } = useAuth()

  if (checking.value || !authenticated.value) {
    const isAuth = await checkAuth()
    if (!isAuth) {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
  }

  return true
})

export default router
