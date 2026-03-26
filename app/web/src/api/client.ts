import axios from 'axios'
import router from '../router'

const api = axios.create({
  timeout: 30000
})

api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      const currentPath = router.currentRoute.value.fullPath
      if (currentPath !== '/login') {
        router.push({ path: '/login', query: { redirect: currentPath } })
      }
    }
    return Promise.reject(error)
  }
)

export default api
