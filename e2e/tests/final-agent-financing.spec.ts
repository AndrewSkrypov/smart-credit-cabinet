import { expect, test, type APIRequestContext, type APIResponse, type Page, type TestInfo } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import {
  headers, login, noHorizontalOverflow, scenario, signIn, waitStatus, type Application, type Session,
} from '../helpers/cabinet.js';
import {
  financingFixture as fixture, imageOnlyInvoicePdf, privateStatementApplicationId, privateStatementPdf, reserveLoans, syntheticInvoicePdf,
} from '../fixtures/final-agent-financing.js';

const companies = fixture.companies;
const agent2Id = '9b2f0c4a-6d1e-4f2a-8c3b-000000000202';
const mandatesPath = '/api/v1/companies/me/agent-mandates';
const agentApplication = (id: string) => `/api/v1/agent/applications/${id}`;
const application = (id: string) => `/api/v1/applications/${id}`;
const underwriting = (id: string) => `/api/v1/underwriting/applications/${id}`;

type Draft = Application & { requiredDocuments: Array<{ type: string; uploaded: boolean }> };
type Mandate = { id: string; agentUserId: string; status: string; expiresAt: string | null };
type Scenario = {
  revision: number;
  status: 'DRAFT' | 'AWAITING_CLIENT' | 'CONFIRMED';
  loanAmount: number;
  annualRatePercent: number;
  inputs: typeof scenarioInput & { source?: { page: number; amountQuote: string; dateQuote: string; documentId: string } };
  options: Array<{ termMonths: number; monthlyPayment: number; totalInterest: number; balanceOnPaymentDate: number }>;
};
type Preview = {
  basis: { applicationVersion: string; productCode: string; productVersion: string; savedScenarioRevision: string | null };
  options: Array<{ termMonths: number; monthlyPaymentRub: string; totalInterestRub: string; balanceOnPaymentDateRub: string }>;
};
type Extraction = {
  mode: 'MODEL' | 'MANUAL';
  amount?: { value: number; page: number; quote: string } | null;
  dates: Array<{ value: string; page: number; quote: string }>;
  reason: string | null;
  needsConfirmation: boolean;
};
type Loan = { id: string; availableLimit: number; disbursedAmount: number; tranches: Array<{ amount: number }> };
type PaymentReserve = {
  version: number;
  receiptStatus: 'PENDING' | 'RECEIVED' | 'DELAYED';
  receivedAmount: number;
  remainingLimit: number;
  requiredAmount: number;
  shortfall: number;
  projectedBalance: number;
  canDraw: boolean;
};
type Notification = { id: string; link: string; read: boolean };

const scenarioInput = {
  openingBalance: fixture.offer.monthlyBase,
  expectedReceiptAmount: fixture.offer.expectedReceipt,
  expectedReceiptDate: fixture.offer.expectedReceiptDate,
  paymentDate: fixture.offer.paymentDate,
  delayDays: fixture.offer.delayDays,
  alternativeTermMonths: fixture.offer.terms[1],
  selectedTermMonths: fixture.offer.terms[0] as number,
};
const { openingBalance, expectedReceiptAmount, payableAmount, targetBuffer, controlDate, paymentDate } = fixture.reserve;
const reserveInput = { openingBalance, expectedReceiptAmount, payableAmount, targetBuffer, controlDate, paymentDate };

test.use({ trace: 'off' });

