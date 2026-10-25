import { test, expect } from '@playwright/test';
import { noHorizontalOverflow } from '../helpers/cabinet.js';

// Isolated UI responses: no company/account changes, no user conversations or read cursors.
const company = { id: 'a0000000-0000-4000-8000-000000000001', name: 'ООО «Пример»', inn: '000100000004', legalForm: 'COMPANY', members: [] };
const row = (n: number, done: boolean) => ({ id: `a0000000-0000-4000-8000-${String(n).padStart(12, '0')}`, number: 78000 + n,
  productName: 'Кредит для бизнеса', companyName: company.name, companyInn: company.inn, status: done ? 'REJECTED' : 'MANUAL_REVIEW',
  amount: 1000000, termMonths: 12, createdAt: '2026-10-25T00:00:00Z', statusChangedAt: '2026-10-25T00:00:00Z' });
async function session(page: import('@playwright/test').Page, bank = false) {
  await page.addInitScript(({ company, bank }) => localStorage.setItem('cabinet.session', JSON.stringify({ token: 'isolated-ui-fixture', user: { id: 'ui-user', fullName: 'Проверка интерфейса', email: 'fixture@example.test', role: bank ? 'MANAGER' : 'CLIENT', companyId: company.id, canSign: true } })), { company, bank });
  await page.route('**/api/v1/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/v1/companies/me') return route.fulfill({ json: company });
    if (path.endsWith('/unread-count')) return route.fulfill({ json: { count: 0 } });
    return route.fulfill({ json: [] });
  });
}
function result(group: string, index: number, small: boolean, bank = false) {
  const size = bank ? 20 : 10;
  const count = small ? 1 : size + 1;
  return { items: Array.from({ length: small || index === 1 ? 1 : size }, (_, i) => row(i + 1 + index * size, group === 'done')), page: small ? 0 : index, size,
    totalElements: count, totalPages: small ? 1 : 2, hasNext: !small && index === 0,
    countsByGroup: { active: count, done: count, action: 0, review: count, client: 0, system: 0 }, actionRequiredCount: 0, actionRequiredPreview: [] };
}

