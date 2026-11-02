import { onScopeDispose, ref, shallowRef } from 'vue'
import { isAbortError, toErrorMessage } from '@/utils/async'

/**
 * 统一管理非流式长任务的 loading / error / 取消。
 * AI 生成接口可能几十秒，UI 必须可取消，且错误要展示后端 message。
 */
export function useAsyncTask() {
  const loading = ref(false)
  const error = ref('')
  const controller = shallowRef<AbortController | null>(null)

  async function run<T>(task: (signal: AbortSignal) => Promise<T>): Promise<T | undefined> {
    if (loading.value) {
      return undefined
    }
    loading.value = true
    error.value = ''
    const current = new AbortController()
    controller.value = current
    try {
      return await task(current.signal)
    } catch (cause) {
      if (isAbortError(cause)) {
        return undefined
      }
      error.value = toErrorMessage(cause)
      return undefined
    } finally {
      loading.value = false
      controller.value = null
    }
  }

  function cancel() {
    controller.value?.abort()
  }

  onScopeDispose(() => controller.value?.abort())

  return { loading, error, run, cancel }
}
