<script setup>
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

/**
 * Nhập cả thư mục ảnh tham chiếu (vd "Anh REF" gồm NHAN VAT / BOI CANH / DAO CU) vào dự án kịch bản:
 * 1. chọn / kéo thả thư mục (lấy cả thư mục con) hoặc nhiều file;
 * 2. backend gợi ý mã cho từng file theo tên file + thư mục (chưa tải ảnh lên);
 * 3. người dùng xem, sửa mã, bỏ file không cần → bấm Nhập: ảnh to được thu nhỏ rồi tải lên và gán cho mã.
 */
const props = defineProps({ modelValue: Boolean, projectId: Number, references: { type: Array, default: () => [] } })
const emit = defineEmits(['update:modelValue', 'done'])

const IMAGE_EXT = /\.(jpe?g|png|webp|bmp)$/i
const MAX_BYTES = 8 * 1024 * 1024
const MAX_SIDE = 4096
const TARGET_SIDE = 3072

const rows = ref([])
const matching = ref(false)
const importing = ref(false)
const progress = ref({ done: 0, total: 0, failed: [] })
const overwrite = ref(false)
const dragging = ref(false)
const skipped = ref(0)
const folderInput = ref(null)
const filesInput = ref(null)

const refByCode = computed(() => Object.fromEntries(props.references.map((r) => [r.code, r])))
const options = computed(() =>
  props.references.map((r) => ({ value: r.code, label: `${r.code}${r.file ? ' (đã có ảnh)' : ''}`, name: r.name }))
)
const dupCodes = computed(() => {
  const seen = {}
  for (const r of rows.value) if (r.code && r.include) seen[r.code] = (seen[r.code] || 0) + 1
  return new Set(Object.keys(seen).filter((k) => seen[k] > 1))
})
const toImport = computed(() =>
  rows.value.filter((r) => r.include && r.code && !dupCodes.value.has(r.code) && (overwrite.value || !refByCode.value[r.code]?.file))
)
const stats = computed(() => ({
  sure: rows.value.filter((r) => r.score >= 85).length,
  guess: rows.value.filter((r) => r.score > 0 && r.score < 85).length,
  none: rows.value.filter((r) => !r.code).length,
  existing: rows.value.filter((r) => r.include && r.code && refByCode.value[r.code]?.file).length,
  missingAfter: props.references.filter((r) => r.usedBy > 0 && !r.file && !toImport.value.some((x) => x.code === r.code)).length
}))

watch(
  () => props.modelValue,
  (open) => {
    if (open) return
    rows.value.forEach((r) => URL.revokeObjectURL(r.url))
    rows.value = []
    progress.value = { done: 0, total: 0, failed: [] }
  }
)

function close() {
  if (!importing.value) emit('update:modelValue', false)
}

// ---------- chọn file ----------

function onPick(e) {
  addFiles(Array.from(e.target.files || []).map((f) => ({ file: f, path: f.webkitRelativePath || f.name })))
  e.target.value = ''
}

async function onDrop(e) {
  dragging.value = false
  const items = Array.from(e.dataTransfer?.items || [])
  const out = []
  const entries = items.map((i) => i.webkitGetAsEntry?.()).filter(Boolean)
  if (entries.length) {
    for (const en of entries) await walk(en, '', out)
  } else {
    for (const f of Array.from(e.dataTransfer?.files || [])) out.push({ file: f, path: f.name })
  }
  addFiles(out)
}

function walk(entry, prefix, out) {
  return new Promise((resolve) => {
    if (entry.isFile) {
      entry.file(
        (f) => {
          out.push({ file: f, path: prefix + f.name })
          resolve()
        },
        () => resolve()
      )
    } else if (entry.isDirectory) {
      const reader = entry.createReader()
      const all = []
      const read = () =>
        reader.readEntries(async (batch) => {
          if (!batch.length) {
            for (const child of all) await walk(child, prefix + entry.name + '/', out)
            resolve()
          } else {
            all.push(...batch)
            read()
          }
        }, () => resolve())
      read()
    } else resolve()
  })
}

async function addFiles(list) {
  const imgs = list.filter((x) => IMAGE_EXT.test(x.file.name))
  skipped.value += list.length - imgs.length
  if (!imgs.length) {
    ElMessage.warning('Không có ảnh jpg / png / webp / bmp nào trong phần đã chọn')
    return
  }
  const known = new Set(rows.value.map((r) => r.path))
  const fresh = imgs
    .filter((x) => !known.has(x.path))
    .map((x, i) => ({ key: `${Date.now()}_${rows.value.length + i}`, file: x.file, path: x.path, url: URL.createObjectURL(x.file), code: null, score: 0, reason: '', include: true }))
  rows.value = [...rows.value, ...fresh]
  await rematch()
}

