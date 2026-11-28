import { expect, test, type Page } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { setTimeout as pause } from 'node:timers/promises';
import { headers, type Session } from '../helpers/cabinet.js';

test.use({ trace: 'off', screenshot: 'off' });

const credentials = (account: string) => ({ email: `${account}@demo.test`, password: 'Demo2026' });

async function browserLogin(page: Page, account: string): Promise<Session> {
  await page.goto('/sign-in');
  await page.getByLabel('Email', { exact: false }).fill(credentials(account).email);
  await page.getByLabel('Пароль', { exact: false }).fill(credentials(account).password);
  const signIn = page.waitForResponse((response) => response.url().endsWith('/api/v1/auth/sign-in'));
  await page.getByRole('button', { name: 'Войти', exact: true }).click();
  const response = await signIn;
  expect(response.status()).toBe(200);
  return response.json();
}

test('two browser sessions keep the winning draft and can explicitly overwrite the fresh version', async ({ browser, baseURL, request }) => {
  const [first, second] = await Promise.all([browser.newContext({ baseURL }), browser.newContext({ baseURL })]);
  const [a, b] = await Promise.all([first.newPage(), second.newPage()]);
  const failedRequests: number[] = [];
  for (const page of [a, b]) page.on('response', response => {
    if (response.status() === 401 || response.status() === 429) failedRequests.push(response.status());
  });
  let id: string | undefined;
  let owner: Session | undefined;
  try {
    const sessionA = await browserLogin(a, 'client');
    // Existing JWT issuance has second-resolution timestamps and no token nonce.
    await pause(1100);
    const sessionB = await browserLogin(b, 'client');
    expect(sessionA.token !== sessionB.token).toBe(true);
    owner = sessionA;
    const input = {
      productCode: 'WORKING_CAPITAL', purpose: 'WORKING_CAPITAL', amount: 1_000_000, termMonths: 12,
      declaredRevenue12m: 12_000_000, bureauConsent: true
    };
    const created = await request.post('/api/v1/applications', { headers: headers(sessionA), data: input });
    expect(created.status()).toBe(201);
    id = (await created.json()).id;
    const path = `/api/v1/applications/${id}`;
    const card = `/client/applications/${id}`;
    await Promise.all([a.goto(card), b.goto(card)]);
    const termA = a.getByLabel('Срок, месяцев');
    const termB = b.getByLabel('Срок, месяцев');
    await expect(termA).toHaveValue('12');
    await expect(termB).toHaveValue('12');
    const saved = a.waitForResponse(response => response.url().endsWith(path) && response.request().method() === 'PUT');
    await termA.fill('15');
    expect((await saved).status()).toBe(200);

    await b.evaluate(() => window.dispatchEvent(new Event('focus')));
    await expect(termB).toHaveValue('15');
    const savedAgain = a.waitForResponse(response => response.url().endsWith(path) && response.request().method() === 'PUT');
    await termA.fill('18');
    const winner = await savedAgain;
    expect(winner.status()).toBe(200);
    const winningVersion = (await winner.json()).version;

    const rejected = b.waitForResponse(response => response.url().endsWith(path) && response.request().method() === 'PUT');
    await termB.fill('21');
    const conflict = await rejected;
    expect(conflict.status()).toBe(409);
    expect(conflict.request().postDataJSON().expectedVersion).toBeLessThan(winningVersion);
    await expect(termB).toHaveValue('21');
    expect(await (await request.get(path, { headers: headers(sessionB) })).json()).toMatchObject({ termMonths: 18, version: winningVersion });

    const overwrite = b.waitForResponse(response => response.url().endsWith(path) && response.request().method() === 'PUT');
    await b.getByRole('button', { name: 'Сохранить поверх' }).click();
    const overwritten = await overwrite;
    expect(overwritten.status()).toBe(200);
    expect(overwritten.request().postDataJSON().expectedVersion).toBe(winningVersion);
    const observed = await Promise.all([sessionA, sessionB].map(session => request.get(path, { headers: headers(session) })));
    expect(observed.map(response => response.status())).toEqual([200, 200]);
    for (const response of observed) expect(await response.json()).toMatchObject({ termMonths: 21 });

    await b.route(`**${path}/submit`, async route => {
      const { expectedVersion } = route.request().postDataJSON();
      expect(Number.isInteger(expectedVersion)).toBe(true);
      const newer = await request.put(path, {
        headers: headers(sessionA), data: { ...input, termMonths: 24, expectedVersion }
      });
      expect(newer.status()).toBe(200);
      await route.continue();
    }, { times: 1 });
    const submission = b.waitForResponse(response => response.url().endsWith(`${path}/submit`) && response.request().method() === 'POST');
    await b.getByRole('button', { name: /^Отправить в банк/ }).click();
    const stopped = await submission;
    expect(stopped.status()).toBe(409);
    expect(stopped.headers()['x-conflict-code']).toBe('draft-version');
    await expect(termB).toHaveValue('21');
    await expect(b.getByRole('button', { name: 'Сохранить поверх' })).toHaveCount(0);
    await b.getByRole('button', { name: 'Загрузить с сервера' }).click();
    await expect(termB).toHaveValue('24');
    expect(await (await request.get(path, { headers: headers(sessionB) })).json()).toMatchObject({ status: 'DRAFT', termMonths: 24 });
    expect(failedRequests).toEqual([]);
  } finally {
    if (id && owner) await request.post(`/api/v1/applications/${id}/withdraw`, { headers: headers(owner), data: { comment: 'BACK-9 E2E' } });
    await Promise.all([first.close(), second.close()]);
  }
});

