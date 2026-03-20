import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

/**
 * URL hash와 탭 상태를 동기화하는 composable.
 * 예: /agent#routine → activeTab = 'routine'
 */
export function useTabRoute(defaultTab: string, validTabs: string[]) {
  const route = useRoute()
  const router = useRouter()

  const hash = route.hash?.replace('#', '')
  const initialTab = (hash && validTabs.includes(hash)) ? hash : defaultTab
  const activeTab = ref(initialTab)

  watch(activeTab, (tab) => {
    const newHash = tab === defaultTab ? '' : `#${tab}`
    if (route.hash !== newHash) {
      router.replace({ hash: newHash })
    }
  })

  return { activeTab }
}
