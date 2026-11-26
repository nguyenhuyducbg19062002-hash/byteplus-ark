<script setup>
import { ref, reactive, computed, watch, nextTick, onActivated, onDeactivated } from 'vue'
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
import ProjectFolderDialog from '../components/ProjectFolderDialog.vue'

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
      params: { page: query.page, size: query.size, status: filter.value || undefined, q: query.q || undefined, ...limitParams() },
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

// ---------- tính năng theo nguồn video (theo tài liệu từng API) ----------
/** Có API key BytePlus: cần cho tạo ảnh AI (Seedream), AI viết prompt, phân tích chặn – dù chọn nguồn video nào. */
const hasBytePlusKey = computed(() => !!appSettings.settings?.apiKeySet)
const NEED_KEY = 'Cần API key BytePlus (Cài đặt) – tạo ảnh AI / AI viết prompt luôn chạy bằng BytePlus'
/** Nguồn đã lưu của dự án (tab Clip / Ảnh tham chiếu); form cấu hình dùng settings.provider. */
const prov = computed(() => project.value?.provider || 'BYTEPLUS')
const isBytePlus = computed(() => prov.value === 'BYTEPLUS')
const PROVIDER_LABEL = { BYTEPLUS: 'BytePlus', BOTSD: 'BOTSD', API888: '888API' }
/** Nơi task của clip đã được gửi (theo tiền tố mã task). */
const taskSource = (t) => (!t ? null : t.startsWith('botsd:') ? 'BOTSD' : t.startsWith('888:') ? '888API' : 'BytePlus')
const ALL_RATIOS = ['16:9', '9:16', '1:1', '4:3', '3:4', '21:9']
const BOTSD_SECONDS = { sd20: [5, 10, 15], sd25: [5, 10, 15, 30], sd25hd: [5, 10, 15, 30], muse: [5, 10] }
const BOTSD_MAX_IMAGES = { sd20: 30, sd25: 30, sd25hd: 30, muse: 1 }
const ratioOptions = computed(() => {
  if (settings.provider === 'BOTSD') return settings.botsdModel === 'muse' ? ['16:9', '9:16'] : ALL_RATIOS
  if (settings.provider === 'API888') return api888Model.value?.ratios || ALL_RATIOS
  return [...ALL_RATIOS, 'adaptive']
})
watch(ratioOptions, (opts) => {
  if (opts.includes(settings.ratio)) return
  const old = settings.ratio
  settings.ratio = opts.includes('16:9') ? '16:9' : opts[0]
  ElMessage.info(`Tỉ lệ ${old} không dùng được với lựa chọn này – đã đổi sang ${settings.ratio} (bấm Lưu cấu hình)`)
})
/** Clip X giây sẽ gửi bao nhiêu giây với nguồn / model đang chọn. */
const durationRule = computed(() => {
  if (settings.provider === 'BOTSD') {
    const s = BOTSD_SECONDS[settings.botsdModel] || [5, 10, 15]
    return `Thời lượng: BOTSD chỉ nhận ${s.join(' / ')}s – clip lẻ làm tròn lên mức gần nhất (vd 12s → 15s), clip dài hơn ${s[s.length - 1]}s bị cắt về ${s[s.length - 1]}s.`
  }
  if (settings.provider === 'API888') {
    const m = api888Model.value
    if (!m) return 'Thời lượng: model tự nhập – app giữ trong 4–15s.'
    return m.durations
      ? `Thời lượng: ${m.id} chỉ nhận ${m.durations.join(' / ')}s – clip làm tròn lên mức gần nhất, dài hơn thì cắt về ${m.durations[m.durations.length - 1]}s.`
      : `Thời lượng: ${m.id} nhận ${m.min}–${m.max}s – clip ngắn hơn gửi ${m.min}s, dài hơn bị cắt về ${m.max}s.`
  }
  const m = settingsModel.value
  return m ? `Thời lượng: ${m.name} nhận ${m.minDuration}–${m.maxDuration}s – clip ngoài khoảng này được kẹp về ${m.minDuration} / ${m.maxDuration}s.` : ''
})
/**
 * Giới hạn của nguồn + model ĐANG CHỌN trong form cấu hình (chưa cần Lưu): số ảnh tối đa, thời lượng nhận được, độ dài prompt.
 * Danh sách clip bên dưới dùng giới hạn này nên đổi model là cảnh báo từng clip tính lại ngay.
 */
