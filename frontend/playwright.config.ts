import { defineConfig, devices } from '@playwright/test'

// 백엔드(http://localhost:8080 또는 BACKEND_URL)와 체험용 데이터가 준비돼 있어야 한다.
export default defineConfig({
  testDir: 'e2e',
  workers: 1,
  reporter: 'list',
  use: { baseURL: 'http://localhost:5173', headless: true, locale: 'ko-KR', trace: 'retain-on-failure' },
  webServer: { command: 'npm run dev', url: 'http://localhost:5173', reuseExistingServer: true },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } } },
    { name: 'mobile', use: { ...devices['Pixel 7'] } },
  ],
})
