import { test, expect, devices, type APIRequestContext } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { setTimeout as pause } from 'node:timers/promises';
import { clearActive, createScenario, headers, login, noHorizontalOverflow, waitStatus } from '../helpers/cabinet.js';

const pdf = readFileSync(new URL('../../contracts/postman/fixtures/synthetic.pdf', import.meta.url));
test.use({ trace: 'off', screenshot: 'off' });
test.beforeEach(async () => { await pause(6000); });
test.afterEach(async () => { await pause(6000); });
test.setTimeout(180_000);

async function review(request: APIRequestContext) {
  const prepared = await createScenario(request, 'logistic');
  const state = await waitStatus(request, prepared.session, prepared.app.id, ['MANUAL_REVIEW', 'INFO_REQUESTED']);
  if (state.status === 'INFO_REQUESTED') {
    for (const item of state.process.infoRequest!.items) {
      if (item.documentType) expect((await request.post(`/api/v1/applications/${state.id}/documents?type=${item.documentType}`, {
        headers: headers(prepared.session), multipart: { file: { name: 'business.pdf', mimeType: 'application/pdf', buffer: pdf } }
      })).status()).toBe(201);
    }
    expect((await request.post(`/api/v1/applications/${state.id}/info-provided`, { headers: headers(prepared.session), data: { answer: 'Данные для синтетической проверки' } })).status()).toBe(200);
    await waitStatus(request, prepared.session, state.id, ['MANUAL_REVIEW']);
  }
  return prepared;
}

