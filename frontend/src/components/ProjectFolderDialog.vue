<script setup>
import { ref, reactive, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

/**
 * Mở thư mục dự án (Kịch bản hàng loạt): chọn / dán đường dẫn thư mục (vd ...\KET QUA - IDE 21) hoặc chọn dự án gần đây →
 * Quét: app đọc các file Excel trong thư mục (không tải lên), đếm ảnh tham chiếu / video đã có → Mở dự án.
 */
const props = defineProps({ modelValue: Boolean, initialPath: String })
const emit = defineEmits(['update:modelValue', 'opened'])

const path = ref('')
const recent = ref([])
const report = ref(null)
const sel = reactive({ sceneFile: '', refFile: '', voiceFile: '' })
const busy = ref('')

watch(
  () => props.modelValue,
  async (open) => {
    if (!open) return
    report.value = null
    path.value = props.initialPath || ''
    try {
      recent.value = await api.get('/project-folders/recent', { silent: true })
    } catch {
      recent.value = []
    }
    if (path.value) scan()
  }
)

async function pick() {
  busy.value = 'pick'
  try {
    const r = await api.post('/system/pick-folder', { initial: path.value || undefined, title: 'Chọn thư mục dự án (vd KET QUA - IDE 21)' }, { timeout: 0 })
    if (r?.path) {
      path.value = r.path
      await scan(true)
    }
  } finally {
    busy.value = ''
  }
}

async function scan(reset = true) {
  if (!path.value.trim()) {
    ElMessage.warning('Chọn hoặc dán đường dẫn thư mục dự án')
    return
  }
  busy.value = 'scan'
  try {
    const body = { path: path.value.trim() }
    if (!reset) Object.assign(body, sel)
    report.value = await api.post('/project-folders/scan', body, { timeout: 120000 })
    sel.sceneFile = report.value.sceneFile || ''
    sel.refFile = report.value.refFile || ''
    sel.voiceFile = report.value.voiceFile || ''
  } catch {
    report.value = null
  } finally {
    busy.value = ''
  }
}

async function openProject() {
  busy.value = 'open'
  try {
    const r = await api.post('/project-folders/open', { path: report.value.path, ...sel }, { timeout: 300000 })
    const i = r.imported
    ElMessage.success(
      `${r.created ? 'Đã mở' : 'Đã quét lại'} dự án ${r.project.name}: ${i.created + i.updated + i.unchanged} cảnh` +
        (i.created || i.updated ? ` (${i.created} mới, ${i.updated} cập nhật)` : '') +
        (r.synced ? ` · gán ${r.synced.refsAssigned} ảnh từ thư mục · ${r.synced.scenesDone} cảnh đã có video` : '')
    )
    emit('opened', r)
    emit('update:modelValue', false)
  } finally {
    busy.value = ''
  }
}

const KIND = { SCENES: 'Danh sách cảnh', REFERENCES: 'Ảnh tham chiếu', VOICE: 'Thoại' }
const blocked = computed(() => !report.value || !sel.sceneFile)
</script>

<template>
  <el-dialog :model-value="modelValue" title="Mở thư mục dự án" width="860px" @update:model-value="emit('update:modelValue', $event)">
    <p class="hint mb-3">
      Chọn thư mục dự án (vd <code>KET QUA - IDE 21</code>). App tự đọc các file Excel trong thư mục – không cần nhập hay tải file lên – và đếm
      ảnh tham chiếu, video đã có. File Excel chỉ được đọc, không bị sửa.
    </p>
    <div class="flex gap-2">
      <el-input v-model="path" placeholder="Dán đường dẫn, vd C:\Users\...\KET QUA - IDE 21" clearable @keyup.enter="scan()" />
      <el-button :loading="busy === 'pick'" @click="pick"><el-icon><FolderOpened /></el-icon>&nbsp;Chọn thư mục…</el-button>
      <el-button type="primary" :loading="busy === 'scan'" @click="scan()"><el-icon><Search /></el-icon>&nbsp;Quét</el-button>
    </div>

    <div v-if="recent.length && !report" class="mt-4">
      <div class="field-label">Dự án gần đây</div>
      <div class="space-y-1">
        <button
          v-for="r in recent"
          :key="r.id"
          type="button"
          class="flex w-full items-center justify-between gap-3 rounded-lg px-3 py-2 text-left text-sm hover:bg-slate-100 dark:hover:bg-zinc-800"
          :class="!r.exists && 'opacity-50'"
          @click="(path = r.path), scan()"
        >
          <span class="font-medium">{{ r.name }}</span>
          <span class="truncate text-xs text-slate-400">{{ r.exists ? r.path : 'Không còn thư mục: ' + r.path }}</span>
        </button>
      </div>
    </div>

    <div v-if="report" class="mt-4 space-y-3">
      <div class="text-sm">
        <b>{{ report.name }}</b> <span class="hint">{{ report.path }}</span>
        <el-tag v-if="report.projectId" size="small" type="info" class="ml-2">đã mở trước đây – quét lại vào dự án cũ</el-tag>
      </div>

      <table class="w-full text-sm">
        <tbody>
          <tr class="border-b border-slate-100 dark:border-zinc-800">
            <td class="w-44 py-2 font-medium">Danh sách cảnh <span class="text-rose-500">*</span></td>
            <td class="py-2">
              <el-select v-if="report.sceneCandidates.length > 1" v-model="sel.sceneFile" placeholder="Chọn file" class="w-full">
                <el-option v-for="f in report.sceneCandidates" :key="f" :label="f" :value="f" />
              </el-select>
              <span v-else-if="report.sceneCandidates.length" class="text-emerald-600 dark:text-emerald-400">✓ {{ report.sceneCandidates[0] }}</span>
              <span v-else class="font-medium text-rose-600">✗ Không thấy – cần Excel có cột “STT segment” + “Reference IDs” (hoặc sheet KỊCH BẢN)</span>
            </td>
          </tr>
          <tr class="border-b border-slate-100 dark:border-zinc-800">
            <td class="py-2 font-medium">Prompt ảnh tham chiếu</td>
            <td class="py-2">
              <el-select v-if="report.refCandidates.length > 1" v-model="sel.refFile" clearable placeholder="Không dùng" class="w-full">
                <el-option v-for="f in report.refCandidates" :key="f" :label="f" :value="f" />
              </el-select>
              <span v-else-if="report.refCandidates.length" class="text-emerald-600 dark:text-emerald-400">✓ {{ report.refCandidates[0] }}</span>
              <span v-else class="hint">Không có (không bắt buộc)</span>
            </td>
          </tr>
          <tr class="border-b border-slate-100 dark:border-zinc-800">
            <td class="py-2 font-medium">Thoại</td>
            <td class="py-2">
              <el-select v-if="report.voiceCandidates.length > 1" v-model="sel.voiceFile" clearable placeholder="Không dùng" class="w-full">
                <el-option v-for="f in report.voiceCandidates" :key="f" :label="f" :value="f" />
              </el-select>
              <span v-else-if="report.voiceCandidates.length" class="text-emerald-600 dark:text-emerald-400">✓ {{ report.voiceCandidates[0] }}</span>
              <span v-else class="hint">Không có (không bắt buộc)</span>
            </td>
          </tr>
          <tr class="border-b border-slate-100 dark:border-zinc-800">
            <td class="py-2 font-medium">Ảnh tham chiếu</td>
            <td class="py-2">
              <span v-if="report.refDir">📁 {{ report.refDir.name }} – {{ report.refDir.files }} ảnh</span>
              <span v-else class="hint">Chưa có thư mục (bước sau app tạo khi lưu ảnh)</span>
            </td>
          </tr>
          <tr class="border-b border-slate-100 dark:border-zinc-800">
            <td class="py-2 font-medium">Ảnh chờ duyệt</td>
            <td class="py-2">
              <span v-if="report.pendingDir">📁 {{ report.pendingDir.name }} – {{ report.pendingDir.files }} ảnh</span>
              <span v-else class="hint">Chưa có</span>
            </td>
          </tr>
          <tr>
            <td class="py-2 font-medium">Video</td>
            <td class="py-2">
              <span v-if="report.videoDir">📁 {{ report.videoDir.name }} – {{ report.videoDir.files }} file ({{ report.videoDir.scenes }} cảnh có video)</span>
              <span v-else class="hint">Chưa có</span>
            </td>
          </tr>
        </tbody>
      </table>

      <details v-if="report.excels.length" class="text-xs">
        <summary class="cursor-pointer text-slate-500">{{ report.excels.length }} file Excel trong thư mục</summary>
        <ul class="mt-1 space-y-0.5">
          <li v-for="f in report.excels" :key="f.name">
            {{ f.name }} –
            <span v-if="f.error" class="text-amber-600">{{ f.error }}</span>
            <span v-else>{{ f.kinds.map((k) => KIND[k]).join(' + ') }}</span>
          </li>
        </ul>
      </details>

      <el-alert v-for="p in report.problems" :key="p" :title="p" type="warning" :closable="false" show-icon />
    </div>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">Huỷ</el-button>
      <el-button type="primary" :disabled="blocked" :loading="busy === 'open'" @click="openProject">
        <el-icon><FolderOpened /></el-icon>&nbsp;{{ report?.projectId ? 'Quét lại & mở' : 'Mở dự án' }}
      </el-button>
    </template>
  </el-dialog>
</template>
