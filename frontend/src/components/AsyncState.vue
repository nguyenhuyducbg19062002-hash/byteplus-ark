<script setup>
/**
 * Hiển thị chuẩn cho mọi lời gọi API: loading (skeleton) → lỗi (có nút Thử lại) → trống → nội dung.
 * <AsyncState :loading :error :empty @retry="run"> ...nội dung... </AsyncState>
 * Khi tải lại mà đã có dữ liệu, truyền loading=false (dùng v-loading trên nội dung) để không nháy skeleton.
 */
defineProps({
  loading: Boolean,
  error: { type: [Object, Error, String], default: null },
  empty: Boolean,
  emptyText: { type: String, default: 'Chưa có dữ liệu' },
  emptyIcon: { type: String, default: 'Box' },
  rows: { type: Number, default: 4 }
})
defineEmits(['retry'])
</script>

<template>
  <div v-if="error" class="flex flex-col items-center justify-center gap-3 rounded-xl border border-dashed border-rose-200 bg-rose-50/60 px-6 py-10 text-center dark:border-rose-500/30 dark:bg-rose-500/5">
    <div class="grid h-11 w-11 place-items-center rounded-full bg-rose-100 text-rose-600 dark:bg-rose-500/15 dark:text-rose-300">
      <el-icon :size="20"><WarningFilled /></el-icon>
    </div>
    <div class="max-w-xl text-sm text-rose-700 dark:text-rose-300">{{ error.message || error }}</div>
    <el-button size="small" @click="$emit('retry')"><el-icon><Refresh /></el-icon>&nbsp;Thử lại</el-button>
  </div>
  <div v-else-if="loading" class="space-y-3 p-2">
    <el-skeleton :rows="rows" animated />
  </div>
  <div v-else-if="empty" class="flex flex-col items-center justify-center gap-2 rounded-xl border border-dashed border-slate-200 px-6 py-12 text-center dark:border-zinc-700">
    <div class="grid h-11 w-11 place-items-center rounded-full bg-slate-100 text-slate-400 dark:bg-zinc-800 dark:text-zinc-500">
      <el-icon :size="20"><component :is="emptyIcon" /></el-icon>
    </div>
    <div class="text-sm text-slate-500 dark:text-zinc-400">{{ emptyText }}</div>
    <slot name="empty-action" />
  </div>
  <slot v-else />
</template>
