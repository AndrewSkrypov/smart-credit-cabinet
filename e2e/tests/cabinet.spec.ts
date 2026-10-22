import { test, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import {
  assertNotification, clearActive, createScenario, headers, login, noHorizontalOverflow,
  scenario, signIn, syntheticInn, waitStatus, type Application, type Session
} from '../helpers/cabinet.js';

test.beforeAll(async ({ browser }, info) => {
  console.log(`Окружение ${info.project.name}: браузер ${browser.version()}, Node ${process.version}`);
});

test('Синтетическая компания регистрируется с согласием и корректным ИНН', async ({ page }) => {
  await page.goto('/');
  await noHorizontalOverflow(page);
  await page.goto('/sign-up');
  const inn = syntheticInn();
  await page.getByLabel('ИНН компании или ИП').fill(inn);
  await page.getByLabel('Ваше имя').fill('Представитель тестовой компании');
  await page.getByLabel('Email').fill(`e2e-${randomUUID()}@example.test`);
  await page.getByLabel('Пароль').fill('Test2026safe');
  await expect(page.getByRole('button', { name: 'Подключить', exact: true })).toBeDisabled();
  await page.getByRole('checkbox', { name: 'Согласен на обработку персональных данных', exact: true }).check();
  const registered = page.waitForResponse((response) => response.url().endsWith('/auth/sign-up') && response.request().method() === 'POST');
  await page.getByRole('button', { name: 'Подключить', exact: true }).click();
  expect((await registered).status()).toBe(200);
  await expect(page).toHaveURL(/\/client$/);
  await expect(page.getByText(inn, { exact: false })).toBeVisible();
  await noHorizontalOverflow(page);
});

test('Клиент проходит подбор, черновик, решение, подписание и выдачу', async ({ page, request }) => {
  const session = await signIn(request, 'client');
  await clearActive(request, session);
  await login(page, 'client');
  await noHorizontalOverflow(page);
  await page.goto('/client/new');
  await page.getByRole('button', { name: 'Закупка товара или сырья', exact: true }).click();
  await page.getByLabel('Сумма', { exact: false }).fill('1000000');
  await page.getByRole('button', { name: 'Подобрать', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Подходящие продукты', exact: false })).toBeVisible();
  await noHorizontalOverflow(page);
  const product = page.getByRole('heading', { name: 'Кредит на пополнение оборота', exact: true });
  const card = product.locator('..').locator('..').locator('..');
  await card.getByRole('button', { name: 'Оформить заявку', exact: true }).click();
  await expect(page).toHaveURL(/\/client\/applications\/[\w-]+$/);
  const id = page.url().split('/').at(-1)!;
  try {
    await page.getByLabel('Выручка за последние 12 месяцев').fill('14400000');
    await page.getByLabel('Платежи по кредитам в других банках, в месяц').fill('30000');
    await page.getByRole('checkbox', { name: 'Согласен на запрос кредитной истории компании в бюро кредитных историй' }).check();
    await expect(page.getByText('Сохранено', { exact: true })).toBeVisible();
    await noHorizontalOverflow(page);
    const submitted = page.waitForResponse((response) => response.url().endsWith(`/${id}/submit`) && response.request().method() === 'POST');
    await page.getByRole('button', { name: 'Отправить в банк', exact: true }).click();
    expect((await submitted).ok()).toBeTruthy();
    const app = await waitStatus(request, session, id, ['OFFER_ISSUED']);
    expect(app.process.offer?.kind).toBe('APPROVED');
    await expect(page.getByRole('heading', { name: 'Заявка одобрена', exact: true })).toBeVisible();
    await assertNotification(request, page, session, app);
    await page.getByRole('button', { name: 'Принять и перейти к подписанию', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Подписание договора', exact: true })).toBeVisible();
    const code = page.getByText(/^\d{6}$/, { exact: true });
    await expect(code).toBeVisible();
    await page.getByLabel('Код из 6 цифр', { exact: true }).fill((await code.innerText()).trim());
    await page.getByRole('button', { name: 'Подписать', exact: true }).click();
    await waitStatus(request, session, id, ['DISBURSED']);
    await expect(page.getByRole('heading', { name: 'Кредит выдан', exact: true })).toBeVisible();
    const schedule = page.getByRole('table', { name: 'График платежей', exact: true });
    await expect(schedule).toBeVisible();
    await expect(schedule.getByRole('row')).toHaveCount(13);
    await noHorizontalOverflow(page);
  } finally {
    const response = await request.get(`/api/v1/applications/${id}`, { headers: headers(session) });
    const current = await response.json();
    if (current.allowedActions?.includes('WITHDRAW')) {
      const cleanup = await request.post(`/api/v1/applications/${id}/withdraw`, { headers: headers(session) });
      expect(cleanup.ok()).toBeTruthy();
    }
  }
});

test('Технопарк загружает запрошенные документы через интерфейс', async ({ page, request }) => {
  const { session, app } = await createScenario(request, 'technopark');
  try {
    const requested = await waitStatus(request, session, app.id, ['INFO_REQUESTED']);
    const items = requested.process.infoRequest?.items.filter((item) => item.documentType) ?? [];
    expect(items.length).toBeGreaterThan(0);
    await login(page, 'technopark');
    await page.goto(`/client/applications/${app.id}`);
    await expect(page.getByRole('heading', { name: 'Банк просит дополнить заявку', exact: true })).toBeVisible();
    for (const [index] of items.entries()) {
      const chooser = page.waitForEvent('filechooser');
      await page.getByRole('button', { name: 'Загрузить', exact: true }).first().click();
      await (await chooser).setFiles({ name: `statement-${index}.pdf`, mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.7\nSynthetic test statement\n%%EOF\n') });
      await expect(page.getByRole('button', { name: 'Загружаем…', exact: true })).toHaveCount(0);
      await expect(page.getByRole('button', { name: 'Загрузить', exact: true })).toHaveCount(items.length - index - 1);
    }
    await noHorizontalOverflow(page);
    await page.getByRole('button', { name: 'Отправить в банк', exact: true }).click();
    await waitStatus(request, session, app.id, ['OFFER_ISSUED']);
    await expect(page.getByRole('heading', { name: 'Заявка одобрена', exact: true })).toBeVisible();
  } finally {
    await clearActive(request, session);
  }
});

for (const [account, reasonCode, reason] of [['romashka', 'SF_STATUS', /компания не действует/i], ['belova', 'R_SCORE', /риск|рейтинг|балл/i]] as const) {
  test(`Клиент ${account} видит причину отказа`, async ({ page, request }) => {
    const { session, app } = await createScenario(request, account);
    const rejected = await waitStatus(request, session, app.id, ['REJECTED']);
    expect(rejected.process.outcome?.reasonCode).toBe(reasonCode);
    await login(page, account);
    await page.goto(`/client/applications/${app.id}`);
    await expect(page.getByRole('heading', { name: rejected.process.outcome!.reasonTitle, exact: true })).toBeVisible();
    await expect(page.getByRole('list').filter({ hasText: reason }).first()).toBeVisible();
    await noHorizontalOverflow(page);
  });
}

test('Андеррайтер меняет условия, менеджер не может одобрить, приходят уведомления', async ({ page, browser, request }) => {
  const { session, app } = await createScenario(request, 'logistic');
  const otherContext = await browser.newContext({ baseURL: test.info().project.use.baseURL, locale: 'ru-RU' });
  try {
    const manual = await waitStatus(request, session, app.id, ['MANUAL_REVIEW']);
    const managerPage = await otherContext.newPage();
    const manager = await login(managerPage, 'manager');
    const assessment = managerPage.waitForResponse((response) => response.url().endsWith(`/underwriting/applications/${app.id}`));
    await managerPage.goto(`/bank/applications/${app.id}`);
    expect((await assessment).status()).toBe(200);
    await expect(managerPage.getByRole('button', { name: 'Одобрить', exact: true })).toHaveCount(0);
    const forbidden = await request.post(`/api/v1/underwriting/applications/${app.id}/approve`, { headers: headers(manager), data: { comment: 'Проверка запрета' } });
    expect(forbidden.status()).toBe(403);
    const underwriter = await login(page, 'underwriter');
    await assertNotification(request, page, underwriter, manual);
    await page.goto(`/bank/applications/${app.id}`);
    await page.getByRole('button', { name: 'Другие условия', exact: true }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('Сумма', { exact: false }).fill('2000000');
    await dialog.getByLabel(/Обоснование|Комментарий/).fill('Уменьшаем сумму после проверки долговой нагрузки');
    await dialog.getByRole('button', { name: 'Сохранить решение', exact: true }).click();
    const counter = await waitStatus(request, session, app.id, ['OFFER_ISSUED']);
    expect(counter.process.offer).toMatchObject({ kind: 'COUNTER', amount: 2000000 });
    await noHorizontalOverflow(page);
    await page.getByRole('button', { name: 'Выйти', exact: true }).click();
    await login(page, 'logistic');
    await page.goto(`/client/applications/${app.id}`);
    await expect(page.getByRole('heading', { name: 'Банк предлагает другие условия', exact: true })).toBeVisible();
    await expect(page.getByRole('table', { name: 'Сравнение условий', exact: true })).toContainText(/2\s*000\s*000/);
    await expect(page.getByRole('alert').filter({ hasText: 'Уменьшаем сумму после проверки долговой нагрузки' })).toBeVisible();
    await noHorizontalOverflow(page);
    await assertNotification(request, page, session, counter);
  } finally {
    await otherContext.close();
    await clearActive(request, session);
  }
});

test('Риск-администратор создаёт версию правил и видит реальную воронку', async ({ page, request }) => {
  const risk = await login(page, 'risk');
  const beforeResponse = await request.get('/api/v1/rules', { headers: headers(risk) });
  expect(beforeResponse.ok()).toBeTruthy();
  const before = await beforeResponse.json();
  const prepared = await createScenario(request, 'romashka');
  await waitStatus(request, prepared.session, prepared.app.id, ['REJECTED']);
  try {
    await page.goto('/bank/rules');
    await page.getByLabel('Предельная долговая нагрузка', { exact: false }).fill(String(before.params.maxLoad * 100 - 1));
    await page.getByLabel('Что и зачем меняется', { exact: false }).fill('Проверка версионирования и воспроизводимости');
    await page.getByRole('button', { name: 'Сохранить новую версию', exact: true }).click();
    await expect(page.getByText(`Сохранена версия ${before.version + 1}. Новые оценки идут по ней.`, { exact: true })).toBeVisible();
    const after = await (await request.get('/api/v1/rules', { headers: headers(risk) })).json();
    expect(after.version).toBe(before.version + 1);
    expect(after.params.maxLoad).toBeCloseTo(before.params.maxLoad - 0.01);
    await noHorizontalOverflow(page);
    const funnelResponse = page.waitForResponse((response) => response.url().includes('/analytics/funnel'));
    await page.goto('/bank/analytics');
    const funnel = await (await funnelResponse).json();
    expect(funnel.funnel[0].applications).toBeGreaterThan(0);
    await expect(page.getByRole('heading', { name: 'Аналитика конвейера', exact: true })).toBeVisible();
    await expect(page.getByText('Отправлено заявок', { exact: true })).toBeVisible();
    await noHorizontalOverflow(page);
  } finally {
    const restored = await request.post('/api/v1/rules', { headers: headers(risk), data: { params: before.params, comment: 'Восстановление исходных параметров после проверки' } });
    expect(restored.ok(), await restored.text()).toBeTruthy();
  }
});

test('БКИ выключается на пульте, заявка ждёт и продолжается после включения', async ({ page, request }) => {
  const initial = await (await request.get('/sim/control')).json();
  const bureau = initial.sources.find((source: { source: string }) => source.source === 'BUREAU');
  expect(bureau).toBeTruthy();
  let session: Session | undefined;
  try {
    await page.goto('/sim/');
    const source = page.getByText(bureau.title, { exact: true }).locator('..');
    await source.getByRole('button', { name: 'Недоступен', exact: true }).click();
    await expect(source.getByRole('button', { name: 'Недоступен', exact: true })).toHaveAttribute('aria-pressed', 'true');
    const created = await createScenario(request, 'client');
    session = created.session;
    await waitStatus(request, session, created.app.id, ['ENRICHMENT']);
    await expect.poll(async () => {
      const response = await request.get(`/api/v1/applications/${created.app.id}/events`, { headers: headers(session!) });
      return (await response.json()).filter((entry: { event: string }) => entry.event === 'SOURCE_FAILED').length;
    }, { timeout: 30_000 }).toBeGreaterThan(0);
    const waiting = await (await request.get(`/api/v1/applications/${created.app.id}`, { headers: headers(session) })).json();
    expect(waiting.status).toBe('ENRICHMENT');
    await source.getByRole('button', { name: 'Работает', exact: true }).click();
    await expect(source.getByRole('button', { name: 'Работает', exact: true })).toHaveAttribute('aria-pressed', 'true');
    await waitStatus(request, session, created.app.id, ['OFFER_ISSUED']);
  } finally {
    const restored = await request.put('/sim/control/sources/BUREAU', { data: { mode: bureau.mode } });
    expect(restored.ok()).toBeTruthy();
    if (session) await clearActive(request, session);
  }
});
