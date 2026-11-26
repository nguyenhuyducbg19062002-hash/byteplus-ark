<script setup>
import { ref, reactive, computed, watch, onActivated, onDeactivated } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api, { formatTime } from '../api'
import { useAsync } from '../composables/useAsync'
import { useModelsStore } from '../stores/models'
import { useSettingsStore } from '../stores/settings'
import PageHeader from '../components/PageHeader.vue'
import AsyncState from '../components/AsyncState.vue'
import StatusBadge from '../components/StatusBadge.vue'
import LibraryPicker from '../components/LibraryPicker.vue'
import RefFolderImport from '../components/RefFolderImport.vue'

/**
 * Kịch bản Seedance chạy hàng loạt (luồng như Flow Batch Studio):
 * 1. Nhập Excel kịch bản → mỗi dòng có MÃ là một clip, mã CHAR_/LOC_/PROP_ là ảnh tham chiếu.
 * 2. Gán ảnh cho từng mã (tải lên, chọn từ thư viện, hoặc tự ghép theo tên file).
 * 3. Bấm Chạy → backend tự gửi clip theo thứ tự, song song tối đa N, tự thử lại khi lỗi tạm thời.
 * 4. Xem video, làm lại clip hỏng theo mã (kèm ghi chú sửa).
 */
const models = useModelsStore()
const appSettings = useSettingsStore()
/** Thư mục ảnh đã duyệt khi để app tự tạo: <Thư mục lưu (Cài đặt)>/assets/<dự án> – cùng gốc với video. */
const projectRefDir = computed(() => {
  const root = appSettings.settings?.outputDir
  const sep = root?.includes('/') && !root.includes('\\') ? '/' : '\\'
  return root && project.value ? [root, 'assets', project.value.folder].join(sep) : ['output', 'assets', '<dự án>'].join(sep)
})
const projects = useAsync(() => api.get('/script-projects', { silent: true }), { initial: [] })
const currentId = ref(null)
const project = computed(() => (projects.data.value || []).find((p) => p.id === currentId.value) || null)

const tab = ref('clips')
const filter = ref('')
const query = reactive({ page: 1, size: 20, q: '' })
const clips = useAsync(
  () =>
    api.get(`/script-projects/${currentId.value}/clips`, {
      params: { page: query.page, size: query.size, status: filter.value || undefined, q: query.q || undefined },
      silent: true
    }),
  { initial: { items: [], total: 0 } }
)
const refs = useAsync(() => api.get(`/script-projects/${currentId.value}/references`, { silent: true }), { initial: [] })
/** Cảnh của kịch bản (theo cột cảnh trong Excel) để chọn chạy theo cảnh. */
const scenes = useAsync(() => api.get(`/script-projects/${currentId.value}/scenes`, { silent: true }), { initial: [] })
const clipTable = ref(null)
/** Clip đang tick chọn (giữ qua các trang). */
const selectedClips = ref([])
const sceneSel = ref([])
const redoDone = ref(false)

const importing = ref(false)
const importResult = ref(null)
const busy = ref('')
const settings = reactive({ model: '', resolution: '', ratio: '16:9', generateAudio: true, maxParallel: 3, maxAttempts: 3, imageModel: '', imageSize: '', promptModel: '', autoApproveImages: false, refSaveMode: 'PROJECT', refSaveDir: '', provider: 'BYTEPLUS', botsdModel: 'sd25', botsdResolution: 'original', botsdRealPerson: 'OFF', api888Model: 'seedance2.0-933-c5-fast' })
const settingsErrors = ref({})
const redo = reactive({ codes: '', note: '' })
const expanded = ref([])
const drafts = reactive({})
const pickerFor = ref(null)
const promptFor = ref(null)
const promptDraft = ref('')
const folderImport = ref(false)
const folderImportFiles = ref([])
const excelInput = ref(null)
const resultFolderInput = ref(null)
let excelIntoCurrent = false

const omniModels = computed(() => models.video.filter((m) => m.omni))
const settingsModel = computed(() => omniModels.value.find((m) => m.id === settings.model))
// Nguồn tạo video thứ hai: BOTSD (botsd.pro) – giá cơ bản theo tài liệu 10/11/2026, giá thật xem ở Cài đặt › BOTSD
const BOTSD_MODELS = [
  { id: 'sd20', name: 'Seedance 2.0', hint: '5/10/15s · 2/6/8P' },
  { id: 'sd25', name: 'Seedance 2.5', hint: '5/10/15/30s · 9/18/25/50P' },
  { id: 'sd25hd', name: 'Seedance 2.5 1080P', hint: '5/10/15/30s · 12/24/35/70P' },
  { id: 'muse', name: 'Muse AI', hint: '5/10s · 2/4P · tối đa 1 ảnh, chỉ 16:9 / 9:16' }
]
const botsdModelName = (id) => BOTSD_MODELS.find((m) => m.id === id)?.name || id
// Nguồn thứ ba: 888API (888api.xin) – danh sách model lấy từ backend (giới hạn thời lượng / tỉ lệ / số ảnh)
const api888Models = ref([])
const api888Model = computed(() => api888Models.value.find((m) => m.id === settings.api888Model))
async function loadApi888Models() {
  if (api888Models.value.length) return
  try {
    api888Models.value = await api.get('/settings/api888/models', { silent: true })
  } catch {
    api888Models.value = []
  }
}
watch(() => settings.provider, (p) => p === 'API888' && loadApi888Models(), { immediate: true })
const providerRaw = computed(() => settings.provider === 'BOTSD' || settings.provider === 'API888')
const imageModel = computed(() => models.image.find((m) => m.id === settings.imageModel))
const IMAGE_STATUS = {
  PENDING: { status: 'queued', label: 'Chờ tạo ảnh' },
  GENERATING: { status: 'running', label: 'Đang tạo ảnh' },
  REVIEW: { status: 'info', label: 'Chờ duyệt' },
  SUCCEEDED: { status: 'succeeded', label: 'AI đã tạo' },
  FAILED: { status: 'failed', label: 'Tạo ảnh lỗi' }
}
const missingRefs = computed(() => refs.data.value.filter((r) => !r.file && r.usedBy > 0).length)
/** Mã có ảnh AI chờ duyệt được đưa lên đầu lưới. */
const sortedRefs = computed(() => {
  const list = refs.data.value || []
  return [...list.filter((r) => r.imageStatus === 'REVIEW'), ...list.filter((r) => r.imageStatus !== 'REVIEW')]
})
/** Ảnh nhân vật đã có nhưng chưa có asset Virtual Portrait Active. */
const vpPending = computed(() =>
  refs.data.value.filter((r) => r.file && (r.kind === 'CHARACTER' || r.blockedReason) && r.file.assetStatus !== 'Active' && r.file.assetStatus !== 'Processing')
)

const CLIP_STATUS = {
  PENDING: { status: 'off', label: 'Chờ chạy' },
  GENERATING: { status: 'running', label: 'Đang tạo' },
  SUCCEEDED: { status: 'succeeded', label: 'Xong' },
  FAILED: { status: 'failed', label: 'Lỗi' },
  CANCELLED: { status: 'cancelled', label: 'Đã huỷ' }
}
const PROJECT_STATUS = {
  DRAFT: { status: 'off', label: 'Chưa chạy' },
  RUNNING: { status: 'running', label: 'Đang chạy' },
  PAUSED: { status: 'queued', label: 'Tạm dừng' },
  DONE: { status: 'succeeded', label: 'Đã xong' }
}
const KIND = { CHARACTER: 'Nhân vật', LOCATION: 'Bối cảnh', PROP: 'Đạo cụ', OTHER: 'Khác' }

const tiles = computed(() => {
  const c = project.value?.counts || {}
  return [
    { key: '', label: 'Tổng clip', value: c.TOTAL, tone: 'text-slate-700 dark:text-zinc-100' },
    { key: 'PENDING', label: 'Chờ chạy', value: c.PENDING, tone: 'text-slate-600 dark:text-zinc-300' },
    { key: 'BLOCKED', label: 'Chờ ảnh', value: project.value?.blockedClips, tone: 'text-rose-600 dark:text-rose-400' },
    { key: 'GENERATING', label: 'Đang tạo', value: c.GENERATING, tone: 'text-amber-600 dark:text-amber-400' },
    { key: 'SUCCEEDED', label: 'Xong', value: c.SUCCEEDED, tone: 'text-emerald-600 dark:text-emerald-400' },
    { key: 'FAILED', label: 'Lỗi', value: c.FAILED, tone: 'text-rose-600 dark:text-rose-400' },
    { key: 'CANCELLED', label: 'Đã huỷ', value: c.CANCELLED, tone: 'text-slate-500' },
    { key: 'WARNING', label: 'Cảnh báo', value: project.value?.warningClips, tone: 'text-indigo-600 dark:text-indigo-400' }
  ]
})
const progress = computed(() => {
  const c = project.value?.counts
  return c?.TOTAL ? Math.round(((c.SUCCEEDED || 0) * 100) / c.TOTAL) : 0
})

