import { test, expect, type Locator, type TestInfo } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { setTimeout as pause } from 'node:timers/promises';
import { clearActive, createScenario, headers, login, noHorizontalOverflow, scenario, signIn, waitStatus } from '../helpers/cabinet.js';

const fullNotice = 'Не отправляйте полный номер карты, срок её действия вместе с номером, CVV/CVC, PIN-код или фотографию карты. В документах заранее скройте эти реквизиты. Кабинету они не нужны.';
const shortNotice = 'Не вводите реквизиты платёжной карты и PIN-код. Для ответа помощнику они не нужны.';
const pdf = readFileSync(new URL('../../contracts/postman/fixtures/synthetic.pdf', import.meta.url));
// A transparent synthetic placeholder, not a photograph containing card data.
const image = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a1WQAAAAASUVORK5CYII=', 'base64');

test.use({ trace: 'off', screenshot: 'off' });
test.skip(process.env.MAIL_TESTS === '1', 'Предупреждения и tabletop проверяются основным синтетическим прогоном');
test.setTimeout(180_000);
test.beforeEach(async () => { await pause(6000); });
test.afterEach(async () => { await pause(6000); });

async function capture(locator: Locator, info: TestInfo, name: string) {
  await locator.scrollIntoViewIfNeeded();
  await info.attach(name, { body: await locator.screenshot(), contentType: 'image/png' });
}

test('PCI P0: предупреждения перед вводом, обычная и запрошенная загрузка, assistant и read-only', async ({ page, browser, request }, info) => {
  const client = await login(page, 'client');
  await clearActive(request, client);
  const company = await scenario(request, 'client');
  const created = await request.post('/api/v1/applications', { headers: headers(client), data: { ...company.scenario.request, bureauConsent: true } });
  expect(created.status()).toBe(201);
  const draft = await created.json() as { id: string };
  const context = await browser.newContext({ baseURL: process.env.CABINET_URL ?? 'http://localhost:8080', viewport: page.viewportSize()!, locale: 'ru-RU' });
  const bankContext = await browser.newContext({ baseURL: process.env.CABINET_URL ?? 'http://localhost:8080', viewport: page.viewportSize()!, locale: 'ru-RU' });
  const tech = await createScenario(request, 'technopark');
  try {
    await page.goto(`/client/applications/${draft.id}`);
    const docs = page.getByRole('heading', { name: 'Документы', exact: true }).locator('..');
    await expect(docs.getByText(fullNotice, { exact: true })).toBeVisible();
    await capture(docs, info, 'PCI-A04-draft-upload');
    const uploaded = page.waitForResponse((response) => response.url().includes(`/applications/${draft.id}/documents`) && response.request().method() === 'POST');
    await docs.locator('input[type=file]').first().setInputFiles({ name: 'synthetic-settlement-statement.pdf', mimeType: 'application/pdf', buffer: pdf });
    expect((await uploaded).status()).toBe(201);
    await expect(docs.getByRole('button', { name: 'synthetic-settlement-statement.pdf', exact: true })).toBeVisible();

    await page.getByRole('button', { name: 'Открыть помощника', exact: true }).click();
    const drawer = page.locator('.MuiDrawer-paper');
    await expect(drawer.getByText(shortNotice, { exact: true })).toBeVisible();
    await expect(drawer.getByLabel('Ваш вопрос')).toBeVisible();
    await capture(drawer, info, 'PCI-A03-assistant-before-request');
    await drawer.getByLabel('Ваш вопрос').fill('Что сейчас с моей заявкой?');
    await drawer.getByRole('button', { name: 'Спросить', exact: true }).click();
    await expect(drawer.getByText('По правилам и данным кабинета', { exact: true })).toBeVisible();
    await expect(drawer.getByText(/Источники:.*Заявка №/)).toBeVisible();
    await drawer.getByRole('button', { name: 'Закрыть помощника', exact: true }).click();
    await noHorizontalOverflow(page);

    await waitStatus(request, tech.session, tech.app.id, ['INFO_REQUESTED']);
    const techPage = await context.newPage();
    await login(techPage, 'technopark');
    await techPage.goto(`/client/applications/${tech.app.id}`);
    const infoBlock = techPage.getByRole('heading', { name: 'Банк просит дополнить заявку', exact: true }).locator('..');
    await expect(infoBlock.getByText(fullNotice, { exact: true })).toBeVisible();
    await capture(infoBlock, info, 'PCI-A04-requested-upload');
    const requestedUpload = techPage.waitForResponse((response) => response.url().includes(`/applications/${tech.app.id}/documents`) && response.request().method() === 'POST');
    await infoBlock.locator('input[type=file]').first().setInputFiles({ name: 'synthetic-requested-statement.pdf', mimeType: 'application/pdf', buffer: pdf });
    expect((await requestedUpload).status()).toBe(201);
    const clientChat = techPage.getByRole('region', { name: 'Переписка по заявке', exact: true });
    await expect(clientChat.getByText(fullNotice, { exact: true })).toBeVisible();
    await expect(clientChat.getByLabel('Сообщение', { exact: true })).toBeVisible();
    await capture(clientChat, info, 'PCI-A02-client-chat-before-send');

    const bankPage = await bankContext.newPage();
    await login(bankPage, 'manager');
    await bankPage.goto(`/bank/applications/${tech.app.id}`);
    const bankChat = bankPage.getByRole('region', { name: 'Переписка по заявке', exact: true });
    await expect(bankChat.getByText(fullNotice, { exact: true })).toBeVisible();
    await capture(bankChat, info, 'PCI-A02-bank-chat-before-send');
    await bankChat.getByLabel('Сообщение', { exact: true }).fill('Синтетическая проверка обычного сообщения');
    await bankChat.getByRole('button', { name: 'Отправить сообщение', exact: true }).click();
    await expect(clientChat.getByText('Синтетическая проверка обычного сообщения', { exact: true })).toBeVisible();
    await noHorizontalOverflow(bankPage);
    await noHorizontalOverflow(techPage);

    expect((await request.post(`/api/v1/applications/${tech.app.id}/withdraw`, { headers: headers(tech.session) })).status()).toBe(200);
    await techPage.reload();
    await expect(clientChat.getByText('Заявка завершена. Переписка доступна только для чтения', { exact: true })).toBeVisible();
    await expect(clientChat.getByRole('button', { name: 'Отправить сообщение', exact: true })).toHaveCount(0);
    await expect(clientChat.getByText(fullNotice, { exact: true })).toHaveCount(0);
  } finally {
    await context.close();
    await bankContext.close();
    await clearActive(request, client);
    await clearActive(request, tech.session);
  }
});

