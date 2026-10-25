import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import { noHorizontalOverflow } from '../helpers/cabinet.js';

// Every API request is intercepted. These races must never submit to a user's preview.
const company = { id: 'a0000000-0000-4000-8000-000000000001', name: 'ООО «Пример»', inn: '000100000004', legalForm: 'LEGAL_ENTITY' };
const a = 'a0000000-0000-4000-8000-000000000002';
const b = 'a0000000-0000-4000-8000-000000000003';
const product = { code: 'WORKING_CAPITAL', name: 'Оборотный кредит', version: 1, active: true, minAmount: 100000, maxAmount: 10000000, minTermMonths: 6, maxTermMonths: 36, baseRate: 19, revolving: false };
const application = (id = a) => ({
  id, number: id === a ? 101 : 202, status: 'DRAFT', company, product, currentProduct: product,
  purpose: 'WORKING_CAPITAL', purposeTitle: 'Закупка товара', amount: 1000000, termMonths: 12,
  declaredRevenue12m: 12000000, declaredMonthlyDebtPayments: 0, bureauConsent: false,
  createdAt: '2026-10-25T00:00:00Z', allowedActions: [], editable: true, canUploadDocuments: false,
  submissionProblems: [], requiredDocuments: [], documents: [], estimate: null,
  process: { offer: null, loan: null, outcome: null }
});
async function fixture(page: Page, bank = false) {
  await page.addInitScript(({ company, bank }) => localStorage.setItem('cabinet.session', JSON.stringify({ token: 'isolated-review-fixture', user: { id: 'ui', role: bank ? 'UNDERWRITER' : 'CLIENT', companyId: bank ? null : company.id, fullName: 'Проверка', email: 'fixture@example.test' } })), { company, bank });
  await page.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname;
    return route.fulfill({ json: path === '/api/v1/companies/me' ? company : path.endsWith('/products') ? [product]
      : path.endsWith('/unread-count') ? { unread: 1, count: 1 }
        : path.endsWith('/messages') ? { messages: [], lastSeq: 0, readSeq: 0, unreadCount: 0, writable: false, hasOlder: false, hasNewer: false }
          : path.endsWith('/assistant/status') ? { mode: 'RULES' } : [] });
  });
}

