import { test, expect } from '@playwright/test';
import { setTimeout as pause } from 'node:timers/promises';
import { clearActive, createScenario, headers, login, noHorizontalOverflow, scenario, waitStatus } from '../helpers/cabinet.js';

test.use({ trace: 'off', screenshot: 'off' });
test.beforeEach(async () => { await pause(6000); });
test.afterEach(async () => { await pause(10000); });
test.setTimeout(180_000);

if (process.env.MAIL_TESTS !== '1') {
  test('Риск меняет ставку, новая заявка подтверждает условия, выданный кредит сохраняет прежнюю ставку', async ({ page, request }, testInfo) => {
    const risk = await login(page, 'risk');
    const history = await request.get('/api/v1/bank/products/WORKING_CAPITAL', { headers: headers(risk) });
    expect(history.status()).toBe(200);
    const original = (await history.json())[0].product;
    const changedRate = original.baseRate + 1;
    const { session: client, app: old } = await createScenario(request, 'client');
    try {
      await waitStatus(request, client, old.id, ['OFFER_ISSUED']);
      const accepted = await request.post(`/api/v1/applications/${old.id}/offer/accept`, { headers: headers(client) });
      expect(accepted.status()).toBe(200);
      const code = (await accepted.json()).process.signing.code;
      expect((await request.post(`/api/v1/applications/${old.id}/sign`, { headers: headers(client), data: { code } })).status()).toBe(200);
      await waitStatus(request, client, old.id, ['DISBURSED']);
      const data = await scenario(request, 'client');
      const draftResponse = await request.post('/api/v1/applications', { headers: headers(client), data: { ...data.scenario.request, bureauConsent: true } });
      expect(draftResponse.status()).toBe(201);
      const draft = await draftResponse.json();
      await page.goto('/bank/products');
      await page.getByLabel('Продукт', { exact: true }).click();
      await page.getByRole('option', { name: original.name, exact: true }).click();
      await page.getByLabel('Базовая ставка, %', { exact: true }).fill(String(changedRate));
      await page.getByLabel('Причина изменения продукта').fill('Проверка новой ставки и сохранения выданного кредита');
      await page.getByRole('button', { name: 'Сохранить новую версию продукта', exact: true }).click();
      await expect(page.getByRole('dialog', { name: 'Сохранить новую версию?' })).toBeVisible();
      expect((await (await request.get('/api/v1/products/WORKING_CAPITAL')).json()).baseRate).toBe(original.baseRate);
      await page.getByRole('button', { name: 'ОК', exact: true }).click();
      await expect(page.getByText(`Сохранена версия ${original.version + 1}. Новые заявки используют эти условия.`, { exact: true })).toBeVisible();
      await expect(page.getByRole('heading', { name: 'История версий продукта', exact: true })).toBeVisible();
      await noHorizontalOverflow(page);
      await page.evaluate(() => { window.scrollTo(0, 0); return document.fonts.ready; });
      await page.screenshot({ path: testInfo.outputPath('product-editor.png') });
      const publicProduct = await (await request.get('/api/v1/products/WORKING_CAPITAL')).json();
      expect(publicProduct.baseRate).toBe(changedRate);
      expect((await request.post(`/api/v1/applications/${draft.id}/submit`, { headers: headers(client) })).status()).toBe(409);
      await page.getByRole('button', { name: 'Выйти', exact: true }).click();
      await login(page, 'client');
      await page.goto(`/client/applications/${draft.id}`);
      await expect(page.getByText('Условия продукта изменились', { exact: true })).toBeVisible();
      await expect(page.getByRole('button', { name: 'Отправить в банк', exact: true })).toBeDisabled();
      await page.evaluate(() => { window.scrollTo(0, 0); return document.fonts.ready; });
      await page.screenshot({ path: testInfo.outputPath('product-changed-draft.png'), fullPage: true });
      await page.getByRole('checkbox', { name: 'Проверил новые условия продукта и согласен отправить заявку по ним', exact: true }).check();
      await page.getByRole('button', { name: 'Отправить в банк', exact: true }).click();
      await expect(page.getByRole('heading', { name: 'Заявка одобрена', exact: true })).toBeVisible({ timeout: 90_000 });
      const fresh = await (await request.get(`/api/v1/applications/${draft.id}`, { headers: headers(client) })).json();
      expect(fresh.process.offer.rate).toBe(changedRate);
      expect(fresh.product.version).toBe(original.version + 1);
      await noHorizontalOverflow(page);
      await page.goto(`/client/applications/${old.id}`);
      const prior = await (await request.get(`/api/v1/applications/${old.id}`, { headers: headers(client) })).json();
      expect(prior.process.offer.rate).toBe(original.baseRate);
      expect(prior.process.loan.rate).toBe(original.baseRate);
      await expect(page.getByRole('heading', { name: 'Кредит выдан', exact: true })).toBeVisible();
      await expect(page.getByRole('heading', { name: 'Принятое предложение', exact: true })).not.toBeVisible();
      await page.getByText('Согласованные условия', { exact: true }).click();
      await expect(page.getByRole('heading', { name: 'Принятое предложение', exact: true })).toBeVisible();
      await noHorizontalOverflow(page);
    } finally {
      const current = (await (await request.get('/api/v1/bank/products/WORKING_CAPITAL', { headers: headers(risk) })).json())[0].product;
      const restored = await request.put('/api/v1/bank/products/WORKING_CAPITAL', { headers: headers(risk), data: { ...original, expectedVersion: current.version, reason: 'Восстановление исходных условий после проверки интерфейса' } });
      expect(restored.status()).toBe(200);
      await clearActive(request, client);
    }
  });

  test('Конфликт правок требует обновления, выключенный продукт исчезает из публичного каталога', async ({ page, request }, testInfo) => {
    const risk = await login(page, 'risk');
    const original = (await (await request.get('/api/v1/bank/products/WORKING_CAPITAL', { headers: headers(risk) })).json())[0].product;
    try {
      await page.goto('/bank/products');
      await page.getByLabel('Продукт', { exact: true }).click();
      await page.getByRole('option', { name: original.name, exact: true }).click();
      await expect(page.getByLabel('Базовая ставка, %', { exact: true })).toHaveValue(String(original.baseRate));
      await page.getByLabel('Базовая ставка, %', { exact: true }).fill(String(original.baseRate + 2));
      const competing = await request.put('/api/v1/bank/products/WORKING_CAPITAL', { headers: headers(risk), data: { ...original, baseRate: original.baseRate + 1, expectedVersion: original.version, reason: 'Конкурентная правка для проверки конфликта' } });
      expect(competing.status()).toBe(200);
      await page.getByLabel('Причина изменения продукта').fill('Устаревшая правка карточки');
      await page.getByRole('button', { name: 'Сохранить новую версию продукта', exact: true }).click();
      await page.getByRole('button', { name: 'ОК', exact: true }).click();
      await expect(page.getByText('Условия только что изменил другой администратор — обновите карточку и проверьте изменения', { exact: false })).toBeVisible();
      await expect(page.getByLabel('Базовая ставка, %', { exact: true })).toHaveValue(String(original.baseRate + 2));
      await page.getByRole('button', { name: `Посмотреть версию ${original.version + 1}`, exact: true }).click();
      await expect(page.getByRole('dialog').locator('dt').filter({ hasText: /^Базовая ставка$/ }).locator('xpath=following-sibling::dd[1]')).toHaveText(`${String(original.baseRate + 1).replace('.', ',')}%`);
      await page.getByRole('button', { name: 'Закрыть', exact: true }).click();
      await page.getByRole('button', { name: 'Загрузить текущие условия', exact: true }).click();
      await page.getByRole('button', { name: 'Вернуться к форме', exact: true }).click();
      await expect(page.getByLabel('Базовая ставка, %', { exact: true })).toHaveValue(String(original.baseRate + 2));
      await page.getByRole('button', { name: 'Загрузить текущие условия', exact: true }).click();
      await page.getByRole('button', { name: 'Продолжить без сохранения', exact: true }).click();
      await expect(page.getByLabel('Базовая ставка, %', { exact: true })).toHaveValue(String(original.baseRate + 1));
      await page.getByRole('checkbox', { name: 'Показывать в каталоге и принимать новые заявки', exact: true }).uncheck();
      await page.getByLabel('Причина изменения продукта').fill('Временное выключение для проверки каталога');
      await page.getByRole('button', { name: 'Сохранить новую версию продукта', exact: true }).click();
      await page.getByRole('button', { name: 'ОК', exact: true }).click();
      await expect(page.getByText(`Сохранена версия ${original.version + 2}. Новые заявки используют эти условия.`, { exact: true })).toBeVisible();
      expect((await request.get('/api/v1/products/WORKING_CAPITAL')).status()).toBe(404);
      const catalogue = await (await request.get('/api/v1/products')).json();
      expect(catalogue.some((product: { code: string }) => product.code === 'WORKING_CAPITAL')).toBe(false);
      await noHorizontalOverflow(page);
      await page.evaluate(() => { window.scrollTo(0, 0); return document.fonts.ready; });
      await page.screenshot({ path: testInfo.outputPath('product-disabled.png') });
    } finally {
      const current = (await (await request.get('/api/v1/bank/products/WORKING_CAPITAL', { headers: headers(risk) })).json())[0].product;
      expect((await request.put('/api/v1/bank/products/WORKING_CAPITAL', { headers: headers(risk), data: { ...original, expectedVersion: current.version, reason: 'Возврат каталога после проверки конфликта и выключения' } })).status()).toBe(200);
    }
  });

  test('Андеррайтер видит условия и историю продукта, но не может изменить ставку', async ({ page, request }) => {
    const staff = await login(page, 'underwriter');
    await page.goto('/bank/products');
    await expect(page.getByText('Изменять продукты может риск-администратор. Вам доступен просмотр условий и истории.', { exact: true })).toBeVisible();
    await expect(page.getByLabel('Базовая ставка, %', { exact: true })).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Сохранить новую версию продукта', exact: true })).toHaveCount(0);
    const current = (await (await request.get('/api/v1/bank/products/WORKING_CAPITAL', { headers: headers(staff) })).json())[0].product;
    expect((await request.put('/api/v1/bank/products/WORKING_CAPITAL', { headers: headers(staff), data: { ...current, expectedVersion: current.version, reason: 'Запрещённая правка' } })).status()).toBe(403);
    await noHorizontalOverflow(page);
  });
}