test.describe('Финальное агентское финансирование', () => {
  test.skip(process.env.CABINET_FINAL_FINANCING_E2E !== '1', 'Требуется отдельный синтетический стенд и фикстуры финала');
  test.setTimeout(210_000);

  test.beforeEach(async ({ baseURL }) => {
    expect(baseURL, 'Используйте локальный шлюз синтетического стенда').toBeTruthy();
    expect(['127.0.0.1', 'localhost', '[::1]']).toContain(new URL(baseURL!).hostname);
  });

  test('@api Выбор срока, возврат пакета, версионное согласие и смоделированная выдача', async ({ request }, info) => {
    const agent = await signIn(request, 'agent');
    const client = await signIn(request, companies.vector.account);
    const underwriter = await signIn(request, 'underwriter');
    const draft = await createDraft(request, agent, companies.vector);
    try {
      const selected = await transferRevisedPackage(request, agent, client, underwriter, draft, info);
      const foreign = await signIn(request, companies.furniture.account);
      const draftPath = application(draft.id);
      await status(await request.get(`${draftPath}/financing-scenario`, { headers: headers(foreign) }), 404);
      await status(await request.get(`${draftPath}/financing-scenario`, { headers: headers(agent) }), 403);
      await status(await request.post(`${draftPath}/confirm`, {
        headers: headers(foreign), data: { acceptedScenarioRevision: selected.revision, bureauConsent: true },
      }), 404);
      const confirmed = await json<Draft>(await request.post(`${draftPath}/confirm`, {
        headers: headers(client), data: { acceptedScenarioRevision: selected.revision, bureauConsent: true },
      }), 200);
      expect(confirmed).toMatchObject({ id: draft.id, bureauConsent: true, termMonths: 24 });
      await assertBankPackage(request, client, underwriter, draft.id, selected);
      await completeThroughApi(request, client, underwriter, draft.id);
    } finally {
      await cleanupApplication(request, agent, client, draft.id);
    }
  });

  test('@api Мандат, два действия в портфеле, отзыв и уже поставленное уведомление', async ({ request }) => {
    const agent = await signIn(request, 'agent2');
    const vector = await signIn(request, companies.vector.account);
    const furniture = await signIn(request, companies.furniture.account);
    const technopark = await signIn(request, companies.technopark.account);
    const agro = await signIn(request, 'agro');
    const bank = await signIn(request, 'manager');
    const agroMandates = await json<Mandate[]>(await request.get(mandatesPath, { headers: headers(agro) }), 200);
    const original = agroMandates.find((row) => row.agentUserId === agent2Id && row.status === 'ACTIVE');
    const granted: Array<{ owner: Session; id: string }> = [];
    const apps: Array<{ owner: Session; id: string }> = [];
    try {
      if (original) await revoke(request, agro, original.id);
      await status(await request.post(mandatesPath, {
        headers: headers(vector), data: { agentUserId: agent2Id, expiresAt: '2025-01-01T00:00:00Z' },
      }), 400);
      const vectorGrant = await grant(request, vector);
      granted.push({ owner: vector, id: vectorGrant.id });
      granted.push({ owner: furniture, id: (await grant(request, furniture)).id });
      const portfolio = await json<Array<{ companyId: string }>>(await request.get('/api/v1/agent/clients', {
        headers: headers(agent),
      }), 200);
      expect(portfolio.map((row) => row.companyId).sort()).toEqual([companies.vector.id, companies.furniture.id].sort());

      const techGrant = await grant(request, technopark);
      granted.push({ owner: technopark, id: techGrant.id });
      await status(await request.post('/api/v1/agent/applications', {
        headers: headers(agent), data: await draftInput(request, companies.technopark),
      }), 409);
      await revoke(request, technopark, techGrant.id);

      const draft = await createDraft(request, agent, companies.vector);
      apps.push({ owner: vector, id: draft.id });
      const furnitureDraft = await createDraft(request, agent, companies.furniture);
      apps.push({ owner: furniture, id: furnitureDraft.id });
      const ordinary = await json<{ id: string }>(await request.post(`${agentApplication(draft.id)}/documents?type=TAX_RETURN`, {
        headers: headers(agent), multipart: { file: syntheticInvoicePdf() },
      }), 201);
      const agentDocument = `${agentApplication(draft.id)}/documents/${ordinary.id}`;
      await status(await request.get(agentDocument, { headers: headers(agent) }), 200);
      await sendChat(request, agent, draft.id, 'Попытка отправить сообщение из черновика', 409);
      await status(await request.get(application(draft.id), { headers: headers(bank) }), 404);
      await status(await request.get(`${application(draft.id)}/messages`, { headers: headers(bank) }), 404);
      await sendToClient(request, agent, draft);
      const workingJournal = await json<Array<{ event: string }>>(
        await request.get(`${agentApplication(draft.id)}/events`, { headers: headers(agent) }), 200);
      expect(workingJournal).toEqual(expect.arrayContaining([expect.objectContaining({ event: 'REQUEST_CLIENT_CONFIRMATION' })]));
      await sendChat(request, agent, draft.id, 'Синтетическое сообщение до подачи', 200);
      await status(await request.get(`${application(draft.id)}/messages`, { headers: headers(bank) }), 404);
      const bankNotice = (await notifications(request, bank)).find((row) => row.link?.includes(draft.id));
      expect(bankNotice).toBeUndefined();
      const entries = await json<Array<{ id: string; actionOwner: string; actionDeadline?: string }>>(
        await request.get('/api/v1/agent/applications', { headers: headers(agent) }), 200);
      expect(entries).toEqual(expect.arrayContaining([
        expect.objectContaining({ id: draft.id, actionOwner: 'CLIENT' }),
        expect.objectContaining({ id: furnitureDraft.id, actionOwner: 'AGENT' }),
      ]));

      await status(await request.post(`${application(draft.id)}/return-to-agent`, {
        headers: headers(vector), data: { comment: 'Верните пакет на синтетическую доработку' },
      }), 200);
      const link = `/agent/applications/${draft.id}`;
      let queued: Notification | undefined;
      await expect.poll(async () => {
        queued = (await notifications(request, agent)).find((row) => row.link === link && !row.read);
        return queued?.id;
      }, { timeout: 25_000 }).toBeTruthy();
      expect((await notifications(request, bank)).some((row) => row.link?.includes(draft.id))).toBe(false);
      await sendToClient(request, agent, draft);
      const before = await unread(request, agent);
      expect(before).toBeGreaterThan(0);
      const noticeAccess = `${agentApplication(draft.id)}/notification-access`;
      await status(await request.get(noticeAccess, { headers: headers(agent) }), 204);
      await revoke(request, vector, vectorGrant.id);
      await revoke(request, vector, vectorGrant.id);
      for (const route of ['', '/events', '/documents', '/messages', '/notification-access']) {
        await status(await request.get(`${agentApplication(draft.id)}${route}`, { headers: headers(agent) }), 404);
      }
      await status(await request.get(agentDocument, { headers: headers(agent) }), 404);
      const remaining = await json<Array<{ id: string }>>(await request.get('/api/v1/agent/applications', { headers: headers(agent) }), 200);
      expect(remaining.map((row) => row.id)).not.toContain(draft.id);
      expect((await notifications(request, agent)).map((row) => row.id)).not.toContain(queued!.id);
      await status(await request.post(`/api/v1/notifications/${queued!.id}/read`, { headers: headers(agent) }), 404);
      expect(await unread(request, agent)).toBeLessThan(before);
      const visibleToClient = await json<Application>(await request.get(application(draft.id), { headers: headers(vector) }), 200);
      expect(visibleToClient).toMatchObject({ id: draft.id, status: 'PENDING_CLIENT_CONFIRMATION' });
      const submitted = await json<Application>(await request.post(`${application(draft.id)}/confirm`, {
        headers: headers(vector), data: { bureauConsent: true },
      }), 200);
      expect(submitted).toMatchObject({ id: draft.id, status: 'SUBMITTED' });
      await status(await request.get(application(draft.id), { headers: headers(bank) }), 200);

      const withdrawnBefore = await bankWithdrawnCount(request, bank);
      await status(await request.post(`${agentApplication(furnitureDraft.id)}/cancel`, { headers: headers(agent) }), 200);
      await status(await request.get(application(furnitureDraft.id), { headers: headers(bank) }), 404);
      const bankApps = await json<Array<{ id: string }>>(await request.get('/api/v1/applications', { headers: headers(bank) }), 200);
      expect(bankApps.map((row) => row.id)).not.toContain(furnitureDraft.id);
      expect(await bankWithdrawnCount(request, bank)).toBe(withdrawnBefore);
    } finally {
      for (const { owner, id } of apps.reverse()) await cleanupApplication(request, agent, owner, id);
      for (const { owner, id } of granted.reverse()) await revoke(request, owner, id);
      if (original) await grant(request, agro, original.expiresAt);
    }
  });

  test('@api Приватная выписка видна банку, агенту доступна только готовность', async ({ request }) => {
    const agent = await signIn(request, 'agent');
    const otherAgent = await signIn(request, 'agent2');
    const owner = await signIn(request, companies.furniture.account);
    const foreign = await signIn(request, companies.vector.account);
    const bank = await signIn(request, 'underwriter');
    const id = privateStatementApplicationId;
    const agentPath = agentApplication(id);
    const clientPath = application(id);
    const file = privateStatementPdf();
    const uploadPath = `${clientPath}/documents?type=BANK_STATEMENT&privateToBank=true`;
    const before = await json<{ process: { infoRequest: { items: Array<{ documentType: string; satisfied: boolean }> } } }>(
      await request.get(agentPath, { headers: headers(agent) }), 200);
    expect(before.process.infoRequest.items).toContainEqual(expect.objectContaining({
      documentType: 'BANK_STATEMENT', satisfied: false,
    }));
    await status(await request.post(uploadPath, { headers: headers(foreign), multipart: { file } }), 404);
    const uploaded = await json<{ id: string }>(await request.post(uploadPath, {
      headers: headers(owner), multipart: { file },
    }), 201);
    const bankFile = await request.get(`${clientPath}/documents/${uploaded.id}`, { headers: headers(bank) });
    expect(bankFile.status()).toBe(200);
    expect(await bankFile.body()).toEqual(file.buffer);
    for (const session of [agent, otherAgent]) {
      await status(await request.get(`${agentPath}/documents/${uploaded.id}`, { headers: headers(session) }), 404);
      await status(await request.get(`${clientPath}/documents/${uploaded.id}`, { headers: headers(session) }), [403, 404]);
    }
    await status(await request.get(`${clientPath}/documents/${uploaded.id}`, { headers: headers(foreign) }), 404);
    for (const route of ['', '/documents', '/messages', '/events']) {
      const response = await request.get(`${agentPath}${route}`, { headers: headers(agent) });
      expect(response.status(), route).toBe(200);
      const body = await response.text();
      for (const denied of [uploaded.id, file.name, 'FURNITURE_PRIVATE_QA_470396']) expect(body).not.toContain(denied);
    }
    const after = await json<typeof before>(await request.get(agentPath, { headers: headers(agent) }), 200);
    expect(after.process.infoRequest.items).toContainEqual(expect.objectContaining({
      documentType: 'BANK_STATEMENT', satisfied: true,
    }));
    await status(await request.post(`${agentPath}/info-provided`, { headers: headers(agent) }), [403, 409]);
    await status(await request.post(`${clientPath}/info-provided`, { headers: headers(owner) }), 200);
  });

  test('@api Резерв 0/130 000 ₽, остаток 80 000 ₽ и единственная подтверждённая выборка', async ({ request }) => {
    await checkReserve(request);
  });

  test('@browser Агент проверяет варианты, клиент подтверждает, банк решает, клиент подписывает', async ({ page, request }, info) => {
    const agent = await signIn(request, 'agent');
    const client = await signIn(request, companies.vector.account);
    const underwriter = await signIn(request, 'underwriter');
    const draft = await createDraft(request, agent, companies.vector);
    try {
      await login(page, 'agent');
      await page.goto(`/agent/applications/${draft.id}`);
      await test.step('Граница задержки в предпросмотре без сохранения черновика', async () => {
        await expectReadOnlyFinancingPreview(page, request, agent, draft.id);
      });

      const selected = await transferRevisedPackage(request, agent, client, underwriter, draft, info, async () => {
        await uploadRequiredDocuments(request, agent, draft);
        await page.reload();
        await expectFinancingPackage(page);
        const resend = page.waitForResponse((response) =>
          response.url().endsWith(`${agentApplication(draft.id)}/request-confirmation`)
            && response.request().method() === 'POST');
        await page.getByRole('button', { name: 'Отправить клиенту на подтверждение', exact: true }).click();
        const response = await resend;
        expect(response.status(), await response.text()).toBe(200);
        expect(await response.json()).toMatchObject({ status: 'PENDING_CLIENT_CONFIRMATION' });
      });
      await logout(page);
      await login(page, companies.vector.account);
      await page.goto(`/client/applications/${draft.id}`);
      await expectFinancingPackage(page);
      await page.getByRole('checkbox', { name: /кредитной истории|БКИ/i }).check();
      const confirmation = page.waitForResponse((response) => response.url().endsWith(`${application(draft.id)}/confirm`)
        && response.request().method() === 'POST');
      await page.getByRole('button', { name: 'Подтвердить и отправить в банк', exact: true }).click();
      const accepted = await confirmation;
      expect(accepted.status(), await accepted.text()).toBe(200);
      expect(accepted.request().postDataJSON()).toMatchObject({ acceptedScenarioRevision: selected.revision, bureauConsent: true });
      await assertBankPackage(request, client, underwriter, draft.id, selected);
      await waitStatus(request, client, draft.id, ['MANUAL_REVIEW']);

      await logout(page);
      await login(page, 'underwriter');
      await page.goto(`/bank/applications/${draft.id}`);
      await expectFinancingPackage(page);
      await page.getByRole('button', { name: 'Одобрить', exact: true }).click();
      const dialog = page.getByRole('dialog', { name: 'Одобрить заявку', exact: true });
      await dialog.getByLabel(/Обоснование|Комментарий/).fill('Приёмка условий и приложения на синтетике');
      await dialog.getByRole('button', { name: 'Сохранить решение', exact: true }).click();
      await waitStatus(request, client, draft.id, ['OFFER_ISSUED']);

      await logout(page);
      await login(page, companies.vector.account);
      await page.goto(`/client/applications/${draft.id}`);
      await page.getByRole('button', { name: 'Принять и перейти к подписанию', exact: true }).click();
      const code = page.getByTestId('signing-code');
      await expect(code).toHaveText(/^\d{6}$/);
      await page.getByLabel('Код из 6 цифр', { exact: true }).fill((await code.innerText()).trim());
      await page.getByRole('button', { name: 'Подписать', exact: true }).click();
      await waitStatus(request, client, draft.id, ['DISBURSED']);
      await expect(page.getByRole('heading', { name: 'Кредит выдан', exact: true })).toBeVisible();
      await noHorizontalOverflow(page);
    } finally {
      await cleanupApplication(request, agent, client, draft.id);
    }
  });
});