test('ten office accounts can sign in repeatedly from one IP and existing sessions survive a password throttle', async ({ browser, baseURL, request }) => {
  const [first, second] = await Promise.all([browser.newContext({ baseURL }), browser.newContext({ baseURL })]);
  const [a, b] = await Promise.all([first.newPage(), second.newPage()]);
  const failures: number[] = [];
  for (const page of [a, b]) page.on('response', response => {
    if (response.status() === 401 || response.status() === 429) failures.push(response.status());
  });
  try {
    const [sessionA, sessionB] = await Promise.all([browserLogin(a, 'mebel'), browserLogin(b, 'logistic')]);
    await expect(a).toHaveURL(/\/client$/);
    await expect(b).toHaveURL(/\/client$/);
    const accounts = ['client', 'mebel', 'logistic', 'romashka', 'gromov', 'belova', 'technopark', 'agro', 'novikov', 'stroygrad'];
    for (let round = 0; round < 4; round++) {
      const responses = await Promise.all(accounts.map(account => request.post('/api/v1/auth/sign-in', { data: credentials(account) })));
      expect(responses.map(response => response.status())).toEqual(accounts.map(() => 200));
      const session = await responses[round].json() as Session;
      expect((await request.get('/api/v1/applications', { headers: headers(session) })).status()).toBe(200);
    }

    const email = `back9-${randomUUID()}@example.test`;
    const wrong = (value: string) => request.post('/api/v1/auth/sign-in', { data: { email: value, password: 'Incorrect-test-password' } });
    for (let attempt = 0; attempt < 5; attempt++) {
      expect((await wrong(attempt % 2 ? email.toUpperCase() : email)).status()).toBe(401);
    }
    const limited = await wrong(email);
    expect(limited.status()).toBe(429);
    expect(limited.headers()['x-rate-limit-scope']).toBe('signin');
    expect(Number(limited.headers()['retry-after'])).toBeGreaterThan(0);
    const active = await Promise.all([sessionA, sessionB].map(session => request.get('/api/v1/applications', { headers: headers(session) })));
    expect(active.map(response => response.status())).toEqual([200, 200]);
    expect(failures).toEqual([]);
  } finally {
    await Promise.all([first.close(), second.close()]);
  }
});
