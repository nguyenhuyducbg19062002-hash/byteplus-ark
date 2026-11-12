<script setup>
import { reactive, ref, onActivated } from 'vue'
import { ElMessage } from 'element-plus'
import { useSettingsStore } from '../stores/settings'
import PageHeader from '../components/PageHeader.vue'
import AsyncState from '../components/AsyncState.vue'
import StatusBadge from '../components/StatusBadge.vue'

/** Key lưu ở backend (%USERPROFILE%\.byteplus-ark\config.properties); frontend chỉ thấy bản che. */
const store = useSettingsStore()
const form = reactive({ apiKey: '', baseUrl: '', outputDir: '', accessKey: '', secretKey: '', projectName: '' })
const fieldErrors = ref({})
const testResult = ref(null)
const saving = ref(false)
const testing = ref(false)

async function load() {
  const s = await store.load()
  if (s) Object.assign(form, { apiKey: s.apiKey, baseUrl: s.baseUrl, outputDir: s.outputDir, accessKey: s.accessKey, secretKey: s.secretKey, projectName: s.projectName })
}

async function save() {
  fieldErrors.value = {}
  saving.value = true
  try {
    await store.update(form)
    ElMessage.success('Đã lưu cài đặt')
    await load()
  } catch (e) {
    fieldErrors.value = e.fields || {}
  } finally {
    saving.value = false
  }
}

async function test() {
  testing.value = true
  fieldErrors.value = {}
  try {
    // Lưu trước để kiểm tra đúng key vừa nhập (trước đây phải bấm Lưu rồi mới Kiểm tra)
    await store.update(form)
    await load()
    testResult.value = await store.testConnection()
  } catch (e) {
    fieldErrors.value = e.fields || {}
  } finally {
    testing.value = false
  }
}

onActivated(load)
</script>

<template>
  <div class="mx-auto max-w-3xl p-6 lg:p-8">
    <PageHeader title="Cài đặt" subtitle="Khoá truy cập BytePlus và nơi lưu file" icon="Setting" />

    <AsyncState :loading="store.loading && !store.settings" :error="!store.settings ? store.error : null" :rows="8" @retry="load">
      <form class="space-y-6" @submit.prevent="save">
        <section class="card space-y-4 p-6">
          <div>
            <h2 class="font-semibold">Tạo ảnh / video</h2>
            <p class="hint">API key của ModelArk (console.byteplus.com › ModelArk › API keys)</p>
          </div>
          <div>
            <label class="field-label">API key</label>
            <el-input v-model="form.apiKey" type="password" show-password placeholder="Dán API key mới để thay" />
            <p class="hint mt-1">Ô hiển thị bản che (…) – giữ nguyên nếu không đổi.</p>
          </div>
          <div class="grid gap-4 sm:grid-cols-2">
            <div>
              <label class="field-label">Base URL</label>
              <el-input v-model="form.baseUrl" />
              <p v-if="fieldErrors.baseUrl" class="mt-1 text-xs text-rose-600">{{ fieldErrors.baseUrl }}</p>
            </div>
            <div>
              <label class="field-label">Thư mục lưu</label>
              <el-input v-model="form.outputDir" />
            </div>
          </div>
        </section>

        <section class="card space-y-4 p-6">
          <div>
            <h2 class="font-semibold">Virtual Portrait</h2>
            <p class="hint">Access Key / Secret Key (console › IAM › API access key – nên dùng IAM user chỉ có quyền ark:*Asset*)</p>
          </div>
          <div class="grid gap-4 sm:grid-cols-2">
            <div>
              <label class="field-label">Access Key ID</label>
              <el-input v-model="form.accessKey" />
            </div>
            <div>
              <label class="field-label">Secret Access Key</label>
              <el-input v-model="form.secretKey" type="password" show-password />
            </div>
          </div>
          <div>
            <label class="field-label">Project</label>
            <el-input v-model="form.projectName" placeholder="default" />
            <p class="hint mt-1">Asset tách theo project – phải trùng project của API key tạo video (vd. twen-customer).</p>
          </div>
        </section>

        <div class="flex flex-wrap items-center gap-3">
          <el-button type="primary" size="large" native-type="submit" :loading="saving">Lưu cài đặt</el-button>
          <el-button size="large" :loading="testing" @click="test">Kiểm tra kết nối</el-button>
        </div>

        <section v-if="testResult" class="card space-y-3 p-5">
          <div class="flex items-start gap-3">
            <StatusBadge :status="testResult.apiKeyOk ? 'ok' : 'failed'" label="API key" />
            <span class="text-sm">{{ testResult.apiKey }}</span>
          </div>
          <div class="flex items-start gap-3">
            <StatusBadge :status="testResult.akskOk ? 'ok' : 'failed'" label="AK/SK" />
            <span class="text-sm">{{ testResult.aksk }}</span>
          </div>
        </section>

        <p class="hint">File cấu hình: {{ store.settings?.configFile }}</p>
      </form>
    </AsyncState>
  </div>
</template>
