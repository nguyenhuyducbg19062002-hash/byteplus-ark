<script setup>
import { computed } from 'vue'

/**
 * Badge trạng thái dạng "pill": xanh = Active/thành công, vàng = đang xử lý, đỏ = lỗi, xám = dừng/không hoạt động.
 * Nhận cả trạng thái của BytePlus (Active, Processing, succeeded…) lẫn của job (RUNNING, FAILED…).
 */
const props = defineProps({ status: String, label: String })

const TONES = {
  green: 'bg-emerald-50 text-emerald-700 ring-emerald-600/20 dark:bg-emerald-500/10 dark:text-emerald-300 dark:ring-emerald-400/20',
  amber: 'bg-amber-50 text-amber-700 ring-amber-600/20 dark:bg-amber-500/10 dark:text-amber-300 dark:ring-amber-400/20',
  red: 'bg-rose-50 text-rose-700 ring-rose-600/20 dark:bg-rose-500/10 dark:text-rose-300 dark:ring-rose-400/20',
  blue: 'bg-indigo-50 text-indigo-700 ring-indigo-600/20 dark:bg-indigo-500/10 dark:text-indigo-300 dark:ring-indigo-400/20',
  gray: 'bg-slate-100 text-slate-600 ring-slate-500/20 dark:bg-zinc-700/50 dark:text-zinc-300 dark:ring-zinc-500/20'
}
const DOTS = { green: 'bg-emerald-500', amber: 'bg-amber-500 animate-pulse', red: 'bg-rose-500', blue: 'bg-indigo-500', gray: 'bg-slate-400' }
const LABELS = {
  active: 'Active', processing: 'Đang xử lý', failed: 'Lỗi', running: 'Đang chạy', queued: 'Đang chờ',
  succeeded: 'Thành công', cancelled: 'Đã huỷ', expired: 'Hết hạn', ok: 'OK', missing: 'Thiếu', off: 'Tắt'
}

const tone = computed(() => {
  const s = (props.status || '').toLowerCase()
  if (['active', 'succeeded', 'ok', 'success'].includes(s)) return 'green'
  if (['processing', 'running', 'queued'].includes(s)) return 'amber'
  if (['failed', 'error', 'missing'].includes(s)) return 'red'
  if (s === 'info') return 'blue'
  return 'gray'
})
const text = computed(() => props.label || LABELS[(props.status || '').toLowerCase()] || props.status || '—')
</script>

<template>
  <span class="inline-flex items-center gap-1.5 whitespace-nowrap rounded-full px-2.5 py-0.5 text-xs font-medium ring-1 ring-inset" :class="TONES[tone]">
    <span class="h-1.5 w-1.5 rounded-full" :class="DOTS[tone]" />
    {{ text }}
  </span>
</template>
