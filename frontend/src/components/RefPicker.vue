<script setup>
import { ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fileToDataUrl } from '../api'
import LibraryPicker from './LibraryPicker.vue'

/**
 * Danh sách tham chiếu (ảnh / video / audio). Mỗi phần tử: { value, label, preview }.
 * value: data URI (file tải lên), URL, asset://ID hoặc file:{id} (thư viện).
 */
const props = defineProps({
  modelValue: { type: Array, default: () => [] },
  kind: { type: String, default: 'image' },
  max: { type: Number, default: 10 }
})
const emit = defineEmits(['update:modelValue'])
const fileInput = ref(null)
const showLib = ref(false)
const ACCEPT = { image: 'image/*', audio: '.wav,.mp3,audio/wav,audio/mpeg', video: '' }
const MAX_MB = { image: 30, audio: 15 }
const NAMES = { image: 'Image', video: 'Video', audio: 'Audio' }
const ICONS = { image: 'Picture', video: 'VideoCamera', audio: 'Headset' }
const full = computed(() => props.modelValue.length >= props.max)

function add(items) {
  if (props.modelValue.length + items.length > props.max) ElMessage.warning(`Tối đa ${props.max} mục`)
  emit('update:modelValue', [...props.modelValue, ...items].slice(0, props.max))
}

async function onFiles(e) {
  const items = []
  for (const f of e.target.files) {
    if (f.size > MAX_MB[props.kind] * 1024 * 1024) {
      ElMessage.error(`${f.name} vượt quá ${MAX_MB[props.kind]} MB`)
      continue
    }
    items.push({ value: await fileToDataUrl(f), label: f.name, preview: props.kind === 'image' ? URL.createObjectURL(f) : null })
  }
  e.target.value = ''
  add(items)
}

async function addUrl() {
  const hint = props.kind === 'video' ? 'URL video công khai (http/https) hoặc asset://ID' : 'URL công khai (http/https) hoặc asset://ID'
  const { value } = await ElMessageBox.prompt(hint, 'Thêm tham chiếu', {
    inputPattern: /^(https?:\/\/|asset:\/\/).+/,
    inputErrorMessage: 'Phải bắt đầu bằng http(s):// hoặc asset://',
    confirmButtonText: 'Thêm',
    cancelButtonText: 'Huỷ'
  })
  const v = value.trim()
  add([{ value: v, label: v.length > 40 ? '…' + v.slice(-38) : v, preview: props.kind === 'image' && v.startsWith('http') ? v : null }])
}

function remove(i) {
  const next = [...props.modelValue]
  next.splice(i, 1)
  emit('update:modelValue', next)
}
</script>

<template>
  <div class="w-full space-y-2">
    <ul v-if="modelValue.length" class="space-y-1.5">
      <li
        v-for="(r, i) in modelValue"
        :key="i"
        class="flex items-center gap-3 rounded-lg bg-slate-50 px-2.5 py-1.5 ring-1 ring-slate-200/70 dark:bg-zinc-900/50 dark:ring-zinc-700"
        :title="r.value.startsWith('data:') ? r.label : r.value"
      >
        <img v-if="r.preview" :src="r.preview" class="h-9 w-9 rounded-md object-cover" />
        <span v-else class="grid h-9 w-9 place-items-center rounded-md bg-slate-200 text-slate-500 dark:bg-zinc-700 dark:text-zinc-300">
          <el-icon><component :is="ICONS[kind]" /></el-icon>
        </span>
        <span class="min-w-0 flex-1 truncate text-xs">
          <b class="font-semibold text-indigo-600 dark:text-indigo-300">{{ NAMES[kind] }} {{ i + 1 }}</b> · {{ r.label }}
        </span>
        <button type="button" class="rounded p-1 text-slate-400 hover:bg-slate-200 hover:text-slate-700 dark:hover:bg-zinc-700" @click="remove(i)">
          <el-icon><Close /></el-icon>
        </button>
      </li>
    </ul>
    <div class="flex flex-wrap items-center gap-1.5">
      <el-button v-if="kind !== 'video'" size="small" :disabled="full" @click="fileInput.click()"><el-icon><Upload /></el-icon>&nbsp;Tải file</el-button>
      <el-button size="small" :disabled="full" @click="showLib = true"><el-icon><Files /></el-icon>&nbsp;Thư viện</el-button>
      <el-button size="small" :disabled="full" @click="addUrl"><el-icon><Link /></el-icon>&nbsp;URL / asset://</el-button>
      <span class="hint ml-auto">{{ modelValue.length }}/{{ max }}</span>
    </div>
    <input ref="fileInput" type="file" :accept="ACCEPT[kind]" multiple hidden @change="onFiles" />
    <LibraryPicker v-model="showLib" :kind="kind" :multiple="max > 1" @pick="add" />
  </div>
</template>
