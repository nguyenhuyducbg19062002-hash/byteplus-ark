<script setup>
import { ref, watch } from 'vue'
import api from '../api'
import { useAsync } from '../composables/useAsync'
import { refFromFile } from '../stores/references'
import AsyncState from './AsyncState.vue'

/** Hộp thoại chọn file trong thư viện để làm tham chiếu (hoặc để đẩy lên Virtual Portrait). */
// projectId: chỉ hiện ảnh của dự án đó (công tắc "Xem tất cả dự án"); ảnh dự án khác được backend chép thành ảnh riêng khi gán
const props = defineProps({ modelValue: Boolean, kind: String, multiple: { type: Boolean, default: true }, onlyPushable: Boolean, projectId: Number })
const emit = defineEmits(['update:modelValue', 'pick'])
const selected = ref(new Set())
const showAll = ref(false)
const files = useAsync(() =>
  api.get('/files', {
    params: { kind: props.kind ? props.kind.toUpperCase() : undefined, size: 120,
      project: props.projectId && !showAll.value ? String(props.projectId) : undefined },
    silent: true
  }).then((r) =>
    props.onlyPushable ? r.items.filter((f) => !f.assetId && f.sourceUrlValid) : r.items
  )
)

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    selected.value = new Set()
    showAll.value = false
    files.run()
  }
)
watch(showAll, () => files.run())
const foreign = (f) => props.projectId && f.projectId !== props.projectId

function toggle(f) {
  const s = new Set(props.multiple ? selected.value : [])
  if (s.has(f.id)) s.delete(f.id)
  else s.add(f.id)
  selected.value = s
}

function confirm() {
  const picked = (files.data.value || []).filter((f) => selected.value.has(f.id))
  emit('pick', picked.map(refFromFile), picked)
  emit('update:modelValue', false)
}
</script>

<template>
  <el-dialog :model-value="modelValue" title="Chọn từ thư viện" width="920px" @update:model-value="emit('update:modelValue', $event)">
    <AsyncState
      :loading="files.loading.value"
      :error="files.error.value"
      :empty="!files.data.value?.length"
      :empty-text="onlyPushable ? 'Không có file nào còn URL gốc (24h) để đẩy' : projectId && !showAll ? 'Dự án này chưa có ảnh nào trong thư viện – bật “Xem tất cả dự án” để lấy ảnh khác' : 'Thư viện chưa có file nào'"
      empty-icon="Files"
      @retry="files.run()"
    >
      <div class="grid max-h-[60vh] grid-cols-[repeat(auto-fill,minmax(150px,1fr))] gap-3 overflow-auto p-1">
        <button
          v-for="f in files.data.value"
          :key="f.id"
          type="button"
          class="relative overflow-hidden rounded-xl bg-slate-100 text-left ring-2 transition hover:shadow-md dark:bg-zinc-800"
          :class="selected.has(f.id) ? 'ring-indigo-500' : 'ring-transparent'"
          @click="toggle(f)"
        >
          <img v-if="f.kind === 'IMAGE'" :src="f.url" loading="lazy" class="aspect-square w-full object-cover" />
          <video v-else-if="f.kind === 'VIDEO'" :src="f.url" preload="metadata" muted class="aspect-square w-full object-cover" />
          <div v-else class="grid aspect-square place-items-center"><el-icon :size="32"><Headset /></el-icon></div>
          <div class="truncate px-2 py-1.5 text-[11px]">{{ f.name }}</div>
          <span v-if="foreign(f)" class="absolute left-2 top-2 rounded bg-amber-500/90 px-1.5 py-0.5 text-[10px] font-medium text-white">
            {{ f.projectId ? 'Dự án khác' : 'Ngoài dự án' }}
          </span>
          <span v-if="selected.has(f.id)" class="absolute right-2 top-2 grid h-6 w-6 place-items-center rounded-full bg-indigo-600 text-white shadow">
            <el-icon :size="14"><Check /></el-icon>
          </span>
        </button>
      </div>
    </AsyncState>
    <template #footer>
      <el-checkbox v-if="projectId" v-model="showAll" class="!mr-4 float-left">Xem tất cả dự án</el-checkbox>
      <span v-if="projectId && showAll" class="hint float-left mt-1.5">Ảnh ngoài dự án sẽ được chép thành ảnh riêng của dự án này</span>
      <span class="hint mr-3">Đã chọn {{ selected.size }}</span>
      <el-button @click="emit('update:modelValue', false)">Huỷ</el-button>
      <el-button type="primary" :disabled="!selected.size" @click="confirm">Dùng {{ selected.size || '' }} file</el-button>
    </template>
  </el-dialog>
</template>
