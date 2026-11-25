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

    const pending = await request.post(`${agentPath}/request-confirmation`, { headers: headers(agent) });
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
  } finally {
    await clean(request, id, agent, client);
  }
});

test('Агент и клиент проходят подтверждение и выдачу в интерфейсе', async ({ page, request }) => {
  test.skip(process.env.CABINET_AGENT_UI !== '1', 'Включается на кандидате с кабинетом агента и клиентским подтверждением');
  test.setTimeout(180_000);
  const agent = await signIn(request, 'agent');
  const client = await signIn(request, 'client');
  const id = await beginAgentDraft(request, agent, client);
  try {
    await login(page, 'agent');
    await page.goto(`/agent/applications/${id}`);
    await noHorizontalOverflow(page);
    await page.getByRole('button', { name: 'Отправить клиенту на подтверждение', exact: true }).click();
    await waitStatus(request, client, id, ['PENDING_CLIENT_CONFIRMATION']);
    await login(page, 'client');
    await page.goto(`/client/applications/${id}`);
    await noHorizontalOverflow(page);
    await page.getByRole('checkbox', { name: /кредитной истории|БКИ/i }).check();
    await page.getByRole('button', { name: 'Подтвердить и отправить', exact: true }).click();
    await waitStatus(request, client, id, ['OFFER_ISSUED']);
    await page.getByRole('button', { name: 'Принять и перейти к подписанию', exact: true }).click();
    const code = page.getByText(/^\d{6}$/, { exact: true });
    await expect(code).toBeVisible();
    await page.getByLabel('Код из 6 цифр', { exact: true }).fill((await code.innerText()).trim());
    await page.getByRole('button', { name: 'Подписать', exact: true }).click();
    await waitStatus(request, client, id, ['DISBURSED']);
    await expect(page.getByRole('heading', { name: 'Кредит выдан', exact: true })).toBeVisible();
    await noHorizontalOverflow(page);
  } finally {
    await clean(request, id, agent, client);
  }
});
