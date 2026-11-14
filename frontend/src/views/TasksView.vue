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

/** Thông số task: bỏ phần BytePlus không trả về (task lỗi thường không có resolution / ratio / duration). */
function spec(row) {
  const len = row.duration != null ? `${row.duration}s` : row.frames != null ? `${row.frames} frame` : null
  return [row.resolution, row.ratio, len].filter((x) => x != null && x !== '').join(' · ') || '—'
}

/** Giải thích mã lỗi thường gặp (docs/api/error-codes.md). */
const ERROR_HINT = [
  [/^OutputVideoSensitiveContentDetected$/i, 'Video tạo ra bị kiểm duyệt chặn – làm dịu chi tiết nhạy cảm trong prompt rồi tạo lại (mỗi lần tạo cho kết quả khác)'],
  [/^OutputAudioSensitiveContentDetected$/i, 'Âm thanh tạo ra bị kiểm duyệt chặn – sửa lời thoại / mô tả âm thanh, hoặc tắt tạo âm thanh'],
  [/PolicyViolation/i, 'Nghi vi phạm bản quyền – đổi nội dung đầu vào'],
  [/PrivacyInformation/i, 'Ảnh / video đầu vào có mặt người thật – dùng asset Virtual Portrait (asset://)'],
  [/^Input(Text|Image|Video|Audio)SensitiveContentDetected/i, 'Đầu vào bị kiểm duyệt chặn – thay prompt / ảnh tham chiếu'],
  [/SensitiveContentDetected/i, 'Nội dung bị kiểm duyệt chặn – đổi đầu vào rồi thử lại']
]
function errorHint(err) {
  const code = err?.code || ''
  return ERROR_HINT.find(([re]) => re.test(code))?.[1] || err?.message || ''
}

const selected = ref([])
const deleting = ref(false)
/** Xoá bản ghi các task đã chọn (task đang chạy bị bỏ qua), lần lượt từng task. */
async function removeSelected() {
  const rows = selected.value.filter((r) => r.status !== 'running')
  if (!rows.length) return
  await ElMessageBox.confirm(`Xoá / huỷ ${rows.length} task đã chọn? Bản ghi task bị xoá vĩnh viễn trên BytePlus (video đã tải về máy vẫn giữ).`,
    'Xoá hàng loạt', { type: 'warning', confirmButtonText: 'Xoá', cancelButtonText: 'Huỷ' })
  deleting.value = true
  let ok = 0
  const failed = []
  try {
    for (const r of rows) {
      try {
        await api.delete(`/video-tasks/${r.id}`, { silent: true })
        ok++
      } catch (e) {
        failed.push(r.id)
      }
    }
  } finally {
    deleting.value = false
  }
  if (failed.length) ElMessage.warning(`Đã xoá ${ok}, lỗi ${failed.length} task`)
  else ElMessage.success(`Đã xoá ${ok} task`)
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
      <el-button type="danger" plain :disabled="!selected.length" :loading="deleting" @click="removeSelected">
        Xoá {{ selected.length || '' }} task đã chọn
      </el-button>
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
        <el-table v-loading="tasks.loading.value" :data="tasks.data.value.items" row-key="id" @selection-change="(rows) => (selected = rows)">
          <el-table-column type="selection" width="44" :selectable="(row) => row.status !== 'running'" />
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
              <span class="text-sm">{{ spec(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="Lỗi" min-width="240">
            <template #default="{ row }">
              <el-tooltip v-if="row.error" placement="top" :content="`${row.error.code}: ${row.error.message}`">
                <div class="text-xs leading-snug">
                  <div class="truncate font-mono text-rose-600 dark:text-rose-400">{{ row.error.code }}</div>
                  <div class="line-clamp-2 text-slate-500 dark:text-zinc-400">{{ errorHint(row.error) }}</div>
                </div>
              </el-tooltip>
            </template>
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
