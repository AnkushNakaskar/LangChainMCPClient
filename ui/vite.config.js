import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * The bundle is served by Dropwizard's AssetsBundle from the jar, so it is built straight into
 * the resources directory and every asset url has to be absolute under /ui.
 *
 * During `npm run dev` the API is not on the Vite port, so /langchain is proxied to the running
 * application instead. The streaming endpoint must not be buffered by the proxy, which is why
 * compression is left off for it.
 */
export default defineConfig({
  plugins: [react()],
  base: '/ui/',
  build: {
    outDir: '../src/main/resources/assets',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      '/langchain': {
        target: 'http://localhost:8090',
        changeOrigin: true,
      },
    },
  },
});
