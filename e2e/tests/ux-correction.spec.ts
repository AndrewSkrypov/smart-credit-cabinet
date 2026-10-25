import { test, expect } from '@playwright/test';
import { login, noHorizontalOverflow } from '../helpers/cabinet.js';
import { setTimeout as pause } from 'node:timers/promises';

// Explicit UI fixtures. These checks prove layout/navigation, not credit pipeline execution.
const row = (n: number, status = 'REJECTED') => ({ id: `a0000000-0000-4000-8000-${String(n).padStart(12, '0')}`, number: 70000 + n,
  productName: 'Оборотный кредит', companyName: 'Тестовая компания', companyInn: '000100000004', status,
  amount: 1000000, termMonths: 12, createdAt: '2026-10-25T00:00:00Z', statusChangedAt: '2026-10-25T00:00:00Z' });
const response = (items: ReturnType<typeof row>[], page = 0, count = items.length, bank = false) => ({ items, page, size: bank ? 20 : 10,
  totalElements: count, totalPages: Math.ceil(count / (bank ? 20 : 10)), hasNext: (page + 1) * (bank ? 20 : 10) < count,
  countsByGroup: bank ? { review: 0, client: 0, system: 0, done: count } : { active: 0, done: count, action: 0 },
  actionRequiredCount: 0, actionRequiredPreview: [] });

