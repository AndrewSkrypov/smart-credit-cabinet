import { expect, test, type APIRequestContext } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { clearActive, headers, login, noHorizontalOverflow, scenario, signIn, waitStatus, type Application, type Session } from '../helpers/cabinet.js';

type AgentCompany = { companyId: string; inn: string };

async function beginAgentDraft(request: APIRequestContext, agent: Session, client: Session) {
  await clearActive(request, client);
  const fixture = await scenario(request, 'client');
  const clients = await request.get('/api/v1/agent/clients', { headers: headers(agent) });
  expect(clients.status(), await clients.text()).toBe(200);
  const companies: AgentCompany[] = await clients.json();
  const company = companies.find(({ inn }) => inn === fixture.inn);
  expect(company, 'ИП Котова закреплён за демо-агентом').toBeDefined();
  const created = await request.post('/api/v1/agent/applications', {
    headers: headers(agent), data: { ...fixture.scenario.request, companyId: company!.companyId }
  });
  expect(created.status(), await created.text()).toBe(201);
  const draft: Application = await created.json();
  expect(draft.status).toBe('DRAFT');
  return draft.id;
}

async function clean(request: APIRequestContext, id: string, agent: Session, client: Session) {
  const response = await request.get(`/api/v1/agent/applications/${id}`, { headers: headers(agent) });
  expect(response.ok()).toBeTruthy();
  const app: Application = await response.json();
  if (app.status === 'DRAFT') {
    expect((await request.post(`/api/v1/agent/applications/${id}/cancel`, { headers: headers(agent) })).ok()).toBeTruthy();
  } else if (!['DISBURSED', 'REJECTED', 'WITHDRAWN', 'CLOSED'].includes(app.status)) {
    await clearActive(request, client);
  }
}

test('Агентский канал API: мандат, подтверждение клиентом, акцепт, ПЭП и выдача', async ({ request }) => {
  test.setTimeout(180_000);
  const agent = await signIn(request, 'agent');
  const other = await signIn(request, 'agent2');
  const client = await signIn(request, 'client');
  expect(agent.user.role).toBe('AGENT');
  const id = await beginAgentDraft(request, agent, client);
  const agentPath = `/api/v1/agent/applications/${id}`;
  const clientPath = `/api/v1/applications/${id}`;
  try {
    expect((await request.get(agentPath, { headers: headers(other) })).status()).toBe(404);
    expect((await request.get(clientPath, { headers: headers(client) })).status()).toBe(404);
    if (process.env.CABINET_AGENT_ROUTE_BOUNDARY === '1') {
      expect((await request.get(clientPath, { headers: headers(agent) })).status()).toBe(403);
      expect((await request.get(clientPath, { headers: headers(other) })).status()).toBe(404);
    }
    expect((await request.get('/api/v1/agent/clients', { headers: headers(client) })).status()).toBe(403);
    const document = await request.post(`${agentPath}/documents?type=TAX_RETURN`, {
      headers: headers(agent), multipart: { file: { name: 'tax-demo.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.7\nSynthetic tax fixture\n%%EOF\n') } }
    });
    expect(document.status(), await document.text()).toBe(201);
    const { id: documentId } = await document.json();
    expect((await request.get(`${agentPath}/documents/${documentId}`, { headers: headers(other) })).status()).toBe(404);

    const agentDraft = await request.get(agentPath, { headers: headers(agent) });
    expect(agentDraft.ok()).toBeTruthy();
    const pending = await request.post(`${agentPath}/request-confirmation`, {
      headers: headers(agent), data: { expectedVersion: (await agentDraft.json()).version, expectedScenarioRevision: 0 }
    });
    expect(pending.status(), await pending.text()).toBe(200);
    expect((await pending.json()).status).toBe('PENDING_CLIENT_CONFIRMATION');
    expect((await request.post(`${clientPath}/submit`, { headers: { ...headers(client), 'Idempotency-Key': randomUUID() } })).status()).toBe(409);
    expect((await request.post(`${clientPath}/confirm`, { headers: headers(agent), data: { bureauConsent: true } })).status()).toBe(403);
    const confirmed = await request.post(`${clientPath}/confirm`, { headers: headers(client), data: { bureauConsent: true } });
    expect(confirmed.status(), await confirmed.text()).toBe(200);
    expect((await confirmed.json()).status).toBe('SUBMITTED');

    const offer = await waitStatus(request, client, id, ['OFFER_ISSUED']);
    expect(offer.process.offer?.kind).toBe('APPROVED');
    expect((await request.post(`${clientPath}/offer/accept`, { headers: headers(agent) })).status()).toBe(403);
    expect((await request.post(`${clientPath}/offer/accept`, { headers: headers(client) })).ok()).toBeTruthy();
    const signing = await request.post(`${clientPath}/signing/code`, { headers: headers(client) });
    expect(signing.ok(), await signing.text()).toBeTruthy();
    const code = (await signing.json() as Application).process.signing?.code;
    expect(code).toMatch(/^\d{6}$/);
    expect((await request.post(`${clientPath}/sign`, { headers: headers(agent), data: { code } })).status()).toBe(403);
    expect((await request.post(`${clientPath}/sign`, { headers: headers(client), data: { code } })).ok()).toBeTruthy();
    await waitStatus(request, client, id, ['DISBURSED']);
    const serviced = await request.get(`${clientPath}/loan`, { headers: headers(client) });
    expect(serviced.status(), await serviced.text()).toBe(200);
    const loan = await serviced.json();
    expect(loan).toMatchObject({ applicationId: id, status: 'ACTIVE' });
    expect(loan.tranches).toHaveLength(1);
    expect(loan.payments.length).toBeGreaterThan(0);
    const agentLoan = await request.get(`${agentPath}/loan`, { headers: headers(agent) });
    expect(agentLoan.status(), await agentLoan.text()).toBe(200);
    expect(await agentLoan.json()).toEqual(loan);
    expect((await request.get(`${clientPath}/loan`, { headers: headers(agent) })).status()).toBe(403);
    expect((await request.get(`${agentPath}/loan`, { headers: headers(other) })).status()).toBe(404);
    expect((await request.post(`/api/v1/loans/${loan.id}/payments/next`, {
      headers: { ...headers(agent), 'Idempotency-Key': randomUUID() },
      data: { paymentId: loan.payments[0].id },
    })).status()).toBe(403);
  } finally {
    await clean(request, id, agent, client);
  }
});