function specOf(provider, id) {
  if (provider === 'BOTSD') {
    return { label: 'BOTSD · ' + botsdModelName(id), maxImages: BOTSD_MAX_IMAGES[id] || 30, durations: BOTSD_SECONDS[id] || [5, 10, 15],
      promptMax: id === 'sd25' || id === 'sd25hd' ? 15000 : 20000 }
  }
  if (provider === 'API888') {
    const m = api888Models.value.find((x) => x.id === id)
    // Chưa tải xong danh sách model → chưa biết giới hạn, không báo gì (tránh báo nhầm theo mức mặc định)
    if (!m && !api888Models.value.length) return { label: '888API · ' + id, maxImages: null, durations: null, min: null, max: null, promptMax: null }
    return { label: '888API · ' + id, maxImages: m?.maxImages || 9, durations: m?.durations || null, min: m?.min ?? 4, max: m?.max ?? 15,
      promptMax: m?.promptMax || null }
  }
  const m = models.video.find((x) => x.id === id)
  return { label: 'BytePlus · ' + (m?.name || id), maxImages: m?.maxRefImages || null, durations: null, min: m?.minDuration ?? null,
    max: m?.maxDuration ?? null, promptMax: null }
}
const modelIdOf = (o) => (o.provider === 'BOTSD' ? o.botsdModel : o.provider === 'API888' ? o.api888Model : o.model)
const draftSpec = computed(() => specOf(settings.provider, modelIdOf(settings)))
/** Form cấu hình đang khác nguồn / model đã lưu của dự án. */
const specUnsaved = computed(() => {
  const p = project.value
  return !!p && ((p.provider || 'BYTEPLUS') !== settings.provider || modelIdOf(p) !== modelIdOf(settings))
})
const projMaxImages = computed(() => draftSpec.value.maxImages)
const projPromptMax = computed(() => draftSpec.value.promptMax)
/** Clip dài X giây sẽ gửi bao nhiêu giây với giới hạn sp (làm tròn lên mức gần nhất / kẹp trong min–max). */
function sentSeconds(d, sp = draftSpec.value) {
  if (sp.durations) return sp.durations.find((x) => x >= d) ?? sp.durations[sp.durations.length - 1]
  let v = d
  if (sp.max != null) v = Math.min(v, sp.max)
  if (sp.min != null) v = Math.max(v, sp.min)
  return v
}
/** Thông số gọn của mọi clip (không phân trang) để đếm clip quá giới hạn theo model đang chọn. */
const specs = useAsync(() => api.get(`/script-projects/${currentId.value}/clip-specs`, { silent: true }), { initial: [] })
function fitOf(sp) {
  const r = { cut: [], images: [], prompt: [], total: 0 }
  for (const c of specs.data.value || []) {
    if (c.status === 'SUCCEEDED') continue
    r.total++
    if (sentSeconds(c.duration, sp) < c.duration) r.cut.push(c.code)
    if (sp.maxImages && c.images > sp.maxImages) r.images.push(c.code)
    if (sp.promptMax && c.promptLen > sp.promptMax) r.prompt.push(c.code)
  }
  r.ok = (specs.data.value || []).filter((c) => c.status !== 'SUCCEEDED').filter((c) =>
    !r.cut.includes(c.code) && !r.images.includes(c.code) && !r.prompt.includes(c.code)).length
  return r
}
const draftFit = computed(() => fitOf(draftSpec.value))
/** Số giây tối đa model đang chọn nhận được (dài hơn là bị cắt). */
const maxSeconds = (sp) => (sp.durations ? sp.durations[sp.durations.length - 1] : sp.max)
/** Giới hạn gửi kèm khi lấy danh sách clip: bộ lọc "Cảnh báo" tính theo model đang chọn. */
function limitParams() {
  const sp = draftSpec.value
  return { limImages: sp.maxImages ?? undefined, limSeconds: maxSeconds(sp) ?? undefined, limPrompt: sp.promptMax ?? undefined }
}
/** Cảnh báo của một clip = lỗi dữ liệu (từ Excel) + vượt giới hạn của model đang chọn (tính ngay, không lưu). */
function rowWarnings(row) {
  const sp = draftSpec.value
  const out = [...(row.warnings || [])]
  if (row.status === 'SUCCEEDED') return out
  const n = row.refCodes?.length || 0
  if (sp.maxImages && n > sp.maxImages) out.push(`${n} ảnh tham chiếu > tối đa ${sp.maxImages} của ${sp.label}`)
  if (row.duration && sentSeconds(row.duration) < row.duration) out.push(`${row.duration}s > tối đa ${sentSeconds(row.duration)}s của ${sp.label} – video bị cắt ${row.duration - sentSeconds(row.duration)}s`)
  if (sp.promptMax && row.prompt?.length > sp.promptMax) out.push(`Prompt ${row.prompt.length.toLocaleString()} ký tự > tối đa ${sp.promptMax.toLocaleString()} của ${sp.label}`)
  return out
}
/** Số clip có cảnh báo theo model đang chọn (ô "Cảnh báo"). */
const warningCount = computed(() => {
  const sp = draftSpec.value
  return (specs.data.value || []).filter((c) => c.dataWarnings > 0 || (c.status !== 'SUCCEEDED'
    && ((sp.maxImages && c.images > sp.maxImages) || sentSeconds(c.duration, sp) < c.duration || (sp.promptMax && c.promptLen > sp.promptMax)))).length
})
// Đổi model khi đang lọc "Cảnh báo" → lấy lại danh sách theo model mới
watch(() => JSON.stringify(limitParams()), () => filter.value === 'WARNING' && currentId.value && clips.run())
/** "vừa 60/68" cho từng model trong danh sách chọn. */
const fitLabel = (provider, id) => {
  if (!specs.data.value?.length) return ''
  const f = fitOf(specOf(provider, id))
  return f.total ? `vừa ${f.ok}/${f.total} clip` : ''
}
const codesHint = (list) => (list.length > 12 ? list.slice(0, 12).join(', ') + ` … (+${list.length - 12})` : list.join(', '))
watch(prov, (p) => p === 'API888' && loadApi888Models(), { immediate: true })
/** Số dư của nguồn đang chọn (BOTSD Point / 888API). */
const balance = ref(null)
async function loadBalance(p) {
  balance.value = null
  try {
    if (p === 'BOTSD' && appSettings.settings?.botsdLoggedIn) {
      const s = await api.get('/settings/botsd/status', { silent: true })
      balance.value = `Số dư BOTSD: ${s.balance ?? '?'} Point`
    } else if (p === 'API888' && appSettings.settings?.api888KeySet) {
      const s = await api.get('/settings/api888/status', { silent: true })
      balance.value = `Số dư 888API: ${s.balance ?? '?'} ${s.currency || ''}`
    }
  } catch {
    balance.value = null
  }
}
watch(() => settings.provider, (p) => loadBalance(p))
/** 888API cần chỗ để link ảnh: TOS (AK/SK) hoặc Litterbox. */
/** 888API: nơi để ảnh (TOS / Litterbox) có dùng được không – kiểm tra thật trước khi chạy. */
const hostCheck = ref(null)
async function checkImageHost(force = false) {
  try {
    hostCheck.value = await api.get('/settings/api888/image-host-check', { params: { force }, silent: true, timeout: 60000 })
  } catch {
    hostCheck.value = null
  }
}
const needHostCheck = computed(() => settings.provider === 'API888' || prov.value === 'API888')
watch(needHostCheck, (v) => (v ? checkImageHost() : (hostCheck.value = null)), { immediate: true })
async function useLitterbox() {
  busy.value = 'litterbox'
  try {
    await api.put('/settings/api888', { imageHost: 'LITTERBOX' })
    await appSettings.load()
    await checkImageHost()
    ElMessage.success('Đã đổi nơi để ảnh 888API sang Litterbox – bấm Chạy lại')
  } finally {
    busy.value = ''
  }
}
const api888HostNote = computed(() => {
  const s = appSettings.settings
  if (!s) return ''
  if (!s.api888KeySet) return '⚠ Chưa nhập API key 888API (Cài đặt › 888API).'
  if ((s.api888ImageHost || 'TOS') === 'TOS') {
    return s.akskSet
      ? 'Ảnh tải lên TOS riêng tư của bạn (link 48 giờ) trong thư mục của dự án này.'
      : '⚠ Chỗ để ảnh là TOS nhưng chưa nhập AK/SK BytePlus – nhập AK/SK hoặc chọn Litterbox ở Cài đặt › 888API.'
  }
  return 'Ảnh tải lên Litterbox (link công khai, tự xoá sau 72 giờ).'
})
const imageModel = computed(() => models.image.find((m) => m.id === settings.imageModel))
const IMAGE_STATUS = {
  PENDING: { status: 'queued', label: 'Chờ tạo ảnh' },
  GENERATING: { status: 'running', label: 'Đang tạo ảnh' },
  REVIEW: { status: 'info', label: 'Chờ duyệt' },
  SUCCEEDED: { status: 'succeeded', label: 'AI đã tạo' },
  FAILED: { status: 'failed', label: 'Tạo ảnh lỗi' }
}
const missingRefs = computed(() => refs.data.value.filter((r) => !r.file && r.usedBy > 0).length)
/** Trạng thái ảnh của một mã: thiếu / chờ duyệt / đang tạo / bị chặn / có. */
function refState(r) {
  if (r.imageStatus === 'REVIEW') return 'review'
  if (r.imageStatus === 'PENDING' || r.imageStatus === 'GENERATING') return 'generating'
  if (!r.file) return 'missing'
  if (r.blockedReason) return 'blocked'
  return 'ok'
}
const REF_STATE = {
  missing: { label: 'thiếu', chip: 'bg-rose-50 text-rose-700 ring-rose-300 dark:bg-rose-500/10 dark:text-rose-300 dark:ring-rose-500/40' },
  blocked: { label: 'bị chặn', chip: 'bg-rose-50 text-rose-700 ring-rose-300 dark:bg-rose-500/10 dark:text-rose-300 dark:ring-rose-500/40' },
  review: { label: 'chờ duyệt', chip: 'bg-indigo-50 text-indigo-700 ring-indigo-300 dark:bg-indigo-500/10 dark:text-indigo-300 dark:ring-indigo-500/40' },
  generating: { label: 'đang tạo', chip: 'bg-amber-50 text-amber-700 ring-amber-300 dark:bg-amber-500/10 dark:text-amber-300 dark:ring-amber-500/40' },
  ok: { label: 'có ảnh', chip: 'bg-emerald-50 text-emerald-700 ring-emerald-200 dark:bg-emerald-500/10 dark:text-emerald-300 dark:ring-emerald-500/30' }
}
const STATE_ORDER = { missing: 0, blocked: 1, review: 2, generating: 3, ok: 4 }
/** Lọc lưới ảnh: '' tất cả · missing (thiếu / bị chặn) · review (chờ duyệt). */
const refFilter = ref('')
const refSummary = computed(() => {
  const r = { total: 0, ok: 0, missing: 0, review: 0, generating: 0, blocked: 0 }
  for (const x of refs.data.value || []) {
    r.total++
    r[refState(x)]++
  }
  return r
})
/** Dải tổng quan: theo Nhân vật / Bối cảnh / Đạo cụ / Khác, mã thiếu xếp đầu. */
const refGroups = computed(() => {
  const by = {}
  for (const x of refs.data.value || []) (by[x.kind] ||= []).push(x)
  return Object.keys(KIND).filter((k) => by[k]?.length).map((k) => ({
    kind: k,
    label: KIND[k],
    items: by[k].slice().sort((a, b) => STATE_ORDER[refState(a)] - STATE_ORDER[refState(b)] || a.code.localeCompare(b.code))
  }))
})
/** Mã có ảnh AI chờ duyệt / thiếu ảnh được đưa lên đầu lưới; lọc theo ô đã bấm. */
const sortedRefs = computed(() => {
  let list = refs.data.value || []
  if (refFilter.value === 'missing') list = list.filter((r) => ['missing', 'blocked'].includes(refState(r)))
  else if (refFilter.value === 'review') list = list.filter((r) => refState(r) === 'review')
  return [...list.filter((r) => r.imageStatus === 'REVIEW'), ...list.filter((r) => r.imageStatus !== 'REVIEW')]
})
const flashRef = ref('')
/** Bấm mã trong dải tổng quan → cuộn tới thẻ ảnh của mã đó (bỏ lọc nếu thẻ đang bị ẩn). */
async function jumpToRef(code) {
  if (!sortedRefs.value.some((r) => r.code === code)) refFilter.value = ''
  await nextTick()
  document.getElementById('ref-' + code)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  flashRef.value = code
  setTimeout(() => flashRef.value === code && (flashRef.value = ''), 2000)
}
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

