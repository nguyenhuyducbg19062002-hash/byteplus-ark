<script setup>
import { ref, watch, onBeforeUnmount, nextTick } from 'vue'
import api, { formatTime } from '../api'
import FileGrid from './FileGrid.vue'
import StatusBadge from './StatusBadge.vue'

/** Theo dõi 1 job ở backend: GET /api/v1/jobs/{id} mỗi 1.5s, hiện log + kết quả. */
const props = defineProps({ jobId: String })
const emit = defineEmits(['done'])
const job = ref(null)
const pollError = ref(null)
const logBox = ref(null)
let timer = null

async function poll() {
  if (!props.jobId) return
  try {
    job.value = await api.get(`/jobs/${props.jobId}`, { silent: true })
    pollError.value = null
    await nextTick()
    if (logBox.value) logBox.value.scrollTop = logBox.value.scrollHeight
    if (job.value.status === 'RUNNING') timer = setTimeout(poll, 1500)
    else emit('done', job.value)
  } catch (e) {
    pollError.value = e
    timer = setTimeout(poll, 4000)
  }
}

async function cancel() {
  await api.post(`/jobs/${props.jobId}/cancel`)
}

watch(
  () => props.jobId,
  () => {
    clearTimeout(timer)
    job.value = null
    poll()
  },
  { immediate: true }
)
onBeforeUnmount(() => clearTimeout(timer))
</script>

<template>
  <section v-if="jobId" class="card overflow-hidden">
    <div class="flex flex-wrap items-center gap-3 border-b border-slate-100 px-5 py-3.5 dark:border-zinc-700/60">
      <StatusBadge :status="job?.status || 'running'" />
      <span class="text-sm font-medium">{{ job?.title || 'Đang khởi tạo…' }}</span>
      <span v-if="job?.taskId" class="rounded-md bg-slate-100 px-2 py-0.5 font-mono text-[11px] text-slate-600 dark:bg-zinc-700 dark:text-zinc-300">task {{ job.taskId }}</span>
      <span class="hint">{{ formatTime(job?.createdAt) }}</span>
      <el-button v-if="job?.status === 'RUNNING'" size="small" class="ml-auto" @click="cancel">Dừng theo dõi</el-button>
    </div>
    <div class="space-y-4 p-5">
      <el-alert v-if="pollError" :title="'Mất kết nối khi theo dõi job – đang thử lại… (' + pollError.message + ')'" type="warning" :closable="false" show-icon />
      <el-alert v-if="job?.error" :title="job.error" type="error" :closable="false" show-icon />
      <pre
        ref="logBox"
        class="max-h-56 overflow-auto whitespace-pre-wrap rounded-xl bg-zinc-950 p-4 font-mono text-xs leading-relaxed text-zinc-300 shadow-inner"
      >{{ (job?.logs || []).join('\n') || '…' }}</pre>
      <FileGrid v-if="job?.files?.length" :files="job.files" />
    </div>
  </section>
</template>
