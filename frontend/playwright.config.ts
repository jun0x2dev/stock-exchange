import { defineConfig, devices } from '@playwright/test'

// 백엔드(http://localhost:8080 또는 BACKEND_URL)와 체험용 데이터가 준비돼 있어야 한다.
export default defineConfig({
  testDir: 'e2e',
  workers: 1,
  reporter: 'list',
  use: { baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5173', headless: true, locale: 'ko-KR', trace: 'retain-on-failure' },
  // Docker로 띄운 화면을 검사할 때는 개발 서버를 띄우지 않는다.
  webServer: process.env.E2E_BASE_URL ? undefined : { command: 'npm run dev', url: 'http://localhost:5173', reuseExistingServer: true },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } } },
    { name: 'mobile', use: { ...devices['Pixel 7'] } },
  ],
})