if (process.env.MAIL_TESTS !== '1') {
  test('Черновик отправляет последнюю полную ревизию после задержанного автосохранения', async ({ page }) => {
    await fixture(page);
    let server = application(); let release!: () => void;
    const gate = new Promise<void>(done => { release = done; });
    const writes: Array<Record<string, unknown>> = []; const submitted: Array<typeof server> = [];
    let active = 0; let maxActive = 0;
    await page.route(`**/api/v1/applications/${a}`, async route => {
      if (route.request().method() === 'PUT') {
        const input = route.request().postDataJSON(); writes.push(input); maxActive = Math.max(maxActive, ++active);
        if (writes.length === 1) await gate;
        server = { ...server, ...input }; active--;
      }
      return route.fulfill({ json: server });
    });
    await page.route(`**/api/v1/applications/${a}/submit`, route => {
      submitted.push({ ...server }); return route.fulfill({ json: { ...server, status: 'SUBMITTED', editable: false } });
    });
    await page.goto(`/client/applications/${a}`);
    const amount = page.getByRole('textbox', { name: 'Сумма', exact: true });
    await expect(amount).toHaveAttribute('inputmode', 'numeric');
    await amount.fill('2000000'); await expect.poll(() => writes.length).toBe(1);
    await amount.fill('3000000');
    const term = page.getByRole('textbox', { name: 'Срок, месяцев' });
    await expect(term).toHaveAttribute('inputmode', 'numeric'); await term.fill('24');
    await page.getByRole('textbox', { name: /Выручка за/ }).fill('36000000');
    await page.getByRole('textbox', { name: /Платежи по кредитам/ }).fill('25000');
    await page.getByRole('checkbox', { name: /Согласен на запрос/ }).check();
    await page.getByRole('button', { name: 'Отправить в банк' }).click();
    await expect(amount).toBeDisabled(); expect(submitted).toHaveLength(0); expect(writes).toHaveLength(1);
    release(); await expect.poll(() => submitted.length).toBe(1);
    expect(maxActive).toBe(1); expect(writes.map(input => input.amount)).toEqual([2000000, 3000000]);
    expect(submitted[0]).toMatchObject({ amount: 3000000, termMonths: 24, declaredRevenue12m: 36000000, declaredMonthlyDebtPayments: 25000, bureauConsent: true });
    await noHorizontalOverflow(page);
  });

  test('Переход из уведомления и Back/Forward изолируют медленную клиентскую карточку', async ({ page }) => {
    await fixture(page);
    let release!: () => void; let calls = 0;
    const gate = new Promise<void>(done => { release = done; });
    await page.route('**/api/v1/notifications?*', route => route.fulfill({ json: { items: [{ id: 'notice', type: 'APPLICATION_STATUS', title: 'Перейти к заявке B', body: 'Изолированное уведомление', link: `/client/applications/${b}`, read: true, createdAt: '2026-10-25T00:00:00Z' }], page: 0, size: 10, total: 1, unread: 0 } }));
    await page.route(`**/api/v1/applications/${a}`, async route => { if (++calls === 1) await gate; return route.fulfill({ json: application(a) }); });
    await page.route(`**/api/v1/applications/${b}`, route => route.fulfill({ json: application(b) }));
    await page.goto(`/client/applications/${a}`);
    await page.getByRole('button', { name: /^Уведомления:/ }).click();
    await page.getByRole('dialog', { name: 'Уведомления', exact: true }).getByRole('button', { name: /Перейти к заявке B/ }).click();
    await expect(page.getByRole('heading', { name: 'Заявка № 202' })).toBeVisible();
    release(); await expect(page.getByRole('heading', { name: 'Заявка № 101' })).toHaveCount(0);
    await page.goBack(); await expect(page.getByRole('heading', { name: 'Заявка № 101' })).toBeVisible();
    await page.goForward(); await expect(page.getByRole('heading', { name: 'Заявка № 202' })).toBeVisible();
    await noHorizontalOverflow(page);
  });

  test('Отказ API рекомендаций не скрывает досье; повтор показывает объяснение рейтинга E', async ({ page }, info) => {
    await fixture(page, true); let requests = 0;
    await page.route(`**/api/v1/applications/${a}`, route => route.fulfill({ json: { ...application(), status: 'MANUAL_REVIEW', editable: false } }));
    await page.route(`**/api/v1/underwriting/applications/${a}`, route => route.fulfill({ json: { applicationId: a, actions: [], automaticDecision: null, latestDecision: null, registry: null, tax: null, bureau: null, bank: null, discrepancies: [] } }));
    await page.route(`**/api/v1/underwriting/applications/${a}/advice`, route => ++requests === 1
      ? route.fulfill({ status: 503, json: { message: 'Рекомендации временно недоступны' } })
      : route.fulfill({ json: { applicationId: a, engine: 'Сеть правил', modelVersion: 2, verdict: 'Рейтинг E — отказ по кредитной политике', rulesFired: 2, rulesTotal: 30, items: [{ code: 'RATING_E', kind: 'STOP', title: 'Отказной рейтинг', text: 'Ставка выдачи для E не рассчитывается.', because: ['Сохранённый рейтинг E предусматривает отказ до расчёта ставки.'] }] } }));
    await page.goto(`/bank/applications/${a}`);
    await expect(page.getByRole('heading', { name: 'Заявка № 101' })).toBeVisible();
    await expect(page.getByRole('alert').filter({ hasText: 'Рекомендации временно недоступны' })).toContainText('Основное досье доступно');
    await info.attach('advice-api-error', { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' });
    await page.getByRole('button', { name: 'Повторить', exact: true }).click();
    await expect(page.getByText('Ставка выдачи для E не рассчитывается.')).toBeVisible();
    await page.getByRole('button', { name: 'Почему', exact: true }).click();
    await expect(page.getByText('Сохранённый рейтинг E предусматривает отказ до расчёта ставки.')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Заявка № 101' })).toBeVisible();
    await noHorizontalOverflow(page);
  });
}