async function rematch() {
  if (!rows.value.length) return
  matching.value = true
  try {
    const res = await api.post(`/script-projects/${props.projectId}/references/match-files`, {
      files: rows.value.map((r) => ({ key: r.key, path: r.path }))
    })
    const byKey = Object.fromEntries(res.map((m) => [m.key, m]))
    rows.value = rows.value
      .map((r) => {
        const m = byKey[r.key]
        return r.manual ? r : { ...r, code: m?.code || null, score: m?.score || 0, reason: m?.reason || '' }
      })
      .sort((a, b) => (a.code ? 0 : 1) - (b.code ? 0 : 1) || a.path.localeCompare(b.path))
  } finally {
    matching.value = false
  }
}

function setCode(row, code) {
  row.code = code || null
  row.manual = true
  row.score = code ? 100 : 0
  row.reason = code ? 'chọn tay' : ''
}

// ---------- nhập ----------

/** Ảnh quá to (Seedance gửi ảnh dạng base64, cả request tối đa 64 MB) → thu nhỏ về cạnh dài 3072px, JPEG 92%. */
async function prepare(file, code) {
  let bmp
  try {
    bmp = await createImageBitmap(file)
  } catch {
    return file
  }
  const long = Math.max(bmp.width, bmp.height)
  if (file.size <= MAX_BYTES && long <= MAX_SIDE) {
    bmp.close?.()
    return file
  }
  const scale = Math.min(1, TARGET_SIDE / long)
  const canvas = document.createElement('canvas')
  canvas.width = Math.round(bmp.width * scale)
  canvas.height = Math.round(bmp.height * scale)
  canvas.getContext('2d').drawImage(bmp, 0, 0, canvas.width, canvas.height)
  bmp.close?.()
  const blob = await new Promise((res) => canvas.toBlob(res, 'image/jpeg', 0.92))
  return blob ? new File([blob], `${code}.jpg`, { type: 'image/jpeg' }) : file
}

async function upload(row) {
  const f = await prepare(row.file, row.code)
  const ext = (f.name.match(/\.[^.]+$/) || ['.jpg'])[0].toLowerCase()
  const fd = new FormData()
  fd.append('file', f, `${row.code}${ext}`)
  await api.post(`/script-projects/${props.projectId}/references/${encodeURIComponent(row.code)}/upload`, fd, { silent: true, timeout: 300000 })
}

async function run() {
  const list = [...toImport.value]
  if (!list.length) return
  importing.value = true
  progress.value = { done: 0, total: list.length, failed: [] }
  let next = 0
  const worker = async () => {
    while (next < list.length) {
      const row = list[next++]
      try {
        await upload(row)
        row.done = true
      } catch (e) {
        progress.value.failed.push(`${row.path} → ${row.code}: ${e.message}`)
      }
      progress.value.done++
    }
  }
  try {
    await Promise.all([worker(), worker(), worker()])
  } finally {
    importing.value = false
  }
  const ok = progress.value.total - progress.value.failed.length
  emit('done')
  if (progress.value.failed.length) {
    ElMessage.warning(`Đã nhập ${ok} ảnh, ${progress.value.failed.length} ảnh lỗi – xem danh sách trong hộp thoại`)
  } else {
    ElMessage.success(`Đã nhập ${ok} ảnh tham chiếu`)
    emit('update:modelValue', false)
  }
}

function tone(score) {
  if (score >= 85) return 'success'
  if (score > 0) return 'warning'
  return 'info'
}
</script>

