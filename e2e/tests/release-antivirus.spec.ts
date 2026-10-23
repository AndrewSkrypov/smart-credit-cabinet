import { test, expect } from '@playwright/test';
import { setTimeout as pause } from 'node:timers/promises';
import { createScenario, clearActive, headers, login, noHorizontalOverflow, waitStatus } from '../helpers/cabinet.js';
import { infectedDocx } from '../helpers/antivirus.js';

test.use({ trace: 'off', screenshot: 'off' });
test.beforeEach(async () => { await pause(6000); });
test.afterEach(async () => { await pause(6000); });

if (process.env.MAIL_TESTS !== '1') {
  test('Антивирус отклоняет EICAR до сохранения документа и вложения чата, безопасный PDF принимается', async ({ page, request }, info) => {
    const { session, app } = await createScenario(request, 'technopark');
    try {
      await waitStatus(request, session, app.id, ['INFO_REQUESTED']);
      const state = async () => (await request.get(`/api/v1/applications/${app.id}`, { headers: headers(session) })).json();
      const events = async () => (await request.get(`/api/v1/applications/${app.id}/events`, { headers: headers(session) })).json();
      const before = await state(); const journal = await events();
      await login(page, 'technopark');
      await page.goto(`/client/applications/${app.id}`);
      const sample = { name: 'Проверка-антивируса.docx', mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', buffer: infectedDocx() };
      const deniedBusiness = page.waitForResponse((response) => response.url().includes(`/applications/${app.id}/documents?`) && response.request().method() === 'POST');
      const chooser = page.waitForEvent('filechooser');
      await page.getByRole('button', { name: 'Загрузить', exact: true }).first().click();
      await (await chooser).setFiles(sample);
      expect((await deniedBusiness).status()).toBe(400);
      await expect(page.getByText('Файл содержит угрозу и не сохранён. Выберите безопасный файл', { exact: true })).toBeVisible();
      expect((await state()).documents).toEqual(before.documents);
      expect((await state()).version).toEqual(before.version);
      expect(await events()).toEqual(journal);
      await page.getByText('Файл содержит угрозу и не сохранён. Выберите безопасный файл', { exact: true }).scrollIntoViewIfNeeded();
      await noHorizontalOverflow(page);
      await page.screenshot({ path: info.outputPath('infected-document-rejected.png'), animations: 'disabled' });

      const safe = { name: 'Безопасный-документ.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.7\nSynthetic antivirus check\n%%EOF\n') };
      const accepted = page.waitForResponse((response) => response.url().includes(`/applications/${app.id}/documents?`) && response.request().method() === 'POST');
      const cleanChooser = page.waitForEvent('filechooser');
      await page.getByRole('button', { name: 'Загрузить', exact: true }).first().click();
      await (await cleanChooser).setFiles(safe);
      expect((await accepted).status()).toBe(201);
      await expect(page.getByText(safe.name, { exact: true })).toBeVisible();

      const afterBusiness = await state(); const afterBusinessJournal = await events();
      const chat = page.getByRole('region', { name: 'Переписка по заявке', exact: true });
      const deniedChat = page.waitForResponse((response) => response.url().endsWith(`/applications/${app.id}/messages/attachments`) && response.request().method() === 'POST');
      await chat.getByLabel('Файл для сообщения', { exact: true }).setInputFiles(sample);
      expect((await deniedChat).status()).toBe(400);
      await expect(chat.getByRole('alert')).toContainText('Файл содержит угрозу и не сохранён. Выберите безопасный файл');
      expect((await state()).documents).toEqual(afterBusiness.documents);
      expect(await events()).toEqual(afterBusinessJournal);
      await chat.getByRole('alert').scrollIntoViewIfNeeded();
      await noHorizontalOverflow(page);
      await page.screenshot({ path: info.outputPath('infected-chat-attachment-rejected.png'), animations: 'disabled' });
      await chat.getByRole('button', { name: 'Отменить загрузку', exact: true }).click();
      const acceptedChat = page.waitForResponse((response) => response.url().endsWith(`/applications/${app.id}/messages/attachments`) && response.request().method() === 'POST');
      await chat.getByLabel('Файл для сообщения', { exact: true }).setInputFiles(safe);
      expect((await acceptedChat).status()).toBe(200);
      await expect(chat.getByText(safe.name, { exact: true })).toBeVisible();
      await chat.getByRole('textbox', { name: 'Сообщение', exact: true }).fill('Безопасный файл прошёл проверку');
      await chat.getByRole('button', { name: 'Отправить сообщение', exact: true }).click();
      await expect(chat.getByText('Безопасный файл прошёл проверку', { exact: true })).toBeVisible();
      await noHorizontalOverflow(page);
    } finally { await clearActive(request, session); }
  });
}
