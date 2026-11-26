<script setup>
import { ref, reactive, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import StatusBadge from './StatusBadge.vue'

/**
 * 888API (888api.xin) – nguồn tạo video thứ ba cho Kịch bản hàng loạt. API key "sk-…" chỉ lưu ở backend (config.properties).
 * 888API chỉ nhận link ảnh HTTPS → chọn nơi app để ảnh tạm: TOS riêng tư của BytePlus (cần AK/SK) hoặc Litterbox.
 */
const props = defineProps({ settings: Object })
const emit = defineEmits(['changed'])

const form = reactive({ key: '', imageHost: 'TOS' })
const status = ref(null)
const saving = ref(false)
const checking = ref(false)

watch(
  () => props.settings,
  (s) => {
    if (s) form.imageHost = s.api888ImageHost || 'TOS'
    if (s?.api888KeySet && !status.value) check(true)
  },
  { immediate: true }
)

async function save() {
  if (!form.key && !props.settings?.api888KeySet) {
    ElMessage.warning('Nhập API key 888API (sk-…)')
    return
  }
  saving.value = true
  try {
    const r = await api.put('/settings/api888', { key: form.key || null, imageHost: form.imageHost })
    status.value = r && Object.keys(r).length ? r : null
    form.key = ''
    ElMessage.success('Đã lưu 888API')
    emit('changed')
  } finally {
    saving.value = false
  }
}

async function check(silent = false) {
  checking.value = true
  try {
    status.value = await api.get('/settings/api888/status', { silent })
  } catch {
    status.value = null
  } finally {
    checking.value = false
  }
}

async function clear() {
  await ElMessageBox.confirm('Xoá API key 888API đã lưu trên máy này?', 'Xoá key 888API', { type: 'warning' })
  await api.delete('/settings/api888')
  status.value = null
  emit('changed')
}

const videoModels = (s) => (s?.models || []).filter((m) => /seedance|sd_|sd2|wan|minimax|muse|sora|video|h3/i.test(m))
</script>

<template>
  <section class="card space-y-4 p-6">
    <div class="flex items-start justify-between gap-3">
      <div>
        <h2 class="font-semibold">888API (888api.xin)</h2>
        <p class="hint">Nguồn tạo video thứ ba, trừ tiền theo key 888API. Chọn nguồn này trong Kịch bản hàng loạt › Cấu hình chạy.</p>
      </div>
      <StatusBadge v-if="settings?.api888KeySet" status="ok" label="Đã có key" />
      <StatusBadge v-else status="queued" label="Chưa có key" />
    </div>

    <div class="grid gap-4 sm:grid-cols-2">
      <div>
        <label class="field-label">API key</label>
        <el-input v-model="form.key" type="password" show-password autocomplete="new-password"
          :placeholder="settings?.api888KeySet ? settings.api888Key + ' – nhập lại để đổi' : 'sk-…'" @keyup.enter="save" />
        <p class="hint mt-1">Tạo key ở 888api.xin › Bảng điều khiển › 令牌, bật quyền các model video muốn dùng.</p>
      </div>
      <div>
        <label class="field-label">Chỗ để ảnh tham chiếu tạm</label>
        <el-radio-group v-model="form.imageHost">
          <el-radio value="TOS">TOS BytePlus (riêng tư)</el-radio>
          <el-radio value="LITTERBOX">Litterbox (công khai)</el-radio>
        </el-radio-group>
        <p class="hint mt-1">
          888API chỉ nhận link ảnh, không nhận file. TOS: bucket riêng của bạn, link có chữ ký 48 giờ (cần AK/SK + đã bật TOS).
          Litterbox: dịch vụ miễn phí, ai có link đều xem được, tự xoá sau 72 giờ.
        </p>
      </div>
    </div>

    <div class="flex flex-wrap gap-2">
      <el-button type="primary" :loading="saving" @click="save">Lưu</el-button>
      <el-button v-if="settings?.api888KeySet" :loading="checking" @click="check(false)">Xem số dư</el-button>
      <el-button v-if="settings?.api888KeySet" text type="danger" @click="clear">Xoá key</el-button>
    </div>

    <div v-if="status" class="rounded-xl bg-slate-50 p-4 text-sm dark:bg-zinc-800/60">
      <div>
        Số dư:
        <b class="text-emerald-600 dark:text-emerald-400">{{ status.balance ?? '?' }} {{ status.currency || '' }}</b>
        <span v-if="status.keyUnlimited === false && status.keyRemaining != null" class="hint ml-2">· hạn mức key còn {{ status.keyRemaining }}</span>
      </div>
      <div v-if="videoModels(status).length" class="mt-2">
        <div class="hint mb-1">Model video key dùng được ({{ videoModels(status).length }}):</div>
        <div class="font-mono text-xs leading-relaxed">{{ videoModels(status).join(' · ') }}</div>
      </div>
    </div>
  </section>
</template>
