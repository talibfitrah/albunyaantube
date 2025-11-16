import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';
import path from 'node:path';

export default defineConfig({
  plugins: [
    vue({
      template: {
        compilerOptions: {
          // Remove whitespace for smaller bundle
          whitespace: 'condense'
        }
      }
    })
  ],
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
    // Target modern browsers for smaller bundle
    target: 'esnext',
    rollupOptions: {
      output: {
        // Advanced code splitting strategy
        manualChunks: (id) => {
          // Vendor chunks
          if (id.includes('node_modules')) {
            if (id.includes('vue') || id.includes('pinia') || id.includes('vue-router')) {
              return 'vue-core';
            }
            if (id.includes('firebase')) {
              return 'firebase';
            }
            if (id.includes('axios')) {
              return 'utils';
            }
            if (id.includes('vue-i18n')) {
              return 'vue-i18n';
            }
            // Other vendor libs
            return 'vendor';
          }

          // Component-level chunks for large components
          if (id.includes('/views/')) {
            const viewName = id.split('/views/')[1].split('.')[0];
            return `view-${viewName}`;
          }
        },
        // Better file naming for caching
        chunkFileNames: 'assets/[name]-[hash].js',
        entryFileNames: 'assets/[name]-[hash].js',
        assetFileNames: 'assets/[name]-[hash].[ext]'
      }
    },
    // Enable minification
    minify: 'terser',
    terserOptions: {
      compress: {
        drop_console: true, // Remove console.log in production
        drop_debugger: true,
        pure_funcs: ['console.log', 'console.debug', 'console.info'],
        passes: 2 // Multiple passes for better compression
      },
      mangle: {
        safari10: true
      }
    },
    // Source maps for production debugging (disable if not needed)
    sourcemap: false,
    // CSS code splitting
    cssCodeSplit: true,
    // Report compressed size
    reportCompressedSize: true
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./tests/setup.ts'],
    include: ['tests/**/*.spec.ts'],
    css: true,
    exclude: ['tests/e2e/**'],
    // AGENTS.md: Per-test timeout of 30 seconds
    testTimeout: 30000,
    // Generate JUnit XML reports for CI artifact upload
    reporters: ['default', 'junit'],
    outputFile: {
      junit: './test-results/junit.xml'
    }
  }
});