if (process.env.MAIL_TESTS !== '1') {
  // Replenish the real gateway's 30 auth requests/minute budget between these short UI fixtures.
  test.beforeEach(async () => { await pause(2200); });
  test('Клиент без активных заявок видит завершённые, карточки main и ограниченную загрузку', async ({ page }) => {
    const reads: URL[] = [];
    await page.route('**/api/v1/applications/page?*', route => {
      const url = new URL(route.request().url()); reads.push(url);
      const done = url.searchParams.get('group') === 'done';
      return route.fulfill({ json: { ...response(done ? Array.from({ length: 10 }, (_, i) => row(i + 1)) : [], 0, done ? 21 : 0), countsByGroup: { active: 0, done: 21, action: 0 } } });
    });
    await login(page, 'client');
    await expect(page.getByRole('heading', { name: 'Завершённые', exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'В работе', exact: true })).toHaveCount(0);
    await expect(page.locator('#client-done a[href^="/client/applications/"]')).toHaveCount(10);
    await expect(page.getByRole('heading', { name: 'Представители компании', exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Нужны деньги на бизнес?', exact: true })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Подобрать финансирование', exact: true })).toBeVisible();
    expect(reads.some(url => url.searchParams.get('group') === 'active')).toBeTruthy();
    expect(reads.some(url => url.searchParams.get('group') === 'done')).toBeTruthy();
    expect(reads.every(url => Number(url.searchParams.get('size')) <= 10)).toBeTruthy();
    await noHorizontalOverflow(page);
  });

  test('Банк: медленная страница сохраняет геометрию и счётчики; Далее и Back прокручивают по-разному', async ({ page }, info) => {
    let release: (() => void) | undefined;
    let pending = false;
    await page.route('**/api/v1/applications/page?*', async route => {
      const query = new URL(route.request().url()).searchParams;
      if (query.get('group') !== 'done') return route.fulfill({ json: { ...response([], 0, 0, true), countsByGroup: { review: 0, client: 0, system: 0, done: 60 } } });
      const number = Number(query.get('page') ?? 0);
      if (number === 1 && !pending) {
        pending = true;
        await new Promise<void>(resolve => { release = resolve; });
      }
      await route.fulfill({ json: response(Array.from({ length: 20 }, (_, i) => row(number * 20 + i + 1)), number, 60, true) });
    });
    await login(page, 'manager');
    await page.goto('/bank?group=done&page=0&size=20');
    const list = page.locator('#bank-list');
    await expect(list).toHaveAttribute('aria-busy', 'false');
    const before = await list.boundingBox();
    await page.getByRole('button', { name: 'Далее', exact: true }).click();
    await expect(list).toHaveAttribute('aria-busy', 'true');
    const during = await list.boundingBox();
    expect(Math.abs(during!.height - before!.height)).toBeLessThanOrEqual(2);
    await expect(page.getByRole('tab', { name: 'Выданные и закрытые · 60', exact: true })).toBeVisible();
    await expect(list.getByText('№ 70001', { exact: true })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Далее', exact: true })).toBeDisabled();
    expect(release).toBeDefined(); release!();
    await expect(list).toHaveAttribute('aria-busy', 'false');
    await expect.poll(async () => (await list.boundingBox())!.y).toBeGreaterThanOrEqual(0);
    const inset = await page.locator('header').evaluate(header => header.getBoundingClientRect().bottom + 16);
    await expect.poll(async () => Math.abs((await list.boundingBox())!.y - inset)).toBeLessThanOrEqual(2);
    await page.evaluate(() => window.scrollTo(0, 900));
    const rows = info.project.name === 'desktop' ? list.locator('tbody tr') : list.locator('li button');
    const index = await rows.evaluateAll(elements => elements.findIndex(element => { const box = element.getBoundingClientRect(); return box.top >= (document.querySelector('header')?.getBoundingClientRect().bottom ?? 0) + 16 && box.bottom < innerHeight - 30; }));
    expect(index).toBeGreaterThanOrEqual(0);
    const position = await page.evaluate(() => scrollY);
    const target = (await rows.nth(index).boundingBox())!;
    await page.mouse.click(target.x + target.width / 2, target.y + target.height / 2);
    await expect(page).toHaveURL(/\/bank\/applications\//);
    await page.goBack();
    await expect(list).toHaveAttribute('aria-busy', 'false');
    await expect.poll(async () => Math.abs(await page.evaluate(() => scrollY) - position)).toBeLessThanOrEqual(2);
    await noHorizontalOverflow(page);
  });

  test('Клиент: страницы завершённых и возврат из карточки сохраняют отдельную позицию', async ({ page }) => {
    await page.route('**/api/v1/applications/page?*', route => {
      const query = new URL(route.request().url()).searchParams;
      const done = query.get('group') === 'done', number = Number(query.get('page') ?? 0);
      return route.fulfill({ json: { ...response(done ? Array.from({ length: 10 }, (_, i) => row(number * 10 + i + 1)) : [], number, done ? 21 : 0),
        countsByGroup: { active: 0, done: 21, action: 0 } } });
    });
    await login(page, 'client');
    await expect(page.locator('#client-done a[href^="/client/applications/"]')).toHaveCount(10);
    await page.getByRole('button', { name: 'Далее', exact: true }).click();
    await expect(page).toHaveURL(/donePage=1/);
    const list = page.locator('#client-done');
    await expect(list).toHaveAttribute('aria-busy', 'false');
    const inset = await page.locator('header').evaluate(header => header.getBoundingClientRect().bottom + 16);
    await expect.poll(async () => Math.abs((await list.boundingBox())!.y - inset)).toBeLessThanOrEqual(2);
    await page.evaluate(() => scrollTo(0, 700));
    const rows = list.locator('a[href^="/client/applications/"]');
    const index = await rows.evaluateAll(elements => elements.findIndex(element => { const b = element.getBoundingClientRect(); return b.top >= (document.querySelector('header')?.getBoundingClientRect().bottom ?? 0) + 16 && b.bottom < innerHeight - 30; }));
    expect(index).toBeGreaterThanOrEqual(0);
    const position = await page.evaluate(() => scrollY);
    const target = (await rows.nth(index).boundingBox())!;
    await page.mouse.click(target.x + target.width / 2, target.y + target.height / 2);
    await expect(page).toHaveURL(/\/client\/applications\//);
    await page.goBack();
    await expect(page).toHaveURL(/donePage=1/);
    await expect(list).toHaveAttribute('aria-busy', 'false');
    await expect.poll(async () => Math.abs(await page.evaluate(() => scrollY) - position)).toBeLessThanOrEqual(2);
  });

  test('Наверх справа, единый нативный scroller и спокойная информация о подписи', async ({ page }) => {
    await page.goto('/');
    await page.evaluate(() => scrollTo(0, document.documentElement.scrollHeight));
    const top = page.getByRole('button', { name: 'Наверх', exact: true });
    await expect(top).toBeVisible();
    const box = await top.boundingBox(); const viewport = page.viewportSize()!;
    expect(viewport.width - box!.x - box!.width).toBeLessThanOrEqual(33);
    expect(box!.width).toBeLessThanOrEqual(48);
    expect(await page.evaluate(() => document.scrollingElement?.tagName)).toBe('HTML');
    await top.click();
    await expect.poll(() => page.evaluate(() => scrollY)).toBeLessThanOrEqual(1);
    await login(page, 'client');
    await expect(page.getByText('С правом подписи', { exact: true })).toHaveCount(0);
    await page.getByRole('link', { name: 'Профиль', exact: true }).click();
    await expect(page.getByText('Подписание договоров: доступно', { exact: true })).toBeVisible();
    await noHorizontalOverflow(page);
  });
}