if (process.env.MAIL_TESTS !== '1') {
  test('Формы: готовность, ошибки после blur, inputmode и повтор после сетевого отказа', async ({ page }) => {
    let posts = 0;
    await page.route('**/api/v1/auth/options', route => route.fulfill({ json: { demoAccounts: false } }));
    await page.route('**/api/v1/auth/sign-up', route => { posts++; return route.fulfill({ status: 503, json: { message: 'Проверочный отказ сети' } }); });
    await page.goto('/sign-up');
    const submit = page.getByRole('button', { name: 'Подключить', exact: true });
    await expect(submit).toBeDisabled();
    await page.getByRole('checkbox').check();
    await expect(submit).toBeDisabled();
    const inn = page.getByLabel('ИНН компании или ИП', { exact: false });
    await expect(inn).toHaveAttribute('inputmode', 'numeric');
    const name = page.getByLabel('Ваше имя', { exact: false });
    await name.focus(); await page.keyboard.press('Tab');
    await expect(name).toHaveAttribute('aria-invalid', 'true');
    await name.fill('Alice Example');
    await expect(name).toHaveAttribute('aria-invalid', 'false');
    await inn.fill('000100000004');
    await page.getByLabel('Email', { exact: false }).fill('invalid');
    await page.getByLabel('Пароль', { exact: false }).fill('Demo2026');
    await expect(page.getByText('Укажите корректный email')).toBeVisible();
    await expect(submit).toBeDisabled();
    await page.getByLabel('Email', { exact: false }).fill('fixture@example.test');
    await expect(submit).toBeEnabled();
    await page.getByLabel('Пароль', { exact: false }).fill('A' + 'я'.repeat(36) + '1');
    await page.getByLabel('Пароль', { exact: false }).blur();
    await expect(submit).toBeDisabled();
    await expect(page.getByText(/максимум 72 байта/)).toBeVisible();
    await page.getByLabel('Пароль', { exact: false }).fill('Demo2026');
    await page.getByLabel('Пароль', { exact: false }).press('Enter');
    await expect(page.getByRole('alert')).toContainText('Проверочный отказ сети');
    expect(posts).toBe(1);
    await expect(name).toHaveValue('Alice Example');
    await expect(submit).toBeEnabled();
    await submit.click(); await expect.poll(() => posts).toBe(2);
    await noHorizontalOverflow(page);
  });

  for (const bank of [false, true]) test(`${bank ? 'Банк' : 'Клиент'}: поиск не прокручивает, явная страница прокручивает один раз`, async ({ page }) => {
    await session(page, bank);
    await page.route('**/api/v1/applications/page?*', async route => {
      const query = new URL(route.request().url()).searchParams;
      await new Promise(resolve => setTimeout(resolve, 120));
      await route.fulfill({ json: result(query.get('group')!, Number(query.get('page') ?? 0), false, bank) });
    });
    await page.goto(bank ? '/bank' : '/client');
    const search = page.getByLabel(bank ? 'Поиск по номеру, компании или ИНН' : 'Поиск по номеру или продукту', { exact: true });
    await expect(search).toBeVisible();
    await expect(page.getByRole('region', { name: 'Список заявок' }).first()).toHaveAttribute('aria-busy', 'false');
    await page.evaluate(() => { const native = window.scrollTo.bind(window); (window as unknown as { listScrollCalls: unknown[] }).listScrollCalls = []; window.scrollTo = ((...args: unknown[]) => { (window as unknown as { listScrollCalls: unknown[] }).listScrollCalls.push(args); (native as (...args: unknown[]) => void)(...args); }) as typeof window.scrollTo; });
    for (const value of ['78', '780', '']) {
      await search.fill(value);
      await page.waitForTimeout(650);
      await expect(search).toBeFocused();
      expect(await page.evaluate(() => (window as unknown as { listScrollCalls: unknown[] }).listScrollCalls.length)).toBe(0);
    }
    await page.getByRole('button', { name: 'Далее', exact: true }).first().click();
    await expect.poll(() => page.evaluate(() => (window as unknown as { listScrollCalls: unknown[] }).listScrollCalls.length)).toBe(1);
    await page.waitForTimeout(600);
    expect(await page.evaluate(() => (window as unknown as { listScrollCalls: unknown[] }).listScrollCalls.length)).toBe(1);
    await noHorizontalOverflow(page);
  });

  test('Клиент: обе страницы нормализуются сразу и при общем применении двух буферов', async ({ page }) => {
    await session(page);
    let shrink = true;
    await page.route('**/api/v1/applications/page?*', route => {
      const query = new URL(route.request().url()).searchParams;
      return route.fulfill({ json: result(query.get('group')!, Number(query.get('page') ?? 0), shrink) });
    });
    await page.goto('/client?activePage=1&donePage=1&q=keep');
    await expect.poll(() => new URL(page.url()).searchParams.get('activePage')).toBe('0');
    await expect.poll(() => new URL(page.url()).searchParams.get('donePage')).toBe('0');
    await expect(page.locator('#client-active')).toHaveAttribute('aria-busy', 'false');
    await expect(page.locator('#client-done')).toHaveAttribute('aria-busy', 'false');
    shrink = false;
    await page.goto('/client?activePage=1&donePage=1&q=keep');
    await expect(page.locator('#client-active')).toHaveAttribute('aria-busy', 'false');
    await expect(page.locator('#client-done')).toHaveAttribute('aria-busy', 'false');
    shrink = true;
    await page.getByRole('button', { name: 'Показать обновления' }).click();
    await expect.poll(() => new URL(page.url()).searchParams.get('activePage')).toBe('0');
    await expect.poll(() => new URL(page.url()).searchParams.get('donePage')).toBe('0');
    await expect(page.locator('#client-active')).toHaveAttribute('aria-busy', 'false');
    await expect(page.locator('#client-done')).toHaveAttribute('aria-busy', 'false');
  });
}
