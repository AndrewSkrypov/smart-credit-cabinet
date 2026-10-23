import { test, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { headers, login, noHorizontalOverflow, syntheticInn, waitStatus, type Session, type Application } from '../helpers/cabinet.js';

const mailEnabled = process.env.MAIL_TESTS === '1';
const mailpit = process.env.MAILPIT_URL ?? 'http://127.0.0.1:8025';
const label = 'Дублировать важные уведомления на почту';

test('Профиль показывает почтовые настройки только при доступном канале', async ({ page, request }) => {
  const session = await login(page, 'client');
  await expect.poll(async () => {
    const response = await request.get('/api/v1/notifications/settings', { headers: headers(session) });
    expect(response.ok()).toBeTruthy();
    return (await response.json()).emailAvailable;
  }, { timeout: 20000 }).toBe(mailEnabled);
  await page.getByRole('link', { name: 'Профиль', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Профиль', exact: true })).toBeVisible();
  if (mailEnabled) {
    await expect(page.getByLabel(label, { exact: true })).toBeVisible();
  } else {
    await expect(page.getByText(/Почтовый канал на этом стенде выключен/)).toBeVisible();
    await expect(page.getByLabel(label, { exact: true })).toHaveCount(0);
  }
  await noHorizontalOverflow(page);
});

if (mailEnabled) {
  test('Получатель включает почту и получает безопасное письмо о своей заявке', async ({ page, request }) => {
    const email = `mail-e2e-${randomUUID()}@example.test`;
    const created = await request.post('/api/v1/auth/sign-up', { data: {
      inn: syntheticInn(), email, password: 'Test2026safe', fullName: 'Имя не должно попасть в письмо', personalDataConsent: true
    } });
    expect(created.ok()).toBeTruthy();
    const session: Session = await created.json();
    await page.addInitScript((value) => localStorage.setItem('cabinet.session', JSON.stringify(value)), session);
    await page.goto('/profile');
    const toggle = page.getByLabel(label, { exact: true });
    await expect(toggle).not.toBeChecked();
    const saved = page.waitForResponse((response) => response.url().endsWith('/notifications/settings') && response.request().method() === 'PUT');
    await toggle.click();
    expect((await saved).status()).toBe(200);
    await expect(toggle).toBeChecked();
    await page.reload();
    await expect(page.getByLabel(label, { exact: true })).toBeChecked();
    await noHorizontalOverflow(page);
    const draft = await request.post('/api/v1/applications', { headers: headers(session), data: {
      productCode: 'WORKING_CAPITAL', purpose: 'WORKING_CAPITAL', amount: 300000, termMonths: 12,
      declaredRevenue12m: 24000000, declaredMonthlyDebtPayments: 0, bureauConsent: true
    } });
    expect(draft.status()).toBe(201);
    const app: Application = await draft.json();
    expect((await request.post(`/api/v1/applications/${app.id}/submit`, { headers: headers(session) })).ok()).toBeTruthy();
    await waitStatus(request, session, app.id, ['OFFER_ISSUED', 'MANUAL_REVIEW', 'REJECTED']);
    // A synthetic company can need manual review; only action-required and outcome mail is expected.
    const appState = await request.get(`/api/v1/applications/${app.id}`, { headers: headers(session) });
    if ((await appState.json()).status === 'MANUAL_REVIEW') {
      const bankResponse = await request.post('/api/v1/auth/sign-in', { data: { email: 'underwriter@demo.test', password: 'Demo2026' } });
      expect(bankResponse.ok()).toBeTruthy();
      const bank: Session = await bankResponse.json();
      expect((await request.post(`/api/v1/underwriting/applications/${app.id}/approve`, {
        headers: headers(bank), data: { comment: 'Синтетическая проверка почтового канала' }
      })).ok()).toBeTruthy();
    }
    let messageId = '';
    await expect.poll(async () => {
      const response = await request.get(`${mailpit}/api/v1/search`, { params: { query: `to:${email}` } });
      expect(response.ok()).toBeTruthy();
      const result = await response.json() as { messages: Array<{ ID: string }> };
      messageId = result.messages[0]?.ID ?? '';
      return messageId;
    }, { timeout: 45000 }).not.toBe('');
    const messageResponse = await request.get(`${mailpit}/api/v1/message/${messageId}`);
    expect(messageResponse.ok()).toBeTruthy();
    const message = await messageResponse.json() as { Subject: string; Text: string; HTML: string };
    expect(message.Text).toContain(`/client/applications/${app.id}`);
    expect(message.Text).toContain('Откройте кабинет:');
    expect(message.Subject + message.Text).not.toContain('300000');
    expect(message.Subject + message.Text).not.toContain('Имя не должно попасть в письмо');
    expect(message.Subject + message.Text).not.toContain('R_SCORE');
    expect(message.HTML).toBe('');
    const current = await request.get('/api/v1/notifications/settings', { headers: headers(session) });
    expect((await request.put('/api/v1/notifications/settings', { headers: headers(session), data: {
      emailEnabled: false, version: (await current.json()).version
    } })).ok()).toBeTruthy();
  });
}