async function status(response: APIResponse, allowed: number | number[]) {
  expect(Array.isArray(allowed) ? allowed : [allowed], `${response.url()}: ${await response.text()}`).toContain(response.status());
}

async function json<T>(response: APIResponse, allowed: number): Promise<T> {
  await status(response, allowed);
  return response.json();
}

async function draftInput(request: APIRequestContext, company: { account: string; id: string }) {
  const data = await scenario(request, company.account);
  return {
    ...data.scenario.request, companyId: company.id, productCode: 'WORKING_CAPITAL',
    purpose: 'WORKING_CAPITAL', amount: fixture.offer.amount, termMonths: fixture.offer.terms[0],
  };
}

async function createDraft(request: APIRequestContext, agent: Session, company: { account: string; id: string }) {
  const draft = await json<Draft>(await request.post('/api/v1/agent/applications', {
    headers: headers(agent), data: await draftInput(request, company),
  }), 201);
  expect(draft.status).toBe('DRAFT');
  return draft;
}

async function sendToClient(request: APIRequestContext, agent: Session, draft: Draft) {
  await uploadRequiredDocuments(request, agent, draft);
  const pending = await json<Draft>(await request.post(`${agentApplication(draft.id)}/request-confirmation`, {
    headers: headers(agent),
  }), 200);
  expect(pending.status).toBe('PENDING_CLIENT_CONFIRMATION');
}

