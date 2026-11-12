<script setup>
import { ref, reactive, computed, watch, onActivated } from 'vue'
import api from '../api'
import { useModelsStore } from '../stores/models'
import { useReferencesStore } from '../stores/references'
import PageHeader from '../components/PageHeader.vue'
import AsyncState from '../components/AsyncState.vue'
import RefPicker from '../components/RefPicker.vue'
import JobPanel from '../components/JobPanel.vue'

/** Seedream: POST /api/v1/images/generations → job → ảnh lưu vào thư viện. */
const models = useModelsStore()
const references = useReferencesStore()

const form = reactive({ model: '', prompt: '', refs: [], size: '', sequential: false, maxImages: 4, outputFormat: 'jpeg', watermark: false })
const fieldErrors = ref({})
const jobId = ref(null)
const submitting = ref(false)
const model = computed(() => models.image.find((m) => m.id === form.model))

async function init() {
  await models.ensure()
  if (!form.model && models.image.length) form.model = models.image[0].id
}
init()

watch(model, (m) => {
  if (!m) return
  form.size = m.defaultSize
  if (!m.sequential) form.sequential = false
})

onActivated(() => {
  const incoming = references.takeImage()
  if (incoming.length) form.refs = [...form.refs, ...incoming].slice(0, model.value?.maxRefs || 14)
})

async function submit() {
  fieldErrors.value = {}
  submitting.value = true
  try {
    const job = await api.post('/images/generations', {
      model: form.model,
      prompt: form.prompt,
      refs: form.refs.map((r) => r.value),
      size: form.size,
      sequential: form.sequential,
      maxImages: form.maxImages,
      outputFormat: model.value?.outputFormat ? form.outputFormat : null,
      watermark: form.watermark
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
    <PageHeader title="Tạo ảnh" subtitle="Seedream 4.0 – 5.0 · text-to-image và image-to-image" icon="Picture" />

    <AsyncState :loading="models.loading" :error="models.error" @retry="models.ensure()">
      <div class="grid items-start gap-6 xl:grid-cols-[minmax(380px,520px)_1fr]">
        <form class="card space-y-5 p-6" @submit.prevent="submit">
          <div>
            <label class="field-label">Model</label>
            <el-select v-model="form.model" class="w-full">
              <el-option v-for="m in models.image" :key="m.id" :label="m.name" :value="m.id">
                <div class="flex justify-between gap-6"><span>{{ m.name }}</span><span class="font-mono text-xs text-slate-400">{{ m.id }}</span></div>
              </el-option>
            </el-select>
          </div>

          <div>
            <label class="field-label">Prompt</label>
            <el-input v-model="form.prompt" type="textarea" :rows="6" maxlength="4000" show-word-limit placeholder="Mô tả ảnh muốn tạo…" />
            <p v-if="fieldErrors.prompt" class="mt-1 text-xs text-rose-600">{{ fieldErrors.prompt }}</p>
          </div>

          <div>
            <label class="field-label">Ảnh tham chiếu <span class="font-normal text-slate-400">· để trống = text-to-image</span></label>
            <RefPicker v-model="form.refs" kind="image" :max="model?.maxRefs || 10" />
          </div>

          <div class="grid gap-4 sm:grid-cols-2">
            <div>
              <label class="field-label">Kích thước</label>
              <el-select v-model="form.size" filterable allow-create default-first-option class="w-full">
                <el-option v-for="s in model?.sizes || []" :key="s" :label="s" :value="s" />
              </el-select>
              <p class="hint mt-1">Mức 1K/2K/4K hoặc gõ WxH</p>
            </div>
            <div v-if="model?.outputFormat">
              <label class="field-label">Định dạng</label>
              <el-radio-group v-model="form.outputFormat">
                <el-radio-button value="jpeg">JPEG</el-radio-button>
                <el-radio-button value="png">PNG</el-radio-button>
              </el-radio-group>
            </div>
          </div>

          <div v-if="model?.sequential" class="flex flex-wrap items-center gap-3 rounded-xl bg-slate-50 p-3 dark:bg-zinc-900/50">
            <el-switch v-model="form.sequential" active-text="Chuỗi ảnh liên quan" />
            <template v-if="form.sequential">
              <el-input-number v-model="form.maxImages" :min="1" :max="15" size="small" />
              <span class="hint">ảnh tối đa (tham chiếu + kết quả ≤ 15)</span>
            </template>
          </div>

          <el-checkbox v-model="form.watermark">Gắn watermark "AI generated"</el-checkbox>

          <div class="flex items-center gap-3 border-t border-slate-100 pt-5 dark:border-zinc-700/60">
            <el-button type="primary" size="large" native-type="submit" :loading="submitting">
              <el-icon><MagicStick /></el-icon>&nbsp;Tạo ảnh
            </el-button>
            <p class="hint">Ảnh Seedream 5.0 tạo từ chữ được tin cậy 30 ngày khi làm đầu vào Seedance 2.x.</p>
          </div>
        </form>

        <div>
          <JobPanel v-if="jobId" :job-id="jobId" />
          <div v-else class="card">
            <AsyncState empty empty-text="Kết quả sẽ hiện ở đây sau khi bấm Tạo ảnh" empty-icon="PictureRounded" />
          </div>
        </div>
      </div>
    </AsyncState>
  </div>
</template>
