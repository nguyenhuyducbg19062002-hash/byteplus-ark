<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import api, { formatSize, formatTime } from '../api'
import { useReferencesStore, refFromFile } from '../stores/references'
import StatusBadge from './StatusBadge.vue'

/** Lưới ảnh / video trong thư viện, kèm thao tác: đẩy Virtual Portrait, dùng làm tham chiếu, tải, xoá. */
const props = defineProps({
  /** (id dự án) => tên dự án – hiện nhãn dự án sở hữu file */
  projectName: Function,
  files: { type: Array, default: () => [] },
  deletable: Boolean,
  /** Bật ô chọn để tải hàng loạt; danh sách id đã chọn dùng v-model:selected */
  selectable: Boolean,
  selected: { type: Array, default: () => [] }
})
const emit = defineEmits(['changed', 'job', 'update:selected'])
function toggle(f) {
  const set = new Set(props.selected)
  set.has(f.id) ? set.delete(f.id) : set.add(f.id)
  emit('update:selected', [...set])
}
const router = useRouter()
const refs = useReferencesStore()
const imageUrls = computed(() => props.files.filter((f) => f.kind === 'IMAGE').map((f) => f.url))

async function push(f) {
  const job = await api.post('/assets', { fileIds: [f.id] })
  ElMessage.success('Đang đẩy vào Virtual Portrait – theo dõi ở trang Tiến trình')
  emit('job', job)
}

function useInVideo(f) {
  refs.sendToVideo([refFromFile(f)])
  router.push('/video')
}

function useInImage(f) {
  refs.sendToImage([refFromFile(f)])
  router.push('/image')
}

async function remove(f) {
  await ElMessageBox.confirm(`Xoá file ${f.name} khỏi máy? Không khôi phục được.`, 'Xác nhận xoá', { type: 'warning', confirmButtonText: 'Xoá', cancelButtonText: 'Huỷ' })
  await api.delete(`/files/${f.id}`)
  ElMessage.success('Đã xoá')
  emit('changed')
}
</script>

<template>
  <div class="grid grid-cols-[repeat(auto-fill,minmax(220px,1fr))] gap-4">
    <article
      v-for="f in files"
      :key="f.id"
      class="group card overflow-hidden transition hover:-translate-y-0.5 hover:shadow-md"
      :class="selectable && selected.includes(f.id) ? 'ring-2 ring-emerald-500' : ''"
    >
      <div class="relative aspect-[4/3] bg-zinc-900">
        <el-image
          v-if="f.kind === 'IMAGE'"
          :src="f.url"
          fit="contain"
          class="h-full w-full"
          :preview-src-list="imageUrls"
          :initial-index="imageUrls.indexOf(f.url)"
          preview-teleported
          lazy
        >
          <template #error><div class="grid h-full place-items-center text-xs text-zinc-400">File không còn trên máy</div></template>
        </el-image>
        <video v-else-if="f.kind === 'VIDEO'" :src="f.url" class="h-full w-full" controls preload="metadata" />
        <div v-else class="grid h-full place-items-center p-3"><audio :src="f.url" controls class="w-full" /></div>
        <div class="absolute left-2 top-2 flex items-center gap-1">
          <el-checkbox
            v-if="selectable"
            :model-value="selected.includes(f.id)"
            class="!h-6 rounded-md bg-white/90 px-1 shadow-sm"
            @change="toggle(f)"
          />
          <StatusBadge v-if="f.assetId" :status="f.assetStatus || 'Active'" :label="'VP · ' + (f.assetStatus || 'Active')" />
        </div>
        <div class="absolute right-2 top-2 flex gap-1 opacity-0 transition group-hover:opacity-100">
          <a :href="f.url" :download="f.name" class="grid h-8 w-8 place-items-center rounded-lg bg-white/90 text-slate-700 shadow-sm hover:bg-white" title="Tải về">
            <el-icon><Download /></el-icon>
          </a>
          <button v-if="deletable" class="grid h-8 w-8 place-items-center rounded-lg bg-white/90 text-rose-600 shadow-sm hover:bg-white" title="Xoá" @click="remove(f)">
            <el-icon><Delete /></el-icon>
          </button>
        </div>
      </div>
      <div class="space-y-2 p-3">
        <div class="truncate text-sm font-medium" :title="f.prompt || f.name">{{ f.name }}</div>
        <div v-if="projectName && f.projectId" class="truncate text-[11px] text-indigo-600 dark:text-indigo-400" :title="projectName(f.projectId)">
          Dự án: {{ projectName(f.projectId) || '#' + f.projectId }}
        </div>
        <div class="hint flex items-center justify-between">
          <span>{{ formatSize(f.size) || 'mất file' }}</span>
          <span>{{ formatTime(f.createdAt) }}</span>
        </div>
        <div class="flex flex-wrap items-center gap-1">
          <el-tooltip :content="f.assetId ? 'Đã có trên Virtual Portrait' : f.sourceUrlValid ? 'Đẩy lên Virtual Portrait' : 'URL gốc đã hết hạn 24h'" placement="top">
            <span>
              <el-button size="small" type="primary" plain :disabled="!!f.assetId || !f.sourceUrlValid" @click="push(f)">Đẩy VP</el-button>
            </span>
          </el-tooltip>
          <el-button v-if="f.kind !== 'AUDIO'" size="small" @click="useInVideo(f)">→ Video</el-button>
          <el-button v-if="f.kind === 'IMAGE'" size="small" @click="useInImage(f)">→ Ảnh</el-button>
        </div>
      </div>
    </article>
  </div>
</template>