async function uploadRequiredDocuments(request: APIRequestContext, agent: Session, draft: Draft) {
  const path = agentApplication(draft.id);
  const current = await json<Draft>(await request.get(path, { headers: headers(agent) }), 200);
  for (const doc of current.requiredDocuments.filter((document) => !document.uploaded)) {
    await status(await request.post(`${path}/documents?type=${doc.type}`, {
      headers: headers(agent), multipart: { file: syntheticInvoicePdf() },
    }), 201);
  }
}

async function extract(request: APIRequestContext, agent: Session, id: string, info: TestInfo) {
  const path = `${agentApplication(id)}/invoice-extraction`;
  const send = (file: ReturnType<typeof syntheticInvoicePdf>) => request.post(path, {
    headers: headers(agent), multipart: { file },
  });
  const noText = await json<Extraction>(await send(imageOnlyInvoicePdf()), 200);
  expect(noText).toMatchObject({ mode: 'MANUAL', dates: [] });
  expect(noText.amount ?? null).toBeNull();

  const oversized = Buffer.alloc(2 * 1024 * 1024 + 1);
  syntheticInvoicePdf().buffer.copy(oversized);
  await status(await send({ name: 'oversized-invoice.pdf', mimeType: 'application/pdf', buffer: oversized }), 413);

  const invoice = await json<Extraction>(await send(syntheticInvoicePdf()), 200);
  expect(['MODEL', 'MANUAL']).toContain(invoice.mode);
  const amount = { value: fixture.offer.expectedReceipt, page: 1, quote: 'Total amount due: 140 000 RUB' };
  const receipt = { value: fixture.offer.expectedReceiptDate, page: 1, quote: 'Due date: 10.12.2026' };
  expect(invoice).toMatchObject({ amount, dates: [receipt], needsConfirmation: true });
  const ambiguous = await json<Extraction>(await send(syntheticInvoicePdf({ includeValidityDate: true })), 200);
  expect(ambiguous).toMatchObject({
    mode: 'MANUAL', amount, needsConfirmation: true,
    dates: [receipt, { value: '2026-12-24', page: 1, quote: 'Valid until: 24.12.2026' }],
  });
  const untrusted = await json<Extraction>(await send(syntheticInvoicePdf({ includeUntrustedText: true })), 200);
  expect(untrusted).toMatchObject({ amount, dates: [receipt] });
  await info.attach('invoice-mode', {
    contentType: 'application/json',
    body: JSON.stringify({ invoice: invoice.mode, noText: noText.mode, ambiguous: ambiguous.mode, untrustedInput: untrusted.mode }),
  });
  if (process.env.CABINET_EXPECTED_INVOICE_MODE) {
    expect(invoice.mode).toBe(process.env.CABINET_EXPECTED_INVOICE_MODE);
  }
  const source = await json<{ id: string }>(await request.post(`${agentApplication(id)}/documents?type=OTHER`, {
    headers: headers(agent), multipart: { file: syntheticInvoicePdf() },
  }), 201);
  return { page: receipt.page, amountQuote: amount.quote, dateQuote: receipt.quote, documentId: source.id };
}

