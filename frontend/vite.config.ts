import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// The assistant backend runs on the desktop at 127.0.0.1:8191 (feat/assistant).
export default defineConfig({
  plugins: [react()],
  server: { allowedHosts: true, proxy: { "/api": process.env["CRV_API"] ?? "http://127.0.0.1:8191" } },
});
