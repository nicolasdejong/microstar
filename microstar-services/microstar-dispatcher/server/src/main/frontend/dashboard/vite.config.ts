import {defineConfig} from 'vite'
import {svelte} from '@sveltejs/vite-plugin-svelte'

// https://vitejs.dev/config/
export default defineConfig({
  build: {
    rollupOptions: {
      output: {
        entryFileNames: 'script.js',
        assetFileNames: 'styles.css',
      },
    },
  },
  css: {
    preprocessorOptions: {
      scss: {
        api: 'modern-compiler' // or "modern"
      }
    }
  },
  plugins: [svelte()],
  server: {
    host: '127.0.0.1',// needed for Java connections, supposedly fixed in Vaadin 22.0.18
    strictPort: true,
  }
})
