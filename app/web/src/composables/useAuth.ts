import { ref } from 'vue'
import api from '../api/client'

const authenticated = ref(false)
const checking = ref(true)

export function useAuth() {
  async function checkAuth(): Promise<boolean> {
    checking.value = true
    try {
      const { data } = await api.get('/api/auth/check')
      authenticated.value = data.data?.authenticated === true
    } catch {
      authenticated.value = false
    } finally {
      checking.value = false
    }
    return authenticated.value
  }

  async function logout(): Promise<void> {
    try {
      await api.post('/api/auth/logout')
    } finally {
      authenticated.value = false
    }
  }

  function setAuthenticated(value: boolean) {
    authenticated.value = value
    checking.value = false
  }

  return {
    authenticated,
    checking,
    checkAuth,
    logout,
    setAuthenticated
  }
}
