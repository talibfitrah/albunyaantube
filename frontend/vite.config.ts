import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import path from 'node:path';

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src')
    }
  },
  server: {
    port: 5173,
    strictPort: true
  },
  build: {
    // Optimize chunk size for better caching
    chunkSizeWarningLimit: 600,
    rollupOptions: {
      output: {
        manualChunks: {
          // Vendor chunks for better caching
          'vue-core': ['vue', 'vue-router', 'pinia'],
          'vue-i18n': ['vue-i18n'],
          'firebase': ['firebase/app', 'firebase/auth'],
          'utils': ['axios']
        }
      }
    },
    // Enable minification
    minify: 'terser',
    terserOptions: {
      compress: {
        drop_console: true, // Remove console.log in production
        drop_debugger: true
      }
    },
    // Source maps for production debugging (disable if not needed)
    sourcemap: false,
    // CSS code splitting
    cssCodeSplit: true
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./tests/setup.ts'],
    include: ['tests/**/*.spec.ts'],
    css: true,
    exclude: ['tests/e2e/**']
  }
});
