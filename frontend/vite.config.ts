import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import { VitePWA } from 'vite-plugin-pwa';

export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
    VitePWA({
      registerType: 'autoUpdate',
      manifest: {
        name: '집에서 본 것',
        short_name: '집에서 본 것',
        description: '집에서 보신 것을 남겨두면 다음 진료 때 여쭤볼 것을 만들어 드립니다.',
        lang: 'ko',
        start_url: '/',
        display: 'standalone',
        background_color: '#ffffff',
        theme_color: '#ffffff',
        icons: [
          { src: '/icon-192.png', sizes: '192x192', type: 'image/png' },
          { src: '/icon-512.png', sizes: '512x512', type: 'image/png' },
        ],
      },
    }),
  ],
  server: { port: 5173 },
  test: {
    projects: [
      {
        extends: true,
        test: {
          name: 'ui',
          include: ['src/**/*.{test,spec}.{ts,tsx}'],
          environment: 'jsdom',
          globals: true,
          setupFiles: ['./src/test/setup.ts'],
          css: true,
        },
      },
      {
        extends: true,
        test: {
          name: 'assets',
          include: ['scripts/**/*.{test,spec}.mjs'],
          environment: 'node',
        },
      },
    ],
  },
});
