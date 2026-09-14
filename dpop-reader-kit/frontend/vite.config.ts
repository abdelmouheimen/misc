import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// La SPA tourne sur http://localhost:5173 et appelle le backend sur http://localhost:8099.
// Les deux origines sont distinctes : c'est pourquoi le backend doit exposer du CORS
// autorisant l'en-tête DPoP et les credentials (voir backend/CorsConfig.kt).
export default defineConfig({
  plugins: [react()],
  server: { port: 5173 },
});
