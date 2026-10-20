import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Backends allow CORS from exactly this origin, so the port must not drift
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5175,
    strictPort: true,
  },
});
