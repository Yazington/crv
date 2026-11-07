import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// The backend serves the API on 127.0.0.1:8190 (docs/code-standards.md).
export default defineConfig({
  plugins: [react()],
  server: { allowedHosts: true, proxy: { "/api": "http://127.0.0.1:8190" } },
});
