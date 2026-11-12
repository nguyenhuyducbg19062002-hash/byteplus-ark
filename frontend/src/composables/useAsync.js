import { ref, shallowRef } from 'vue'

/**
 * Bọc 1 lời gọi API: { data, loading, error, run }.
 * Dùng chung với <AsyncState> để hiện loading / lỗi (có nút thử lại) / trống cho mọi màn hình.
 */
export function useAsync(fn, { initial = null, immediate = false } = {}) {
  const data = shallowRef(initial)
  const loading = ref(false)
  const error = ref(null)

  async function run(...args) {
    loading.value = true
    error.value = null
    try {
      data.value = await fn(...args)
      return data.value
    } catch (e) {
      error.value = e
      return undefined
    } finally {
      loading.value = false
    }
  }

  if (immediate) run()
  return { data, loading, error, run }
}