async function transferRevisedPackage(
  request: APIRequestContext, agent: Session, client: Session, bank: Session, draft: Draft, info: TestInfo,
  resendFromBrowser?: () => Promise<void>,
) {
  const agentPath = `${agentApplication(draft.id)}/financing-scenario`;
  const clientPath = `${application(draft.id)}/financing-scenario`;
  const bankPath = `${underwriting(draft.id)}/financing-scenario`;
  await status(await request.get(agentPath, { headers: headers(agent) }), 204);
  await status(await request.get(clientPath, { headers: headers(client) }), 404);
  await status(await request.get(bankPath, { headers: headers(bank) }), 404);
  const source = await extract(request, agent, draft.id, info);
  const packetDocument = syntheticInvoicePdf();
  const { id: packetDocumentId } = await json<{ id: string }>(
    await request.post(`${agentApplication(draft.id)}/documents?type=TAX_RETURN`, {
      headers: headers(agent), multipart: { file: packetDocument },
    }), 201,
  );
  const clientDocumentPath = `${application(draft.id)}/documents/${packetDocumentId}`;
  const originalInputs = { ...scenarioInput, source };
  await status(await request.put(agentPath, {
    headers: headers(agent), data: { ...originalInputs, selectedTermMonths: 36 },
  }), 400);
  const original = await json<Scenario>(await request.put(agentPath, {
    headers: headers(agent), data: originalInputs,
  }), 200);
  expect(original).toMatchObject({ status: 'DRAFT', loanAmount: fixture.offer.amount, annualRatePercent: fixture.offer.annualRate });
  expect(original.options.map(({ termMonths }) => termMonths).sort((a, b) => a - b)).toEqual([12, 24]);
  for (const option of original.options) {
    const term = option.termMonths as 12 | 24;
    const expected = fixture.offer.delayedBalanceRubles[term];
    expect(Math.abs(option.balanceOnPaymentDate - expected)).toBeLessThanOrEqual(fixture.offer.delayedBalanceToleranceRubles);
    expect(option.monthlyPayment).toBeGreaterThan(0);
    expect(option.totalInterest).toBeGreaterThan(0);
  }

  await sendToClient(request, agent, draft);
  const first = await json<Scenario>(await request.get(clientPath, { headers: headers(client) }), 200);
  expect(first.status).toBe('AWAITING_CLIENT');
  expect(first.options).toEqual(original.options);
  await status(await request.post(`${application(draft.id)}/return-to-agent`, {
    headers: headers(client), data: { comment: 'Сравните изменившуюся задержку и выберите 24 месяца' },
  }), 200);
  await status(await request.delete(`${agentApplication(draft.id)}/documents/${packetDocumentId}`, {
    headers: headers(agent),
  }), 204);
  const handedDocument = await request.get(clientDocumentPath, { headers: headers(client) });
  await status(handedDocument, 200);
  expect(await handedDocument.body()).toEqual(packetDocument.buffer);
  const revisedInputs = { ...originalInputs, delayDays: fixture.offer.delayDays + 1, selectedTermMonths: 24 };
  await status(await request.put(agentPath, { headers: headers(agent), data: revisedInputs }), 200);
  const duringEdit = await json<Scenario>(await request.get(clientPath, { headers: headers(client) }), 200);
  expect(duringEdit.revision).toBe(first.revision);
  expect(duringEdit.inputs).toEqual(first.inputs);
  if (resendFromBrowser) await resendFromBrowser();
  else await sendToClient(request, agent, draft);

  const revised = await json<Scenario>(await request.get(clientPath, { headers: headers(client) }), 200);
  expect(revised.revision).toBeGreaterThan(first.revision);
  expect(revised.inputs).toMatchObject({ delayDays: revisedInputs.delayDays, selectedTermMonths: 24 });
  await status(await request.get(bankPath, { headers: headers(bank) }), 404);
  const confirmation = `${application(draft.id)}/confirm`;
  await status(await request.post(confirmation, {
    headers: headers(client), data: { acceptedScenarioRevision: first.revision, bureauConsent: true },
  }), 409);
  await status(await request.post(confirmation, {
    headers: headers(client), data: { acceptedScenarioRevision: revised.revision, bureauConsent: false },
  }), [400, 409]);
  await status(await request.post(confirmation, {
    headers: headers(agent), data: { acceptedScenarioRevision: revised.revision, bureauConsent: true },
  }), 403);
  expect(await json<Draft>(await request.get(application(draft.id), { headers: headers(client) }), 200))
    .toMatchObject({ status: 'PENDING_CLIENT_CONFIRMATION', bureauConsent: false });
  return revised;
}