// ---------- tải dữ liệu + tự làm mới ----------

let timer = null
async function refresh(all = true) {
  await projects.run()
  if (!currentId.value && projects.data.value?.length) currentId.value = projects.data.value[0].id
  if (currentId.value && !project.value) currentId.value = projects.data.value?.[0]?.id || null
  const imgs = project.value?.imagesPending > 0
  if (currentId.value && all) {
    await Promise.all([clips.run(), scenes.run(), tab.value === 'refs' || imgs ? refs.run() : Promise.resolve()])
  }
}
async function tick() {
  clearTimeout(timer)
  await refresh()
  const live = project.value?.status === 'RUNNING' || project.value?.counts?.GENERATING > 0 || project.value?.imagesPending > 0
  timer = setTimeout(tick, live ? 4000 : 15000)
}
onActivated(() => {
  models.ensure()
  if (!appSettings.settings) appSettings.load()
  tick()
})
onDeactivated(() => clearTimeout(timer))

watch(currentId, () => {
  query.page = 1
  filter.value = ''
  expanded.value = []
  importResult.value = null
  clearSelection()
  if (project.value) Object.assign(settings, pick(project.value))
  if (currentId.value) {
    clips.run()
    refs.run()
  }
})
watch(project, (p, old) => {
  if (p && (!old || old.id !== p.id)) Object.assign(settings, pick(p))
})
watch(tab, (t) => t === 'refs' && currentId.value && refs.run())
watch(filter, () => {
  query.page = 1
  clips.run()
})
function pick(p) {
  return {
    model: p.model, resolution: p.resolution, ratio: p.ratio, generateAudio: p.generateAudio, maxParallel: p.maxParallel,
    maxAttempts: p.maxAttempts, imageModel: p.imageModel, imageSize: p.imageSize, promptModel: p.promptModel, autoApproveImages: p.autoApproveImages,
    refSaveMode: p.refSaveMode || 'PROJECT', refSaveDir: p.refSaveDir || '',
    provider: p.provider || 'BYTEPLUS', botsdModel: p.botsdModel || 'sd25', botsdResolution: p.botsdResolution || 'original',
    botsdRealPerson: p.botsdRealPerson || 'OFF', api888Model: p.api888Model || 'seedance2.0-933-c5-fast'
  }
}
watch(
  () => settings.model,
  () => {
    const m = settingsModel.value
    if (m && !m.resolutions.includes(settings.resolution)) settings.resolution = m.defaultResolution
  }
)
watch(
  () => settings.imageModel,
  () => {
    const m = imageModel.value
    if (m && !m.sizes.includes(settings.imageSize)) settings.imageSize = m.defaultSize
  }
)

// ---------- dự án ----------

// Chọn một hoặc nhiều file .xlsx (vd all-video-prompts + reference-prompts + voice-overlay) rồi nhập một lần
function pickExcel(intoCurrent) {
  excelIntoCurrent = intoCurrent
  excelInput.value.click()
}

function onExcelPicked(e) {
  const files = Array.from(e.target.files || [])
  e.target.value = ''
  if (files.length) importExcel(files, excelIntoCurrent)
}

const XLSX = /\.xlsx$/i
const REF_IMAGE = /\.(jpe?g|png|webp|bmp)$/i
// thư mục ảnh chưa dùng được: ảnh chờ duyệt / lỗi
const SKIP_DIR = /(^|\/)(ảnh chờ duyệt|anh cho duyet|lỗi|loi|_cu|_cho-duyet)(\/|$)/i

/**
 * Nhập cả thư mục kết quả của tool viết kịch bản (vd "KET QUA - IDE 21"): mọi file .xlsx trong thư mục gốc được nhập
 * thành dự án, sau đó ảnh trong "Ảnh tham chiếu" (tên file = mã, vd CHAR-DANIEL-1.jpg) được mở sẵn để ghép mã và tải lên.
 */
async function onResultFolderPicked(e, intoCurrent) {
  const all = Array.from(e.target.files || []).map((f) => ({ file: f, path: f.webkitRelativePath || f.name }))
  e.target.value = ''
  const depth = (p) => p.split('/').length
  const excels = all.filter((x) => XLSX.test(x.file.name) && !x.file.name.startsWith('~$') && depth(x.path) <= 2).map((x) => x.file)
  const images = all.filter((x) => REF_IMAGE.test(x.file.name) && !SKIP_DIR.test(x.path.normalize('NFC')))
  if (!excels.length) {
    ElMessage.warning('Thư mục không có file .xlsx nào (cần all-video-prompts.xlsx hoặc file kịch bản)')
    return
  }
  const ok = await importExcel(excels, intoCurrent)
  if (ok && images.length) {
    folderImportFiles.value = images
    tab.value = 'refs'
    folderImport.value = true
  }
}

async function importExcel(fileOrFiles, intoCurrent) {
  const files = Array.isArray(fileOrFiles) ? fileOrFiles : [fileOrFiles]
  importing.value = true
  try {
    const fd = new FormData()
    for (const f of files) fd.append('files', f)
    if (intoCurrent && currentId.value) fd.append('projectId', currentId.value)
    const r = await api.post('/script-projects/import', fd, { timeout: 300000 })
    importResult.value = r
    currentId.value = r.project.id
    ElMessage.success(`Đã nhập ${r.created + r.updated + r.unchanged} clip (${r.created} mới, ${r.updated} cập nhật)`)
    await refresh()
    refs.run()
    if (r.project.refsMissing) tab.value = 'refs'
    if (r.warnings?.length) console.info('Cảnh báo khi nhập:', r.warnings)
    return true
  } catch {
    return false
  } finally {
    importing.value = false
  }
}

async function act(name, fn) {
  busy.value = name
  try {
    await fn()
    await refresh()
  } finally {
    busy.value = ''
  }
}
const start = () => act('start', () => api.post(`/script-projects/${currentId.value}/start`))
const pause = () => act('pause', () => api.post(`/script-projects/${currentId.value}/pause`))
const retryFailed = () => act('retry', () => api.post(`/script-projects/${currentId.value}/retry-failed`))
const resumeSelected = () => act('resume', () => api.post(`/script-projects/${currentId.value}/resume-selected`))

// ---------- chạy clip / cảnh đã chọn (không kéo theo cả kịch bản) ----------

function onSelect(rows) {
  selectedClips.value = rows
}
function clearSelection() {
  clipTable.value?.clearSelection()
  selectedClips.value = []
}
async function runPicked(body, what) {
  await act('runPicked', () => api.post(`/script-projects/${currentId.value}/run-clips`, { ...body, redoDone: redoDone.value }))
  ElMessage.success(`Đang chạy ${what} – các clip khác không bị gửi`)
}
async function runSelectedClips() {
  const ids = selectedClips.value.map((r) => r.id)
  await runPicked({ clipIds: ids }, `${ids.length} clip đã chọn`)
  clearSelection()
}
async function runScenes() {
  await runPicked({ scenes: sceneSel.value }, sceneSel.value.length === 1 ? `cảnh “${sceneSel.value[0]}”` : `${sceneSel.value.length} cảnh`)
  sceneSel.value = []
}

async function saveSettings() {
  settingsErrors.value = {}
  try {
    await act('settings', () => api.put(`/script-projects/${currentId.value}`, settings))
    ElMessage.success('Đã lưu cấu hình – áp dụng cho các clip gửi tiếp theo')
  } catch (e) {
    settingsErrors.value = e.fields || {}
  }
}

async function removeProject() {
  await ElMessageBox.confirm(`Xoá dự án "${project.value.name}"? Video đã tạo vẫn giữ trong thư viện.`, 'Xoá dự án', { type: 'warning' })
  await act('delete', () => api.delete(`/script-projects/${currentId.value}`))
  currentId.value = projects.data.value?.[0]?.id || null
}

async function submitRedo() {
  await act('redo', () => api.post(`/script-projects/${currentId.value}/redo`, redo))
  ElMessage.success('Đã đưa các clip vào hàng đợi làm lại (ưu tiên chạy trước)')
  redo.codes = ''
  redo.note = ''
}

// ---------- clip ----------

