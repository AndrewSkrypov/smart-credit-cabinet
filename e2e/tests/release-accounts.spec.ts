import { test, expect, type APIRequestContext } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { setTimeout as pause } from 'node:timers/promises';
import { clearActive, createScenario, headers, noHorizontalOverflow, syntheticInn, waitStatus, type Session } from '../helpers/cabinet.js';

const mailEnabled = process.env.MAIL_TESTS === '1';
const mailpit = process.env.MAILPIT_URL ?? 'http://127.0.0.1:8025';

// Capabilities and passwords must not appear in screenshots or HTTP traces.
test.use({ trace: 'off', screenshot: 'off' });
// Keep the combined desktop/mobile flow within the real gateway's 30 auth requests/minute budget.
test.beforeEach(async () => { await pause(6000); });
// A password flow uses up to ten auth requests; replenish that budget before the next role or viewport.
test.afterEach(async () => { await pause(21000); });
test.setTimeout(180_000);

async function register(request: APIRequestContext) {
  const email = `access-e2e-${randomUUID()}@example.test`;
  const response = await request.post('/api/v1/auth/sign-up', { data: { inn: syntheticInn(), email, fullName: 'Синтетический представитель', password: 'Test2026safe', personalDataConsent: true } });
  expect(response.status()).toBe(200);
  return { email, session: await response.json() as Session };
}

async function assertRevoked(request: APIRequestContext, session: Session) {
  for (const path of ['/api/v1/users/me', '/api/v1/applications', '/api/v1/notifications']) {
    expect((await request.get(path, { headers: headers(session) })).status()).toBe(401);
  }
}

async function securityLink(request: APIRequestContext, email: string, subject: string): Promise<URL> {
  let identifier = '';
  await expect.poll(async () => {
    const response = await request.get(`${mailpit}/api/v1/search`, { params: { query: `to:${email}` } });
    expect(response.status()).toBe(200);
    const mailbox = await response.json() as { messages: Array<{ ID: string; Subject: string }> };
    identifier = mailbox.messages.find((item) => item.Subject === subject)?.ID ?? '';
    return Boolean(identifier);
  }, { timeout: 45000 }).toBe(true);
  const response = await request.get(`${mailpit}/api/v1/message/${identifier}`);
  expect(response.status()).toBe(200);
  const message = await response.json() as { Text: string; HTML: string };
  expect(message.HTML).toBe('');
  const link = message.Text.match(/https?:\/\/\S+/)?.[0];
  expect(Boolean(link)).toBe(true);
  return new URL(link!);
}

