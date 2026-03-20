import { ref, onUnmounted } from 'vue'

/**
 * 범용 폴링 훅.
 * 지정된 간격으로 콜백을 반복 실행하며, 컴포넌트 언마운트 시 자동 정리.
 */
export function usePolling(fn: () => Promise<void>, intervalMs = 3000) {
  const active = ref(false)
  let timer: ReturnType<typeof setInterval> | null = null

  function start() {
    if (active.value) return
    active.value = true
    fn() // 즉시 1회 실행
    timer = setInterval(fn, intervalMs)
  }

  function stop() {
    active.value = false
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  }

  onUnmounted(stop)

  return { active, start, stop }
}