async function assertBankPackage(request: APIRequestContext, client: Session, bank: Session, id: string, selected: Scenario) {
  const snapshot = await json<Scenario>(await request.get(`${underwriting(id)}/financing-scenario`, {
    headers: headers(bank),
  }), 200);
  expect(snapshot).toMatchObject({
    revision: selected.revision, status: 'CONFIRMED', inputs: selected.inputs, options: selected.options,
    loanAmount: selected.loanAmount, annualRatePercent: selected.annualRatePercent,
  });
  await status(await request.post(`${application(id)}/confirm`, {
    headers: headers(client), data: { acceptedScenarioRevision: selected.revision, bureauConsent: true },
  }), 200);
  const journal = await json<Array<{ event: string }>>(await request.get(`${application(id)}/events`, {
    headers: headers(client),
  }), 200);
  expect(journal.filter(({ event }) => event === 'CONFIRM_AND_SUBMIT')).toHaveLength(1);
}

async function completeThroughApi(request: APIRequestContext, client: Session, bank: Session, id: string) {
  await waitStatus(request, client, id, ['MANUAL_REVIEW']);
  await status(await request.post(`${underwriting(id)}/approve`, {
    headers: headers(bank), data: { comment: 'Решение принято в синтетическом QA-сценарии' },
  }), 200);
  await waitStatus(request, client, id, ['OFFER_ISSUED']);
  await status(await request.post(`${application(id)}/offer/accept`, { headers: headers(client) }), 200);
  const signing = await json<Application>(await request.post(`${application(id)}/signing/code`, {
    headers: headers(client),
  }), 200);
  const code = signing.process.signing?.code;
  expect(code).toMatch(/^\d{6}$/);
  await status(await request.post(`${application(id)}/sign`, { headers: headers(client), data: { code } }), 200);
  await waitStatus(request, client, id, ['DISBURSED']);
  const loan = await json<Loan>(await request.get(`${application(id)}/loan`, { headers: headers(client) }), 200);
  expect(loan.disbursedAmount).toBe(fixture.offer.amount);
  expect(loan.tranches).toEqual([expect.objectContaining({ amount: fixture.offer.amount })]);
}

async function grant(request: APIRequestContext, owner: Session, expiresAt?: string | null) {
  const deadline = expiresAt ?? new Date(Date.now() + 7 * 86_400_000).toISOString();
  const row = await json<Mandate>(await request.post(mandatesPath, {
    headers: headers(owner), data: { agentUserId: agent2Id, expiresAt: deadline },
  }), 201);
  expect(row).toMatchObject({ agentUserId: agent2Id, status: 'ACTIVE' });
  return row;
}

async function revoke(request: APIRequestContext, owner: Session, id: string) {
  await status(await request.delete(`${mandatesPath}/${id}`, { headers: headers(owner) }), 204);
}

async function sendChat(request: APIRequestContext, agent: Session, id: string, text: string, expected: number) {
  await status(await request.post(`${agentApplication(id)}/messages`, {
    headers: { ...headers(agent), 'Idempotency-Key': randomUUID() }, data: { text },
  }), expected);
}

async function notifications(request: APIRequestContext, user: Session) {
  return (await json<{ items: Notification[] }>(await request.get('/api/v1/notifications?page=0&size=100', {
    headers: headers(user),
  }), 200)).items;
}

async function unread(request: APIRequestContext, user: Session) {
  return (await json<{ unread: number }>(await request.get('/api/v1/notifications/unread-count', {
    headers: headers(user),
  }), 200)).unread;
}

async function bankWithdrawnCount(request: APIRequestContext, bank: Session) {
  const funnel = await json<{ currentStatuses: Record<string, number> }>(
    await request.get('/api/v1/analytics/funnel', { headers: headers(bank) }), 200);
  return funnel.currentStatuses.WITHDRAWN ?? 0;
}

async function cleanupApplication(request: APIRequestContext, agent: Session, owner: Session, id: string) {
  const response = await request.get(agentApplication(id), { headers: headers(agent) });
  if (response.ok() && (await response.json() as Application).status === 'DRAFT') {
    await status(await request.post(`${agentApplication(id)}/cancel`, { headers: headers(agent) }), 200);
    return;
  }
  await expect.poll(async () => {
    const current = await request.get(application(id), { headers: headers(owner) });
    if (current.status() === 404) return 200;
    if (!current.ok()) return current.status();
    if (['DISBURSED', 'REJECTED', 'CLOSED', 'WITHDRAWN'].includes((await current.json() as Application).status)) return 200;
    return (await request.post(`${application(id)}/withdraw`, { headers: headers(owner) })).status();
  }, { timeout: 20_000 }).toBe(200);
}