/** Ô đếm trên thanh cố định: bấm ô clip → lọc bảng clip; bấm ô ảnh → tab Ảnh tham chiếu lọc theo ô đó. */
const tiles = computed(() => {
  const p = project.value
  const c = p?.counts || {}
  const ready = Math.max((c.PENDING || 0) - (p?.blockedClips || 0), 0)
  return [
    { key: '', label: 'Tất cả', value: c.TOTAL, tone: 'text-slate-700 dark:text-zinc-100' },
    { key: 'READY', label: 'Sẵn sàng', value: ready, tone: 'text-sky-600 dark:text-sky-400', title: 'Chờ chạy, đủ ảnh tham chiếu' },
    { key: 'BLOCKED', label: 'Thiếu ảnh', value: p?.blockedClips, tone: 'text-rose-600 dark:text-rose-400', title: 'Clip chờ chạy nhưng còn mã ảnh chưa có ảnh' },
    { key: 'GENERATING', label: 'Đang chạy', value: c.GENERATING, tone: 'text-amber-600 dark:text-amber-400' },
    { key: 'SUCCEEDED', label: 'Xong', value: c.SUCCEEDED, tone: 'text-emerald-600 dark:text-emerald-400' },
    { key: 'FAILED', label: 'Lỗi', value: c.FAILED, tone: 'text-rose-600 dark:text-rose-400' },
    { key: 'CANCELLED', label: 'Đã huỷ', value: c.CANCELLED, tone: 'text-slate-500', hideZero: true },
    { key: 'WARNING', label: 'Cảnh báo', value: specs.data.value?.length ? warningCount.value : p?.warningClips, tone: 'text-indigo-600 dark:text-indigo-400',
      title: 'Lỗi dữ liệu trong Excel + clip vượt giới hạn của model đang chọn', hideZero: !(filter.value === 'WARNING') }
  ].filter((t) => !t.hideZero || t.value)
})
const refTiles = computed(() => {
  const p = project.value
  return [
    { key: 'missing', label: 'Ảnh thiếu', value: p?.refsMissing, tone: 'text-rose-600 dark:text-rose-400', title: 'Mã ảnh tham chiếu chưa có ảnh' },
    { key: 'review', label: 'Chờ duyệt', value: p?.imagesReview, tone: 'text-indigo-600 dark:text-indigo-400', title: 'Ảnh AI đang chờ bạn duyệt' }
  ]
})
function pickClipTile(key) {
  filter.value = key
  tab.value = 'clips'
}
function pickRefTile(key) {
  refFilter.value = refFilter.value === key && tab.value === 'refs' ? '' : key
  tab.value = 'refs'
}
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
    await Promise.all([clips.run(), scenes.run(), specs.run(), tab.value === 'refs' || imgs ? refs.run() : Promise.resolve()])
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
  if (needHostCheck.value) checkImageHost() // vừa đổi nơi để ảnh / bật TOS ở trang khác
  tick()
})
onDeactivated(() => clearTimeout(timer))

