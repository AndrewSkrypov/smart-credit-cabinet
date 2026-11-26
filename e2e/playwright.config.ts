import { defineConfig, devices } from '@playwright/test';

const finalFinancing = process.env.CABINET_FINAL_FINANCING_E2E === '1';
const profile = finalFinancing ? 'final-financing' : process.env.MAIL_TESTS === '1' ? 'mail' : 'default';
const finalFinancingSpec = '**/final-agent-financing.spec.ts';
const desktop = { name: 'desktop', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 1000 } } };
const mobile = { name: 'mobile', use: { ...devices['Pixel 7'], viewport: { width: 375, height: 812 } } };

export default defineConfig({
  testDir: './tests',
  ...(finalFinancing ? { testMatch: finalFinancingSpec } : { testIgnore: finalFinancingSpec }),
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
  projects: finalFinancing ? [desktop] : [desktop, mobile]
});
