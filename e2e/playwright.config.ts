import { defineConfig, devices } from '@playwright/test';

const profile = process.env.MAIL_TESTS === '1' ? 'mail' : 'default';

export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  workers: 1,
  forbidOnly: Boolean(process.env.CI),
  retries: 0,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  outputDir: `test-results/${profile}/artifacts`,
  reporter: [['list'], ['html', { open: 'never', outputFolder: `playwright-report/${profile}` }], ['junit', { outputFile: `test-results/${profile}/junit.xml` }]],
  use: {
    baseURL: process.env.CABINET_URL ?? 'http://localhost:8080',
    locale: 'ru-RU',
    channel: process.env.PLAYWRIGHT_CHANNEL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    actionTimeout: 15_000
  },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 1000 } } },
    { name: 'mobile', use: { ...devices['Pixel 7'], viewport: { width: 375, height: 812 } } }
  ]
});
