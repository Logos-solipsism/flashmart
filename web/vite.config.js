import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发环境端口固定 5274，/api 请求统一代理到本机后端 18090
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5274,
    proxy: {
      '/api': {
        target: 'http://localhost:18090',
        changeOrigin: true
      }
    }
  }
})
