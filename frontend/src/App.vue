<script setup>
import { computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { routes } from './router'
import { useSettingsStore } from './stores/settings'
import { useUiStore } from './stores/ui'
import StatusBadge from './components/StatusBadge.vue'

const route = useRoute()
const settings = useSettingsStore()
const ui = useUiStore()

const groups = computed(() => {
  const out = []
  for (const r of routes.filter((x) => x.meta)) {
    let g = out.find((x) => x.name === r.meta.group)
    if (!g) out.push((g = { name: r.meta.group, items: [] }))
    g.items.push(r)
  }
  return out
})

onMounted(() => {
  ui.apply()
  settings.load()
})
</script>

<template>
  <div class="flex h-full">
    <!-- Sidebar -->
    <aside class="flex w-60 shrink-0 flex-col border-r border-slate-200/70 bg-white/80 backdrop-blur dark:border-zinc-800 dark:bg-zinc-950/60">
      <div class="flex items-center gap-3 px-5 py-5">
        <div class="grid h-9 w-9 place-items-center rounded-xl bg-gradient-to-br from-indigo-500 to-fuchsia-500 text-white shadow-md">
          <el-icon :size="18"><MagicStick /></el-icon>
        </div>
        <div>
          <div class="text-sm font-semibold leading-tight">Ark Studio</div>
          <div class="text-[11px] text-slate-500 dark:text-zinc-400">Seedream · Seedance</div>
        </div>
      </div>

      <nav class="flex-1 space-y-5 overflow-y-auto px-3 pb-4">
        <div v-for="g in groups" :key="g.name">
          <div class="px-3 pb-1.5 text-[11px] font-semibold uppercase tracking-wider text-slate-400 dark:text-zinc-500">{{ g.name }}</div>
          <router-link
            v-for="r in g.items"
            :key="r.path"
            :to="r.path"
            class="group flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors"
            :class="
              route.path === r.path
                ? 'bg-indigo-50 text-indigo-700 shadow-sm ring-1 ring-indigo-100 dark:bg-indigo-500/15 dark:text-indigo-300 dark:ring-indigo-500/20'
                : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900 dark:text-zinc-400 dark:hover:bg-zinc-800 dark:hover:text-zinc-100'
            "
          >
            <el-icon :size="16"><component :is="r.meta.icon" /></el-icon>
            {{ r.meta.title }}
          </router-link>
        </div>
      </nav>

      <div class="space-y-2 border-t border-slate-200/70 p-4 dark:border-zinc-800">
        <div v-if="settings.error" class="rounded-lg bg-rose-50 px-3 py-2 text-xs text-rose-700 dark:bg-rose-500/10 dark:text-rose-300">
          Mất kết nối backend
          <button class="ml-1 underline" @click="settings.load()">thử lại</button>
        </div>
        <div v-else-if="settings.settings" class="flex flex-wrap gap-1.5">
          <StatusBadge :status="settings.apiKeySet ? 'ok' : 'missing'" :label="settings.apiKeySet ? 'API key' : 'Chưa có API key'" />
          <StatusBadge :status="settings.akskSet ? 'ok' : 'off'" :label="settings.akskSet ? 'AK/SK' : 'Chưa có AK/SK'" />
          <StatusBadge v-if="settings.autoPush" status="info" label="Tự đẩy VP" />
        </div>
        <button
          class="flex w-full items-center justify-between rounded-lg px-3 py-2 text-xs text-slate-500 transition hover:bg-slate-100 dark:text-zinc-400 dark:hover:bg-zinc-800"
          @click="ui.toggleDark()"
        >
          <span>{{ ui.dark ? 'Giao diện tối' : 'Giao diện sáng' }}</span>
          <el-icon><component :is="ui.dark ? 'Moon' : 'Sunny'" /></el-icon>
        </button>
      </div>
    </aside>

    <!-- Content -->
    <main class="min-w-0 flex-1 overflow-y-auto">
      <router-view v-slot="{ Component }">
        <keep-alive>
          <component :is="Component" />
        </keep-alive>
      </router-view>
    </main>
  </div>
</template>
