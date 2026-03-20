import { ref, nextTick } from 'vue'
import api from '../api/client'
import { usePolling } from './usePolling'

export interface LogLine {
  text: string
  level: 'ERROR' | 'WARN' | 'INFO' | 'DEBUG' | ''
}

/**
 * 라이브 로그 폴링 공통 composable.
 * Agent 라이브 로그 표시용.
 *
 * API 응답: RootResponse<List<String>> — 각 항목은 JSON 문자열
 * {"time":"...","level":"...","logger":"...","message":"..."}
 */
export function useLiveLogs(apiUrl: string, lines = 200, intervalMs = 3000) {
  const logs = ref<LogLine[]>([])
  const autoScroll = ref(true)
  let containerEl: HTMLElement | null = null
  let lastLogLine: string | null = null

  function setContainer(el: HTMLElement | null) {
    containerEl = el
  }

  async function fetchLogs() {
    try {
      const { data } = await api.get(apiUrl, { params: { lines } })
      const rawLines: string[] = data.data ?? []
      if (rawLines.length === 0) return

      const newLastLine = rawLines[rawLines.length - 1]
      if (newLastLine === lastLogLine) return

      // 증분 업데이트: 이전에 본 마지막 줄 이후만 추가
      let startIdx = 0
      if (lastLogLine) {
        const idx = rawLines.lastIndexOf(lastLogLine)
        if (idx >= 0) {
          startIdx = idx + 1
        } else {
          // 이전 줄이 없음 — 전체 교체
          logs.value = []
        }
      }

      for (let i = startIdx; i < rawLines.length; i++) {
        logs.value.push(parseLine(rawLines[i]))
      }
      lastLogLine = newLastLine

      if (autoScroll.value && containerEl) {
        await nextTick()
        containerEl.scrollTop = containerEl.scrollHeight
      }
    } catch {
      // 무시
    }
  }

  const { active, start: startPolling, stop: stopPolling } = usePolling(fetchLogs, intervalMs)

  function start() {
    lastLogLine = null
    logs.value = []
    startPolling()
  }

  function stop() {
    stopPolling()
  }

  return {
    logs,
    autoScroll,
    active,
    start,
    stop,
    setContainer
  }
}

function parseLine(rawJson: string): LogLine {
  try {
    const log = JSON.parse(rawJson)
    const text = `${log.time} ${(log.level || '').padEnd(5)} [${log.logger}] ${log.message}`
    const level = (['ERROR', 'WARN', 'INFO', 'DEBUG'].includes(log.level) ? log.level : '') as LogLine['level']
    return { text, level }
  } catch {
    // JSON 파싱 실패 시 원본 텍스트 표시
    return { text: rawJson, level: '' }
  }
}
