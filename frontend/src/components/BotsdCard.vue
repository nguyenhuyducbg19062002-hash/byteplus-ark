<script setup>
import { ref, reactive, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import StatusBadge from './StatusBadge.vue'

/**
 * Tài khoản BOTSD (botsd.pro) – nguồn tạo video thứ hai cho Kịch bản hàng loạt.
 * Mật khẩu và clientToken chỉ lưu ở backend (config.properties); app tự đăng nhập lại khi phiên hết hạn.
 */
const props = defineProps({ settings: Object })
const emit = defineEmits(['changed'])

const form = reactive({ username: '', password: '' })
const status = ref(null)
const loading = ref(false)
const checking = ref(false)

// Bảng giá cơ bản theo tài liệu BOTSD 10/11/2026 – giá thật lấy từ videoPricing khi đăng nhập
const MODELS = [
  { id: 'sd20', name: 'Seedance 2.0', price: '5s 2P · 10s 6P · 15s 8P' },
  { id: 'sd25', name: 'Seedance 2.5', price: '5s 9P · 10s 18P · 15s 25P · 30s 50P' },
  { id: 'sd25hd', name: 'Seedance 2.5 1080P', price: '5s 12P · 10s 24P · 15s 35P · 30s 70P' },
  { id: 'muse', name: 'Muse AI', price: '5s 2P · 10s 4P (16:9 / 9:16, tối đa 1 ảnh)' }
]

watch(
  () => props.settings,
  (s) => {
    if (s && !form.username) form.username = s.botsdUsername || ''
    if (s?.botsdLoggedIn && !status.value) check(true)
  },
  { immediate: true }
)

async function login() {
  if (!form.username || !form.password) {
    ElMessage.warning('Nhập tên đăng nhập và mật khẩu BOTSD')
    return
  }
  loading.value = true
  try {
    status.value = await api.post('/settings/botsd/login', { username: form.username, password: form.password })
    form.password = ''
    ElMessage.success('Đã đăng nhập BOTSD')
    emit('changed')
  } finally {
    loading.value = false
  }
}

async function check(silent = false) {
  checking.value = true
  try {
    status.value = await api.get('/settings/botsd/status', { silent })
  } catch {
    status.value = null
  } finally {
    checking.value = false
  }
}

async function logout() {
  await ElMessageBox.confirm('Xoá phiên đăng nhập và mật khẩu BOTSD đã lưu trên máy này?', 'Đăng xuất BOTSD', { type: 'warning' })
  await api.delete('/settings/botsd')
  status.value = null
  emit('changed')
}

function pricingRows(p) {
  if (!p || typeof p !== 'object') return []
  return Object.entries(p).map(([k, v]) => ({ k, v: typeof v === 'object' ? JSON.stringify(v) : String(v) }))
}
</script>

<template>
  <section class="card space-y-4 p-6">
    <div class="flex items-start justify-between gap-3">
      <div>
        <h2 class="font-semibold">BOTSD (botsd.pro)</h2>
        <p class="hint">Nguồn tạo video thứ hai, tính phí bằng Point BOTSD. Chọn nguồn này trong Kịch bản hàng loạt › Cấu hình chạy.</p>
      </div>
      <StatusBadge v-if="settings?.botsdLoggedIn" status="ok" label="Đã đăng nhập" />
      <StatusBadge v-else status="queued" label="Chưa đăng nhập" />
    </div>

    <div class="grid gap-4 sm:grid-cols-2">
      <div>
        <label class="field-label">Tên đăng nhập</label>
        <el-input v-model="form.username" autocomplete="off" />
      </div>
      <div>
        <label class="field-label">Mật khẩu</label>
        <el-input v-model="form.password" type="password" show-password autocomplete="new-password"
          :placeholder="settings?.botsdLoggedIn ? 'Đã lưu – nhập lại để đổi' : ''" @keyup.enter="login" />
      </div>
    </div>
    <p class="hint">Mật khẩu và phiên (clientToken) chỉ lưu ở file cấu hình trên máy này; phiên hết hạn thì app tự đăng nhập lại.</p>

    <div class="flex flex-wrap gap-2">
      <el-button type="primary" :loading="loading" @click="login">Đăng nhập</el-button>
      <el-button v-if="settings?.botsdLoggedIn" :loading="checking" @click="check(false)">Xem số dư</el-button>
      <el-button v-if="settings?.botsdLoggedIn" text type="danger" @click="logout">Đăng xuất</el-button>
    </div>

    <div v-if="status" class="rounded-xl bg-slate-50 p-4 text-sm dark:bg-zinc-800/60">
      <div>
        Tài khoản <b>{{ status.username }}</b> · Số dư:
        <b class="text-emerald-600 dark:text-emerald-400">{{ status.balance ?? '?' }} Point</b>
      </div>
      <div v-if="pricingRows(status.videoPricing).length" class="mt-2">
        <div class="hint mb-1">Bảng giá hiện hành (videoPricing):</div>
        <div v-for="r in pricingRows(status.videoPricing)" :key="r.k" class="font-mono text-xs">{{ r.k }}: {{ r.v }}</div>
      </div>
    </div>

    <details class="text-sm">
      <summary class="cursor-pointer text-slate-500">Model & giá cơ bản</summary>
      <ul class="mt-2 space-y-1">
        <li v-for="m in MODELS" :key="m.id"><code>{{ m.id }}</code> {{ m.name }} – {{ m.price }}</li>
      </ul>
      <p class="hint mt-1">Clip dài lẻ được làm tròn lên mức BOTSD nhận (5 / 10 / 15 / 30s), vd clip 12s gửi 15s.</p>
    </details>
  </section>
</template>
