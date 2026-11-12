<script setup>
import { ref, reactive, computed, watch, onActivated } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import { useModelsStore } from '../stores/models'
import { useReferencesStore } from '../stores/references'
import PageHeader from '../components/PageHeader.vue'
import AsyncState from '../components/AsyncState.vue'
import RefPicker from '../components/RefPicker.vue'
import JobPanel from '../components/JobPanel.vue'

/** Seedance: POST /api/v1/videos/generations → job (tạo task, theo dõi, tải video, tự đẩy Virtual Portrait nếu bật). */
const models = useModelsStore()
const references = useReferencesStore()

const form = reactive({
  model: '', mode: 'TEXT', prompt: '',
  firstFrame: [], lastFrame: [], refImages: [], refVideos: [], refAudios: [],
  resolution: '', ratio: 'adaptive', duration: 5, generateAudio: true, watermark: false,
  seed: '', cameraFixed: false, returnLastFrame: false, outputFormat: 'mp4'
})
const fieldErrors = ref({})
const jobId = ref(null)
const submitting = ref(false)
const model = computed(() => models.video.find((m) => m.id === form.model))

function supports(m, mode) {
  if (!m) return false
  if (mode === 'FIRST_LAST') return m.firstLast
  if (mode === 'OMNI') return m.omni
  return true
}
const modes = computed(() => models.videoModes.filter((x) => supports(model.value, x.value)))
const ratioLocked = computed(() => model.value?.is25 && (form.mode === 'FIRST_FRAME' || form.mode === 'FIRST_LAST'))

async function init() {
  await models.ensure()
  if (!form.model && models.video.length) form.model = models.video[0].id
}
init()

watch(model, (m) => {
  if (!m) return
  form.resolution = m.defaultResolution
  form.duration = m.defaultDuration
  if (!supports(m, form.mode)) form.mode = 'TEXT'
})

watch([model, () => form.mode], () => {
  if (ratioLocked.value) form.ratio = 'adaptive'
  if (model.value?.id.startsWith('seedance-1-0') && form.mode === 'TEXT' && form.ratio === 'adaptive') form.ratio = '16:9'
})

/** Nhận tham chiếu gửi từ Thư viện / Virtual Portrait. */
onActivated(async () => {
  const incoming = references.takeVideo()
  if (!incoming.length) return
  await models.ensure()
  if (!model.value?.omni) {
    const omni = models.video.find((m) => m.omni)
    if (omni) form.model = omni.id
  }
  form.mode = 'OMNI'
  for (const r of incoming) {
    if (r.kind === 'video') form.refVideos.push(r)
    else if (r.kind === 'audio') form.refAudios.push(r)
    else form.refImages.push(r)
  }
  ElMessage.success(`Đã thêm ${incoming.length} tham chiếu – trong prompt gọi Image 1, Image 2, Video 1…`)
})