test('Агент и клиент проходят подтверждение и выдачу в интерфейсе', async ({ page, request }) => {
  test.setTimeout(180_000);
  const account = 'logistic';
  const agent = await signIn(request, 'agent');
  await login(page, 'agent');
  const welcome = page.getByTestId('onboarding-welcome-banner');
  await expect(welcome.getByText('Впервые в кабинете агента?', { exact: true })).toBeVisible();
  await expect(page.locator('[data-tour="agent-kpi"]')).toBeVisible();
  await noHorizontalOverflow(page);
  await welcome.getByRole('button', { name: 'Начать обучение', exact: true }).click();
  const onboarding = page.getByRole('dialog', { name: /Шаг 1 из \d+: Сводка по клиентам и вознаграждению/ });
  await expect(onboarding).toBeVisible();
  await onboarding.getByRole('button', { name: 'Закрыть обучение', exact: true }).click();
  await expect(onboarding).toHaveCount(0);
  await expect(welcome).toHaveCount(0);
  const client = await signIn(request, account);
  await clearActive(request, client);
  const fixture = await scenario(request, account);
  const clients = await request.get('/api/v1/agent/clients', { headers: headers(agent) });
  expect(clients.status()).toBe(200);
  const companies: AgentCompany[] = await clients.json();
  const company = companies.find(({ inn }) => inn === fixture.inn);
  expect(company, 'Вектор Логистик закреплён за демо-агентом').toBeDefined();
  const companyLink = page.getByRole('link', { name: 'Оформить заявку', exact: true })
    .and(page.locator(`a[href="/agent/clients/${company!.companyId}"]`));
  await noHorizontalOverflow(page);
  await companyLink.click();
  const input = fixture.scenario.request;
  const product = await request.get(`/api/v1/products/${input.productCode}`);
  expect(product.status()).toBe(200);
  await page.getByLabel('Кредитный продукт', { exact: true }).click();
  await page.getByRole('option').filter({ hasText: (await product.json()).name }).click();
  const purposes = await request.get('/api/v1/products/purposes');
  expect(purposes.status()).toBe(200);
  const purpose = (await purposes.json() as Array<{ code: string; title: string }>).find(({ code }) => code === input.purpose);
  expect(purpose).toBeDefined();
  await page.getByLabel('Цель финансирования', { exact: true }).click();
  await page.getByRole('option', { name: purpose!.title, exact: true }).click();
  await page.getByRole('textbox', { name: 'Сумма', exact: true }).fill(String(input.amount));
  await page.getByRole('textbox', { name: 'Срок, месяцев', exact: true }).fill(String(input.termMonths));
  await page.getByRole('textbox', { name: 'Выручка за 12 месяцев', exact: true }).fill(String(input.declaredRevenue12m));
  await page.getByRole('textbox', { name: 'Платежи по кредитам, в месяц', exact: true }).fill(String(input.declaredMonthlyDebtPayments ?? 0));
  await noHorizontalOverflow(page);
  const created = page.waitForResponse((response) => response.url().endsWith('/api/v1/agent/applications') && response.request().method() === 'POST');
  await page.getByRole('button', { name: 'Создать черновик заявки', exact: true }).click();
  const draftResponse = await created;
  expect(draftResponse.status(), await draftResponse.text()).toBe(201);
  const draft = (await draftResponse.json()) as Application & { requiredDocuments: Array<{ uploaded: boolean }> };
  const id = draft.id;
  expect(draft.status).toBe('DRAFT');
  const logout = () => page.getByRole('button', { name: 'Выйти', exact: true }).click();
  try {
    await expect(page).toHaveURL(new RegExp(`/agent/applications/${id}$`));
    await noHorizontalOverflow(page);
    for (const [index] of draft.requiredDocuments.filter(({ uploaded }) => !uploaded).entries()) {
      const file = `agent-demo-${index + 1}.pdf`;
      const chooser = page.waitForEvent('filechooser');
      await page.getByRole('button', { name: 'Загрузить', exact: true }).first().click();
      await (await chooser).setFiles({ name: file, mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.7\nSynthetic fixture\n%%EOF\n') });
      await expect(page.getByRole('button', { name: file, exact: true })).toBeVisible();
    }
    const confirmation = page.waitForResponse((response) => response.url().endsWith(`/agent/applications/${id}/request-confirmation`));
    await page.getByRole('button', { name: 'Отправить клиенту на подтверждение', exact: true }).click();
    const pending = await confirmation;
    expect(pending.status(), await pending.text()).toBe(200);
    await waitStatus(request, client, id, ['PENDING_CLIENT_CONFIRMATION']);
    await logout();
    await login(page, account);
    await expect(welcome.getByText('Впервые в кредитном кабинете?', { exact: true })).toBeVisible();
    await noHorizontalOverflow(page);
    await welcome.getByRole('button', { name: 'Скрыть', exact: true }).click();
    await expect(welcome).toHaveCount(0);
    await page.goto(`/client/applications/${id}`);
    await noHorizontalOverflow(page);
    await page.getByRole('checkbox', { name: /кредитной истории|БКИ/i }).check();
    await page.getByRole('button', { name: 'Подтвердить и отправить в банк', exact: true }).click();
    await waitStatus(request, client, id, ['MANUAL_REVIEW']);
    await logout();
    await login(page, 'underwriter');
    await page.goto(`/bank/applications/${id}`);
    await page.getByRole('button', { name: 'Одобрить', exact: true }).click();
    const decision = page.getByRole('dialog', { name: 'Одобрить заявку', exact: true });
    await decision.getByLabel(/Обоснование|Комментарий/).fill('Одобрение в синтетическом сценарии агентского канала');
    await decision.getByRole('button', { name: 'Сохранить решение', exact: true }).click();
    await waitStatus(request, client, id, ['OFFER_ISSUED']);
    await noHorizontalOverflow(page);
    await logout();
    await login(page, account);
    await expect(welcome).toHaveCount(0);
    await page.goto(`/client/applications/${id}`);
    await page.getByRole('button', { name: 'Принять и перейти к подписанию', exact: true }).click();
    const code = page.getByTestId('signing-code');
    await expect(code).toHaveText(/^\d{6}$/);
    await page.getByLabel('Код из 6 цифр', { exact: true }).fill((await code.innerText()).trim());
    await page.getByRole('button', { name: 'Подписать', exact: true }).click();
    await waitStatus(request, client, id, ['DISBURSED']);
    await expect(page.getByRole('heading', { name: 'Кредит выдан', exact: true })).toBeVisible();
    await noHorizontalOverflow(page);
    await logout();
    await login(page, 'agent');
    await expect(welcome).toHaveCount(0);
    const agentLoan = page.waitForResponse((response) => response.url().endsWith(`/api/v1/agent/applications/${id}/loan`));
    await page.goto(`/agent/applications/${id}`);
    expect((await agentLoan).status()).toBe(200);
    await expect(page.getByRole('heading', { name: 'Выданные транши (1)', exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'График платежей', exact: true })).toBeVisible();
    for (const action of ['Запросить транш', 'Внести очередной платёж', 'Досрочно погасить']) {
      await expect(page.getByRole('button', { name: action, exact: true })).toHaveCount(0);
    }
    await noHorizontalOverflow(page);
  } finally {
    await clean(request, id, agent, client);
  }
});