<template>
  <el-dialog :model-value="modelValue" title="Nhập thư mục ảnh tham chiếu" width="1100px" :close-on-click-modal="false" @update:model-value="close">
    <input ref="folderInput" type="file" webkitdirectory directory multiple class="hidden" @change="onPick" />
    <input ref="filesInput" type="file" multiple accept="image/*" class="hidden" @change="onPick" />

    <div
      class="mb-4 flex flex-col items-center justify-center gap-3 rounded-xl border-2 border-dashed px-6 py-6 text-center transition"
      :class="dragging ? 'border-indigo-400 bg-indigo-50 dark:bg-indigo-500/10' : 'border-slate-200 bg-slate-50 dark:border-zinc-700 dark:bg-zinc-900/50'"
      @dragover.prevent="dragging = true"
      @dragleave.prevent="dragging = false"
      @drop.prevent="onDrop"
    >
      <div class="text-sm text-slate-600 dark:text-zinc-300">
        Kéo thả thư mục <b>Anh REF</b> (gồm NHAN VAT / BOI CANH / DAO CU) vào đây, hoặc
      </div>
      <div class="flex gap-2">
        <el-button type="primary" @click="folderInput.click()"><el-icon><FolderOpened /></el-icon>&nbsp;Chọn thư mục</el-button>
        <el-button @click="filesInput.click()"><el-icon><Picture /></el-icon>&nbsp;Chọn nhiều ảnh</el-button>
      </div>
      <p class="hint max-w-3xl">
        App đoán mã cho từng ảnh theo: tên file có mã (CHAR_YVO_NAVYTEE_01) → mã rút gọn (YVO_NAVYTEE_01) → thư mục + số thứ tự
        (BOI CANH/02.jpg → LOC_02…) → tên nhân vật / mô tả (vd “Yvonne dam nhung xanh”). Kiểm tra lại cột Mã trước khi nhập.
        Ảnh lớn hơn 8 MB hoặc 4096px được thu nhỏ trước khi gửi.
      </p>
    </div>

    <template v-if="rows.length">
      <div class="mb-3 flex flex-wrap items-center gap-3 text-sm">
        <el-tag type="success">{{ stats.sure }} chắc chắn</el-tag>
        <el-tag type="warning">{{ stats.guess }} đoán – nên xem lại</el-tag>
        <el-tag type="info">{{ stats.none }} chưa có mã</el-tag>
        <span v-if="skipped" class="hint">bỏ qua {{ skipped }} file không phải ảnh</span>
        <el-checkbox v-model="overwrite" class="ml-auto">Thay cả mã đã có ảnh ({{ stats.existing }})</el-checkbox>
        <el-button size="small" :loading="matching" @click="rematch"><el-icon><Refresh /></el-icon>&nbsp;Đoán lại</el-button>
      </div>

      <el-table :data="rows" max-height="440" size="small" row-key="key" v-loading="matching">
        <el-table-column width="46">
          <template #default="{ row }"><el-checkbox v-model="row.include" :disabled="importing" /></template>
        </el-table-column>
        <el-table-column label="Ảnh" width="84">
          <template #default="{ row }">
            <el-image :src="row.url" :preview-src-list="[row.url]" preview-teleported fit="cover" class="h-14 w-14 rounded-lg" />
          </template>
        </el-table-column>
        <el-table-column label="File" min-width="260">
          <template #default="{ row }">
            <div class="break-all text-xs">{{ row.path }}</div>
            <div class="text-[11px] text-slate-400">{{ (row.file.size / 1024 / 1024).toFixed(1) }} MB</div>
          </template>
        </el-table-column>
        <el-table-column label="Mã tham chiếu" min-width="300">
          <template #default="{ row }">
            <el-select :model-value="row.code" filterable clearable placeholder="Chọn mã…" class="w-full" size="small" :disabled="importing" @update:model-value="setCode(row, $event)">
              <el-option v-for="o in options" :key="o.value" :label="o.label" :value="o.value">
                <div class="flex justify-between gap-4"><span class="font-mono text-xs">{{ o.label }}</span><span class="truncate text-xs text-slate-400">{{ o.name }}</span></div>
              </el-option>
            </el-select>
            <div v-if="row.code && dupCodes.has(row.code)" class="mt-1 text-[11px] text-rose-600">Nhiều ảnh cùng chọn mã này – chỉ giữ 1</div>
            <div v-else-if="row.code && !overwrite && refByCode[row.code]?.file" class="mt-1 text-[11px] text-slate-400">Mã đã có ảnh – bỏ qua (tích “Thay cả mã đã có ảnh” để thay)</div>
          </template>
        </el-table-column>
        <el-table-column label="Độ tin" width="210">
          <template #default="{ row }">
            <el-icon v-if="row.done" class="text-emerald-500"><CircleCheckFilled /></el-icon>
            <el-tag v-else :type="tone(row.score)" size="small">{{ row.code ? row.score + '%' : '—' }}</el-tag>
            <div class="mt-0.5 text-[11px] text-slate-400">{{ row.reason }}</div>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="progress.total" class="mt-4">
        <el-progress :percentage="Math.round((progress.done * 100) / progress.total)" :status="progress.failed.length ? 'warning' : progress.done === progress.total ? 'success' : undefined" />
        <ul v-if="progress.failed.length" class="mt-2 max-h-32 overflow-auto text-xs text-rose-600">
          <li v-for="f in progress.failed" :key="f">• {{ f }}</li>
        </ul>
      </div>
    </template>

    <template #footer>
      <div class="flex items-center justify-between gap-3">
        <span class="hint text-left">
          <template v-if="rows.length">Sau khi nhập còn thiếu ảnh: {{ stats.missingAfter }} mã (có thể bấm “Tạo bằng AI” cho các mã này).</template>
        </span>
        <div>
          <el-button :disabled="importing" @click="close">Đóng</el-button>
          <el-button type="primary" :disabled="!toImport.length" :loading="importing" @click="run">
            Nhập {{ toImport.length }} ảnh
          </el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>
