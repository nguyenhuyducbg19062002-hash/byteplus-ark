<script setup>
import { ref, reactive, onActivated } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import api, { formatTime } from '../api'
import { useAsync } from '../composables/useAsync'
import { useSettingsStore } from '../stores/settings'
import { useReferencesStore, refFromAsset } from '../stores/references'
import PageHeader from '../components/PageHeader.vue'
import AsyncState from '../components/AsyncState.vue'
import StatusBadge from '../components/StatusBadge.vue'
import JobPanel from '../components/JobPanel.vue'
import LibraryPicker from '../components/LibraryPicker.vue'

/** Virtual Portrait: thư viện asset riêng (AK/SK). Đẩy file vào, xem lại, dùng asset:// làm tham chiếu. */
const router = useRouter()
const settings = useSettingsStore()
const references = useReferencesStore()

const query = reactive({ name: '', status: '', page: 1, size: 20 })
const selection = ref([])
const jobId = ref(null)
const showLib = ref(false)

const groups = useAsync(() => api.get('/asset-groups', { silent: true }), { initial: [] })
const assets = useAsync(
  () =>
    api.get('/assets', {
      params: { group: settings.assetGroup, name: query.name || undefined, status: query.status || undefined, page: query.page, size: query.size },
      silent: true
    }),
  { initial: { items: [], total: 0 } }
)

async function loadAll() {
  if (!settings.settings) await settings.load()
  if (!settings.akskSet) return
  await groups.run()
  if (!settings.assetGroup && groups.data.value?.length) await settings.update({ assetGroup: groups.data.value[0].Id })
  if (settings.assetGroup) await assets.run()
}

async function selectGroup(id) {
  await settings.update({ assetGroup: id })
  query.page = 1
  assets.run()
}

async function createGroup() {
  const { value } = await ElMessageBox.prompt('Tên group (tối đa 64 ký tự), ví dụ tên nhân vật', 'Tạo asset group', {
    inputPattern: /^.{1,64}$/,
    inputErrorMessage: '1–64 ký tự',
    confirmButtonText: 'Tạo',
    cancelButtonText: 'Huỷ'
  })
  try {
    const r = await api.post('/asset-groups', { name: value })
    ElMessage.success('Đã tạo group ' + r.id)
    await settings.load()
    await loadAll()
  } catch {
    ElMessage.info({ message: 'Lần đầu tạo group phải ký thư uỷ quyền trong console: Model Playground › My assets › Virtual Portrait', duration: 8000 })
  }
}

function search() {
  query.page = 1
  assets.run()
}

async function pushUrl() {
  const { value } = await ElMessageBox.prompt('URL công khai (http/https) của ảnh / video / audio', 'Đẩy từ URL', {
    inputPattern: /^https?:\/\/.+/,
    inputErrorMessage: 'Phải là URL http(s)',
    confirmButtonText: 'Đẩy',
    cancelButtonText: 'Huỷ'
  })
  jobId.value = (await api.post('/assets/from-url', { url: value.trim() })).id
}

async function pushFromLibrary(_refs, files) {
  jobId.value = (await api.post('/assets', { fileIds: files.map((f) => f.id) })).id
}

function activeSelection() {
  const ok = selection.value.filter((a) => a.Status === 'Active')
  if (ok.length < selection.value.length) ElMessage.warning('Chỉ dùng được asset ở trạng thái Active')
  return ok
}

function useInVideo() {
  const list = activeSelection()
  if (!list.length) return
  references.sendToVideo(list.map(refFromAsset))
  router.push('/video')
}

function useInImage() {
  const list = activeSelection().filter((a) => a.AssetType === 'Image' && a.URL)
  if (!list.length) return ElMessage.warning('Chọn asset ảnh đang Active')
  // Seedream không nhận asset:// → dùng URL của asset (hạn 12h)
  references.sendToImage(list.map((a) => ({ kind: 'image', value: a.URL, label: a.Name || a.Id, preview: a.URL })))
  router.push('/image')
}

async function copyUris() {
  await navigator.clipboard.writeText(selection.value.map((a) => 'asset://' + a.Id).join('\n'))
  ElMessage.success(`Đã copy ${selection.value.length} asset://`)
}

