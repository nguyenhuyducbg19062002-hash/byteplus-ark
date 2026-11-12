<script setup>
import { ref, reactive, onActivated, onDeactivated } from 'vue'
import api, { formatTime } from '../api'
import { useAsync } from '../composables/useAsync'
import PageHeader from '../components/PageHeader.vue'
import AsyncState from '../components/AsyncState.vue'
import StatusBadge from '../components/StatusBadge.vue'
import JobPanel from '../components/JobPanel.vue'

/** Lịch sử job chạy nền (lưu trong DB). Tự làm mới mỗi 3s khi đang mở trang. */
const query = reactive({ page: 1, size: 20 })
const current = ref(null)
const jobs = useAsync(() => api.get('/jobs', { params: query, silent: true }), { initial: { items: [], total: 0 } })
let timer = null

async function tick() {
  await jobs.run()
  timer = setTimeout(tick, 3000)
}

const TYPES = { image: 'Tạo ảnh', video: 'Tạo video', 'asset-push': 'Đẩy asset', download: 'Tải về' }

onActivated(tick)
onDeactivated(() => clearTimeout(timer))
</script>

<template>
  <div class="mx-auto max-w-[1400px] p-6 lg:p-8">
    <PageHeader title="Tiến trình" subtitle="Lịch sử các việc chạy nền ở backend" icon="Timer" />

    <section class="card p-4">
      <AsyncState
        :loading="jobs.loading.value && !jobs.data.value.items.length"
        :error="jobs.error.value"
        :empty="!jobs.data.value.items.length"
        empty-text="Chưa có tiến trình nào"
        empty-icon="Timer"
        @retry="tick"
      >
        <el-table :data="jobs.data.value.items" highlight-current-row class="cursor-pointer" @row-click="current = $event.id">
          <el-table-column label="Trạng thái" width="140">
            <template #default="{ row }"><StatusBadge :status="row.status" /></template>
          </el-table-column>
          <el-table-column label="Loại" width="110">
            <template #default="{ row }"><span class="text-sm text-slate-500">{{ TYPES[row.type] || row.type }}</span></template>
          </el-table-column>
          <el-table-column prop="title" label="Việc" min-width="260" />
          <el-table-column label="File" width="70" align="center">
            <template #default="{ row }">{{ row.files?.length || '' }}</template>
          </el-table-column>
          <el-table-column label="Bắt đầu" width="150">
            <template #default="{ row }"><span class="text-sm text-slate-500">{{ formatTime(row.createdAt) }}</span></template>
          </el-table-column>
          <el-table-column label="Lỗi" min-width="220" show-overflow-tooltip>
            <template #default="{ row }"><span class="text-xs text-rose-600">{{ row.error }}</span></template>
          </el-table-column>
        </el-table>
        <div class="mt-4 flex items-center justify-between">
          <p class="hint">Bấm vào một dòng để xem log chi tiết.</p>
          <el-pagination v-model:current-page="query.page" :page-size="query.size" :total="jobs.data.value.total" layout="total, prev, pager, next" background @change="jobs.run()" />
        </div>
      </AsyncState>
    </section>

    <div v-if="current" class="mt-6"><JobPanel :job-id="current" /></div>
  </div>
</template>