async function checkReserve(request: APIRequestContext) {
  const owner = await signIn(request, companies.furniture.account);
  const foreign = await signIn(request, companies.vector.account);
  const agent = await signIn(request, 'agent');
  const otherAgent = await signIn(request, 'agent2');
  const byApp = async (applicationId: string) => json<Loan>(await request.get(`${application(applicationId)}/loan`, {
    headers: headers(owner),
  }), 200);
  const expected = fixture.reserve;
  const low = reserveLoans.limited;
  const lowPath = `/api/v1/loans/${low.id}/payment-reserve`;
  const lowAccountBefore = await byApp(low.applicationId);
  expect(lowAccountBefore.availableLimit).toBe(expected.availableLimit);
  await status(await request.get(lowPath, { headers: headers(owner) }), 204);
  for (const [unauthorised, denied] of [[foreign, 404], [agent, 403]] as const) {
    await status(await request.get(lowPath, { headers: headers(unauthorised) }), denied);
    await status(await request.put(lowPath, { headers: headers(unauthorised), data: reserveInput }), denied);
    await status(await request.post(`${lowPath}/receipt-event`, {
      headers: headers(unauthorised), data: { eventId: randomUUID(), status: 'DELAYED' },
    }), denied);
    await status(await request.post(`${lowPath}/confirm-draw`, {
      headers: headers(unauthorised), data: { expectedVersion: 1 },
    }), denied);
  }
  await status(await request.get(lowPath, { headers: headers(owner) }), 204);
  const waiting = await json<PaymentReserve>(await request.put(lowPath, { headers: headers(owner), data: reserveInput }), 200);
  expect(waiting).toMatchObject({ receiptStatus: 'PENDING', canDraw: false });
  const agentSummaryPath = `${agentApplication(low.applicationId)}/payment-reserve`;
  const summary = await json<Record<string, unknown>>(
    await request.get(agentSummaryPath, { headers: headers(agent) }), 200);
  expect(summary).toEqual({
    version: waiting.version, receiptStatus: 'PENDING', paymentDate: expected.paymentDate, action: 'WAIT_FOR_RECEIPT',
  });
  await status(await request.get(agentSummaryPath, { headers: headers(otherAgent) }), 404);
  await status(await request.post(`${lowPath}/confirm-draw`, {
    headers: headers(owner), data: { expectedVersion: waiting.version },
  }), 409);
  const missed = { eventId: randomUUID(), status: 'DELAYED' };
  const markLow = (data: object) => request.post(`${lowPath}/receipt-event`, { headers: headers(owner), data });
  const late = await json<PaymentReserve>(await markLow(missed), 200);
  expect(late).toMatchObject({
    receiptStatus: 'DELAYED', requiredAmount: expected.withoutReceipt,
    remainingLimit: expected.availableLimit, shortfall: expected.uncovered, canDraw: false,
  });
  const duplicate = await json<PaymentReserve>(await markLow(missed), 200);
  expect(duplicate).toEqual(late);
  await status(await markLow({ eventId: missed.eventId, status: 'RECEIVED', receivedAmount: expected.postedReceipt }), 409);
  for (const expectedVersion of [waiting.version, late.version]) {
    await status(await request.post(`${lowPath}/confirm-draw`, { headers: headers(owner), data: { expectedVersion } }), 409);
  }
  const received = await json<PaymentReserve>(await markLow({
    eventId: randomUUID(), status: 'RECEIVED', receivedAmount: expected.postedReceipt,
  }), 200);
  expect(received).toMatchObject({
    receiptStatus: 'RECEIVED', receivedAmount: expected.postedReceipt, requiredAmount: expected.withReceipt,
    shortfall: 0, projectedBalance: 40_000, canDraw: false,
  });
  await status(await request.post(`${lowPath}/confirm-draw`, {
    headers: headers(owner), data: { expectedVersion: received.version },
  }), 409);
  expect(await byApp(low.applicationId)).toEqual(lowAccountBefore);

  const high = reserveLoans.sufficient;
  const highPath = `/api/v1/loans/${high.id}/payment-reserve`;
  const highAccountBefore = await byApp(high.applicationId);
  expect(highAccountBefore.availableLimit).toBe(high.availableLimit);
  const initial = await json<PaymentReserve>(await request.put(highPath, { headers: headers(owner), data: reserveInput }), 200);
  const eligible = await json<PaymentReserve>(await request.post(`${highPath}/receipt-event`, {
    headers: headers(owner), data: { eventId: randomUUID(), status: 'DELAYED' },
  }), 200);
  expect(eligible).toMatchObject({ canDraw: true, requiredAmount: expected.withoutReceipt, shortfall: 0 });
  const confirm = (expectedVersion: number) => request.post(`${highPath}/confirm-draw`, {
    headers: headers(owner), data: { expectedVersion },
  });
  await status(await confirm(initial.version), 409);
  await status(await confirm(eligible.version), 200);
  await status(await confirm(eligible.version), 200);
  const highAccountAfter = await byApp(high.applicationId);
  expect(highAccountAfter).toMatchObject({
    disbursedAmount: highAccountBefore.disbursedAmount + expected.withoutReceipt,
    availableLimit: high.availableLimit - expected.withoutReceipt,
  });
  expect(highAccountAfter.tranches).toHaveLength(highAccountBefore.tranches.length + 1);
  expect(highAccountAfter.tranches.at(-1)).toMatchObject({ amount: expected.withoutReceipt });
}

