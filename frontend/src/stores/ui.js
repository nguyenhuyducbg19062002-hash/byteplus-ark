import { defineStore } from 'pinia'

function readDark() {
  try {
    const v = localStorage.getItem('ark-dark')
    if (v !== null) return v === '1'
  } catch {
    // storage blocked
  }
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false
}

/** Giao diện sáng / tối (class .dark trên <html>, dùng chung cho Tailwind và Element Plus). */
export const useUiStore = defineStore('ui', {
  state: () => ({ dark: readDark() }),
  actions: {
    apply() {
      document.documentElement.classList.toggle('dark', this.dark)
    },
    toggleDark() {
      this.dark = !this.dark
      try {
        localStorage.setItem('ark-dark', this.dark ? '1' : '0')
      } catch {
        // storage blocked
      }
      this.apply()
    }
  }
})
