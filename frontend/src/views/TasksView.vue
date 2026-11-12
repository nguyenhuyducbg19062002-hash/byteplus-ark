<script setup>
import { ref, reactive, onActivated } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api, { formatTime } from '../api'
import { useAsync } from '../composables/useAsync'
import PageHeader from '../components/PageHeader.vue'
import AsyncState from '../components/AsyncState.vue'
import StatusBadge from '../components/StatusBadge.vue'
import JobPanel from '../components/JobPanel.vue'

/** Task Seedance trên BytePlus (GET /api/v1/video-tasks). */
const query = reactive({ status: '', page: 1, size: 20 })
const jobId = ref(null)
const tasks = useAsync(
  () => api.get('/video-tasks', { params: { page: query.page, size: query.size, status: query.status || undefined }, silent: true }),
  { initial: { items: [], total: 0 } }
)

function filter() {
  query.page = 1
  tasks.run()
}

async function download(row) {
  jobId.value = (await api.post(`/video-tasks/${row.id}/downloads`)).id
}

async function remove(row) {
  const action = row.status === 'queued' ? 'Huỷ task đang chờ' : 'Xoá vĩnh viễn bản ghi task'
  await ElMessageBox.confirm(`${action}?\n${row.id}`, 'Xác nhận', { type: 'warning', confirmButtonText: 'Đồng ý', cancelButtonText: 'Huỷ' })
  await api.delete(`/video-tasks/${row.id}`)
  ElMessage.success('Đã thực hiện')
  tasks.run()
}

async function copy(text) {
  await navigator.clipboard.writeText(text)
  ElMessage.success('Đã copy')
}

onActivated(() => tasks.run())
</script>

<template>
  <div class="mx-auto max-w-[1400px] p-6 lg:p-8">
    <PageHeader title="Task video" subtitle="Các task Seedance trên BytePlus · URL video sống 24h, task ID lưu 7 ngày" icon="List">
      <el-select v-model="query.status" placeholder="Mọi trạng thái" clearable class="!w-44" @change="filter">
        <el-option v-for="s in ['queued', 'running', 'succeeded', 'failed', 'expired', 'cancelled']" :key="s" :value="s">
          <StatusBadge :status="s" />
        </el-option>
      </el-select>
      <el-button :loading="tasks.loading.value" @click="tasks.run()"><el-icon><Refresh /></el-icon></el-button>
    </PageHeader>

    <section class="card p-4">
      <AsyncState
        :loading="tasks.loading.value && !tasks.data.value.items.length"
        :error="tasks.error.value"
        :empty="!tasks.data.value.items.length"
        empty-text="Chưa có task video nào"
        empty-icon="VideoCamera"
        @retry="tasks.run()"
      >
        <el-table v-loading="tasks.loading.value" :data="tasks.data.value.items">
          <el-table-column label="Task" min-width="260">
            <template #default="{ row }">
              <div class="font-mono text-xs">{{ row.id }}</div>
              <div class="text-xs text-slate-400">{{ row.model }}</div>
            </template>
          </el-table-column>
          <el-table-column label="Trạng thái" width="140">
            <template #default="{ row }"><StatusBadge :status="row.status" /></template>
          </el-table-column>
          <el-table-column label="Tạo lúc" width="150">
            <template #default="{ row }"><span class="text-sm text-slate-500">{{ formatTime(row.created_at) }}</span></template>
          </el-table-column>
          <el-table-column label="Thông số" width="180">
            <template #default="{ row }">
              <span class="text-sm">{{ row.resolution }} · {{ row.ratio }} · {{ row.duration ?? row.frames }}{{ row.duration != null ? 's' : 'f' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="Lỗi" min-width="180" show-overflow-tooltip>
            <template #default="{ row }"><span class="text-xs text-rose-600">{{ row.error ? `${row.error.code}: ${row.error.message}` : '' }}</span></template>
          </el-table-column>
          <el-table-column label="" width="220" fixed="right" align="right">
            <template #default="{ row }">
              <el-button size="small" type="primary" plain :disabled="row.status !== 'succeeded'" @click="download(row)">Tải về</el-button>
              <el-button size="small" link @click="copy(row.id)">Copy ID</el-button>
              <el-button size="small" link type="danger" :disabled="row.status === 'running'" @click="remove(row)">Huỷ/Xoá</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="mt-4 flex justify-end">
          <el-pagination
            v-model:current-page="query.page"
            v-model:page-size="query.size"
            :total="tasks.data.value.total"
            :page-sizes="[20, 50, 100]"
            layout="total, sizes, prev, pager, next"
            background
            @change="tasks.run()"
          />
        </div>
      </AsyncState>
    </section>

    <div v-if="jobId" class="mt-6"><JobPanel :job-id="jobId" /></div>
  </div>
</template>
