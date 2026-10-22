import { expect, type APIRequestContext, type Page } from '@playwright/test';
import { randomBytes, randomUUID } from 'node:crypto';

export interface Session { token: string; user: { role: string } }
export interface Application {
  id: string;
  number: number;
  status: string;
  productCode?: string;
  process: {
    offer?: { kind: string; amount: number };
    outcome?: { reasonTitle: string; reasonCode: string };
    infoRequest?: { items: Array<{ documentType: string | null }> };
    signing?: { code: string };
  };
}
export interface Scenario {
  inn: string;
  name: string;
  scenario: { code: string; request: Record<string, unknown> };
}

export async function signIn(request: APIRequestContext, account: string): Promise<Session> {
  const response = await request.post('/api/v1/auth/sign-in', { data: { email: `${account}@demo.test`, password: 'Demo2026' } });
  expect(response.status(), `Вход ${account}: ${await response.text()}`).toBe(200);
  return response.json();
}

export function headers(session: Session) { return { Authorization: `Bearer ${session.token}` }; }

export async function login(page: Page, account: string): Promise<Session> {
  await page.goto('/sign-in');
  await page.getByLabel('Email', { exact: false }).fill(`${account}@demo.test`);
  await page.getByLabel('Пароль', { exact: false }).fill('Demo2026');
  const responsePromise = page.waitForResponse((response) => response.url().endsWith('/api/v1/auth/sign-in') && response.request().method() === 'POST');
  await page.getByRole('button', { name: 'Войти', exact: true }).click();
  const response = await responsePromise;
  expect(response.status()).toBe(200);
  await expect(page).toHaveURL(/\/(client|bank)$/);
  return response.json();
}

export async function scenario(request: APIRequestContext, account: string): Promise<Scenario> {
  const inns: Record<string, string> = {
    client: '000100000004', mebel: '0001001014', logistic: '0001001021', romashka: '0001001039',
    gromov: '000100020145', belova: '000100020219', technopark: '0001001046'
  };
  const response = await request.get('/sim/control');
  expect(response.ok()).toBeTruthy();
  const state = await response.json();
  const company = state.companies.find((item: Scenario) => item.inn === inns[account]);
  expect(company, `Сценарная компания ${account}`).toBeTruthy();
  return company;
}

export async function clearActive(request: APIRequestContext, session: Session): Promise<void> {
  const response = await request.get('/api/v1/applications', { headers: headers(session) });
  expect(response.ok()).toBeTruthy();
  for (const app of await response.json() as Application[]) {
    if (app.status === 'SIGNING') {
      const codeResponse = await request.post(`/api/v1/applications/${app.id}/signing/code`, { headers: headers(session) });
      expect(codeResponse.ok(), await codeResponse.text()).toBeTruthy();
      const signing: Application = await codeResponse.json();
      const signed = await request.post(`/api/v1/applications/${app.id}/sign`, { headers: headers(session), data: { code: signing.process.signing!.code } });
      expect(signed.ok(), await signed.text()).toBeTruthy();
      await waitStatus(request, session, app.id, ['DISBURSED']);
      continue;
    }
    if (app.status === 'DISBURSING') {
      await waitStatus(request, session, app.id, ['DISBURSED']);
      continue;
    }
    if (!['DISBURSED', 'REJECTED', 'WITHDRAWN', 'CLOSED'].includes(app.status)) {
      const withdrawn = await request.post(`/api/v1/applications/${app.id}/withdraw`, { headers: headers(session), data: { comment: 'Подготовка независимого демо-прогона' } });
      expect(withdrawn.ok(), `Очистка ${app.id}, ${app.status}: ${await withdrawn.text()}`).toBeTruthy();
    }
  }
}

export async function createScenario(request: APIRequestContext, account: string): Promise<{ session: Session; app: Application }> {
  const session = await signIn(request, account);
  await clearActive(request, session);
  const company = await scenario(request, account);
  const created = await request.post('/api/v1/applications', { headers: headers(session), data: { ...company.scenario.request, bureauConsent: true } });
  expect(created.status(), await created.text()).toBe(201);
  const app: Application = await created.json();
  const submitted = await request.post(`/api/v1/applications/${app.id}/submit`, { headers: { ...headers(session), 'Idempotency-Key': randomUUID() } });
  expect(submitted.ok(), await submitted.text()).toBeTruthy();
  return { session, app };
}

export async function waitStatus(request: APIRequestContext, session: Session, id: string, statuses: string[]): Promise<Application> {
  let latest: Application;
  await expect.poll(async () => {
    const response = await request.get(`/api/v1/applications/${id}`, { headers: headers(session) });
    expect(response.ok(), await response.text()).toBeTruthy();
    latest = await response.json();
    return latest.status;
  }, { timeout: 90_000, intervals: [500, 1000, 2000], message: `Заявка ${id}: ожидается ${statuses.join('/')}` }).toMatch(new RegExp(`^(${statuses.join('|')})$`));
  return latest!;
}

export async function noHorizontalOverflow(page: Page): Promise<void> {
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth), { message: 'Страница не должна прокручиваться горизонтально' }).toBeLessThanOrEqual(1);
}

export function syntheticInn(): string {
  const digits = `00${[...randomBytes(7)].map((value) => value % 10).join('')}`;
  const checksum = [2, 4, 10, 3, 5, 9, 4, 6, 8].reduce((sum, weight, index) => sum + weight * Number(digits[index]), 0) % 11 % 10;
  return digits + checksum;
}

export async function assertNotification(request: APIRequestContext, page: Page, session: Session, app: Application): Promise<void> {
  const link = `/${session.user.role === 'CLIENT' ? 'client' : 'bank'}/applications/${app.id}`;
  await expect.poll(async () => {
    const response = await request.get('/api/v1/notifications?page=0&size=100', { headers: headers(session) });
    expect(response.ok()).toBeTruthy();
    const data = await response.json();
    return data.items.some((item: { link: string }) => item.link === link);
  }, { timeout: 20_000 }).toBe(true);
  await page.getByRole('button', { name: /^Уведомления:/ }).click();
  const dialog = page.getByRole('dialog', { name: 'Уведомления', exact: true });
  await expect(dialog.getByRole('button', { name: new RegExp(`Заявк[аиуе].*${app.number}|заявке.*${app.number}`, 'i') }).first()).toBeVisible();
  await page.keyboard.press('Escape');
}
