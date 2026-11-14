import { defineStore } from 'pinia'

function read(key) {
  try {
    return localStorage.getItem(key)
  } catch {
    return null // storage blocked
  }
}
function write(key, v) {
  try {
    localStorage.setItem(key, v)
  } catch {
    // storage blocked
  }
}

function readDark() {
  const v = read('ark-dark')
  if (v !== null) return v === '1'
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false
}

/**
 * Giao diện sáng / tối (class .dark trên <html>, dùng chung cho Tailwind và Element Plus)
 * và chủ đề Giáng sinh (class .christmas: nền đỏ + tuyết rơi, khung sáng màu kem – xanh lá). Mặc định bật Giáng sinh.
 */
export const useUiStore = defineStore('ui', {
  state: () => ({ dark: readDark(), christmas: read('ark-christmas') !== '0' }),
  actions: {
    apply() {
      const el = document.documentElement
      el.classList.toggle('dark', this.dark && !this.christmas)
      el.classList.toggle('christmas', this.christmas)
    },
    toggleDark() {
      this.dark = !this.dark
      write('ark-dark', this.dark ? '1' : '0')
      this.apply()
    },
    /** Tắt Giáng sinh → về giao diện tối. */
    toggleChristmas() {
      this.christmas = !this.christmas
      write('ark-christmas', this.christmas ? '1' : '0')
      if (!this.christmas) {
        this.dark = true
        write('ark-dark', '1')
      }
      this.apply()
    }
  }
})
