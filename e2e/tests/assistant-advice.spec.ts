import { test, expect } from '@playwright/test';
import { setTimeout as pause } from 'node:timers/promises';
import { createScenario, login, noHorizontalOverflow, waitStatus } from '../helpers/cabinet.js';

test.use({ trace: 'off', screenshot: 'off' });
test.beforeEach(async () => { await pause(3000); });
test.setTimeout(180_000);

if (process.env.MAIL_TESTS !== '1') {
  test('Помощник подбирает продукт по словам клиента и открывает мастер с заполненными полями', async ({ page }) => {
    await login(page, 'belova');
    await page.getByRole('button', { name: 'Открыть помощника' }).click();
    await page.getByLabel('Ваш вопрос').fill('Подбери кредит: нужно 2 млн на закупку товара на год');
    await page.getByRole('button', { name: 'Спросить' }).click();
    await expect(page.getByText('Кредит на пополнение оборота: ставка от', { exact: false })).toBeVisible({ timeout: 30_000 });
    await page.getByRole('link', { name: 'Оформить', exact: true }).click();
    await expect(page).toHaveURL(/\/client\/new\?purpose=WORKING_CAPITAL&amount=2000000&term=12/);
    await expect(page.getByText('Подходящие продукты')).toBeVisible();
    await noHorizontalOverflow(page);
  });

  test('Андеррайтер видит рекомендации экспертной системы с цепочкой «почему», клиенту они закрыты', async ({ page, request }) => {
    const prepared = await createScenario(request, 'logistic');
    await waitStatus(request, prepared.session, prepared.app.id, ['MANUAL_REVIEW']);
    expect((await request.get(`/api/v1/underwriting/applications/${prepared.app.id}/advice`, { headers: { Authorization: `Bearer ${prepared.session.token}` } })).status()).toBe(403);

    await login(page, 'underwriter');
    await page.goto(`/bank/applications/${prepared.app.id}`);
    await expect(page.getByRole('heading', { name: 'Рекомендации экспертной системы' })).toBeVisible();
    await expect(page.getByText('Недоимка по налогам', { exact: false })).toBeVisible();
    await page.getByRole('button', { name: 'Почему' }).first().click();
    await expect(page.getByText('Худшая просрочка за 2 года', { exact: false })).toBeVisible();
    await page.getByRole('button', { name: 'Кратко словами' }).click();
    await expect(page.getByText('Кратко ·', { exact: false })).toBeVisible({ timeout: 120_000 });
    await noHorizontalOverflow(page);
  });
}
