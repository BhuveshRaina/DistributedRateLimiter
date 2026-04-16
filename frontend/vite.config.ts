import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react-swc';
import path from 'path';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api/ratelimit/check': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
      '/api/ratelimit': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/api/benchmark': {
        target: 'http://localhost:8082',
        changeOrigin: true,
      },
      '/metrics': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/actuator': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/admin': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      }
    },
  },
});