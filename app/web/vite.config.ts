import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 3000,
    proxy: {
      '/api': 'http://localhost:6060',
      '/agent/api': 'http://localhost:6060',
      '/webhook': 'http://localhost:6060',
    }
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true
  }
})
