import { test, expect } from '@playwright/test';
import { headers, login, noHorizontalOverflow } from '../helpers/cabinet.js';

if (process.env.MAIL_TESTS !== '1') {
  test('Контролируемый API: A → B → A очищает обновления, превью остаются ограниченными', async ({ page }) => {
    await page.route('**/api/v1/auth/sign-in', async route => {
      const response = await route.fetch();
      const session = await response.json(); session.user.canSign = false;
      await route.fulfill({ response, json: session }); // UI fixture only; does not alter JWT or prove server authorization.
    });
    await page.route('**/api/v1/companies/me', async route => {
      const response = await route.fetch(); const company = await response.json();
      company.members = Array.from({ length: 7 }, (_, i) => ({ ...company.members[0], id: `member-${i}`, fullName: `Представитель ${i}`, canSign: i === 0 }));
      await route.fulfill({ response, json: company });
    });
    let reads = 0;
    const row = { id: 'a0000000-0000-4000-8000-000000000001', number: 70001, productName: 'Контрольный продукт',
      companyName: 'Синтетическая компания', companyInn: '000100000004', status: 'DRAFT',
      amount: 1000000, termMonths: 12, createdAt: '2026-10-25T00:00:00Z', statusChangedAt: '2026-10-25T00:00:00Z' };
    await page.route('**/api/v1/applications/page?*', route => {
      const changed = ++reads === 2;
      return route.fulfill({ json: { items: [{ ...row, productName: changed ? 'Новые условия B' : 'Контрольный продукт A' }],
        page: 0, size: 10, totalElements: 1, totalPages: 1, hasNext: false, countsByGroup: { active: 1, done: 0, action: 7 },
        actionRequiredCount: 7, actionRequiredPreview: Array.from({ length: 5 }, (_, i) => ({ ...row, id: `action-${i}`, number: 80001 + i })) } });
    });
    await login(page, 'client');
    await expect(page.getByText('Контрольный продукт A', { exact: true })).toBeVisible();
    await expect(page.getByText(/Без права подписи · договор подписывает/)).toBeVisible();
    await expect(page.getByText('Представитель 4', { exact: true })).toBeVisible();
    await expect(page.getByText('Представитель 5', { exact: true })).toHaveCount(0);
    await expect(page.getByRole('link', { name: 'Все сотрудники · 7' })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Нужно ваше действие · 7' })).toBeVisible();
    await expect(page.locator('a[href^="/client/applications/action-"]')).toHaveCount(5);
    await expect(page.getByRole('button', { name: 'Показать обновления' })).toBeVisible({ timeout: 12000 });
    await expect(page.getByText('Новые условия B', { exact: true })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Показать обновления' })).toHaveCount(0, { timeout: 12000 });
    await expect(page.getByText('Контрольный продукт A', { exact: true })).toBeVisible();
  });

  test('Помощник: 12 вопросов, ограниченный контекст и повтор без дублирования', async ({ page }) => {
    const calls: Array<{ question: string; applicationId: string | null; history: Array<{ role: string; text: string }> }> = [];
    let fail = false;
    await page.route('**/api/v1/assistant/status', route => route.fulfill({ json: { mode: 'RULES', model: null } }));
    await page.route('**/api/v1/assistant/messages', async route => {
      const body = route.request().postDataJSON(); calls.push(body);
      if (fail) { fail = false; await route.fulfill({ status: 503, json: { message: 'Проверка повтора' } }); return; }
      await route.fulfill({ json: { text: `Ответ: ${body.question} ${'а'.repeat(1100)}`, mode: 'RULES', sources: [] } });
    });
    await login(page, 'client');
    await page.getByRole('button', { name: 'Открыть помощника' }).click();
    for (let i = 1; i <= 12; i++) {
      await page.getByLabel('Ваш вопрос', { exact: true }).fill(`Вопрос ${i}`);
      await page.getByRole('button', { name: 'Спросить', exact: true }).click();
      await expect(page.getByText(`Ответ: Вопрос ${i} ${'а'.repeat(1100)}`, { exact: true })).toHaveCount(1);
      const call = calls[i - 1];
      expect(call.question).toBe(`Вопрос ${i}`); expect(call.applicationId).toBeNull();
      expect(call.history).toHaveLength(Math.min(4, (i - 1) * 2));
      expect(call.history.every(t => t.text.length <= 1000 && t.text !== call.question)).toBe(true);
      if (i >= 3) expect(call.history.map(t => t.role)).toEqual(['user', 'assistant', 'user', 'assistant']);
    }
    await expect(page.getByText('Вопрос 1', { exact: true })).toHaveCount(1);
    await expect(page.getByText('Вопрос 12', { exact: true })).toHaveCount(1);
    fail = true;
    await page.getByLabel('Ваш вопрос', { exact: true }).fill('Вопрос с повтором');
    await page.getByRole('button', { name: 'Спросить', exact: true }).click();
    await page.getByRole('button', { name: 'Повторить вопрос' }).click();
    await expect(page.getByText(`Ответ: Вопрос с повтором ${'а'.repeat(1100)}`, { exact: true })).toHaveCount(1);
    await expect(page.getByText('Вопрос с повтором', { exact: true })).toHaveCount(1);
    expect(calls[12]).toEqual(calls[13]);
    await noHorizontalOverflow(page);
  });

  test('История 100 версий: границы DOM, страницы, снимок и фокус при resize', async ({ page, request }, info) => {
    const session = await login(page, 'risk');
    const response = await request.get('/api/v1/rules', { headers: headers(session) });
    expect(response.ok()).toBeTruthy();
    const current = await response.json();
    const versions = Array.from({ length: 100 }, (_, i) => ({ ...current.versions[0], version: i + 1,
      createdAt: '2026-10-25T00:00:00Z', authorName: 'Редактор визуальной фикстуры', comment: 'Длинная причина '.repeat(30) }));
    await page.route('**/api/v1/rules', route => route.fulfill({ json: { ...current, version: 100, versions } }));
    await page.route('**/api/v1/rules/*', route => {
      const version = Number(route.request().url().split('/').at(-1));
      return route.fulfill({ json: { params: current.params, metadata: versions[version - 1] } });
    });
    await page.goto('/bank/rules');
    const history = page.getByRole('region', { name: 'История версий правил: список', exact: true });
    const nav = page.getByRole('navigation', { name: 'История версий правил: страницы', exact: true });
    const desktop = info.project.name === 'desktop';
    await expect(history.getByRole('button', { name: /^Посмотреть версию/ })).toHaveCount(desktop ? 20 : 5);
    const overflow = await history.evaluate(e => ({ y: getComputedStyle(e).overflowY, height: e.clientHeight }));
    if (desktop) { expect(overflow.y).toBe('auto'); expect(overflow.height).toBeLessThanOrEqual(560); }
    else expect(overflow.y).toBe('visible');
    await nav.getByRole('button', { name: 'Далее', exact: true }).click();
    const version = desktop ? 80 : 95;
    const opener = page.getByRole('button', { name: `Посмотреть версию ${version}`, exact: true });
    await opener.click();
    await expect(page.getByRole('dialog')).toContainText('Архивная');
    await expect(page.getByRole('dialog')).toContainText('Сейчас действует версия 100');
    await expect(page.getByRole('dialog')).toContainText('Длинная причина '.repeat(30).trim());
    await page.keyboard.press('Escape');
    await expect(opener).toBeFocused();
    await page.setViewportSize({ width: desktop ? 390 : 1440, height: 720 });
    await expect(opener).toHaveCount(1);
    await noHorizontalOverflow(page);
  });

  test('Кабинет банка сохраняет URL страницы и возврат из карточки', async ({ page }, info) => {
    await login(page, 'manager');
    await page.goto('/bank?group=done&page=0&size=20&sort=number_desc');
    const table = info.project.name === 'desktop' ? page.getByRole('table') : page.getByRole('list', { name: 'Выданные и закрытые', exact: true });
    const rows = info.project.name === 'desktop' ? table.locator('tbody tr') : table.locator('li');
    // On a pristine CI database there may be fewer than 21 rows. Existing business tests create rows first.
    await expect(table).toBeVisible();
    const count = await rows.count();
    expect(count).toBeGreaterThan(0); expect(count).toBeLessThanOrEqual(20);
    const first = await rows.first().textContent();
    await rows.first().click();
    await expect(page).toHaveURL(/\/bank\/applications\//);
    await page.goBack();
    await expect(page).toHaveURL(/group=done.*size=20.*sort=number_desc/);
    await expect(rows.first()).toHaveText(first!);
    await noHorizontalOverflow(page);
  });

  test('Холст документа: семейства маршрутов, четыре края, длинные страницы и модальное окно', async ({ page }, info) => {
    for (const [url, family] of [['/', 'landing'], ['/sign-in', 'auth'], ['/sign-up', 'auth'], ['/password-recovery', 'auth'], ['/reset-password', 'auth'], ['/accept-invitation', 'auth'], ['/access-denied', 'light'], ['/no-such-route', 'light']]) {
      await page.goto(url);
      await expect(page.locator('html')).toHaveAttribute('data-surface', family);
      await noHorizontalOverflow(page);
      for (const [x, y] of [[0, -800], [0, 12000], [-800, 0], [800, 0]]) await page.mouse.wheel(x, y);
      const surface = await page.locator('html').evaluate(e => ({ background: getComputedStyle(e).background, body: getComputedStyle(document.body).backgroundColor }));
      expect(surface.background).not.toBe('none'); expect(surface.body).toBe('rgba(0, 0, 0, 0)');
    }
    await login(page, 'risk');
    for (const url of ['/bank', '/bank/products', '/bank/rules', '/bank/analytics', '/profile']) {
      await page.goto(url);
      await expect(page.locator('html')).toHaveAttribute('data-surface', 'workspace');
      await noHorizontalOverflow(page);
    }
    await page.goto('/bank/rules');
    await page.getByRole('button', { name: /^Посмотреть версию/ }).first().click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await expect(page.locator('html')).toHaveAttribute('data-surface', 'workspace');
    await page.screenshot({ path: info.outputPath('canvas-modal.png') });
    await page.keyboard.press('Escape');
    await page.setViewportSize({ width: 720, height: 450 });
    await noHorizontalOverflow(page); // 1440x900 at a 200% layout-equivalent viewport; not a native gesture claim.
    await page.getByRole('button', { name: 'Выйти', exact: true }).click();
    await expect(page.locator('html')).toHaveAttribute('data-surface', 'auth');
  });
}