test('PCI P0 tabletop: текст и изображение-заглушка, копии и неподдержанное удаление', async ({ request }, info) => {
  const { session: owner, app } = await createScenario(request, 'technopark');
  const bank = await signIn(request, 'manager');
  const foreign = await signIn(request, 'client');
  await waitStatus(request, owner, app.id, ['INFO_REQUESTED']);
  try {
    const marker = `PCI_TABLETOP_SYNTHETIC_${randomUUID()}`;
    const textMessage = await request.post(`/api/v1/applications/${app.id}/messages`, {
      headers: { ...headers(owner), 'Idempotency-Key': randomUUID() }, data: { text: marker, documentIds: [] }
    });
    expect(textMessage.status()).toBe(200);
    const textId = (await textMessage.json()).id as string;
    const upload = await request.post(`/api/v1/applications/${app.id}/messages/attachments`, {
      headers: { ...headers(owner), 'Idempotency-Key': randomUUID() },
      multipart: { file: { name: 'pci-tabletop-synthetic-placeholder.png', mimeType: 'image/png', buffer: image } }
    });
    expect(upload.status()).toBe(200);
    const documentId = (await upload.json()).id as string;
    const published = await request.post(`/api/v1/applications/${app.id}/messages`, {
      headers: { ...headers(owner), 'Idempotency-Key': randomUUID() },
      data: { text: 'Учебная заглушка для сценария фотографии; реквизитов нет', documentIds: [documentId] }
    });
    expect(published.status()).toBe(200);
    const imageMessageId = (await published.json()).id as string;
    const page = await (await request.get(`/api/v1/applications/${app.id}/messages`, { headers: headers(bank) })).json();
    expect(page.messages.some((message: { id: string }) => message.id === textId)).toBe(true);
    expect(page.messages.some((message: { id: string }) => message.id === imageMessageId)).toBe(true);
    expect((await request.get(`/api/v1/applications/${app.id}/messages`, { headers: headers(foreign) })).status()).toBe(404);
    const download = await request.get(`/api/v1/applications/${app.id}/documents/${documentId}`, { headers: headers(bank) });
    expect(download.status()).toBe(200);
    expect(await download.body()).toEqual(image);
    const deletion = await request.delete(`/api/v1/applications/${app.id}/documents/${documentId}`, { headers: headers(owner) });
    expect(deletion.status()).toBe(409);
    expect((await request.get(`/api/v1/applications/${app.id}/documents/${documentId}`, { headers: headers(owner) })).status()).toBe(200);
    await expect.poll(async () => {
      const notices = await (await request.get('/api/v1/notifications?size=100', { headers: headers(bank) })).json();
      const related = notices.items.filter((item: { link: string }) => item.link === `/bank/applications/${app.id}`);
      expect(JSON.stringify(related)).not.toContain(marker);
      expect(JSON.stringify(related)).not.toContain('pci-tabletop-synthetic-placeholder.png');
      return related.length;
    }).toBeGreaterThan(0);
    await info.attach('PCI-A05-demo-tabletop-metadata', { contentType: 'application/json', body: Buffer.from(JSON.stringify({
      scope: 'synthetic tabletop with explicitly mocked incident/storage roles and escalation channel',
      applicationId: app.id, textMessageId: textId, imageMessageId, documentId,
      foreignAccess: 404, publishedDeletion: deletion.status(), noticesContainNoScenarioPayload: true,
      textRedaction: 'unsupported', objectQuarantine: 'unsupported', backupErasure: 'unsupported',
      realOperationalAppointments: 'not verified; user requested mocks', cardDataUsed: false
    })) });
  } finally { await clearActive(request, owner); }
});
