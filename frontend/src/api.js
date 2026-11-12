import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * Client cho backend Spring Boot (/api/v1/...).
 * Lỗi được chuẩn hoá thành Error có .fields (lỗi validation theo từng trường) và .status.
 * Mặc định hiện ElMessage; truyền { silent: true } để tự xử lý (ví dụ AsyncState).
 */
const api = axios.create({ baseURL: '/api/v1', timeout: 180000 })

api.interceptors.response.use(
  (res) => res.data,
  (err) => {
    const d = err.response?.data
    let msg =
      d?.error ||
      (err.code === 'ERR_NETWORK' || err.response?.status === 502 || err.response?.status === 504
        ? 'Không kết nối được backend (http://127.0.0.1:8080) – đã chạy start-backend.bat chưa?'
        : err.message)
    if (d?.code && d.code !== 'VALIDATION' && d.code !== 'BAD_REQUEST') msg = `[${d.code}] ${msg}`
    const e = new Error(msg)
    e.status = err.response?.status
    e.fields = d?.fields || {}
    if (!err.config?.silent) ElMessage.error({ message: msg, duration: 7000, showClose: true, grouping: true })
    return Promise.reject(e)
  }
)

export default api

/** File → data URI (BytePlus yêu cầu data:image/png;base64,... với định dạng viết thường). */
export function fileToDataUrl(file) {
  return new Promise((resolve, reject) => {
    const r = new FileReader()
    r.onload = () => {
      const s = String(r.result)
        .replace(/^data:audio\/mpeg;/, 'data:audio/mp3;')
        .replace(/^data:audio\/(x-wav|wave|vnd\.wave);/, 'data:audio/wav;')
      resolve(s)
    }
    r.onerror = reject
    r.readAsDataURL(file)
  })
}

export function formatSize(n) {
  if (n == null) return ''
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return (n / 1024).toFixed(0) + ' KB'
  return (n / 1024 / 1024).toFixed(1) + ' MB'
}

export function formatTime(v) {
  if (!v) return ''
  const d = typeof v === 'number' ? new Date(v < 1e12 ? v * 1000 : v) : new Date(v)
  return isNaN(d) ? String(v) : d.toLocaleString('vi-VN', { dateStyle: 'short', timeStyle: 'short' })
}
