import { defineStore } from 'pinia'
import api from '../api'

/** Cài đặt từ backend (secret chỉ ở dạng che). */
export const useSettingsStore = defineStore('settings', {
  state: () => ({ settings: null, loading: false, error: null }),
  getters: {
    apiKeySet: (s) => !!s.settings?.apiKeySet,
    akskSet: (s) => !!s.settings?.akskSet,
    autoPush: (s) => !!s.settings?.autoPush,
    assetGroup: (s) => s.settings?.assetGroup || ''
  },
  actions: {
    async load() {
      this.loading = true
      this.error = null
      try {
        this.settings = await api.get('/settings', { silent: true })
      } catch (e) {
        this.error = e
      } finally {
        this.loading = false
      }
      return this.settings
    },
    async update(patch) {
      this.settings = await api.put('/settings', patch)
      return this.settings
    },
    testConnection() {
      return api.post('/settings/connection-test')
    }
  }
})
