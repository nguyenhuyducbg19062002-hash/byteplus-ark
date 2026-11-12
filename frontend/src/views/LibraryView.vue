<script setup>
import { reactive, ref, onActivated } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import api from '../api'
import { useAsync } from '../composables/useAsync'
import PageHeader from '../components/PageHeader.vue'
import AsyncState from '../components/AsyncState.vue'
import FileGrid from '../components/FileGrid.vue'

/** Ảnh / video đã lưu (bảng generated_file + thư mục output). */
const router = useRouter()
const query = reactive({ kind: '', page: 1, size: 24 })
const files = useAsync(
  () => api.get('/files', { params: { kind: query.kind || undefined, page: query.page, size: query.size }, silent: true }),
  { initial: { items: [], total: 0 } }
)

function changeKind() {
  query.page = 1
  files.run()
}

/** Tải hàng loạt (.zip): file đã chọn, hoặc cả thư viện theo bộ lọc Ảnh / Video hiện tại. */
const selected = ref([])
function download(onlySelected) {
  const params = new URLSearchParams()
  if (onlySelected) selected.value.forEach((id) => params.append('ids', id))
  else if (query.kind) params.set('kind', query.kind)
  const a = document.createElement('a')
  a.href = `/api/v1/files/download?${params}`
  a.click()
  ElMessage.success('Đang nén và tải về – xem thanh tải của trình duyệt')
}
function selectPage() {
  const ids = files.data.value.items.map((f) => f.id)
  const all = ids.every((id) => selected.value.includes(id))
  selected.value = all ? selected.value.filter((id) => !ids.includes(id)) : [...new Set([...selected.value, ...ids])]
}

onActivated(() => files.run())
</script>

<template>
  <div class="mx-auto max-w-[1400px] p-6 lg:p-8">
    <PageHeader title="Thư viện" subtitle="Ảnh và video đã tạo, lưu trên máy" icon="Files">
      <el-radio-group v-model="query.kind" @change="changeKind">
        <el-radio-button value="">Tất cả</el-radio-button>
        <el-radio-button value="IMAGE">Ảnh</el-radio-button>
        <el-radio-button value="VIDEO">Video</el-radio-button>
      </el-radio-group>
      <el-button :loading="files.loading.value" @click="files.run()"><el-icon><Refresh /></el-icon></el-button>
    </PageHeader>

    <section class="card mb-4 flex flex-wrap items-center gap-2 p-3">
      <el-button size="small" @click="selectPage">Chọn / bỏ chọn cả trang</el-button>
      <el-button size="small" :disabled="!selected.length" @click="selected = []">Bỏ chọn</el-button>
      <el-button size="small" type="success" :disabled="!selected.length" @click="download(true)">
        <el-icon><Download /></el-icon>&nbsp;Tải {{ selected.length }} file đã chọn (.zip)
      </el-button>
      <el-button size="small" type="success" plain :disabled="!files.data.value.total" @click="download(false)">
        <el-icon><Download /></el-icon>&nbsp;Tải tất cả {{ query.kind === 'IMAGE' ? 'ảnh' : query.kind === 'VIDEO' ? 'video' : 'file' }} ({{ files.data.value.total }}) .zip
      </el-button>
      <span class="hint">Chọn được qua nhiều trang.</span>
    </section>

    <AsyncState
      :loading="files.loading.value && !files.data.value.items.length"
      :error="files.error.value"
      :empty="!files.data.value.items.length"
      empty-text="Chưa có file nào – tạo ảnh hoặc video để bắt đầu"
      empty-icon="Picture"
      :rows="6"
      @retry="files.run()"
    >
      <div v-loading="files.loading.value">
        <FileGrid v-model:selected="selected" :files="files.data.value.items" selectable deletable @changed="files.run()" @job="router.push('/jobs')" />
      </div>
      <div class="mt-6 flex items-center justify-between">
        <p class="hint">"Đẩy VP" chỉ dùng được khi URL gốc còn hạn (24h sau khi tạo).</p>
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="files.data.value.total"
          :page-sizes="[24, 48, 96]"
          layout="total, sizes, prev, pager, next"
          background
          @change="files.run()"
        />
      </div>
    </AsyncState>
  </div>
</template>
