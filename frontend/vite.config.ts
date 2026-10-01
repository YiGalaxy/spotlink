import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  build: {
    // Ant Design and its rc-* internals are a known bundle cost. Keep the
    // threshold explicit so a future build-size change remains visible in CI.
    chunkSizeWarningLimit: 900,
    rollupOptions: {
      output: {
        // Split the rarely-changing vendor code out of the app bundle. The
        // app chunk then stays small, and a UI change no longer forces users
        // to re-download React and Ant Design.
        manualChunks: {
          'react-vendor': ['react', 'react-dom', 'react-router-dom'],
          'antd-vendor': ['antd'],
          // Icons ship as one module with hundreds of entries; splitting them
          // out keeps the Ant Design chunk under the default size warning.
          'icons-vendor': ['@ant-design/icons'],
        },
      },
    },
  },
  server: {
    port: 5173,
    // Proxying /api to the backend keeps the browser on a single origin, so
    // there is no preflight request and no CORS configuration to keep in sync
    // between the two sides.
    proxy: {
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
    },
  },
})
