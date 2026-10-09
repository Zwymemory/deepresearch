import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig, loadEnv } from "vite";
import type { ProxyOptions } from "vite";
import { configDefaults } from "vitest/config";

// The app is built for, and served under, /app/ (see docs/frontend/M2_INTEGRATION_HANDOFF.md).
//
// Development proxy: opt-in only. Set DEEPRESEARCH_API_PROXY to an existing public API origin,
// e.g. http://127.0.0.1:8080 (Java) or http://127.0.0.1:8090 (preview mock). The browser then
// talks to the Vite origin, so the page's same-origin rule for Bearer tokens still holds and no
// backend CORS change is needed. Without it, live mode reports the API as not connected.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const target = env.DEEPRESEARCH_API_PROXY?.trim();
  const proxy: Record<string, ProxyOptions> | undefined = target ? {
    "/api": { target, changeOrigin: false },
    // V1 reference page, when the target serves it.
    "/demo.html": { target, changeOrigin: false },
  } : undefined;
  return {
    base: "/app/",
    plugins: [react(), tailwindcss()],
    build: { outDir: "dist", assetsDir: "assets", sourcemap: false },
    server: { host: "127.0.0.1", port: 5173, strictPort: true, proxy },
    preview: { host: "127.0.0.1", port: 4173, strictPort: true, proxy },
    test: { environment: "node", include: ["src/**/*.test.ts"], exclude: [...configDefaults.exclude] },
  };
});
