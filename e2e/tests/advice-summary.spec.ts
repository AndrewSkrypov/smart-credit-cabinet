import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';
import { noHorizontalOverflow } from '../helpers/cabinet.js';

const id = 'a0000000-0000-4000-8000-000000000002';
async function fixture(page: Page) {
  let status = 'MANUAL_REVIEW';
  await page.clock.install();
  await page.addInitScript(() => localStorage.setItem('cabinet.session', JSON.stringify({ token: 'isolated-summary-fixture', user: { id: 'ui', role: 'UNDERWRITER', fullName: 'Проверка', email: 'fixture@example.test' } })));
  // No requests reach a user's backend, including chat read cursors and summary calls.
  await page.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname;
    const company = { name: 'ООО «Пример»', inn: '000100000004' };
    const application = { id, number: 101, status, statusChangedAt: '2026-10-26T00:00:00Z', company,
      product: { code: 'WORKING_CAPITAL', name: 'Оборотный кредит' }, purposeTitle: 'Закупка товара', amount: 1000000,
      termMonths: 12, allowedActions: [], documents: [], process: { offer: null, loan: null, outcome: null } };
    return route.fulfill({ json: path === `/api/v1/applications/${id}` ? application
      : path === `/api/v1/underwriting/applications/${id}` ? { applicationId: id, actions: [], latestDecision: null, automaticDecision: null, registry: null, tax: null, bureau: null, bank: null, discrepancies: [] }
        : path.endsWith('/advice') ? { applicationId: id, engine: 'Сеть правил', modelVersion: 2, rulesFired: 1, rulesTotal: 2,
          verdict: status === 'DISBURSED' ? 'Кредит уже выдан; новые варианты не рассчитываются.' : 'Идёт ручное рассмотрение.',
          items: [{ code: 'CURRENT', kind: 'INFO', title: 'Актуальный статус', text: status, because: [] }] }
          : path.endsWith('/unread-count') ? { unread: 0, count: 0 }
            : path.endsWith('/assistant/status') ? { mode: 'RULES' }
              : path.endsWith('/messages') ? { messages: [], lastSeq: 0, readSeq: 0, unreadCount: 0, writable: false, hasOlder: false, hasNewer: false } : [] });
  });
  return async () => {
    status = 'DISBURSED';
    await page.clock.runFor(10000);
    await expect(page.getByText('Кредит уже выдан; новые варианты не рассчитываются.')).toBeVisible();
  };
}
const answer = (text: string) => ({ mode: 'RULES', text, sources: [], link: null });

if (process.env.MAIL_TESTS !== '1') {
  test('Готовый пересказ сбрасывается при смене статуса той же заявки', async ({ page }) => {
    const finish = await fixture(page); let summaries = 0;
    await page.route('**/api/v1/assistant/underwriting-summary', route => route.fulfill({ json: answer(++summaries === 1 ? 'Старый пересказ: увеличить срок' : 'Новый пересказ: кредит уже выдан') }));
    await page.goto(`/bank/applications/${id}`);
    await page.getByRole('button', { name: 'Кратко словами', exact: true }).click();
    await expect(page.getByText('Старый пересказ: увеличить срок')).toBeVisible();
    await finish();
    await expect(page.getByText('Старый пересказ: увеличить срок')).toHaveCount(0);
    await page.getByRole('button', { name: 'Кратко словами', exact: true }).click();
    await expect(page.getByText('Новый пересказ: кредит уже выдан')).toBeVisible();
    await noHorizontalOverflow(page);
  });

  test('Поздний пересказ прежнего статуса не заменяет новый и не снимает его busy', async ({ page }) => {
    const finish = await fixture(page); let calls = 0; let releaseOld!: () => void; let releaseNew!: () => void;
    const old = new Promise<void>(done => { releaseOld = done; }); const fresh = new Promise<void>(done => { releaseNew = done; });
    await page.route('**/api/v1/assistant/underwriting-summary', async route => {
      const index = ++calls; await (index === 1 ? old : fresh);
      return route.fulfill({ json: answer(index === 1 ? 'Поздний пересказ: увеличить срок' : 'Свежий пересказ: кредит уже выдан') });
    });
    await page.goto(`/bank/applications/${id}`);
    await page.getByRole('button', { name: 'Кратко словами', exact: true }).click(); await expect.poll(() => calls).toBe(1);
    await finish();
    await page.getByRole('button', { name: 'Кратко словами', exact: true }).click(); await expect.poll(() => calls).toBe(2);
    const oldResponse = page.waitForResponse(response => response.url().endsWith('/assistant/underwriting-summary'));
    releaseOld(); await oldResponse; await page.clock.runFor(100);
    await expect(page.getByText('Поздний пересказ: увеличить срок')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Готовлю сводку…', exact: true })).toBeDisabled();
    releaseNew(); await expect(page.getByText('Свежий пересказ: кредит уже выдан')).toBeVisible();
    await noHorizontalOverflow(page);
  });
}
