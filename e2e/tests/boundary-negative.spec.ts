import { expect, test, type APIRequestContext } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { clearActive, headers, scenario, signIn, type Application, type Session } from '../helpers/cabinet.js';

// TC-04..TC-08 из каталога тест-кейсов: границы и неполные данные проверяются через публичный шлюз,
// как их увидит браузер, а не только внутри сервиса.

type AgentCompany = { companyId: string; inn: string };
type DraftRequest = Record<string, unknown>;

test.beforeEach(({}, info) => {
  test.skip(info.project.name !== 'desktop', 'Проверки API не зависят от ширины экрана');
});

async function agentFixture(request: APIRequestContext, agent: Session, client: Session) {
  await clearActive(request, client);
  const fixture = await scenario(request, 'client');
  const clients = await request.get('/api/v1/agent/clients', { headers: headers(agent) });
  expect(clients.status(), await clients.text()).toBe(200);
  const company = (await clients.json() as AgentCompany[]).find(({ inn }) => inn === fixture.inn);
  expect(company, 'ИП Котова закреплён за демо-агентом').toBeDefined();
  return { companyId: company!.companyId, base: fixture.scenario.request as DraftRequest };
}

test('Агентский черновик отклоняет неверные суммы, сроки и продукт', async ({ request }) => {
  const agent = await signIn(request, 'agent');
  const client = await signIn(request, 'client');
  const { companyId, base } = await agentFixture(request, agent, client);

  const invalid: Array<[string, DraftRequest]> = [
    ['нулевая сумма', { amount: 0 }],
    ['отрицательная сумма', { amount: -1 }],
    ['нулевой срок', { termMonths: 0 }],
    ['отрицательная выручка', { declaredRevenue12m: -1 }],
    ['неизвестный продукт', { productCode: 'UNKNOWN_PRODUCT' }],
  ];
  for (const [name, change] of invalid) {
    const response = await request.post('/api/v1/agent/applications', {
      headers: headers(agent), data: { ...base, ...change, companyId }
    });
    expect(response.status(), `${name}: ${await response.text()}`).toBeGreaterThanOrEqual(400);
    expect(response.status(), `${name}: ошибка клиента, а не сервера`).toBeLessThan(500);
  }

  const missing = await request.post('/api/v1/agent/applications', { headers: headers(agent), data: { companyId } });
  expect(missing.status(), 'пустой запрос').toBe(400);
});

test('Чужой агент не видит черновик, клиент не подтверждает без согласия БКИ', async ({ request }) => {
  const agent = await signIn(request, 'agent');
  const other = await signIn(request, 'agent2');
  const client = await signIn(request, 'client');
  const { companyId, base } = await agentFixture(request, agent, client);

  const created = await request.post('/api/v1/agent/applications', { headers: headers(agent), data: { ...base, companyId } });
  expect(created.status(), await created.text()).toBe(201);
  const draft: Application = await created.json();
  try {
    for (const path of [`/api/v1/agent/applications/${draft.id}`, `/api/v1/agent/applications/${draft.id}/documents`]) {
      const response = await request.get(path, { headers: headers(other) });
      expect(response.status(), `чужой агент: ${path}`).toBe(404);
    }
    const foreignCancel = await request.post(`/api/v1/agent/applications/${draft.id}/cancel`, { headers: headers(other) });
    expect(foreignCancel.status(), 'чужой агент не отменяет черновик').toBe(404);

    const handed = await request.post(`/api/v1/agent/applications/${draft.id}/request-confirmation`, { headers: headers(agent) });
    if (handed.status() === 200) {
      const noConsent = await request.post(`/api/v1/applications/${draft.id}/confirm`, {
        headers: headers(client), data: { bureauConsent: false }
      });
      expect([400, 409], `без согласия БКИ: ${await noConsent.text()}`).toContain(noConsent.status());
      const byAgent = await request.post(`/api/v1/applications/${draft.id}/confirm`, {
        headers: headers(agent), data: { bureauConsent: true }
      });
      expect(byAgent.status(), 'агент не подтверждает за клиента').toBe(403);
    } else {
      // Для передачи нужен документ; без него агент получает понятный отказ, а не сбой.
      expect([400, 409], await handed.text()).toContain(handed.status());
    }
  } finally {
    await clearActive(request, client);
    const current = await request.get(`/api/v1/agent/applications/${draft.id}`, { headers: headers(agent) });
    if (current.ok() && (await current.json() as Application).status === 'DRAFT') {
      await request.post(`/api/v1/agent/applications/${draft.id}/cancel`, { headers: headers(agent) });
    }
  }
});

test('Клиент: неполные данные заявки и операции по чужому кредиту', async ({ request }) => {
  const client = await signIn(request, 'client');
  await clearActive(request, client);

  const empty = await request.post('/api/v1/applications', { headers: headers(client), data: {} });
  expect(empty.status(), 'пустая заявка').toBe(400);
  const negative = await request.post('/api/v1/applications', {
    headers: headers(client), data: { productCode: 'WORKING_CAPITAL', purpose: 'WORKING_CAPITAL', amount: -5, termMonths: 12 }
  });
  expect(negative.status(), 'отрицательная сумма').toBe(400);

  const unknownLoan = '00000000-0000-4000-8000-000000000000';
  const tranche = await request.post(`/api/v1/loans/${unknownLoan}/tranches`, {
    headers: { ...headers(client), 'Idempotency-Key': randomUUID() }, data: { amount: 1000 }
  });
  expect(tranche.status(), 'транш по чужому или несуществующему кредиту').toBe(404);
  const noKey = await request.post(`/api/v1/loans/${unknownLoan}/tranches`, { headers: headers(client), data: { amount: 1000 } });
  expect(noKey.status(), 'операция без ключа повтора').toBe(400);
  const certificate = await request.get(`/api/v1/loans/${unknownLoan}/closure-certificate`, { headers: headers(client) });
  expect(certificate.status(), 'справка по чужому кредиту').toBe(404);
});
