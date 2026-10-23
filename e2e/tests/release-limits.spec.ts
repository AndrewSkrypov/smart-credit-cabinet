import { test, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { setTimeout as pause } from 'node:timers/promises';
import { noHorizontalOverflow } from '../helpers/cabinet.js';

test.use({ trace: 'off', screenshot: 'off' });

test('Общий лимит входа показывает понятную ошибку и сохраняет форму', async ({ page, request }, info) => {
  await pause(6000);
  const email = `limited-${randomUUID()}@example.test`;
  try {
    for (let attempt = 0; attempt < 5; attempt++) {
      const response = await request.post('/api/v1/auth/sign-in', { data: { email, password: 'Wrong2026safe' } });
      expect(response.status()).toBe(401);
    }
    await page.goto('/sign-in');
    await page.getByLabel('Email', { exact: false }).fill(email);
    await page.getByLabel('Пароль', { exact: false }).fill('Wrong2026safe');
    const pending = page.waitForResponse((response) => response.url().endsWith('/api/v1/auth/sign-in'));
    await page.getByRole('button', { name: 'Войти', exact: true }).click();
    const response = await pending;
    expect(response.status()).toBe(429);
    expect(Number(response.headers()['retry-after'])).toBeGreaterThan(0);
    await expect(page.getByText('Слишком много попыток входа. Попробуйте через несколько минут.', { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Войти', exact: true })).toBeEnabled();
    await expect(page.getByLabel('Email', { exact: false })).toHaveValue(email);
    await page.getByLabel('Пароль', { exact: false }).fill('');
    await noHorizontalOverflow(page);
    await page.screenshot({ path: info.outputPath('shared-signin-limit.png'), fullPage: true });
  } finally {
    // Six auth requests replenish at the unchanged gateway rate before the next viewport.
    await pause(14000);
  }
});
