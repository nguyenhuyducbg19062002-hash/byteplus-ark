import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

// Dev: http://localhost:5173 – mọi request /api được chuyển tới backend Spring Boot (cổng 8080)
export default defineConfig({
  plugins: [vue(), tailwindcss()],
  server: {
    port: 5173,
    strictPort: true, // báo lỗi nếu 5173 đang bận thay vì tự nhảy cổng
    proxy: {
      '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true, timeout: 600000, proxyTimeout: 600000 }
    }
  },
  build: {
    chunkSizeWarningLimit: 1500
  }
})
