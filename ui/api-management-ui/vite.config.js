import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The backend allows CORS from exactly this origin, so the port must not drift to 5174
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
  },
});