async function submit() {
  fieldErrors.value = {}
  submitting.value = true
  try {
    const omni = form.mode === 'OMNI'
    const job = await api.post('/videos/generations', {
      model: form.model,
      mode: form.mode,
      prompt: form.prompt,
      firstFrame: form.mode !== 'TEXT' && !omni ? form.firstFrame[0]?.value : null,
      lastFrame: form.mode === 'FIRST_LAST' ? form.lastFrame[0]?.value : null,
      refImages: omni ? form.refImages.map((r) => r.value) : [],
      refVideos: omni ? form.refVideos.map((r) => r.value) : [],
      refAudios: omni ? form.refAudios.map((r) => r.value) : [],
      resolution: form.resolution,
      ratio: form.ratio,
      duration: form.duration,
      generateAudio: form.generateAudio,
      watermark: form.watermark,
      seed: model.value?.seedAndCamera && form.seed !== '' ? Number(form.seed) : null,
      cameraFixed: form.cameraFixed,
      returnLastFrame: form.returnLastFrame,
      outputFormat: model.value?.is25 ? form.outputFormat : null
    })
    jobId.value = job.id
  } catch (e) {
    fieldErrors.value = e.fields || {}
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="mx-auto max-w-[1400px] p-6 lg:p-8">
    <PageHeader title="Tạo video" subtitle="Seedance 1.0 – 2.5 · văn bản, khung hình, tham chiếu đa phương tiện" icon="VideoCamera" />

    <AsyncState :loading="models.loading" :error="models.error" @retry="models.ensure()">
      <div class="grid items-start gap-6 xl:grid-cols-[minmax(400px,560px)_1fr]">
        <form class="card space-y-5 p-6" @submit.prevent="submit">
          <div class="grid gap-4 sm:grid-cols-2">
            <div>
              <label class="field-label">Model</label>
              <el-select v-model="form.model" class="w-full">
                <el-option v-for="m in models.video" :key="m.id" :label="m.name" :value="m.id">
                  <div class="flex justify-between gap-6"><span>{{ m.name }}</span><span class="font-mono text-xs text-slate-400">{{ m.id }}</span></div>
                </el-option>
              </el-select>
            </div>
            <div>
              <label class="field-label">Chế độ</label>
              <el-select v-model="form.mode" class="w-full">
                <el-option v-for="x in modes" :key="x.value" :label="x.label" :value="x.value" />
              </el-select>
            </div>
          </div>

          <div>
            <label class="field-label">Prompt</label>
            <el-input
              v-model="form.prompt"
              type="textarea"
              :rows="5"
              maxlength="4000"
              show-word-limit
              :placeholder="form.mode === 'OMNI' ? 'Ví dụ: Cô gái ở Image 1 mặc bộ đồ ở Image 2, đi dạo trên phố…' : 'Mô tả video…'"
            />
            <p v-if="fieldErrors.prompt" class="mt-1 text-xs text-rose-600">{{ fieldErrors.prompt }}</p>
          </div>

          <div v-if="form.mode === 'FIRST_FRAME' || form.mode === 'FIRST_LAST'" class="grid gap-4 rounded-xl bg-slate-50 p-4 dark:bg-zinc-900/50" :class="form.mode === 'FIRST_LAST' ? 'sm:grid-cols-2' : ''">
            <div>
              <label class="field-label">Ảnh khung đầu</label>
              <RefPicker v-model="form.firstFrame" kind="image" :max="1" />
            </div>
            <div v-if="form.mode === 'FIRST_LAST'">
              <label class="field-label">Ảnh khung cuối</label>
              <RefPicker v-model="form.lastFrame" kind="image" :max="1" />
            </div>
          </div>

          <div v-if="form.mode === 'OMNI'" class="space-y-4 rounded-xl bg-slate-50 p-4 dark:bg-zinc-900/50">
            <div>
              <label class="field-label">Ảnh tham chiếu <span class="font-normal text-slate-400">· tối đa {{ model?.maxRefImages }}</span></label>
              <RefPicker v-model="form.refImages" kind="image" :max="model?.maxRefImages || 9" />
            </div>
            <div>
              <label class="field-label">Video tham chiếu <span class="font-normal text-slate-400">· URL / asset:// / thư viện, tối đa {{ model?.maxRefVideos }}</span></label>
              <RefPicker v-model="form.refVideos" kind="video" :max="model?.maxRefVideos || 3" />
            </div>
            <div>
              <label class="field-label">Audio tham chiếu <span class="font-normal text-slate-400">· tối đa {{ model?.maxRefAudios }}</span></label>
              <RefPicker v-model="form.refAudios" kind="audio" :max="model?.maxRefAudios || 3" />
            </div>
          </div>

          <div class="grid gap-4 sm:grid-cols-3">
            <div>
              <label class="field-label">Độ phân giải</label>
              <el-select v-model="form.resolution" class="w-full">
                <el-option v-for="r in model?.resolutions || []" :key="r" :label="r" :value="r" />
              </el-select>
            </div>
            <div>
              <label class="field-label">Tỉ lệ khung</label>
              <el-select v-model="form.ratio" :disabled="ratioLocked" class="w-full">
                <el-option v-for="r in models.ratios" :key="r" :label="r" :value="r" />
              </el-select>
            </div>
            <div>
              <label class="field-label">Thời lượng (giây)</label>
              <el-input-number v-model="form.duration" :min="model?.autoDuration ? -1 : model?.minDuration" :max="model?.maxDuration" class="!w-full" />
              <p class="hint mt-1">{{ model?.minDuration }}–{{ model?.maxDuration }}s<span v-if="model?.autoDuration">, -1 = tự chọn</span></p>
              <p v-if="fieldErrors.duration" class="mt-1 text-xs text-rose-600">{{ fieldErrors.duration }}</p>
            </div>
          </div>

          <div class="flex flex-wrap gap-x-6 gap-y-2">
            <el-checkbox v-model="form.generateAudio" :disabled="!model?.audio">Tạo âm thanh</el-checkbox>
            <el-checkbox v-model="form.returnLastFrame">Trả ảnh khung cuối</el-checkbox>
            <el-checkbox v-model="form.watermark">Watermark</el-checkbox>
            <el-checkbox v-if="model?.seedAndCamera" v-model="form.cameraFixed">Cố định camera</el-checkbox>
          </div>

          <div v-if="model?.seedAndCamera || model?.is25" class="grid gap-4 sm:grid-cols-2">
            <div v-if="model?.seedAndCamera">
              <label class="field-label">Seed <span class="font-normal text-slate-400">· trống = ngẫu nhiên</span></label>
              <el-input v-model="form.seed" placeholder="-1" />
            </div>
            <div v-if="model?.is25">
              <label class="field-label">Định dạng</label>
              <el-radio-group v-model="form.outputFormat">
                <el-radio-button value="mp4">MP4</el-radio-button>
                <el-radio-button value="mov">MOV</el-radio-button>
              </el-radio-group>
            </div>
          </div>

          <div class="flex items-center gap-3 border-t border-slate-100 pt-5 dark:border-zinc-700/60">
            <el-button type="primary" size="large" native-type="submit" :loading="submitting">
              <el-icon><VideoPlay /></el-icon>&nbsp;Tạo video
            </el-button>
            <p class="hint">Seedance 2.x không nhận mặt người thật – dùng asset Virtual Portrait hoặc ảnh do Seedream 5.0 tạo.</p>
          </div>
        </form>

        <div>
          <JobPanel v-if="jobId" :job-id="jobId" />
          <div v-else class="card">
            <AsyncState empty empty-text="Video sẽ hiện ở đây sau khi tạo xong (thường 1–5 phút)" empty-icon="Film" />
          </div>
        </div>
      </div>
    </AsyncState>
  </div>
</template>