async function expectFinancingPackage(page: Page) {
  const comparison = page.getByRole('table', { name: 'Сравнение сценариев финансирования' });
  await expect(comparison.getByRole('rowheader', { name: /12\s*мес/i })).toBeVisible();
  const selectedTerm = comparison.getByRole('rowheader', { name: /24\s*мес/i });
  await expect(selectedTerm.getByText('Выбран', { exact: true })).toBeVisible();
  await noHorizontalOverflow(page);
}

async function expectReadOnlyFinancingPreview(page: Page, request: APIRequestContext, agent: Session, id: string) {
  const draftPath = agentApplication(id);
  const scenarioPath = `${draftPath}/financing-scenario`;
  const previewPath = `${scenarioPath}/preview`;
  const agentHeaders = headers(agent);
  const readDraft = async () => json<Draft & { version: number; amount: number; termMonths: number; documents: unknown[] }>(
    await request.get(draftPath, { headers: agentHeaders }), 200,
  );
  const before = await readDraft();
  expect(before).toMatchObject({ status: 'DRAFT', amount: fixture.offer.amount, termMonths: fixture.offer.terms[0] });
  await status(await request.get(scenarioPath, { headers: agentHeaders }), 204);

  await expect(page.getByRole('button', { name: 'Прочитать счёт PDF', exact: true })).toBeEnabled();
  const amount = before.amount - 50_000;
  await page.getByLabel('Сумма', { exact: true }).fill(String(amount));
  await page.getByLabel('Остаток до поступления', { exact: true }).fill(String(scenarioInput.openingBalance));
  await page.getByLabel('Сумма ожидаемого поступления', { exact: true }).fill(String(scenarioInput.expectedReceiptAmount));
  await page.getByLabel('Дата ожидаемого поступления', { exact: true }).fill(scenarioInput.expectedReceiptDate);
  await page.getByLabel('Дата платежа', { exact: true }).fill(scenarioInput.paymentDate);
  const delay = page.getByLabel('Задержка поступления, дней', { exact: true });
  const view = page.getByRole('region', { name: 'Предварительный расчёт' });
  const calculate = async (days: 5 | 6): Promise<Preview> => {
    await delay.fill(String(days));
    await expect(view).toHaveCount(0);
    const completed = page.waitForResponse((response) =>
      new URL(response.url()).pathname === previewPath && response.request().method() === 'POST');
    await page.getByRole('button', { name: 'Рассчитать примерку', exact: true }).click();
    const response = await completed;
    expect(response.status(), await response.text()).toBe(200);
    expect(response.request().postDataJSON()).toEqual({
      mode: 'WEB_FORM', openingBalance: scenarioInput.openingBalance, expectedReceiptAmount: scenarioInput.expectedReceiptAmount,
      expectedReceiptDate: scenarioInput.expectedReceiptDate, paymentDate: scenarioInput.paymentDate,
      delayDays: days, alternativeTermMonths: scenarioInput.alternativeTermMonths,
      draftTermsOverride: { amount, baseTermMonths: before.termMonths },
    });
    const result = await response.json();
    expect(result).toMatchObject({
      source: 'credit', preview: true,
      basis: { applicationVersion: String(before.version), productCode: 'WORKING_CAPITAL', savedScenarioRevision: null },
      inputsUsed: {
        loanAmountRub: String(amount), baseTermMonths: before.termMonths, delayDays: days,
        receiptIncludedByPayment: days === 5, lastIncludedDelayDays: 5,
      },
    });
    await expect(view).toBeVisible();
    await status(await request.get(scenarioPath, { headers: agentHeaders }), 204);
    return result;
  };

  const included = await calculate(5);
  await expect(view.getByText(/Поступление учтено в остатке/)).toBeVisible();
  const delayed = await calculate(6);
  await expect(view.getByText(/Поступление ожидается после даты платежа/)).toBeVisible();
  await expect(view.getByText(/При сроке 12 месяцев на дату платежа не хватает/)).toBeVisible();
  expect(included.options.map(({ termMonths }) => termMonths)).toEqual([12, 24]);
  expect(delayed.basis).toEqual(included.basis);
  expect(delayed.options).toHaveLength(included.options.length);
  for (const [index, option] of delayed.options.entries()) {
    const matching = included.options[index];
    expect(option).toMatchObject({
      termMonths: matching.termMonths, monthlyPaymentRub: matching.monthlyPaymentRub,
      totalInterestRub: matching.totalInterestRub,
    });
    expect(Number(matching.balanceOnPaymentDateRub) - Number(option.balanceOnPaymentDateRub)).toBeCloseTo(fixture.offer.expectedReceipt, 2);
  }
  expect(Number(delayed.options[0].balanceOnPaymentDateRub)).toBeLessThan(0);
  expect(Number(delayed.options[1].balanceOnPaymentDateRub)).toBeGreaterThan(0);
  expect(await readDraft()).toMatchObject({
    version: before.version, status: before.status, amount: before.amount,
    termMonths: before.termMonths, documents: before.documents,
  });
}

async function logout(page: Page) {
  await page.getByRole('button', { name: 'Меню профиля' }).click().then(() => page.getByRole('menuitem', { name: 'Выйти', exact: true }).click());
}