function toggle(row, column) {
  if (column?.type === 'selection') return // bấm ô tick chỉ để chọn, không mở chi tiết
  const i = expanded.value.indexOf(row.id)
  if (i >= 0) expanded.value.splice(i, 1)
  else {
    expanded.value.push(row.id)
    drafts[row.id] = { prompt: row.prompt, duration: row.duration }
  }
}
async function saveClip(row, run) {
  await act('clip' + row.id, () => api.put(`/script-clips/${row.id}`, { ...drafts[row.id], run }))
  ElMessage.success(run ? `Đã lưu và chạy riêng clip ${row.code}` : 'Đã lưu prompt')
}
const runClip = (row) => act('clip' + row.id, () => api.post(`/script-clips/${row.id}/run`))
const cancelClip = (row) => act('clip' + row.id, () => api.post(`/script-clips/${row.id}/cancel`))
/** AI (Dola-Seed) chỉ ra bằng tiếng Việt vì sao clip bị kiểm duyệt chặn: loại vi phạm, đoạn nghi vấn, prompt đã sửa. */
async function analyzeBlock(row) {
  await act('analyze' + row.id, () => api.post(`/script-clips/${row.id}/analyze-block`, null, { timeout: 200000 }))
  await clips.run()
}
/** Đưa prompt AI đã sửa vào ô soạn – xem lại rồi bấm “Lưu & tạo lại”. */
function useFixedPrompt(row) {
  if (!drafts[row.id]) drafts[row.id] = { prompt: row.prompt, duration: row.duration }
  drafts[row.id].prompt = row.blockAnalysis.fixedPrompt
  ElMessage.success('Đã đưa prompt đã sửa vào ô soạn – xem lại rồi bấm “Lưu & tạo lại”')
}
const isBlockError = (row) => row.status === 'FAILED' && /SensitiveContent|kiểm duyệt|Bị từ chối/i.test(row.error || '')
function search() {
  query.page = 1
  clips.run()
}

// ---------- ảnh tham chiếu ----------

async function uploadRef(code, file) {
  const fd = new FormData()
  fd.append('file', file)
  await act('ref' + code, () => api.post(`/script-projects/${currentId.value}/references/${encodeURIComponent(code)}/upload`, fd))
  await refs.run()
}
async function setRef(code, fileId) {
  await act('ref' + code, () => api.put(`/script-projects/${currentId.value}/references/${encodeURIComponent(code)}`, { fileId }))
  await refs.run()
}
function onPicked(_refs, files) {
  if (pickerFor.value && files?.[0]) setRef(pickerFor.value, files[0].id)
  pickerFor.value = null
}
async function generateRefs(codes = [], overwrite = false) {
  await act('gen' + (codes[0] || ''), async () => {
    refs.data.value = await api.post(`/script-projects/${currentId.value}/references/generate`, { codes, overwrite })
  })
  ElMessage.success(codes.length ? `Đã đưa ${codes.join(', ')} vào hàng đợi tạo ảnh` : 'Đã đưa các mã còn thiếu vào hàng đợi tạo ảnh')
}
/** Duyệt ảnh AI: backend đổi tên thành MÃ.đuôi trong thư mục ảnh tham chiếu của dự án (tự tạo thư mục) và gán cho mã. */
async function approveRef(r) {
  await act('approve' + r.code, () => api.post(`/script-projects/${currentId.value}/references/${encodeURIComponent(r.code)}/approve`))
  await refs.run()
  ElMessage.success(`Đã duyệt ${r.code} – lưu vào ảnh tham chiếu`)
}
async function rejectRef(r) {
  await act('reject' + r.code, () => api.post(`/script-projects/${currentId.value}/references/${encodeURIComponent(r.code)}/reject`))
  await refs.run()
}
async function approveAll() {
  const n = project.value?.imagesReview || 0
  await ElMessageBox.confirm(`Duyệt ${n} ảnh đang chờ? Ảnh được đổi tên theo mã và dùng ngay cho clip.`, 'Duyệt tất cả', { type: 'info' })
  try {
    await act('approveAll', async () => {
      refs.data.value = await api.post(`/script-projects/${currentId.value}/references/approve-all`)
    })
    ElMessage.success(`Đã duyệt ${n} ảnh`)
  } finally {
    refs.run()
  }
}
async function stopRefs() {
  await act('stopgen', async () => {
    refs.data.value = await api.post(`/script-projects/${currentId.value}/references/stop-generate`)
  })
}
/** Tạo ảnh còn thiếu rồi chạy video – clip tự chạy khi đủ ảnh. */
async function runAll() {
  busy.value = 'all'
  try {
    if (project.value.refsMissing && !project.value.imagesPending) {
      refs.data.value = await api.post(`/script-projects/${currentId.value}/references/generate`, {})
    }
    await api.post(`/script-projects/${currentId.value}/start`)
    ElMessage.success('Đang tạo ảnh còn thiếu – clip nào đủ ảnh sẽ tự chạy video')
    await refresh()
  } finally {
    busy.value = ''
  }
}
function openPrompt(r) {
  promptFor.value = r
  promptDraft.value = r.imagePrompt || ''
}
/** Trong hộp thoại Prompt: model Language viết bản mới vào ô soạn (chưa lưu – xem rồi bấm Lưu). */
async function aiDraft() {
  const r = promptFor.value
  busy.value = 'aiDraft'
  try {
    const res = await api.post(`/script-projects/${currentId.value}/references/${encodeURIComponent(r.code)}/ai-prompt`, null, { timeout: 200000 })
    promptDraft.value = res.prompt
    ElMessage.success('AI đã viết prompt mới – xem lại rồi bấm Lưu')
  } finally {
    busy.value = ''
  }
}
/** Mã đang cần ảnh (clip dùng, chưa có ảnh, không đang tạo / chờ duyệt) và prompt chưa sửa tay. */
const aiTargets = computed(() =>
  (refs.data.value || []).filter((r) => r.usedBy > 0 && !r.file && !r.promptEdited && !['PENDING', 'GENERATING', 'REVIEW'].includes(r.imageStatus))
)
const aiProgress = ref('')
/** Model Language viết lại prompt cho mọi mã còn thiếu ảnh (3 mã song song), lưu luôn. */
async function aiWriteAll() {
  const list = aiTargets.value
  if (!list.length) return
  const name = models.text.find((m) => m.id === project.value?.promptModel)?.name || project.value?.promptModel
  await ElMessageBox.confirm(
    `${name} sẽ viết lại prompt ảnh cho ${list.length} mã còn thiếu ảnh (bỏ qua mã đã sửa prompt tay). Mỗi mã tốn một ít token chat. Tiếp tục?`,
    'AI viết prompt ảnh',
    { type: 'info' }
  )
  busy.value = 'aiAll'
  let done = 0
  const failed = []
  const queue = [...list]
  aiProgress.value = `0/${list.length}`
  const worker = async () => {
    while (queue.length) {
      const r = queue.shift()
      try {
        await api.post(`/script-projects/${currentId.value}/references/${encodeURIComponent(r.code)}/ai-prompt`, null, {
          params: { save: true },
          timeout: 200000,
          silent: true
        })
      } catch (e) {
        failed.push(`${r.code}: ${e.message || e}`)
        if (/chưa bật model|ModelNotOpen|API key/i.test(String(e.message))) queue.length = 0 // lỗi tài khoản → dừng hết
      }
      done++
      aiProgress.value = `${done}/${list.length}`
    }
  }
  try {
    await Promise.all([worker(), worker(), worker()])
  } finally {
    busy.value = ''
    aiProgress.value = ''
    await refs.run()
  }
  if (failed.length) {
    ElMessageBox.alert(failed.slice(0, 5).join('\n\n'), `Lỗi ${failed.length} mã`, { type: 'error' })
  } else {
    ElMessage.success(`Đã viết prompt cho ${list.length} mã – bấm “Tạo bằng AI các ảnh còn thiếu” để tạo ảnh`)
  }
}
async function savePrompt(generate) {
  const r = promptFor.value
  await act('prompt', () => api.put(`/script-projects/${currentId.value}/references/${encodeURIComponent(r.code)}/prompt`, { prompt: promptDraft.value, generate }))
  await refs.run()
  promptFor.value = null
  ElMessage.success(generate ? `Đã lưu prompt và đưa ${r.code} vào hàng đợi tạo ảnh` : 'Đã lưu prompt ảnh')
}
async function pushCharactersToVp() {
  const ids = [...new Set(vpPending.value.map((r) => r.file.id))]
  if (!ids.length) return
  await act('vp', () => api.post('/assets', { fileIds: ids }))
  ElMessage.success(`Đang đẩy ${ids.length} ảnh lên Virtual Portrait (ảnh trên máy được tải lên TOS trước) – xem tiến độ ở trang Tiến trình`)
}
async function autoMatch() {
  const before = missingRefs.value
  await act('auto', async () => {
    refs.data.value = await api.post(`/script-projects/${currentId.value}/references/auto-match`)
  })
  const n = before - missingRefs.value
  ElMessage[n > 0 ? 'success' : 'info'](n > 0 ? `Đã ghép ${n} mã theo tên file` : 'Không có ảnh nào trong thư viện trùng tên mã còn thiếu')
}
/** Asset VP Active (upload trên console, đặt tên theo mã) → tải về + gán cho mã, clip gửi asset://. */
async function matchVpAssets() {
  const activeCount = () => refs.data.value.filter((r) => r.file?.assetId && r.file.assetStatus === 'Active').length
  const before = activeCount()
  await act('vpmatch', async () => {
    refs.data.value = await api.post(`/script-projects/${currentId.value}/references/match-vp-assets`)
  })
  const n = activeCount() - before
  ElMessage[n > 0 ? 'success' : 'info'](n > 0
    ? `Đã ghép ${n} mã với asset Virtual Portrait – clip sẽ gửi asset://`
    : 'Không có asset Active nào có tên asset hoặc tên group trùng mã (vd CHAR_AUD_PINKSUIT_01) – kiểm tra asset đã Active và đúng project')
}
/** Tải hàng loạt về máy (.zip): videos | images | all – trình duyệt tự tải, file lớn vẫn chạy (stream). */
function downloadZip(what) {
  const doneVideos = project.value?.counts?.SUCCEEDED || 0
  const refImages = (refs.data.value || []).filter((r) => r.file).length
  if ((what === 'videos' && !doneVideos) || (what === 'images' && !refImages) || (what === 'all' && !doneVideos && !refImages)) {
    ElMessage.info(what === 'images' ? 'Chưa có ảnh tham chiếu nào được gán' : 'Chưa có video nào tạo xong')
    return
  }
  const a = document.createElement('a')
  a.href = `/api/v1/script-projects/${currentId.value}/download?what=${what}`
  a.click()
  ElMessage.success('Đang nén và tải về – file lớn có thể mất một lúc, xem thanh tải của trình duyệt')
}
</script>

