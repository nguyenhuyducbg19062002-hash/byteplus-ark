import { defineStore } from 'pinia'
import api from '../api'

/** Danh sách model ảnh / video và giới hạn của từng model (GET /api/v1/models, tải 1 lần). */
export const useModelsStore = defineStore('models', {
  state: () => ({ data: null, loading: false, error: null }),
  getters: {
    image: (s) => s.data?.image || [],
    video: (s) => s.data?.video || [],
    text: (s) => s.data?.text || [],
    videoModes: (s) => s.data?.videoModes || [],
    ratios: (s) => s.data?.ratios || []
  },
  actions: {
    async ensure() {
      if (this.data || this.loading) return this.data
      this.loading = true
      this.error = null
      try {
        this.data = await api.get('/models', { silent: true })
      } catch (e) {
        this.error = e
      } finally {
        this.loading = false
      }
      return this.data
    }
  }
})
