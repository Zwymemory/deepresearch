import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

// Milestone 1 runs entirely on labelled demo fixtures, so no API proxy is configured.
// The same-origin proxy to the Java API (port 8080) belongs to the Milestone 2 migration.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: { host: "127.0.0.1", port: 5173, strictPort: true },
  preview: { host: "127.0.0.1", port: 4173, strictPort: true },
  test: { environment: "node", include: ["src/**/*.test.ts"] },
});