<template>
  <div class="mx-auto max-w-[1500px] p-6 lg:p-8">
    <PageHeader title="Kịch bản hàng loạt" subtitle="Nhập Excel kịch bản Seedance → gán ảnh tham chiếu → tự động gửi từng clip" icon="Film" />

    <!-- chọn / nhập dự án -->
    <section class="card mb-6 flex flex-wrap items-center gap-3 p-4">
      <el-select v-model="currentId" placeholder="Chọn dự án kịch bản" class="w-80" :disabled="!projects.data.value?.length">
        <el-option v-for="p in projects.data.value" :key="p.id" :label="p.name" :value="p.id">
          <div class="flex items-center justify-between gap-4">
            <span>{{ p.name }}</span>
            <span class="text-xs text-slate-400">{{ p.counts?.SUCCEEDED || 0 }}/{{ p.counts?.TOTAL || 0 }}</span>
          </div>
        </el-option>
      </el-select>
      <input ref="excelInput" type="file" multiple accept=".xlsx" class="hidden" @change="onExcelPicked" />
      <input ref="resultFolderInput" type="file" webkitdirectory directory multiple class="hidden" @change="(e) => onResultFolderPicked(e, false)" />
      <el-tooltip content="Chọn được nhiều file cùng lúc, vd all-video-prompts.xlsx + reference-prompts.xlsx + voice-overlay.xlsx" placement="bottom">
        <el-button type="primary" :loading="importing" @click="pickExcel(false)"><el-icon><Upload /></el-icon>&nbsp;Nhập Excel mới</el-button>
      </el-tooltip>
      <el-tooltip content="Chọn cả thư mục kết quả (vd KET QUA - IDE 21): nhập mọi file Excel rồi ghép luôn ảnh trong “Ảnh tham chiếu”" placement="bottom">
        <el-button type="success" plain :loading="importing" @click="resultFolderInput.click()"><el-icon><FolderOpened /></el-icon>&nbsp;Nhập thư mục kết quả</el-button>
      </el-tooltip>
      <el-button v-if="project" :loading="importing" :disabled="project.status === 'RUNNING'" @click="pickExcel(true)"><el-icon><RefreshRight /></el-icon>&nbsp;Nhập lại vào dự án này</el-button>
      <span v-if="project" class="hint ml-auto">File: {{ project.sourceFile }} · tạo {{ formatTime(project.createdAt) }} · video lưu ở output/videos/{{ project.folder }}</span>
    </section>

    <AsyncState
      :loading="projects.loading.value && !projects.data.value?.length"
      :error="projects.error.value"
      :empty="!projects.data.value?.length"
      empty-text="Chưa có kịch bản nào. Bấm “Nhập Excel mới” và chọn file kịch bản (.xlsx) có sheet KỊCH BẢN, NHÂN VẬT, BỐI CẢNH, ĐẠO CỤ."
      empty-icon="Film"
      @retry="tick"
    >
      <template v-if="project">
        <!-- kết quả nhập -->
        <el-alert v-if="importResult" type="success" class="mb-4" show-icon @close="importResult = null">
          <template #title>
            Đã nhập {{ importResult.created }} clip mới, {{ importResult.updated }} cập nhật, {{ importResult.unchanged }} không đổi ·
            {{ importResult.references }} mã ảnh tham chiếu ({{ importResult.autoMatched }} tự ghép được theo tên file)
          </template>
          <div v-if="importResult.warnings.length" class="mt-1 text-xs">
            {{ importResult.warnings.length }} cảnh báo – lọc “Cảnh báo” để xem từng clip.
          </div>
        </el-alert>

        <!-- trạng thái + điều khiển -->
        <section class="card mb-6 p-5">
          <div class="flex flex-wrap items-center gap-3">
            <h2 class="text-lg font-semibold">{{ project.name }}</h2>
            <StatusBadge :status="PROJECT_STATUS[project.status].status" :label="PROJECT_STATUS[project.status].label" />
            <el-tag v-if="project.onlySelected" type="warning" effect="plain" size="small">
              Chỉ chạy clip đã chọn{{ project.selectedPending ? ` · còn ${project.selectedPending}` : '' }}
            </el-tag>
            <div class="ml-auto flex flex-wrap gap-2">
              <el-button v-if="project.status !== 'RUNNING' && project.refsMissing" type="primary" :loading="busy === 'all'" @click="runAll">
                <el-icon><MagicStick /></el-icon>&nbsp;Tạo ảnh thiếu + chạy video
              </el-button>
              <el-button v-if="project.status !== 'RUNNING' && project.onlySelected && project.selectedPending" type="primary" :loading="busy === 'resume'" @click="resumeSelected">
                <el-icon><VideoPlay /></el-icon>&nbsp;Tiếp tục {{ project.selectedPending }} clip đã chọn
              </el-button>
              <el-button
                v-if="project.status !== 'RUNNING'"
                :type="project.refsMissing || project.onlySelected ? 'default' : 'primary'"
                :loading="busy === 'start'"
                title="Chạy mọi clip đang chờ của cả kịch bản"
                @click="start"
              >
                <el-icon><VideoPlay /></el-icon>&nbsp;Chạy tất cả clip
              </el-button>
              <el-button v-else type="warning" plain :loading="busy === 'pause'" @click="pause">
                <el-icon><VideoPause /></el-icon>&nbsp;Tạm dừng
              </el-button>
              <el-button :disabled="!(project.counts.FAILED + project.counts.CANCELLED)" :loading="busy === 'retry'" @click="retryFailed">
                <el-icon><RefreshLeft /></el-icon>&nbsp;Chạy lại clip lỗi ({{ project.counts.FAILED + project.counts.CANCELLED }})
              </el-button>
              <el-dropdown trigger="click" @command="downloadZip">
                <el-button type="success" plain><el-icon><Download /></el-icon>&nbsp;Tải về máy</el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="videos">Tất cả video đã xong ({{ project.counts.SUCCEEDED || 0 }}) – .zip</el-dropdown-item>
                    <el-dropdown-item command="images">Tất cả ảnh tham chiếu – .zip</el-dropdown-item>
                    <el-dropdown-item command="all" divided>Video + ảnh tham chiếu – .zip</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
              <el-button type="danger" plain :disabled="project.status === 'RUNNING'" :loading="busy === 'delete'" @click="removeProject">
                <el-icon><Delete /></el-icon>
              </el-button>
            </div>
          </div>
          <p v-if="project.pausedReason" class="mt-2 text-sm" :class="project.status === 'DONE' ? 'text-emerald-600' : 'text-amber-600 dark:text-amber-400'">
            {{ project.pausedReason }}
          </p>
          <p v-if="project.imagesPending" class="mt-1 text-sm text-indigo-600 dark:text-indigo-400">
            Đang tạo {{ project.imagesPending }} ảnh tham chiếu bằng AI – clip dùng các ảnh này sẽ chạy khi ảnh được duyệt.
          </p>
          <p v-if="project.imagesReview" class="mt-1 text-sm text-indigo-600 dark:text-indigo-400">
            Có {{ project.imagesReview }} ảnh AI đang chờ duyệt –
            <el-link type="primary" @click="tab = 'refs'">mở tab Ảnh tham chiếu</el-link> để duyệt / tạo lại. Clip dùng các mã này chờ đến khi ảnh được duyệt.
          </p>
          <p v-if="project.cooldownUntil && project.status === 'RUNNING'" class="mt-1 text-xs text-amber-600">
            BytePlus đang giới hạn tốc độ – tạm nghỉ đến {{ formatTime(project.cooldownUntil) }}
          </p>
          <el-progress class="mt-4" :percentage="progress" :stroke-width="10" :status="progress === 100 ? 'success' : undefined" />

          <div class="mt-5 grid grid-cols-2 gap-3 sm:grid-cols-4 xl:grid-cols-8">
            <button
              v-for="t in tiles"
              :key="t.key"
              type="button"
              class="rounded-xl bg-slate-50 px-4 py-3 text-left ring-1 transition hover:shadow-sm dark:bg-zinc-900/50"
              :class="filter === t.key && tab === 'clips' ? 'ring-indigo-500' : 'ring-slate-200/70 dark:ring-zinc-700/60'"
              @click="(filter = t.key), (tab = 'clips')"
            >
              <div class="text-2xl font-semibold tabular-nums" :class="t.tone">{{ t.value ?? 0 }}</div>
              <div class="text-xs text-slate-500 dark:text-zinc-400">{{ t.label }}</div>
            </button>
          </div>

          <!-- cấu hình -->
          <el-collapse class="mt-5">
            <el-collapse-item name="cfg">
              <template #title>
                <span class="text-sm font-medium">Cấu hình chạy</span>
                <span class="hint ml-3"><template v-if="project.provider === 'BOTSD'"><b class="text-emerald-600 dark:text-emerald-400">BOTSD</b> · {{ botsdModelName(project.botsdModel) }} · {{ project.botsdResolution }}</template><template v-else-if="project.provider === 'API888'"><b class="text-amber-600 dark:text-amber-400">888API</b> · {{ project.api888Model }}</template><template v-else>BytePlus · {{ settingsModel?.name || project.model }} · {{ project.resolution }}</template> · {{ project.ratio }} · song song {{ project.maxParallel }} · thử lại {{ project.maxAttempts }} lần</span>
              </template>
              <div class="mb-4 grid gap-4 sm:grid-cols-3 lg:grid-cols-6">
                <div class="sm:col-span-2">
                  <label class="field-label">Nguồn tạo video</label>
                  <el-radio-group v-model="settings.provider">
                    <el-radio-button value="BYTEPLUS">BytePlus</el-radio-button>
                    <el-radio-button value="BOTSD">BOTSD</el-radio-button>
                    <el-radio-button value="API888">888API</el-radio-button>
                  </el-radio-group>
                </div>
                <template v-if="settings.provider === 'API888'">
                  <div class="sm:col-span-3">
                    <label class="field-label">Model 888API</label>
                    <el-select v-model="settings.api888Model" class="w-full" filterable allow-create default-first-option
                      placeholder="Chọn hoặc gõ mã model">
                      <el-option v-for="m in api888Models" :key="m.id" :label="m.id" :value="m.id">
                        <span>{{ m.id }}</span>
                        <span class="hint ml-2">{{ m.durations ? m.durations.join('/') : m.min + '–' + m.max }}s · {{ m.resolution }} · ≤{{ m.maxImages }} ảnh · {{ m.note }}</span>
                      </el-option>
                    </el-select>
                  </div>
                </template>
                <template v-if="settings.provider === 'BOTSD'">
                  <div class="sm:col-span-2">
                    <label class="field-label">Model BOTSD</label>
                    <el-select v-model="settings.botsdModel" class="w-full">
                      <el-option v-for="m in BOTSD_MODELS" :key="m.id" :label="m.name" :value="m.id">
                        <span>{{ m.name }}</span><span class="hint ml-2">{{ m.hint }}</span>
                      </el-option>
                    </el-select>
                  </div>
                  <div>
                    <label class="field-label">Độ phân giải BOTSD</label>
                    <el-select v-model="settings.botsdResolution" class="w-full">
                      <el-option v-for="r in ['original', '1080p', '4k']" :key="r" :label="r" :value="r" />
                    </el-select>
                  </div>
                  <div>
                    <label class="field-label">Chế độ người thật</label>
                    <el-select v-model="settings.botsdRealPerson" class="w-full" :disabled="settings.botsdModel === 'muse'">
                      <el-option value="OFF" label="Tắt" />
                      <el-option value="BASIC" label="Cơ bản" />
                      <el-option value="DEEP" label="Chuyên sâu" />
                    </el-select>
                  </div>
                </template>
              </div>
              <p v-if="settings.provider === 'API888'" class="hint mb-4">
                888API: dùng ảnh trong thư viện của chính dự án này (đúng thứ tự Ảnh 1, 2…), không cần đẩy Virtual Portrait. 888API chỉ nhận link
                ảnh nên app tự tải ảnh lên chỗ để tạm (Cài đặt › 888API) dưới thư mục của dự án, mỗi ảnh một lần.
                <template v-if="api888Model">Model này: {{ api888Model.durations ? api888Model.durations.join(' / ') : api888Model.min + '–' + api888Model.max }}s
                  (clip dài hơn bị cắt về mức tối đa), tỉ lệ {{ api888Model.ratios.join(', ') }}, tối đa {{ api888Model.maxImages }} ảnh.</template>
                888API không có lệnh huỷ – dừng clip chỉ ngừng theo dõi. Không dùng “Tạo âm thanh” và model BytePlus bên dưới.
              </p>
              <p v-if="settings.provider === 'BOTSD'" class="hint mb-4">
                BOTSD: ảnh tham chiếu gửi thẳng từ máy (không cần Virtual Portrait), tính bằng Point BOTSD (đăng nhập ở Cài đặt › BOTSD).
                Thời lượng clip làm tròn lên 5 / 10 / 15 / 30s. BOTSD không có lệnh huỷ – dừng clip chỉ ngừng theo dõi, Point đã trừ.
                Không dùng “Tạo âm thanh” và model / độ phân giải BytePlus bên dưới.
              </p>
              <div class="grid gap-4 sm:grid-cols-3 lg:grid-cols-6">
                <div v-if="!providerRaw" class="sm:col-span-2">
                  <label class="field-label">Model</label>
                  <el-select v-model="settings.model" class="w-full">
                    <el-option v-for="m in omniModels" :key="m.id" :label="m.name" :value="m.id" />
                  </el-select>
                </div>
                <div v-if="!providerRaw">

                  <label class="field-label">Độ phân giải</label>
                  <el-select v-model="settings.resolution" class="w-full">
                    <el-option v-for="r in settingsModel?.resolutions || []" :key="r" :label="r" :value="r" />
                  </el-select>
                </div>
                <div>
                  <label class="field-label">Tỉ lệ</label>
                  <el-select v-model="settings.ratio" class="w-full">
                    <el-option v-for="r in ['16:9', '9:16', '1:1', '4:3', '3:4', '21:9', 'adaptive']" :key="r" :label="r" :value="r" />
                  </el-select>
                </div>
                <div>
                  <label class="field-label">Chạy song song</label>
                  <el-input-number v-model="settings.maxParallel" :min="1" :max="50" class="!w-full" />
                  <p v-if="settingsErrors.maxParallel" class="mt-1 text-xs text-rose-600">{{ settingsErrors.maxParallel }}</p>
                </div>
                <div>
                  <label class="field-label">Số lần thử</label>
                  <el-input-number v-model="settings.maxAttempts" :min="1" :max="10" class="!w-full" />
                </div>
              </div>
              <div class="mt-4 grid gap-4 sm:grid-cols-3 lg:grid-cols-6">
                <div class="sm:col-span-2">
                  <label class="field-label">Model tạo ảnh tham chiếu</label>
                  <el-select v-model="settings.imageModel" class="w-full">
                    <el-option v-for="m in models.image" :key="m.id" :label="m.name" :value="m.id" />
                  </el-select>
                </div>
                <div>
                  <label class="field-label">Kích thước ảnh</label>
                  <el-select v-model="settings.imageSize" class="w-full">
                    <el-option v-for="x in imageModel?.sizes || []" :key="x" :label="x" :value="x" />
                  </el-select>
                </div>
              </div>
              <div class="mt-4 grid gap-4 sm:grid-cols-3 lg:grid-cols-6">
                <div class="sm:col-span-2">
                  <label class="field-label">Lưu ảnh đã duyệt</label>
                  <el-select v-model="settings.refSaveMode" class="w-full">
                    <el-option value="PROJECT" label="Tự tạo – cùng thư mục lưu với video" />
                    <el-option value="FOLDER" label="Tự tạo + chép vào thư mục riêng của tôi" />
                  </el-select>
                  <p class="hint mt-1 break-all">Ảnh duyệt lưu ở <code>{{ projectRefDir }}</code> (đổi “Thư mục lưu” ở trang Cài đặt – video cũng lưu ở đó).</p>
                </div>
                <div v-if="settings.refSaveMode === 'FOLDER'" class="sm:col-span-3 lg:col-span-4">
                  <label class="field-label">Thư mục riêng (tự tạo NHAN VAT / BOI CANH / DAO CU bên trong)</label>
                  <el-input v-model="settings.refSaveDir" placeholder="vd D:\Anh REF\S11_11" clearable />
                  <p v-if="settingsErrors.refSaveDir" class="mt-1 text-xs text-rose-600">{{ settingsErrors.refSaveDir }}</p>
                </div>
              </div>
              <div class="mt-4 flex flex-wrap items-center gap-4">
                <el-checkbox v-model="settings.generateAudio">Tạo âm thanh (thoại trong prompt)</el-checkbox>
                <el-checkbox v-model="settings.autoApproveImages">Tự duyệt ảnh AI (bỏ qua bước chờ duyệt)</el-checkbox>
                <el-button type="primary" plain :loading="busy === 'settings'" @click="saveSettings">Lưu cấu hình</el-button>
                <span class="hint">Mỗi clip dùng thời lượng ghi trong kịch bản (cột DÀI). Song song nhiều quá dễ bị BytePlus trả 429 – app sẽ tự chờ rồi gửi lại.</span>
              </div>
            </el-collapse-item>
          </el-collapse>
        </section>

        <el-tabs v-model="tab" class="card px-5 pb-5 pt-2">
          <!-- ===== CLIP ===== -->
          <el-tab-pane name="clips" :label="`Clip (${project.counts.TOTAL})`">
            <div class="mb-4 grid gap-3 rounded-xl bg-slate-50 p-4 lg:grid-cols-[220px_1fr_auto] dark:bg-zinc-900/50">
              <el-input v-model="redo.codes" placeholder="Làm lại: 005, 012-015, 019A" clearable />
              <el-input v-model="redo.note" placeholder="Ghi chú sửa (tuỳ chọn, tiếng Anh) – được ghép vào cuối prompt, vd: keep CHAR_YVO's backpack on her left shoulder" clearable />
              <el-button type="primary" :disabled="!redo.codes.trim()" :loading="busy === 'redo'" @click="submitRedo">
                <el-icon><RefreshRight /></el-icon>&nbsp;Làm lại các clip này
              </el-button>
            </div>

            <div class="mb-4 flex flex-wrap items-center gap-3 rounded-xl bg-indigo-50/70 p-3 ring-1 ring-inset ring-indigo-100 dark:bg-indigo-500/10 dark:ring-indigo-500/20">
              <span class="text-sm font-medium text-indigo-700 dark:text-indigo-300">Chạy riêng:</span>
              <el-select v-model="sceneSel" multiple collapse-tags collapse-tags-tooltip filterable clearable placeholder="Chọn cảnh…" class="!w-80">
                <el-option v-for="sc in scenes.data.value" :key="sc.scene" :value="sc.scene" :label="sc.scene">
                  <div class="flex items-center justify-between gap-4">
                    <span class="truncate">{{ sc.scene }}</span>
                    <span class="shrink-0 text-xs text-slate-400">{{ sc.done }}/{{ sc.total }} xong{{ sc.failed ? ` · ${sc.failed} lỗi` : '' }}</span>
                  </div>
                </el-option>
              </el-select>
              <el-button type="primary" :disabled="!sceneSel.length" :loading="busy === 'runPicked'" @click="runScenes">
                <el-icon><VideoPlay /></el-icon>&nbsp;Chạy {{ sceneSel.length > 1 ? sceneSel.length + ' cảnh' : 'cảnh' }} đã chọn
              </el-button>
              <el-divider direction="vertical" />
              <el-button type="primary" plain :disabled="!selectedClips.length" :loading="busy === 'runPicked'" @click="runSelectedClips">
                <el-icon><VideoPlay /></el-icon>&nbsp;Chạy {{ selectedClips.length || '' }} clip đã tick
              </el-button>
              <el-button v-if="selectedClips.length" text @click="clearSelection">Bỏ chọn</el-button>
              <el-checkbox v-model="redoDone" title="Mặc định bỏ qua clip đã có video">Tạo lại cả clip đã xong</el-checkbox>
              <span class="hint w-full">Tick ô đầu dòng (chọn được qua nhiều trang) hoặc chọn cảnh rồi bấm chạy – chỉ những clip đó được gửi, các clip khác giữ nguyên. “Chạy tất cả clip” ở trên mới chạy cả kịch bản.</span>
            </div>

            <div class="mb-3 flex flex-wrap items-center gap-3">
              <el-input v-model="query.q" placeholder="Tìm theo mã, cảnh, mã ảnh, nội dung prompt…" clearable class="max-w-md" @keyup.enter="search" @clear="search">
                <template #prefix><el-icon><Search /></el-icon></template>
              </el-input>
              <el-tag v-if="filter" closable @close="filter = ''">Lọc: {{ tiles.find((t) => t.key === filter)?.label }}</el-tag>
            </div>

            <AsyncState
              :loading="clips.loading.value && !clips.data.value.items.length"
              :error="clips.error.value"
              :empty="!clips.data.value.items.length"
              :empty-text="filter || query.q ? 'Không có clip nào khớp bộ lọc' : 'Dự án chưa có clip'"
              empty-icon="Film"
              @retry="clips.run()"
            >
              <el-table
                ref="clipTable"
                :data="clips.data.value.items"
                row-key="id"
                :expand-row-keys="expanded"
                class="script-table"
                @row-click="toggle"
                @selection-change="onSelect"
              >
                <el-table-column type="selection" width="40" reserve-selection :selectable="(row) => row.status !== 'GENERATING'" />
                <el-table-column type="expand" width="1">
                  <template #default="{ row }">
                    <div class="grid gap-5 px-4 py-3 xl:grid-cols-[1fr_380px]" @click.stop>
                      <div class="space-y-3">
                        <div>
                          <label class="field-label">Seedance prompt (gửi nguyên văn)</label>
                          <el-input v-if="drafts[row.id]" v-model="drafts[row.id].prompt" type="textarea" :autosize="{ minRows: 8, maxRows: 22 }" class="font-mono" />
                        </div>
                        <div class="flex flex-wrap items-center gap-3">
                          <span class="text-sm text-slate-500">Thời lượng</span>
                          <el-input-number v-if="drafts[row.id]" v-model="drafts[row.id].duration" :min="4" :max="30" size="small" />
                          <el-button size="small" :loading="busy === 'clip' + row.id" :disabled="row.status === 'GENERATING'" @click="saveClip(row, false)">Lưu</el-button>
                          <el-button size="small" type="primary" :loading="busy === 'clip' + row.id" :disabled="row.status === 'GENERATING'" @click="saveClip(row, true)">Lưu &amp; tạo lại</el-button>
                        </div>
                        <div v-if="row.blockAnalysis || row.analyzing || isBlockError(row)" class="space-y-2 rounded-xl bg-rose-50 p-3 text-sm ring-1 ring-inset ring-rose-200 dark:bg-rose-500/10 dark:ring-rose-500/30">
                          <div class="flex flex-wrap items-center justify-between gap-2">
                            <div class="font-semibold text-rose-700 dark:text-rose-300">
                              Vì sao bị chặn{{ row.blockAnalysis?.category ? ': ' + row.blockAnalysis.category : '' }}
                            </div>
                            <div class="flex gap-2">
                              <el-button v-if="row.blockAnalysis?.fixedPrompt" size="small" type="primary" plain @click="useFixedPrompt(row)">Dùng prompt đã sửa</el-button>
                              <el-button size="small" :loading="row.analyzing || busy === 'analyze' + row.id" @click="analyzeBlock(row)">
                                {{ row.analyzing ? 'AI đang phân tích…' : row.blockAnalysis ? 'Phân tích lại' : 'Phân tích vì sao bị chặn' }}
                              </el-button>
                            </div>
                          </div>
                          <p v-if="row.blockAnalysis?.summary" class="text-slate-700 dark:text-zinc-200">{{ row.blockAnalysis.summary }}</p>
                          <ol v-if="row.blockAnalysis?.issues?.length" class="space-y-2">
                            <li v-for="(it, i) in row.blockAnalysis.issues" :key="i" class="rounded-lg bg-white/70 p-2 dark:bg-zinc-900/60">
                              <div class="text-xs text-slate-400">{{ i + 1 }}. Trong {{ it.where || 'prompt' }}</div>
                              <mark class="rounded bg-rose-200/70 px-1 font-mono text-xs text-rose-900 dark:bg-rose-500/30 dark:text-rose-100">{{ it.quote }}</mark>
                              <div v-if="it.vi" class="mt-1 text-xs italic text-slate-500 dark:text-zinc-400">“{{ it.vi }}”</div>
                              <div v-if="it.reason" class="mt-1 text-xs text-slate-700 dark:text-zinc-200"><b>Lý do:</b> {{ it.reason }}</div>
                              <div v-if="it.fix" class="mt-1 text-xs text-emerald-700 dark:text-emerald-300"><b>Sửa thành:</b> {{ it.fix }}</div>
                            </li>
                          </ol>
                          <p v-if="!row.blockAnalysis && !row.analyzing" class="hint">BytePlus không nói đoạn nào bị chặn – bấm nút để AI đọc prompt + lời thoại và chỉ ra bằng tiếng Việt.</p>
                        </div>
                        <ul v-if="row.warnings.length" class="space-y-1 rounded-lg bg-indigo-50 p-3 text-xs text-indigo-700 dark:bg-indigo-500/10 dark:text-indigo-300">
                          <li v-for="w in row.warnings" :key="w">• {{ w }}</li>
                        </ul>
                        <details v-if="row.promptVi" class="text-sm">
                          <summary class="cursor-pointer text-slate-500">Prompt tiếng Việt (để đọc kiểm tra)</summary>
                          <pre class="mt-2 whitespace-pre-wrap font-sans text-slate-600 dark:text-zinc-300">{{ row.promptVi }}</pre>
                        </details>
                      </div>
                      <div class="space-y-3">
                        <video v-if="row.file" :src="row.file.url" controls preload="metadata" class="w-full rounded-xl bg-black" />
                        <div v-if="row.dialogue">
                          <label class="field-label">Lời thoại</label>
                          <pre class="whitespace-pre-wrap rounded-lg bg-slate-50 p-3 font-sans text-xs text-slate-600 dark:bg-zinc-900/50 dark:text-zinc-300">{{ row.dialogue }}</pre>
                        </div>
                        <p v-if="row.note" class="text-xs text-slate-500"><b>Dàn dựng:</b> {{ row.note }}</p>
                        <p v-if="row.taskId" class="font-mono text-xs text-slate-400">task: {{ row.taskId }}</p>
                      </div>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column label="Mã" width="80">
                  <template #default="{ row }"><span class="font-mono font-semibold">{{ row.code }}</span></template>
                </el-table-column>
                <el-table-column label="Cảnh" min-width="200">
                  <template #default="{ row }">
                    <div class="text-sm">{{ row.sceneTitle }}</div>
                    <div class="text-xs text-slate-400">{{ row.timecode }} · {{ row.duration }}s</div>
                  </template>
                </el-table-column>
                <el-table-column label="Ảnh tham chiếu (theo thứ tự)" min-width="300">
                  <template #default="{ row }">
                    <div class="flex flex-wrap gap-1">
                      <span
                        v-for="(c, i) in row.refCodes"
                        :key="c"
                        class="rounded-md px-1.5 py-0.5 font-mono text-[11px] ring-1 ring-inset"
                        :class="row.missingRefs.includes(c) ? 'bg-rose-50 text-rose-700 ring-rose-200 dark:bg-rose-500/10 dark:text-rose-300 dark:ring-rose-500/30' : 'bg-slate-50 text-slate-600 ring-slate-200 dark:bg-zinc-900 dark:text-zinc-300 dark:ring-zinc-700'"
                        :title="row.missingRefs.includes(c) ? 'Chưa gán ảnh' : ''"
                      >{{ i + 1 }}·{{ c }}</span>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column label="Trạng thái" width="150">
                  <template #default="{ row }">
                    <StatusBadge v-if="row.status === 'PENDING' && row.missingRefs.length" status="missing" label="Chờ ảnh" />
                    <StatusBadge v-else :status="CLIP_STATUS[row.status].status" :label="CLIP_STATUS[row.status].label" />
                    <div v-if="row.attempt" class="mt-1 text-[11px] text-slate-400">lần thử {{ row.attempt }}</div>
                    <div v-if="row.runSelected && (row.status === 'PENDING' || row.status === 'GENERATING')" class="mt-1 text-[11px] font-medium text-indigo-500">● trong lượt chạy riêng</div>
                    <el-icon v-if="row.warnings.length" class="ml-1 text-indigo-500" :title="row.warnings.join('\n')"><WarningFilled /></el-icon>
                  </template>
                </el-table-column>
                <el-table-column label="Tiến độ / lỗi" min-width="240" show-overflow-tooltip>
                  <template #default="{ row }">
                    <div v-if="row.blockAnalysis?.category" class="mb-0.5 text-xs font-semibold text-rose-700 dark:text-rose-300">⚠ Vi phạm: {{ row.blockAnalysis.category }} – bấm Chi tiết</div>
                    <div v-else-if="row.analyzing" class="mb-0.5 text-xs text-amber-600">AI đang phân tích vì sao bị chặn…</div>
                    <span class="text-xs" :class="row.status === 'FAILED' ? 'text-rose-600' : 'text-slate-500'">{{ row.error }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="" width="190" align="right">
                  <template #default="{ row }">
                    <div class="flex justify-end gap-1" @click.stop>
                      <el-button v-if="row.file" size="small" text type="primary" tag="a" :href="row.file.url" target="_blank"><el-icon><VideoCamera /></el-icon></el-button>
                      <el-button v-if="row.status === 'GENERATING' || row.status === 'PENDING'" size="small" text :loading="busy === 'clip' + row.id" @click="cancelClip(row)">Dừng</el-button>
                      <el-button v-if="row.status !== 'GENERATING'" size="small" text type="primary" :loading="busy === 'clip' + row.id" title="Chỉ chạy clip này" @click="runClip(row)">{{ row.status === 'PENDING' ? 'Chạy ngay' : 'Tạo lại' }}</el-button>
                      <el-button size="small" text @click="toggle(row)">Chi tiết</el-button>
                    </div>
                  </template>
                </el-table-column>
              </el-table>
              <div class="mt-4 flex items-center justify-between">
                <p class="hint">Bấm vào một dòng để xem / sửa prompt, xem video. Clip làm lại được ưu tiên chạy trước.</p>
                <el-pagination v-model:current-page="query.page" v-model:page-size="query.size" :page-sizes="[20, 50, 100]" :total="clips.data.value.total" layout="total, sizes, prev, pager, next" background @change="clips.run()" />
              </div>
            </AsyncState>
          </el-tab-pane>

          <!-- ===== ẢNH THAM CHIẾU ===== -->
          <el-tab-pane name="refs">
            <template #label>
              Ảnh tham chiếu <el-badge v-if="project.refsMissing" :value="project.refsMissing" class="ml-1" type="danger" />
              <el-badge v-if="project.imagesReview" :value="project.imagesReview" class="ml-1" type="primary" title="Chờ duyệt" />
            </template>
            <div class="mb-4 flex flex-wrap items-center gap-3">
              <el-button type="primary" :disabled="!project.refsMissing || project.imagesPending > 0" :loading="busy === 'gen'" @click="generateRefs()">
                <el-icon><MagicStick /></el-icon>&nbsp;Tạo bằng AI các ảnh còn thiếu ({{ project.refsMissing }})
              </el-button>
              <el-button v-if="aiTargets.length || busy === 'aiAll'" type="primary" plain :loading="busy === 'aiAll'" @click="aiWriteAll">
                <el-icon><EditPen /></el-icon>&nbsp;{{ busy === 'aiAll' ? `AI đang viết prompt ${aiProgress}` : `AI viết prompt ảnh (${aiTargets.length})` }}
              </el-button>
              <el-button v-if="project.imagesReview" type="success" :loading="busy === 'approveAll'" @click="approveAll">
                <el-icon><Select /></el-icon>&nbsp;Duyệt tất cả ({{ project.imagesReview }})
              </el-button>
              <el-button v-if="project.imagesPending" type="warning" plain :loading="busy === 'stopgen'" @click="stopRefs">Dừng tạo ảnh ({{ project.imagesPending }})</el-button>
              <el-button v-if="vpPending.length" type="warning" plain :loading="busy === 'vp'" @click="pushCharactersToVp">
                <el-icon><UploadFilled /></el-icon>&nbsp;Đẩy {{ vpPending.length }} ảnh nhân vật lên Virtual Portrait
              </el-button>
              <el-button plain @click="downloadZip('images')"><el-icon><Download /></el-icon>&nbsp;Tải hết ảnh về (.zip)</el-button>
              <el-button type="success" plain @click="(folderImportFiles = []), (folderImport = true)"><el-icon><FolderOpened /></el-icon>&nbsp;Nhập thư mục ảnh</el-button>
              <el-button :loading="busy === 'auto'" @click="autoMatch"><el-icon><Connection /></el-icon>&nbsp;Tự ghép theo tên file</el-button>
              <el-button type="primary" plain :loading="busy === 'vpmatch'" @click="matchVpAssets"><el-icon><Connection /></el-icon>&nbsp;Ghép asset VP theo tên</el-button>
              <p class="hint max-w-3xl">
                Ảnh nhân vật lấy từ máy được app tự đẩy lên Virtual Portrait (qua bucket TOS riêng tư) trước khi gửi clip – hoặc bấm nút đẩy trước cho nhanh.
                Ảnh tạo bằng AI vào mục <b>chờ duyệt</b> trước (<code>_cho-duyet/</code>): bấm <b>Duyệt</b> để đổi tên thành <code>MÃ.jpeg</code> trong thư mục ảnh tham chiếu của dự án (ảnh cũ chuyển vào <code>_cu/</code>; nếu chọn “thư mục riêng” trong Cấu hình chạy thì chép thêm vào đó theo loại), hoặc <b>Tạo lại</b> / <b>Bỏ</b>. Việc đẩy Virtual Portrait do phần chạy video tự làm khi gửi clip.
                Prompt ảnh được viết sẵn từ mô tả nhân vật / bối cảnh / đạo cụ trong Excel (bấm “Prompt” để xem, sửa). Nhân vật nhiều bộ đồ:
                bộ đầu được tạo trước và dùng làm tham chiếu khuôn mặt cho các bộ sau. Hoặc đặt tên file đúng mã (vd <code>CHAR_YVO_NAVYTEE_01.png</code>) rồi bấm “Tự ghép”, hoặc tải lên / chọn từ thư viện cho từng mã.
                Ảnh nhân vật là người thật nên đẩy vào <b>Virtual Portrait</b> trước – khi ảnh đã có asset Active, app gửi <code>asset://</code> để Seedance không chặn mặt người.
              </p>
            </div>
            <AsyncState :loading="refs.loading.value && !refs.data.value.length" :error="refs.error.value" :empty="!refs.data.value.length" empty-text="Dự án chưa có mã ảnh tham chiếu" @retry="refs.run()">
              <div class="grid grid-cols-[repeat(auto-fill,minmax(250px,1fr))] gap-4">
                <div
                  v-for="r in sortedRefs"
                  :key="r.code"
                  class="overflow-hidden rounded-xl bg-slate-50 ring-1 dark:bg-zinc-900/50"
                  :class="r.pendingFile ? 'ring-2 ring-indigo-400 dark:ring-indigo-500/60' : r.file && !r.blockedReason ? 'ring-slate-200/70 dark:ring-zinc-700/60' : 'ring-2 ring-rose-400 dark:ring-rose-500/60'"
                >
                  <div class="relative aspect-video bg-slate-100 dark:bg-zinc-800">
                    <el-image
                      v-if="r.pendingFile"
                      :src="r.pendingFile.url"
                      :preview-src-list="r.file ? [r.pendingFile.url, r.file.url] : [r.pendingFile.url]"
                      preview-teleported
                      fit="cover"
                      class="h-full w-full"
                    />
                    <img v-else-if="r.file" :src="r.file.url" loading="lazy" class="h-full w-full object-cover" />
                    <div v-else class="grid h-full place-items-center text-sm text-rose-500"><span><el-icon><Picture /></el-icon> Chưa có ảnh</span></div>
                    <div v-if="r.pendingFile && r.file" class="absolute bottom-2 right-2 overflow-hidden rounded-md ring-2 ring-white/90 dark:ring-zinc-900/90" title="Ảnh hiện tại (sẽ chuyển vào _cu/ khi duyệt ảnh mới)">
                      <img :src="r.file.url" loading="lazy" class="h-12 w-20 object-cover" />
                      <span class="absolute inset-x-0 bottom-0 bg-black/55 text-center text-[10px] text-white">ảnh cũ</span>
                    </div>
                    <span class="absolute left-2 top-2 rounded-full bg-white/90 px-2 py-0.5 text-[11px] font-medium text-slate-600 dark:bg-zinc-900/90 dark:text-zinc-300">{{ KIND[r.kind] }} · {{ r.usedBy }} clip</span>
                    <span v-if="IMAGE_STATUS[r.imageStatus] && r.imageStatus !== 'SUCCEEDED'" class="absolute bottom-2 left-2"><StatusBadge :status="IMAGE_STATUS[r.imageStatus].status" :label="IMAGE_STATUS[r.imageStatus].label" /></span>
                    <span v-if="r.file?.assetId" class="absolute right-2 top-2"><StatusBadge :status="r.file.assetStatus" :label="'VP ' + (r.file.assetStatus || '')" /></span>
                  </div>
                  <div class="space-y-1 p-3">
                    <div class="font-mono text-xs font-semibold">{{ r.code }}</div>
                    <div class="line-clamp-1 text-sm" :title="r.name">{{ r.name }}</div>
                    <div class="line-clamp-2 text-xs text-slate-500" :title="r.description">{{ r.description }}</div>
                    <div v-if="r.blockedReason" class="rounded-md bg-rose-50 px-2 py-1 text-[11px] text-rose-700 dark:bg-rose-500/10 dark:text-rose-300">
                      ⛔ {{ r.blockedReason }}. Đẩy ảnh này lên Virtual Portrait (khi VP Active app gửi asset://) hoặc bấm “Tạo lại AI”.
                    </div>
                    <div v-if="r.identityCode" class="text-[11px] text-indigo-600 dark:text-indigo-400">Giữ khuôn mặt theo {{ r.identityCode }}</div>
                    <div v-if="r.imageError" class="line-clamp-2 text-[11px]" :class="r.imageStatus === 'FAILED' ? 'text-rose-600' : 'text-slate-500'" :title="r.imageError">{{ r.imageError }}</div>
                    <div v-if="r.imageStatus === 'REVIEW'" class="flex flex-wrap gap-1 rounded-lg bg-indigo-50 p-2 dark:bg-indigo-500/10">
                      <el-button size="small" type="success" :loading="busy === 'approve' + r.code" @click="approveRef(r)"><el-icon><Select /></el-icon>&nbsp;Duyệt</el-button>
                      <el-button size="small" type="primary" plain :loading="busy === 'gen' + r.code" @click="generateRefs([r.code], true)"><el-icon><RefreshRight /></el-icon>&nbsp;Tạo lại</el-button>
                      <el-button size="small" text type="danger" :loading="busy === 'reject' + r.code" @click="rejectRef(r)">Bỏ ảnh mới</el-button>
                    </div>
                    <div class="flex flex-wrap gap-1 pt-2">
                      <el-upload :show-file-list="false" accept="image/*" :auto-upload="false" :on-change="(f) => uploadRef(r.code, f.raw)">
                        <el-button size="small" :loading="busy === 'ref' + r.code"><el-icon><Upload /></el-icon>&nbsp;Tải lên</el-button>
                      </el-upload>
                      <el-button size="small" @click="pickerFor = r.code"><el-icon><Files /></el-icon>&nbsp;Thư viện</el-button>
                      <el-button v-if="r.imageStatus !== 'REVIEW'" size="small" type="primary" plain :disabled="r.imageStatus === 'PENDING' || r.imageStatus === 'GENERATING'" :loading="busy === 'gen' + r.code" @click="generateRefs([r.code], true)">
                        <el-icon><MagicStick /></el-icon>&nbsp;{{ r.file ? 'Tạo lại AI' : 'Tạo AI' }}
                      </el-button>
                      <el-button size="small" text @click="openPrompt(r)">Prompt</el-button>
                      <el-button v-if="r.file" size="small" text type="danger" @click="setRef(r.code, null)">Bỏ</el-button>
                    </div>
                  </div>
                </div>
              </div>
            </AsyncState>
          </el-tab-pane>
        </el-tabs>
      </template>
    </AsyncState>

    <el-dialog :model-value="!!promptFor" :title="`Prompt ảnh – ${promptFor?.code || ''}`" width="760px" @update:model-value="(v) => !v && (promptFor = null)">
      <p class="hint mb-2">
        {{ promptFor?.name }}<span v-if="promptFor?.identityCode"> · ảnh {{ promptFor.identityCode }} được gửi kèm làm tham chiếu khuôn mặt</span>
        <span v-if="promptFor?.promptEdited"> · đã sửa tay (nhập lại Excel không ghi đè)</span>
      </p>
      <el-input v-model="promptDraft" type="textarea" :autosize="{ minRows: 8, maxRows: 20 }" maxlength="6000" show-word-limit />
      <template #footer>
        <el-button class="float-left" :loading="busy === 'aiDraft'" @click="aiDraft"><el-icon><EditPen /></el-icon>&nbsp;AI viết lại (Language)</el-button>
        <el-button @click="promptFor = null">Huỷ</el-button>
        <el-button :loading="busy === 'prompt'" :disabled="!promptDraft.trim()" @click="savePrompt(false)">Lưu</el-button>
        <el-button type="primary" :loading="busy === 'prompt'" :disabled="!promptDraft.trim()" @click="savePrompt(true)">Lưu &amp; tạo ảnh</el-button>
      </template>
    </el-dialog>

    <RefFolderImport v-if="project" v-model="folderImport" :project-id="project.id" :references="refs.data.value" :initial-files="folderImportFiles" @done="refs.run(), refresh(false)" />

    <LibraryPicker :model-value="!!pickerFor" kind="image" :multiple="false" @update:model-value="(v) => !v && (pickerFor = null)" @pick="onPicked" />
  </div>
</template>

<style scoped>
.script-table :deep(.el-table__expand-column .cell) {
  display: none;
}
.script-table :deep(.el-table__row) {
  cursor: pointer;
}
</style>
