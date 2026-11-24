import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// 백엔드와 같은 출처로 보이도록 프록시한다. CORS를 열지 않는다.
export default defineConfig({
  plugins: [react()],
  // CSP가 data: 글꼴을 막으므로 파일로 둔다.
  build: { assetsInlineLimit: 0 },
  server: { proxy: { '/api': process.env.BACKEND_URL ?? 'http://localhost:8080' } },
})