if (!mailEnabled) {
  test('Восстановление без почты явно выключено', async ({ page }) => {
    await page.goto('/sign-in');
    await page.getByRole('link', { name: 'Забыли пароль?', exact: true }).click();
    await expect(page.getByText('Восстановление через почту на этом стенде выключено. Обратитесь к владельцу стенда.')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Отправить ссылку', exact: true })).toHaveCount(0);
    await noHorizontalOverflow(page);
  });

  test('Приглашённый сотрудник видит заявку, не подписывает и сразу теряет доступ после отзыва', async ({ page, request }) => {
    const { session: owner, app } = await createScenario(request, 'client');
    let employee: Session | null = null;
    let employeeId = '';
    try {
      await waitStatus(request, owner, app.id, ['OFFER_ISSUED']);
      expect((await request.post(`/api/v1/applications/${app.id}/offer/accept`, { headers: headers(owner) })).status()).toBe(200);
      const inviteResponse = await request.post('/api/v1/companies/me/invitations', { headers: { ...headers(owner), 'Idempotency-Key': randomUUID() }, data: { email: `employee-e2e-${randomUUID()}@example.test`, canSign: false } });
      expect(inviteResponse.status()).toBe(200);
      const invitation = await inviteResponse.json() as { id: string; link: string };
      expect(Boolean(invitation.link)).toBe(true);
      await page.goto(invitation.link);
      await expect(page.getByText('Без права подписи от компании.', { exact: false })).toBeVisible();
      expect(page.url().includes('#')).toBe(false);
      await page.getByLabel('Ваше имя', { exact: false }).fill('Сотрудник без подписи');
      await page.getByLabel('Пароль', { exact: false }).fill('Employee2026');
      await page.getByRole('checkbox', { name: 'Согласен на обработку персональных данных', exact: true }).check();
      await noHorizontalOverflow(page);
      const accepted = page.waitForResponse((response) => response.url().endsWith('/auth/invitations/accept'));
      await page.getByRole('button', { name: 'Принять приглашение', exact: true }).click();
      const response = await accepted;
      expect(response.status()).toBe(200);
      const session = await response.json() as Session & { user: { id: string; canSign: boolean } };
      employee = session;
      employeeId = session.user.id;
      expect(session.user.canSign).toBe(false);
      await expect(page).toHaveURL(/\/client$/);
      await page.goto(`/client/applications/${app.id}`);
      await expect(page.getByText('Договор должен подписать представитель компании с правом подписи.', { exact: true })).toBeVisible();
      await expect(page.getByRole('button', { name: 'Подписать', exact: true })).toHaveCount(0);
      await noHorizontalOverflow(page);
      expect((await request.post(`/api/v1/applications/${app.id}/signing/code`, { headers: headers(employee) })).status()).toBe(403);
      expect((await request.post(`/api/v1/applications/${app.id}/sign`, { headers: headers(employee), data: { code: '123456' } })).status()).toBe(403);
      await page.goto('/client/employees');
      await expect(page.getByText('Приглашать сотрудников и отзывать доступ может представитель с правом подписи.')).toBeVisible();
      await noHorizontalOverflow(page);
      expect((await request.delete(`/api/v1/companies/me/employees/${employeeId}/access`, { headers: { ...headers(owner), 'Idempotency-Key': randomUUID() } })).status()).toBe(200);
      await assertRevoked(request, employee);
      await page.goto('/client');
      await expect(page).toHaveURL(/\/sign-in\?/);
    } finally {
      if (employeeId) expect((await request.delete(`/api/v1/companies/me/employees/${employeeId}/access`, { headers: { ...headers(owner), 'Idempotency-Key': randomUUID() } })).status()).toBe(200);
      await clearActive(request, owner);
    }
  });

  test('Профиль меняет пароль и завершает все сессии', async ({ page, request }) => {
    const { email, session } = await register(request);
    await page.goto('/sign-in');
    await page.evaluate((value) => localStorage.setItem('cabinet.session', JSON.stringify(value)), session);
    await page.goto('/profile');
    await page.getByLabel('Текущий пароль', { exact: false }).fill('Test2026safe');
    await page.getByLabel('Новый пароль', { exact: false }).fill('Updated2026');
    await noHorizontalOverflow(page);
    await page.getByRole('button', { name: 'Сменить пароль', exact: true }).click();
    await expect(page).toHaveURL(/\/sign-in\?security=1$/);
    await assertRevoked(request, session);
    await page.getByLabel('Email', { exact: false }).fill(email);
    await page.getByLabel('Пароль', { exact: false }).fill('Updated2026');
    const loggedIn = page.waitForResponse((response) => response.url().endsWith('/auth/sign-in'));
    await page.getByRole('button', { name: 'Войти', exact: true }).click();
    const response = await loggedIn;
    expect(response.status()).toBe(200);
    const fresh: Session = await response.json();
    await expect(page).toHaveURL(/\/client$/);
    await page.goto('/profile');
    await page.getByRole('button', { name: 'Выйти на всех устройствах', exact: true }).click();
    await page.getByRole('button', { name: 'Завершить все сессии', exact: true }).click();
    await expect(page).toHaveURL(/\/sign-in\?security=1$/);
    await assertRevoked(request, fresh);
  });
} else {
  test('Почтовая ссылка восстанавливает пароль без подписки на бизнес-уведомления', async ({ page, request }) => {
    const { email, session } = await register(request);
    await page.goto('/password-recovery');
    await page.getByLabel('Email', { exact: false }).fill(email);
    await page.getByRole('button', { name: 'Отправить ссылку', exact: true }).click();
    await expect(page.getByText(/Если адрес зарегистрирован, на него придёт ссылка/)).toBeVisible();
    const link = await securityLink(request, email, 'Восстановление пароля в кабинете');
    expect(link.pathname).toBe('/reset-password');
    await page.goto(link.toString());
    await expect(page.getByRole('heading', { name: 'Новый пароль', exact: true })).toBeVisible();
    expect(page.url().includes('#')).toBe(false);
    await page.getByLabel('Новый пароль', { exact: false }).fill('Restored2026');
    await noHorizontalOverflow(page);
    await page.getByRole('button', { name: 'Установить пароль', exact: true }).click();
    await expect(page.getByText('Пароль изменён. Войдите с новым паролем.')).toBeVisible();
    await assertRevoked(request, session);
    const reused = await request.post('/api/v1/auth/password-reset', { data: { token: link.hash.slice('#token='.length), password: 'Restored2026' } });
    expect(reused.status()).toBe(410);
    await page.getByRole('link', { name: 'Вернуться ко входу', exact: true }).click();
    await page.getByLabel('Email', { exact: false }).fill(email);
    await page.getByLabel('Пароль', { exact: false }).fill('Restored2026');
    await page.getByRole('button', { name: 'Войти', exact: true }).click();
    await expect(page).toHaveURL(/\/client$/);
  });

  test('Приглашение доставляется по почте, а ссылка не возвращается приглашающему', async ({ page, request }) => {
    const { session: owner } = await register(request);
    const email = `invited-mail-${randomUUID()}@example.test`;
    const response = await request.post('/api/v1/companies/me/invitations', { headers: { ...headers(owner), 'Idempotency-Key': randomUUID() }, data: { email, canSign: false } });
    expect(response.status()).toBe(200);
    expect((await response.json()).link).toBe(null);
    const link = await securityLink(request, email, 'Приглашение в кабинет компании');
    expect(link.pathname).toBe('/accept-invitation');
    await page.goto(link.toString());
    await expect(page.getByText('Без права подписи от компании.', { exact: false })).toBeVisible();
    expect(page.url().includes('#')).toBe(false);
    await page.getByLabel('Ваше имя', { exact: false }).fill('Сотрудник по приглашению');
    await page.getByLabel('Пароль', { exact: false }).fill('Employee2026');
    await page.getByRole('checkbox', { name: 'Согласен на обработку персональных данных', exact: true }).check();
    await noHorizontalOverflow(page);
    await page.getByRole('button', { name: 'Принять приглашение', exact: true }).click();
    await expect(page).toHaveURL(/\/client$/);
    await page.goto('/client/employees');
    await expect(page.getByText('Приглашать сотрудников и отзывать доступ может представитель с правом подписи.')).toBeVisible();
    await noHorizontalOverflow(page);
  });
}