if (process.env.MAIL_TESTS !== '1') {
  test('Клиент пишет, банк видит непрочитанное и отвечает с файлом, клиент получает уведомление', async ({ page, browser, request }, info) => {
    const { session: client, app } = await review(request);
    const profile = devices[info.project.name === 'mobile' ? 'Pixel 7' : 'Desktop Chrome'];
    const context = await browser.newContext({ baseURL: process.env.CABINET_URL ?? 'http://localhost:8080', viewport: page.viewportSize()!,
      userAgent: profile.userAgent, deviceScaleFactor: profile.deviceScaleFactor, isMobile: profile.isMobile, hasTouch: profile.hasTouch, locale: 'ru-RU' });
    const bankPage = await context.newPage();
    try {
      const before = await (await request.get(`/api/v1/applications/${app.id}`, { headers: headers(client) })).json();
      const eventsBefore = await (await request.get(`/api/v1/applications/${app.id}/events`, { headers: headers(client) })).json();
      const staff = await login(bankPage, 'underwriter');
      await login(page, 'logistic');
      await page.goto(`/client/applications/${app.id}`);
      const clientChat = page.getByRole('region', { name: 'Переписка по заявке', exact: true });
      const text = 'План поставок: 5 < 10. <b>Это обычный текст</b>';
      await clientChat.getByRole('textbox', { name: 'Сообщение', exact: true }).fill(text);
      const sent = page.waitForResponse((response) => response.url().endsWith(`/applications/${app.id}/messages`) && response.request().method() === 'POST');
      await clientChat.getByRole('button', { name: 'Отправить сообщение', exact: true }).click();
      expect((await sent).status()).toBe(200);
      await expect(clientChat.getByText(text, { exact: true })).toBeVisible();
      await expect(clientChat.locator('b')).toHaveCount(0);
      await noHorizontalOverflow(page);
      await page.goto('/client');
      const marker = new RegExp(`№ ${app.number}\\b`);
      const bankRow = info.project.name === 'mobile'
        ? bankPage.getByRole('button', { name: new RegExp(`^№ ${app.number} `) })
        : bankPage.getByRole('row').filter({ hasText: marker });
      await expect(bankRow.getByText('Сообщений: 1', { exact: true })).toBeVisible({ timeout: 25000 });
      await bankRow.click();
      const bankChat = bankPage.getByRole('region', { name: 'Переписка по заявке', exact: true });
      await bankChat.getByText(text, { exact: true }).scrollIntoViewIfNeeded();
      await expect(bankChat.getByText(text, { exact: true })).toBeVisible();
      await expect.poll(async () => (await (await request.get(`/api/v1/applications/${app.id}/messages`, { headers: headers(staff) })).json()).unreadCount).toBe(0);
      const fileName = 'Подтверждение-поставок-и-согласованных-сроков-по-заявке-для-компании.pdf';
      await bankChat.getByLabel('Файл для сообщения', { exact: true }).setInputFiles({ name: fileName, mimeType: 'application/pdf', buffer: pdf });
      await expect(bankChat.getByText(fileName, { exact: true })).toBeVisible();
      const reply = 'Банк получил план. Прилагаем подтверждение.';
      await bankChat.getByRole('textbox', { name: 'Сообщение', exact: true }).fill(reply);
      await bankChat.getByRole('button', { name: 'Отправить сообщение', exact: true }).click();
      await expect(bankChat.getByText(reply, { exact: true })).toBeVisible();
      await noHorizontalOverflow(bankPage);
      await bankPage.screenshot({ path: info.outputPath('bank-conversation.png'), animations: 'disabled' });
      const clientRow = page.getByRole('link').filter({ hasText: marker });
      await expect(clientRow.getByText('Сообщений: 1', { exact: true })).toBeVisible({ timeout: 20000 });
      await expect.poll(async () => {
        const notices = await (await request.get('/api/v1/notifications?page=0&size=100', { headers: headers(client) })).json();
        return notices.items.filter((item: { type: string; link: string }) => item.type === 'APPLICATION_MESSAGE' && item.link === `/client/applications/${app.id}`).length;
      }, { timeout: 25000 }).toBe(1);
      await page.getByRole('button', { name: /^Уведомления:/ }).click();
      await page.getByRole('dialog', { name: 'Уведомления', exact: true }).getByRole('button', { name: new RegExp(`Новое сообщение по заявке № ${app.number}`) }).click();
      await expect(page).toHaveURL(new RegExp(`/client/applications/${app.id}$`));
      const received = page.getByRole('region', { name: 'Переписка по заявке', exact: true });
      const file = received.getByRole('button', { name: new RegExp(fileName.replace('.', '\\.')) });
      await file.scrollIntoViewIfNeeded();
      await expect(received.getByText(reply, { exact: true })).toBeVisible();
      const downloadReady = page.waitForEvent('download');
      await file.click();
      const download = await downloadReady;
      expect(download.suggestedFilename()).toBe(fileName);
      const stream = await download.createReadStream();
      expect(stream).toBeTruthy();
      const chunks: Buffer[] = [];
      for await (const chunk of stream!) chunks.push(Buffer.from(chunk));
      expect(Buffer.concat(chunks)).toEqual(pdf);
      await expect.poll(async () => (await (await request.get(`/api/v1/applications/${app.id}/messages`, { headers: headers(client) })).json()).unreadCount).toBe(0);
      await noHorizontalOverflow(page);
      await page.screenshot({ path: info.outputPath('client-conversation.png'), animations: 'disabled' });
      const after = await (await request.get(`/api/v1/applications/${app.id}`, { headers: headers(client) })).json();
      expect(after.status).toBe(before.status); expect(after.version).toBe(before.version); expect(after.documents).toEqual(before.documents);
      expect(await (await request.get(`/api/v1/applications/${app.id}/events`, { headers: headers(client) })).json()).toEqual(eventsBefore);
      const audit = await (await request.get(`/api/v1/applications/${app.id}/messages/audit`, { headers: headers(staff) })).json();
      expect(audit.filter((entry: { action: string }) => entry.action === 'MESSAGE_SENT')).toHaveLength(2);
      expect(audit.filter((entry: { action: string }) => entry.action === 'ATTACHMENT_UPLOADED')).toHaveLength(1);
      await bankChat.getByRole('button', { name: 'Аудит переписки', exact: true }).click();
      await expect(bankPage.getByRole('dialog', { name: 'Аудит переписки', exact: true }).getByText('Отправлено сообщение', { exact: true })).toHaveCount(2);
    } finally { await context.close(); await clearActive(request, client); }
  });

  test('Закрытая заявка сохраняет переписку для чтения, новое сообщение не принимается', async ({ page, request }) => {
    const { session, app } = await review(request);
    try {
      expect((await request.post(`/api/v1/applications/${app.id}/messages`, { headers: { ...headers(session), 'Idempotency-Key': randomUUID() }, data: { text: 'Сохранённая переписка до закрытия' } })).status()).toBe(200);
      expect((await request.post(`/api/v1/applications/${app.id}/withdraw`, { headers: headers(session) })).status()).toBe(200);
      await login(page, 'logistic');
      await page.goto(`/client/applications/${app.id}`);
      const chat = page.getByRole('region', { name: 'Переписка по заявке', exact: true });
      await chat.getByText('Сохранённая переписка до закрытия', { exact: true }).scrollIntoViewIfNeeded();
      await expect(chat.getByText('Заявка завершена. Переписка доступна только для чтения', { exact: true })).toBeVisible();
      await expect(chat.getByRole('button', { name: 'Отправить сообщение', exact: true })).toHaveCount(0);
      expect((await request.post(`/api/v1/applications/${app.id}/messages`, { headers: { ...headers(session), 'Idempotency-Key': randomUUID() }, data: { text: 'После закрытия' } })).status()).toBe(409);
      await noHorizontalOverflow(page);
    } finally { await clearActive(request, session); }
  });
}