watch(currentId, () => {
  query.page = 1
  filter.value = ''
  refFilter.value = ''
  expanded.value = []
  importResult.value = null
  clearSelection()
  if (project.value) Object.assign(settings, pick(project.value))
  if (currentId.value) {
    clips.run()
    refs.run()
    specs.run()
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

// ---------- dự án theo thư mục ----------
const folderDialog = ref(false)
/** Kết quả quét thư mục của dự án đang mở (số ảnh / video đã có trong thư mục). */
const folderScan = ref(null)
async function loadFolderScan() {
  folderScan.value = null
  if (!project.value?.rootDir) return
  try {
    folderScan.value = await api.get(`/script-projects/${currentId.value}/folder`, { silent: true, timeout: 120000 })
  } catch {
    folderScan.value = null
  }
}
watch(() => project.value?.id + '|' + (project.value?.rootDir || ''), loadFolderScan)
async function onFolderOpened(r) {
  importResult.value = r.imported
  currentId.value = r.project.id
  folderScan.value = r.scan
  await refresh()
  refs.run()
}
async function rescan() {
  busy.value = 'rescan'
  try {
    const r = await api.post(`/script-projects/${currentId.value}/rescan`, null, { timeout: 300000 })
    importResult.value = r.imported
    folderScan.value = r.scan
    const i = r.imported
    const sy = r.synced || {}
    ElMessage.success(`Đã quét lại: ${i.created + i.updated + i.unchanged} cảnh (${i.created} mới, ${i.updated} cập nhật) · gán ${sy.refsAssigned || 0} ảnh từ thư mục · ${sy.scenesDone || 0} cảnh nhận video có sẵn` + (sy.scenesReopened ? ` · ${sy.scenesReopened} cảnh mất video → chờ tạo lại` : ''))
    await refresh()
    refs.run()
  } finally {
    busy.value = ''
  }
}
const openInExplorer = (p) => api.post('/system/open-folder', { path: p })

async function removeProject() {
  await ElMessageBox.confirm(`Xoá dự án "${project.value.name}"? Video đã tạo vẫn giữ trong thư viện.`, 'Xoá dự án', { type: 'warning' })
  let deleteImages = false
  try {
    await ElMessageBox.confirm('Xoá luôn ảnh tham chiếu của dự án này khỏi máy? Chọn “Giữ ảnh” thì ảnh ở lại thư viện (không thuộc dự án nào).',
      'Ảnh tham chiếu của dự án', { confirmButtonText: 'Xoá cả ảnh', cancelButtonText: 'Giữ ảnh', distinguishCancelAndClose: true, type: 'warning' })
    deleteImages = true
  } catch (a) {
    if (a === 'close') return
  }
  await act('delete', () => api.delete(`/script-projects/${currentId.value}`, { params: { deleteImages } }))
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
async function cancelClip(row) {
  const src = taskSource(row.taskId)
  if (row.status === 'GENERATING' && src && src !== 'BytePlus') {
    await ElMessageBox.confirm(`${src} không có lệnh huỷ: app chỉ ngừng theo dõi, task vẫn chạy và vẫn tính tiền trên ${src}. Tiếp tục?`,
      'Ngừng theo dõi clip ' + row.code, { type: 'warning' })
  }
  await act('clip' + row.id, () => api.post(`/script-clips/${row.id}/cancel`))
}
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
    <PageHeader title="Kịch bản hàng loạt" subtitle="Mở thư mục dự án → app đọc Excel trong thư mục → gán / tạo ảnh tham chiếu → tự động gửi từng clip" icon="Film" />

    <!-- chọn / nhập dự án -->
    <section class="card mb-6 flex flex-wrap items-center gap-3 p-4">
      <el-select v-model="currentId" placeholder="Chọn dự án kịch bản" class="w-80" :disabled="!projects.data.value?.length">
        <el-option v-for="p in projects.data.value" :key="p.id" :label="p.name" :value="p.id">
          <div class="flex items-center justify-between gap-4">
            <span class="truncate">{{ p.name }}</span>
            <span class="shrink-0 text-xs text-slate-400">
              <el-tag size="small" :type="p.rootDir ? 'success' : 'info'" effect="plain" class="mr-1">{{ p.rootDir ? 'Thư mục' : 'Excel' }}</el-tag>
              {{ p.counts?.SUCCEEDED || 0 }}/{{ p.counts?.TOTAL || 0 }}
            </span>
          </div>
        </el-option>
      </el-select>
      <input ref="excelInput" type="file" multiple accept=".xlsx" class="hidden" @change="onExcelPicked" />
      <input ref="resultFolderInput" type="file" webkitdirectory directory multiple class="hidden" @change="(e) => onResultFolderPicked(e, false)" />
      <el-tooltip content="Chọn thư mục dự án (vd KET QUA - IDE 21): app tự đọc các file Excel trong thư mục, không cần nhập file" placement="bottom">
        <el-button type="primary" @click="folderDialog = true"><el-icon><FolderOpened /></el-icon>&nbsp;Mở thư mục dự án</el-button>
      </el-tooltip>
      <template v-if="project?.rootDir">
        <el-button @click="openInExplorer(project.rootDir)"><el-icon><Folder /></el-icon>&nbsp;Mở thư mục</el-button>
      </template>
      <el-dropdown trigger="click" @command="(c) => (c === 'excel' ? pickExcel(false) : c === 'folder' ? resultFolderInput.click() : pickExcel(true))">
        <el-button plain :loading="importing">Nhập Excel (cách cũ)<el-icon class="el-icon--right"><ArrowDown /></el-icon></el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="excel">Nhập file Excel thành dự án mới</el-dropdown-item>
            <el-dropdown-item command="folder">Tải cả thư mục kết quả lên (chép vào app)</el-dropdown-item>
            <el-dropdown-item v-if="project && !project.rootDir" command="again" :disabled="project.status === 'RUNNING'">Nhập lại vào dự án này</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <span v-if="project && !project.rootDir" class="hint ml-auto">File: {{ project.sourceFile }} · tạo {{ formatTime(project.createdAt) }} · video lưu ở output/videos/{{ project.folder }}</span>
    </section>

    <!-- dự án theo thư mục: đường dẫn + những gì đã có trong thư mục -->
    <section v-if="project?.rootDir" class="card -mt-3 mb-6 p-4 text-sm">
      <div class="flex flex-wrap items-center gap-x-3 gap-y-1">
        <span class="font-medium">📁 {{ project.rootDir }}</span>
        <span v-if="project.lastOpenedAt" class="hint">quét lúc {{ formatTime(project.lastOpenedAt) }}</span>
      </div>
      <div class="mt-1 flex flex-wrap gap-x-5 gap-y-1 text-xs text-slate-600 dark:text-zinc-300">
        <span>Cảnh: <b>{{ project.sceneFile }}</b> ({{ project.counts.TOTAL }} cảnh)</span>
        <span v-if="project.refFile && project.refFile !== project.sceneFile">Prompt ảnh: <b>{{ project.refFile }}</b></span>
        <span v-if="project.voiceFile">Thoại: <b>{{ project.voiceFile }}</b></span>
        <template v-if="folderScan">
          <span>Ảnh tham chiếu: <b>{{ folderScan.refDir ? folderScan.refDir.files + ' ảnh' : 'chưa có thư mục' }}</b></span>
          <span>Chờ duyệt: <b>{{ folderScan.pendingDir ? folderScan.pendingDir.files + ' ảnh' : '0' }}</b></span>
          <span>Video trong thư mục: <b>{{ folderScan.videoDir ? `${folderScan.videoDir.files} file · ${folderScan.videoDir.scenes} cảnh` : 'chưa có' }}</b></span>
        </template>
      </div>
      <p v-for="p in folderScan?.problems || []" :key="p" class="mt-1 text-xs text-amber-600">⚠ {{ p }}</p>
      <p class="hint mt-1">
        Ảnh trong <code>{{ folderScan?.refDir?.name || 'Ảnh tham chiếu' }}/</code> (tên file = mã) được tự gán cho mã; cảnh đã có video trong
        <code>{{ folderScan?.videoDir?.name || 'Videos' }}/</code> là Xong, không chạy lại. Ảnh AI mới vào <code>{{ folderScan?.pendingDir?.name || 'Ảnh chờ duyệt' }}/</code>,
        video mới tự lưu vào <code>{{ folderScan?.videoDir?.name || 'Videos' }}/</code> (làm lại thì thêm <code>_v2</code>…). Sửa Excel hoặc bỏ thêm ảnh / video vào thư mục thì bấm <b>Quét lại</b>.
      </p>
    </section>

    <AsyncState
      :loading="projects.loading.value && !projects.data.value?.length"
      :error="projects.error.value"
      :empty="!projects.data.value?.length"
      empty-text="Chưa có dự án nào. Bấm “Mở thư mục dự án” và chọn thư mục dự án (vd KET QUA - IDE 21) – app tự đọc các file Excel trong đó."
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

        <!-- thanh cố định: tên + đường dẫn, ô đếm (bấm để lọc), nút chạy – đứng yên khi cuộn danh sách -->
        <section class="card sticky top-0 z-30 mb-4 bg-white px-5 py-3 shadow-md shadow-slate-900/5 dark:bg-zinc-800">
          <div class="flex flex-wrap items-center gap-3">
            <h2 class="text-lg font-semibold">{{ project.name }}</h2>
            <span v-if="project.rootDir" class="hidden max-w-[28rem] truncate text-xs text-slate-400 lg:inline" :title="project.rootDir">📁 {{ project.rootDir }}</span>
            <StatusBadge :status="PROJECT_STATUS[project.status].status" :label="PROJECT_STATUS[project.status].label" />
            <el-tag v-if="project.onlySelected" type="warning" effect="plain" size="small">
              Chỉ chạy clip đã chọn{{ project.selectedPending ? ` · còn ${project.selectedPending}` : '' }}
            </el-tag>
            <div class="ml-auto flex flex-wrap gap-2">
              <el-button v-if="project.status !== 'RUNNING' && project.refsMissing && hasBytePlusKey" type="primary" :loading="busy === 'all'" @click="runAll">

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
              <el-button v-if="project.rootDir" :loading="busy === 'rescan'" :disabled="project.status === 'RUNNING'" :title="project.status === 'RUNNING' ? 'Tạm dừng dự án rồi mới quét lại' : 'Đọc lại Excel và ảnh / video trong thư mục'" @click="rescan">
                <el-icon><RefreshRight /></el-icon>&nbsp;Quét lại
              </el-button>
              <el-button type="danger" plain :disabled="project.status === 'RUNNING'" :loading="busy === 'delete'" @click="removeProject">
                <el-icon><Delete /></el-icon>
              </el-button>
            </div>
          </div>
          <div class="mt-3 flex flex-wrap items-center gap-2">
            <button
              v-for="t in tiles"
              :key="t.key"
              type="button"
              :title="t.title"
              class="flex items-baseline gap-1.5 rounded-lg bg-slate-50 px-3 py-1.5 ring-1 transition hover:shadow-sm dark:bg-zinc-900/50"
              :class="filter === t.key && tab === 'clips' ? 'ring-2 ring-indigo-500' : 'ring-slate-200/70 dark:ring-zinc-700/60'"
              @click="pickClipTile(t.key)"
            >
              <span class="text-lg font-semibold tabular-nums" :class="t.tone">{{ t.value ?? 0 }}</span>
              <span class="text-xs text-slate-500 dark:text-zinc-400">{{ t.label }}</span>
            </button>
            <span class="mx-1 h-6 w-px bg-slate-200 dark:bg-zinc-700" />
            <button
              v-for="t in refTiles"
              :key="t.key"
              type="button"
              :title="t.title"
              class="flex items-baseline gap-1.5 rounded-lg bg-slate-50 px-3 py-1.5 ring-1 transition hover:shadow-sm dark:bg-zinc-900/50"
              :class="refFilter === t.key && tab === 'refs' ? 'ring-2 ring-indigo-500' : 'ring-slate-200/70 dark:ring-zinc-700/60'"
              @click="pickRefTile(t.key)"
            >
              <span class="text-lg font-semibold tabular-nums" :class="t.tone">{{ t.value ?? 0 }}</span>
              <span class="text-xs text-slate-500 dark:text-zinc-400">{{ t.label }}</span>
            </button>
            <div class="ml-auto flex min-w-[180px] items-center gap-2">
              <el-progress class="flex-1" :percentage="progress" :stroke-width="8" :show-text="false" :status="progress === 100 ? 'success' : undefined" />
              <span class="text-xs font-medium tabular-nums text-slate-500">{{ project.counts.SUCCEEDED || 0 }}/{{ project.counts.TOTAL }} · {{ progress }}%</span>
            </div>
          </div>
        </section>

        <!-- thông báo + cấu hình -->
        <section class="card mb-6 px-5 py-3">
          <el-alert v-if="needHostCheck && hostCheck && !hostCheck.ok" type="error" :closable="false" show-icon class="mb-3"
            title="888API chưa lấy được link ảnh – chạy bây giờ sẽ lỗi">
            <div class="text-sm">{{ hostCheck.message }}</div>
            <div class="mt-2 flex flex-wrap gap-2">
              <el-button size="small" type="primary" :loading="busy === 'litterbox'" @click="useLitterbox">Đổi sang Litterbox</el-button>
              <el-button size="small" @click="checkImageHost(true)">Kiểm tra lại (đã bật TOS)</el-button>
            </div>
          </el-alert>
          <p v-if="project.pausedReason && !(needHostCheck && hostCheck && !hostCheck.ok && project.pausedReason.includes('TOS'))" class="text-sm" :class="project.status === 'DONE' ? 'text-emerald-600' : 'text-amber-600 dark:text-amber-400'">
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
          <!-- cấu hình -->
          <el-collapse :class="project.pausedReason || project.imagesPending || project.imagesReview ? 'mt-3' : ''">
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
                  <div>
                    <label class="field-label">Độ phân giải</label>
                    <div class="pt-1.5 text-sm">{{ api888Model?.resolution || '720p' }} <span class="hint">(cố định theo model)</span></div>
                  </div>
                  <div class="sm:col-span-3">
                    <label class="field-label">Model 888API</label>
                    <el-select v-model="settings.api888Model" class="w-full" filterable allow-create default-first-option
                      placeholder="Chọn hoặc gõ mã model">
                      <el-option v-for="m in api888Models" :key="m.id" :label="m.id" :value="m.id">
                        <span>{{ m.id }}</span>
                        <span class="hint ml-2">{{ m.durations ? m.durations.join('/') : m.min + '–' + m.max }}s · {{ m.resolution }} · ≤{{ m.maxImages }} ảnh · {{ m.note }}</span>
                        <span class="ml-2 text-xs font-medium text-emerald-600">{{ fitLabel('API888', m.id) }}</span>
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
                        <span class="ml-2 text-xs font-medium text-emerald-600">{{ fitLabel('BOTSD', m.id) }}</span>
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
              <p v-if="settings.provider === 'BYTEPLUS'" class="hint mb-4">
                BytePlus: ảnh nhân vật được đẩy lên Virtual Portrait vào <b>asset group riêng của dự án này</b> (app tự tạo) rồi gửi <code>asset://</code>
                để không bị chặn mặt người; chỉ dùng asset của dự án này. Có “Tạo âm thanh” và tỉ lệ <code>adaptive</code>; dừng clip là huỷ hẳn task.
              </p>
              <p class="mb-4 text-xs text-slate-600 dark:text-zinc-300">
                {{ durationRule }}<template v-if="balance"> · <b class="text-emerald-600 dark:text-emerald-400">{{ balance }}</b></template>
              </p>
              <div v-if="draftFit.total" class="mb-4 rounded-lg px-3 py-2 text-xs ring-1 ring-inset"
                :class="draftFit.ok === draftFit.total ? 'bg-emerald-50 text-emerald-700 ring-emerald-200 dark:bg-emerald-500/10 dark:text-emerald-300 dark:ring-emerald-500/30' : 'bg-amber-50 text-amber-800 ring-amber-200 dark:bg-amber-500/10 dark:text-amber-300 dark:ring-amber-500/30'">
                <b>Theo {{ draftSpec.label }}</b><template v-if="specUnsaved"> (đang xem thử – bấm <b>Lưu cấu hình</b> để áp dụng)</template>:
                vừa {{ draftFit.ok }}/{{ draftFit.total }} clip chưa xong<template v-if="draftFit.ok === draftFit.total"> ✓</template>
                <span v-if="draftFit.cut.length" class="ml-2" :title="codesHint(draftFit.cut)">· {{ draftFit.cut.length }} clip dài hơn mức tối đa sẽ bị cắt</span>
                <span v-if="draftFit.images.length" class="ml-2" :title="codesHint(draftFit.images)">· {{ draftFit.images.length }} clip quá {{ draftSpec.maxImages }} ảnh</span>
                <span v-if="draftFit.prompt.length" class="ml-2" :title="codesHint(draftFit.prompt)">· {{ draftFit.prompt.length }} prompt quá {{ draftSpec.promptMax.toLocaleString() }} ký tự</span>
              </div>
              <p v-if="settings.provider === 'BOTSD'" class="hint mb-4">
                BOTSD: ảnh tham chiếu gửi thẳng từ máy (không cần Virtual Portrait), tính bằng Point BOTSD (đăng nhập ở Cài đặt › BOTSD).
                Thời lượng clip làm tròn lên 5 / 10 / 15 / 30s. BOTSD không có lệnh huỷ – dừng clip chỉ ngừng theo dõi, Point đã trừ.
                Không dùng “Tạo âm thanh” và model / độ phân giải BytePlus bên dưới.
              </p>
              <div class="grid gap-4 sm:grid-cols-3 lg:grid-cols-6">
                <div v-if="!providerRaw" class="sm:col-span-2">
                  <label class="field-label">Model</label>
                  <el-select v-model="settings.model" class="w-full">
                    <el-option v-for="m in omniModels" :key="m.id" :label="m.name" :value="m.id">
                      <span>{{ m.name }}</span><span class="ml-2 text-xs font-medium text-emerald-600">{{ fitLabel('BYTEPLUS', m.id) }}</span>
                    </el-option>
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
                    <el-option v-for="r in ratioOptions" :key="r" :label="r" :value="r" />
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
                <el-checkbox v-if="settings.provider === 'BYTEPLUS'" v-model="settings.generateAudio">Tạo âm thanh (thoại trong prompt)</el-checkbox>
                <el-checkbox v-model="settings.autoApproveImages">Tự duyệt ảnh AI (bỏ qua bước chờ duyệt)</el-checkbox>
                <el-button type="primary" plain :loading="busy === 'settings'" @click="saveSettings">Lưu cấu hình</el-button>
                <span class="hint">
                  Mỗi clip dùng thời lượng ghi trong kịch bản (cột DÀI).
                  <template v-if="settings.provider === 'BOTSD'">BOTSD giới hạn số task chạy cùng lúc theo tài khoản (vượt thì app tự chờ 30s); app gửi lần lượt từng task.</template>
                  <template v-else-if="settings.provider === 'API888'">888API: nên để song song 3; gặp 429 app chờ đúng thời gian máy chủ yêu cầu.</template>
                  <template v-else>Song song nhiều quá dễ bị BytePlus trả 429 – app sẽ tự chờ rồi gửi lại.</template>
                </span>
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

            <div v-if="draftFit.total" class="mb-3 rounded-lg px-3 py-2 text-xs ring-1 ring-inset"
                :class="draftFit.ok === draftFit.total ? 'bg-emerald-50 text-emerald-700 ring-emerald-200 dark:bg-emerald-500/10 dark:text-emerald-300 dark:ring-emerald-500/30' : 'bg-amber-50 text-amber-800 ring-amber-200 dark:bg-amber-500/10 dark:text-amber-300 dark:ring-amber-500/30'">
                <b>Theo {{ draftSpec.label }}</b><template v-if="specUnsaved"> (đang xem thử – bấm <b>Lưu cấu hình</b> để áp dụng)</template>:
                vừa {{ draftFit.ok }}/{{ draftFit.total }} clip chưa xong<template v-if="draftFit.ok === draftFit.total"> ✓</template>
                <span v-if="draftFit.cut.length" class="ml-2" :title="codesHint(draftFit.cut)">· {{ draftFit.cut.length }} clip dài hơn mức tối đa sẽ bị cắt</span>
                <span v-if="draftFit.images.length" class="ml-2" :title="codesHint(draftFit.images)">· {{ draftFit.images.length }} clip quá {{ draftSpec.maxImages }} ảnh</span>
                <span v-if="draftFit.prompt.length" class="ml-2" :title="codesHint(draftFit.prompt)">· {{ draftFit.prompt.length }} prompt quá {{ draftSpec.promptMax.toLocaleString() }} ký tự</span>
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
                          <div v-if="drafts[row.id] && projPromptMax" class="mt-1 text-right text-xs"
                            :class="drafts[row.id].prompt.length > projPromptMax ? 'font-semibold text-rose-600' : 'text-slate-400'">
                            {{ drafts[row.id].prompt.length.toLocaleString() }} / {{ projPromptMax.toLocaleString() }} ký tự ({{ draftSpec.label }})
                          </div>
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
                              <el-button v-if="hasBytePlusKey" size="small" :loading="row.analyzing || busy === 'analyze' + row.id" @click="analyzeBlock(row)">
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
                          <p v-if="!row.blockAnalysis && !row.analyzing" class="hint">
                            <template v-if="hasBytePlusKey">{{ taskSource(row.taskId) && taskSource(row.taskId) !== 'BytePlus' ? taskSource(row.taskId) : 'BytePlus' }} không nói đoạn nào bị chặn – bấm nút để AI (BytePlus) đọc prompt + lời thoại và chỉ ra bằng tiếng Việt.</template>
                            <template v-else>Thông báo của máy chủ ở cột Tiến độ / lỗi. Muốn AI phân tích vì sao bị chặn cần API key BytePlus.</template>
                          </p>
                        </div>
                        <ul v-if="rowWarnings(row).length" class="space-y-1 rounded-lg bg-indigo-50 p-3 text-xs text-indigo-700 dark:bg-indigo-500/10 dark:text-indigo-300">
                          <li v-for="w in rowWarnings(row)" :key="w">• {{ w }}</li>
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
                        <p v-if="row.taskId" class="font-mono text-xs text-slate-400">task ({{ taskSource(row.taskId) }}): {{ row.taskId }}</p>
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
                    <template v-if="row.status !== 'SUCCEEDED' && row.duration">
                      <div v-if="sentSeconds(row.duration) < row.duration" class="mt-0.5 text-[11px] font-medium text-rose-600"
                        :title="`${draftSpec.label} không nhận ${row.duration}s – video chỉ dài ${sentSeconds(row.duration)}s, mất ${row.duration - sentSeconds(row.duration)}s cuối`">
                        gửi {{ sentSeconds(row.duration) }}s – bị cắt {{ row.duration - sentSeconds(row.duration) }}s
                      </div>
                      <div v-else-if="sentSeconds(row.duration) > row.duration" class="mt-0.5 text-[11px] text-slate-400">gửi {{ sentSeconds(row.duration) }}s (làm tròn lên)</div>
                    </template>
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
                    <div v-if="projMaxImages && row.refCodes.length > projMaxImages" class="mt-1 text-[11px] font-medium text-rose-600">
                      {{ row.refCodes.length }} ảnh > tối đa {{ projMaxImages }} của {{ draftSpec.label }} – đổi model hoặc bớt ảnh
                    </div>
                  </template>
                </el-table-column>
                <el-table-column label="Trạng thái" width="150">
                  <template #default="{ row }">
                    <StatusBadge v-if="row.status === 'PENDING' && row.missingRefs.length" status="missing" label="Chờ ảnh" />
                    <StatusBadge v-else :status="CLIP_STATUS[row.status].status" :label="CLIP_STATUS[row.status].label" />
                    <div v-if="row.attempt" class="mt-1 text-[11px] text-slate-400">lần thử {{ row.attempt }}</div>
                    <div v-if="row.taskId && taskSource(row.taskId) !== PROVIDER_LABEL[prov]" class="mt-1 text-[11px] text-amber-600" title="Clip này đã gửi ở nguồn khác với nguồn hiện tại của dự án – app theo dõi tiếp ở đúng nguồn đã gửi">
                      gửi qua {{ taskSource(row.taskId) }}
                    </div>
                    <div v-if="row.runSelected && (row.status === 'PENDING' || row.status === 'GENERATING')" class="mt-1 text-[11px] font-medium text-indigo-500">● trong lượt chạy riêng</div>
                    <el-icon v-if="rowWarnings(row).length" class="ml-1 text-indigo-500" :title="rowWarnings(row).join('\n')"><WarningFilled /></el-icon>
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
                      <el-button v-if="row.status === 'GENERATING' || row.status === 'PENDING'" size="small" text :loading="busy === 'clip' + row.id"
                        :title="row.status === 'GENERATING' && taskSource(row.taskId) !== 'BytePlus' ? 'Nguồn này không có lệnh huỷ – chỉ ngừng theo dõi' : ''" @click="cancelClip(row)">
                        {{ row.status === 'GENERATING' && taskSource(row.taskId) && taskSource(row.taskId) !== 'BytePlus' ? 'Ngừng theo dõi' : 'Dừng' }}
                      </el-button>
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
              <el-button type="primary" :disabled="!hasBytePlusKey || !project.refsMissing || project.imagesPending > 0" :title="hasBytePlusKey ? '' : NEED_KEY" :loading="busy === 'gen'" @click="generateRefs()">
                <el-icon><MagicStick /></el-icon>&nbsp;Tạo bằng AI các ảnh còn thiếu ({{ project.refsMissing }})
              </el-button>
              <el-button v-if="aiTargets.length || busy === 'aiAll'" type="primary" plain :disabled="!hasBytePlusKey" :title="hasBytePlusKey ? '' : NEED_KEY" :loading="busy === 'aiAll'" @click="aiWriteAll">
                <el-icon><EditPen /></el-icon>&nbsp;{{ busy === 'aiAll' ? `AI đang viết prompt ${aiProgress}` : `AI viết prompt ảnh (${aiTargets.length})` }}
              </el-button>
              <el-button v-if="project.imagesReview" type="success" :loading="busy === 'approveAll'" @click="approveAll">
                <el-icon><Select /></el-icon>&nbsp;Duyệt tất cả ({{ project.imagesReview }})
              </el-button>
              <el-button v-if="project.imagesPending" type="warning" plain :loading="busy === 'stopgen'" @click="stopRefs">Dừng tạo ảnh ({{ project.imagesPending }})</el-button>
              <el-button v-if="isBytePlus && vpPending.length" type="warning" plain :loading="busy === 'vp'" @click="pushCharactersToVp">
                <el-icon><UploadFilled /></el-icon>&nbsp;Đẩy {{ vpPending.length }} ảnh nhân vật lên Virtual Portrait
              </el-button>
              <el-button plain @click="downloadZip('images')"><el-icon><Download /></el-icon>&nbsp;Tải hết ảnh về (.zip)</el-button>
              <el-button type="success" plain @click="(folderImportFiles = []), (folderImport = true)"><el-icon><FolderOpened /></el-icon>&nbsp;Nhập thư mục ảnh</el-button>
              <el-button :loading="busy === 'auto'" @click="autoMatch"><el-icon><Connection /></el-icon>&nbsp;Tự ghép theo tên file</el-button>
              <el-button v-if="isBytePlus" type="primary" plain :loading="busy === 'vpmatch'" @click="matchVpAssets"><el-icon><Connection /></el-icon>&nbsp;Ghép asset VP theo tên</el-button>
              <p class="w-full text-xs text-slate-600 dark:text-zinc-300">
                Thư viện ảnh <b>riêng của dự án này</b>: “Tự ghép theo tên file” và lúc nhập Excel chỉ tìm ảnh của dự án này; chọn ảnh dự án khác trong Thư viện thì app chép thành ảnh riêng.
                <template v-if="prov === 'BOTSD'"> · BOTSD: ảnh gửi thẳng từ máy (jpg / png / webp ≤30MB), không cần Virtual Portrait<template v-if="project.botsdModel === 'muse'">; Muse AI chỉ nhận 1 ảnh</template>.</template>
                <template v-else-if="prov === 'API888'"> · 888API: {{ api888HostNote }}<template v-if="project.api888Model?.endsWith('-face')"> Model -face chỉ nhận PNG / JPEG ≤10MB.</template></template>
              </p>
              <p v-if="isBytePlus" class="hint max-w-3xl">
                Ảnh nhân vật lấy từ máy được app tự đẩy lên Virtual Portrait (asset group riêng của dự án, qua bucket TOS riêng tư) trước khi gửi clip – hoặc bấm nút đẩy trước cho nhanh.
                Ảnh tạo bằng AI vào mục <b>chờ duyệt</b> trước (<code>_cho-duyet/</code>): bấm <b>Duyệt</b> để đổi tên thành <code>MÃ.jpeg</code> trong thư mục ảnh tham chiếu của dự án (ảnh cũ chuyển vào <code>_cu/</code>; nếu chọn “thư mục riêng” trong Cấu hình chạy thì chép thêm vào đó theo loại), hoặc <b>Tạo lại</b> / <b>Bỏ</b>. Việc đẩy Virtual Portrait do phần chạy video tự làm khi gửi clip.
                Prompt ảnh được viết sẵn từ mô tả nhân vật / bối cảnh / đạo cụ trong Excel (bấm “Prompt” để xem, sửa). Nhân vật nhiều bộ đồ:
                bộ đầu được tạo trước và dùng làm tham chiếu khuôn mặt cho các bộ sau. Hoặc đặt tên file đúng mã (vd <code>CHAR_YVO_NAVYTEE_01.png</code>) rồi bấm “Tự ghép”, hoặc tải lên / chọn từ thư viện cho từng mã.
                Ảnh nhân vật là người thật nên đẩy vào <b>Virtual Portrait</b> trước – khi ảnh đã có asset Active, app gửi <code>asset://</code> để Seedance không chặn mặt người.
              </p>
              <p v-else class="hint max-w-3xl">
                Ảnh tạo bằng AI vào mục <b>chờ duyệt</b> trước: bấm <b>Duyệt</b> để dùng, hoặc <b>Tạo lại</b> / <b>Bỏ</b>. Hoặc đặt tên file đúng mã
                (vd <code>CHAR_YVO_NAVYTEE_01.png</code>) rồi bấm “Tự ghép”, hoặc tải lên / chọn từ thư viện cho từng mã. Ảnh gửi đi đúng thứ tự Ảnh 1, 2… của clip.
              </p>
            </div>
            <!-- dải tổng quan ảnh tham chiếu: theo loại, mã thiếu xếp đầu, bấm mã để nhảy tới thẻ ảnh -->
            <div v-if="refSummary.total" class="mb-5 rounded-xl bg-slate-50 p-4 ring-1 ring-slate-200/70 dark:bg-zinc-900/50 dark:ring-zinc-700/60">
              <div class="mb-3 flex flex-wrap items-center gap-x-4 gap-y-2 text-sm">
                <span class="font-semibold">{{ refSummary.ok + refSummary.blocked }}/{{ refSummary.total }} mã đã có ảnh</span>
                <span v-if="refSummary.missing" class="text-rose-600 dark:text-rose-400">· {{ refSummary.missing }} thiếu</span>
                <span v-if="refSummary.blocked" class="text-rose-600 dark:text-rose-400">· {{ refSummary.blocked }} bị chặn</span>
                <span v-if="refSummary.review" class="text-indigo-600 dark:text-indigo-400">· {{ refSummary.review }} chờ duyệt</span>
                <span v-if="refSummary.generating" class="text-amber-600 dark:text-amber-400">· {{ refSummary.generating }} đang tạo</span>
                <el-radio-group v-model="refFilter" size="small" class="ml-auto">
                  <el-radio-button value="">Tất cả ({{ refSummary.total }})</el-radio-button>
                  <el-radio-button value="missing">Thiếu ({{ refSummary.missing + refSummary.blocked }})</el-radio-button>
                  <el-radio-button value="review">Chờ duyệt ({{ refSummary.review }})</el-radio-button>
                </el-radio-group>
              </div>
              <div class="space-y-2">
                <div v-for="g in refGroups" :key="g.kind" class="flex gap-3">
                  <div class="w-20 shrink-0 pt-0.5 text-xs font-medium text-slate-500 dark:text-zinc-400">{{ g.label }} ({{ g.items.length }})</div>
                  <div class="flex flex-wrap gap-1">
                    <button
                      v-for="r in g.items"
                      :key="r.code"
                      type="button"
                      class="rounded-md px-1.5 py-0.5 font-mono text-[11px] ring-1 ring-inset transition hover:brightness-95"
                      :class="REF_STATE[refState(r)].chip"
                      :title="`${r.code} – ${REF_STATE[refState(r)].label}${r.name ? ' · ' + r.name : ''} · ${r.usedBy} clip dùng`"
                      @click="jumpToRef(r.code)"
                    >{{ r.code }}</button>
                  </div>
                </div>
              </div>
            </div>
            <AsyncState :loading="refs.loading.value && !refs.data.value.length" :error="refs.error.value" :empty="!refs.data.value.length" empty-text="Dự án chưa có mã ảnh tham chiếu" @retry="refs.run()">
              <p v-if="!sortedRefs.length" class="hint py-6 text-center">Không có mã nào trong mục này.</p>
              <div class="grid grid-cols-[repeat(auto-fill,minmax(250px,1fr))] gap-4">
                <div
                  v-for="r in sortedRefs"
                  :id="'ref-' + r.code"
                  :key="r.code"
                  class="scroll-mt-48 overflow-hidden rounded-xl bg-slate-50 ring-1 transition-shadow dark:bg-zinc-900/50"
                  :class="flashRef === r.code ? 'ring-4 ring-amber-400' : r.pendingFile ? 'ring-2 ring-indigo-400 dark:ring-indigo-500/60' : r.file && !r.blockedReason ? 'ring-slate-200/70 dark:ring-zinc-700/60' : 'ring-2 ring-rose-400 dark:ring-rose-500/60'"
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
                    <span v-if="isBytePlus && r.file?.assetId" class="absolute right-2 top-2"><StatusBadge :status="r.file.assetStatus" :label="'VP ' + (r.file.assetStatus || '')" /></span>
                  </div>
                  <div class="space-y-1 p-3">
                    <div class="font-mono text-xs font-semibold">{{ r.code }}</div>
                    <div class="line-clamp-1 text-sm" :title="r.name">{{ r.name }}</div>
                    <div class="line-clamp-2 text-xs text-slate-500" :title="r.description">{{ r.description }}</div>
                    <div v-if="r.blockedReason" class="rounded-md bg-rose-50 px-2 py-1 text-[11px] text-rose-700 dark:bg-rose-500/10 dark:text-rose-300">
                      ⛔ {{ r.blockedReason }}. Đẩy ảnh này lên Virtual Portrait (khi VP Active app gửi asset://) hoặc bấm “Tạo lại AI”.
                    </div>
                    <div v-if="r.file && r.file.projectId !== project.id" class="rounded-md bg-rose-50 px-2 py-1 text-[11px] text-rose-700 dark:bg-rose-500/10 dark:text-rose-300">
                      ⚠ Ảnh này {{ r.file.projectId ? 'thuộc dự án khác' : 'không thuộc dự án nào' }} – bấm “Thư viện” / “Tải lên” chọn lại ảnh cho dự án này.
                    </div>
                    <div v-if="r.identityCode" class="text-[11px] text-indigo-600 dark:text-indigo-400">Giữ khuôn mặt theo {{ r.identityCode }}</div>
                    <div v-if="r.imageError" class="line-clamp-2 text-[11px]" :class="r.imageStatus === 'FAILED' ? 'text-rose-600' : 'text-slate-500'" :title="r.imageError">{{ r.imageError }}</div>
                    <div v-if="r.imageStatus === 'REVIEW'" class="flex flex-wrap gap-1 rounded-lg bg-indigo-50 p-2 dark:bg-indigo-500/10">
                      <el-button size="small" type="success" :loading="busy === 'approve' + r.code" @click="approveRef(r)"><el-icon><Select /></el-icon>&nbsp;Duyệt</el-button>
                      <el-button size="small" type="primary" plain :disabled="!hasBytePlusKey" :title="hasBytePlusKey ? '' : NEED_KEY" :loading="busy === 'gen' + r.code" @click="generateRefs([r.code], true)"><el-icon><RefreshRight /></el-icon>&nbsp;Tạo lại</el-button>
                      <el-button size="small" text type="danger" :loading="busy === 'reject' + r.code" @click="rejectRef(r)">Bỏ ảnh mới</el-button>
                    </div>
                    <div class="flex flex-wrap gap-1 pt-2">
                      <el-upload :show-file-list="false" accept="image/*" :auto-upload="false" :on-change="(f) => uploadRef(r.code, f.raw)">
                        <el-button size="small" :loading="busy === 'ref' + r.code"><el-icon><Upload /></el-icon>&nbsp;Tải lên</el-button>
                      </el-upload>
                      <el-button size="small" @click="pickerFor = r.code"><el-icon><Files /></el-icon>&nbsp;Thư viện</el-button>
                      <el-button v-if="r.imageStatus !== 'REVIEW'" size="small" type="primary" plain :disabled="!hasBytePlusKey || r.imageStatus === 'PENDING' || r.imageStatus === 'GENERATING'" :title="hasBytePlusKey ? '' : NEED_KEY" :loading="busy === 'gen' + r.code" @click="generateRefs([r.code], true)">
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
        <el-button class="float-left" :disabled="!hasBytePlusKey" :title="hasBytePlusKey ? '' : NEED_KEY" :loading="busy === 'aiDraft'" @click="aiDraft"><el-icon><EditPen /></el-icon>&nbsp;AI viết lại (Language)</el-button>
        <el-button @click="promptFor = null">Huỷ</el-button>
        <el-button :loading="busy === 'prompt'" :disabled="!promptDraft.trim()" @click="savePrompt(false)">Lưu</el-button>
        <el-button type="primary" :loading="busy === 'prompt'" :disabled="!promptDraft.trim() || !hasBytePlusKey" :title="hasBytePlusKey ? '' : NEED_KEY" @click="savePrompt(true)">Lưu &amp; tạo ảnh</el-button>
      </template>
    </el-dialog>

    <ProjectFolderDialog v-model="folderDialog" @opened="onFolderOpened" />

    <RefFolderImport v-if="project" v-model="folderImport"
 :project-id="project.id" :references="refs.data.value" :initial-files="folderImportFiles" @done="refs.run(), refresh(false)" />

    <LibraryPicker :model-value="!!pickerFor" kind="image" :multiple="false" :project-id="project?.id" @update:model-value="(v) => !v && (pickerFor = null)" @pick="onPicked" />
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