async function download() {
  for (const a of selection.value) jobId.value = (await api.post(`/assets/${a.Id}/downloads`)).id
  ElMessage.success('Đang tải về thư viện (output/assets)')
}

async function remove() {
  await ElMessageBox.confirm(`Xoá vĩnh viễn ${selection.value.length} asset? Không khôi phục được.`, 'Xác nhận xoá', {
    type: 'warning',
    confirmButtonText: 'Xoá',
    cancelButtonText: 'Huỷ'
  })
  for (const a of selection.value) await api.delete(`/assets/${a.Id}`)
  ElMessage.success('Đã xoá')
  assets.run()
}

onActivated(loadAll)
</script>

<template>
  <div class="mx-auto max-w-[1400px] p-6 lg:p-8">
    <PageHeader title="Virtual Portrait" subtitle="Thư viện asset riêng – dùng lại làm tham chiếu asset:// cho Seedance 2.x" icon="User">
      <el-switch
        :model-value="settings.autoPush"
        active-text="Tự đẩy mọi ảnh/video vừa tạo"
        :disabled="!settings.settings"
        @change="(v) => settings.update({ autoPush: v })"
      />
    </PageHeader>

    <!-- Chưa có AK/SK -->
    <div v-if="settings.settings && !settings.akskSet" class="card p-2">
      <AsyncState empty empty-icon="Key" empty-text="Chưa có Access Key / Secret Key – API asset dùng AK/SK, không dùng API key">
        <template #empty-action>
          <el-button type="primary" @click="router.push('/settings')">Mở Cài đặt</el-button>
        </template>
      </AsyncState>
    </div>

    <template v-else>
      <!-- Group -->
      <section class="card mb-6 p-5">
        <AsyncState :loading="groups.loading.value && !groups.data.value.length" :error="groups.error.value" :rows="1" @retry="loadAll">
          <div class="flex flex-wrap items-center gap-3">
            <span class="text-sm font-medium">Asset group</span>
            <el-select :model-value="settings.assetGroup" placeholder="Chọn group" class="!w-80" @change="selectGroup">
              <el-option v-for="g in groups.data.value" :key="g.Id" :label="g.Name" :value="g.Id">
                <div class="flex justify-between gap-6"><span>{{ g.Name }}</span><span class="font-mono text-xs text-slate-400">{{ g.Id }}</span></div>
              </el-option>
            </el-select>
            <el-button @click="createGroup"><el-icon><Plus /></el-icon>&nbsp;Tạo group</el-button>
            <span v-if="!groups.data.value.length" class="hint">Chưa có group nào – tạo group đầu tiên (cần ký thư uỷ quyền trên console trước).</span>
          </div>
        </AsyncState>
      </section>

      <!-- Assets -->
      <section class="card overflow-hidden">
        <div class="flex flex-wrap items-center gap-2 border-b border-slate-100 p-4 dark:border-zinc-700/60">
          <el-input v-model="query.name" placeholder="Tìm theo tên" clearable class="!w-56" @keyup.enter="search" @clear="search">
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-select v-model="query.status" placeholder="Mọi trạng thái" clearable class="!w-40" @change="search">
            <el-option label="Active" value="Active" />
            <el-option label="Đang xử lý" value="Processing" />
            <el-option label="Lỗi" value="Failed" />
          </el-select>
          <el-button :loading="assets.loading.value" @click="assets.run()"><el-icon><Refresh /></el-icon></el-button>
          <div class="ml-auto flex flex-wrap gap-2">
            <el-button type="primary" plain :disabled="!settings.assetGroup" @click="showLib = true"><el-icon><Upload /></el-icon>&nbsp;Đẩy từ thư viện</el-button>
            <el-button :disabled="!settings.assetGroup" @click="pushUrl"><el-icon><Link /></el-icon>&nbsp;Đẩy từ URL</el-button>
          </div>
        </div>

        <!-- Thanh thao tác khi có chọn -->
        <transition name="el-fade-in">
          <div v-if="selection.length" class="flex flex-wrap items-center gap-2 bg-indigo-50/70 px-4 py-2.5 dark:bg-indigo-500/10">
            <span class="mr-2 text-sm font-medium text-indigo-700 dark:text-indigo-300">Đã chọn {{ selection.length }}</span>
            <el-button size="small" type="primary" @click="useInVideo">Dùng làm tham chiếu video</el-button>
            <el-button size="small" @click="useInImage">Dùng làm ảnh tham chiếu</el-button>
            <el-button size="small" @click="copyUris">Copy asset://</el-button>
            <el-button size="small" @click="download">Tải về thư viện</el-button>
            <el-button size="small" type="danger" plain @click="remove">Xoá</el-button>
          </div>
        </transition>

        <div class="p-4">
          <AsyncState
            :loading="assets.loading.value && !assets.data.value.items.length"
            :error="assets.error.value"
            :empty="!settings.assetGroup || !assets.data.value.items.length"
            :empty-text="settings.assetGroup ? 'Group chưa có asset nào – đẩy ảnh/video từ thư viện để bắt đầu' : 'Chọn hoặc tạo asset group'"
            empty-icon="UserFilled"
            @retry="assets.run()"
          >
            <el-table v-loading="assets.loading.value" :data="assets.data.value.items" row-key="Id" @selection-change="selection = $event">
              <el-table-column type="selection" width="44" />
              <el-table-column label="Asset" min-width="280">
                <template #default="{ row }">
                  <div class="flex items-center gap-3">
                    <el-image
                      v-if="row.AssetType === 'Image' && row.URL"
                      :src="row.URL"
                      :preview-src-list="[row.URL]"
                      preview-teleported
                      fit="cover"
                      class="h-11 w-11 shrink-0 rounded-lg ring-1 ring-slate-200 dark:ring-zinc-700"
                    />
                    <span v-else class="grid h-11 w-11 shrink-0 place-items-center rounded-lg bg-slate-100 text-slate-500 dark:bg-zinc-700 dark:text-zinc-300">
                      <el-icon :size="18"><component :is="row.AssetType === 'Video' ? 'VideoCamera' : 'Headset'" /></el-icon>
                    </span>
                    <div class="min-w-0">
                      <div class="truncate text-sm font-medium">{{ row.Name || '—' }}</div>
                      <div class="truncate font-mono text-[11px] text-slate-400">{{ row.Id }}</div>
                    </div>
                  </div>
                </template>
              </el-table-column>
              <el-table-column prop="AssetType" label="Loại" width="90" />
              <el-table-column label="Trạng thái" width="140">
                <template #default="{ row }"><StatusBadge :status="row.Status" /></template>
              </el-table-column>
              <el-table-column label="Tạo lúc" width="150">
                <template #default="{ row }"><span class="text-sm text-slate-500">{{ formatTime(row.CreateTime) }}</span></template>
              </el-table-column>
              <el-table-column label="Dùng lần cuối" width="150">
                <template #default="{ row }"><span class="text-sm text-slate-500">{{ formatTime(row.LastInferenceTime) || '—' }}</span></template>
              </el-table-column>
              <el-table-column label="Lỗi" min-width="180" show-overflow-tooltip>
                <template #default="{ row }"><span class="text-xs text-rose-600">{{ row.Error ? `${row.Error.Code}: ${row.Error.Message}` : '' }}</span></template>
              </el-table-column>
            </el-table>
            <div class="mt-4 flex justify-end">
              <el-pagination
                v-model:current-page="query.page"
                v-model:page-size="query.size"
                :total="assets.data.value.total"
                :page-sizes="[20, 50, 100]"
                layout="total, sizes, prev, pager, next"
                background
                @change="assets.run()"
              />
            </div>
          </AsyncState>
        </div>
      </section>

      <p class="hint mt-4">
        Quy trình: tạo ảnh/video → lưu về máy → đẩy URL gốc (còn hạn 24h) → chờ Active → dùng asset://ID làm tham chiếu.
        Trong prompt gọi Image 1, Image 2, Video 1… (không ghi asset ID). Asset phải là nhân vật ảo, không giống người thật.
      </p>
    </template>

    <div v-if="jobId" class="mt-6"><JobPanel :job-id="jobId" @done="assets.run()" /></div>
    <LibraryPicker v-model="showLib" only-pushable @pick="pushFromLibrary" />
  </div>
</template>
