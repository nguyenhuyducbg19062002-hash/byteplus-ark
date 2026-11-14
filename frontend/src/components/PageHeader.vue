<script setup>
defineProps({ title: String, subtitle: String, icon: String })
</script>

<template>
  <header class="page-header mb-6 flex flex-wrap items-end justify-between gap-4">
    <div class="flex items-center gap-3">
      <div v-if="icon" class="grid h-10 w-10 place-items-center rounded-xl bg-white text-indigo-600 shadow-sm ring-1 ring-slate-200/70 dark:bg-zinc-800 dark:text-indigo-300 dark:ring-zinc-700">
        <el-icon :size="20"><component :is="icon" /></el-icon>
      </div>
      <div>
        <h1 class="text-xl font-semibold tracking-tight text-slate-900 dark:text-white">{{ title }}</h1>
        <p v-if="subtitle" class="mt-0.5 text-sm text-slate-500 dark:text-zinc-400">{{ subtitle }}</p>
      </div>
    </div>
    <div class="flex flex-wrap items-center gap-2"><slot /></div>
  </header>
</template>
