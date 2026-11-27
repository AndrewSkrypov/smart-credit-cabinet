import { expect, test, type Locator, type Page } from '@playwright/test';
import { setTimeout as delay } from 'node:timers/promises';
import { clearActive, createScenario, login, noHorizontalOverflow } from '../helpers/cabinet.js';

/** Walks a started tour to its end and checks that every spotlight stays inside the viewport. */
async function walkTour(page: Page) {
  const dialog = page.getByRole('dialog', { name: /^Шаг \d+ из \d+:/ });
  await expect(dialog).toBeVisible();
  const total = Number((await dialog.getAttribute('aria-label'))!.match(/из (\d+)/)![1]);
  expect(total).toBeGreaterThan(1);
  for (let step = 1; step <= total; step += 1) {
    await expect(page.getByRole('dialog', { name: new RegExp(`^Шаг ${step} из ${total}:`) })).toBeVisible();
    const spotlight = page.getByTestId('onboarding-spotlight');
    await expect(spotlight).toBeVisible();
    const viewport = page.viewportSize()!;
    await expect.poll(async () => {
      const box = await spotlight.boundingBox();
      return box !== null && box.x >= 0 && box.x + box.width <= viewport.width + 1 && box.y < viewport.height;
    }, { message: `Подсветка шага ${step} в пределах экрана` }).toBe(true);
    await noHorizontalOverflow(page);
    await dialog.getByRole('button', { name: step < total ? 'Далее' : 'Готово', exact: true }).click();
  }
  await expect(dialog).toHaveCount(0);
}

test('Тур карточки заявки ждёт поздний ответ и подсвечивает шаги текущего статуса', async ({ page }) => {
  const id = 'a0000000-0000-4000-8000-000000000001';
  const company = { id: 'a0000000-0000-4000-8000-000000000002', name: 'ООО «Пример»', inn: '000100000004', legalForm: 'LEGAL_ENTITY' };
  const createdAt = '2026-10-25T00:00:00Z';
  const application = {
    id, number: 101, status: 'INFO_REQUESTED', company,
    product: { code: 'WORKING_CAPITAL', name: 'Оборотный кредит' },
    purposeTitle: 'Закупка товара', amount: 1000000, termMonths: 12, createdAt, allowedActions: [],
    documents: [{ id: 'document', type: 'OTHER', title: 'Документ', fileName: 'example.pdf', sizeBytes: 1200, uploadedAt: createdAt }],
    process: {
      infoRequest: {
        requestedAt: createdAt, deadline: '2026-12-01T00:00:00Z',
        items: [{ title: 'Уточните цель финансирования', question: 'На что нужны средства?' }]
      }
    }
  };
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.addInitScript(companyId => {
    localStorage.setItem('cabinet.session', JSON.stringify({
      token: 'isolated-onboarding-fixture',
      user: { id: 'ui-onboarding', role: 'CLIENT', fullName: 'Проверка', email: 'fixture@example.test', companyId, canSign: true }
    }));
  }, company.id);
  await page.route('**/api/v1/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith('/unread-count')) return route.fulfill({ json: { unread: 0 } });
    if (path.endsWith('/messages')) return route.fulfill({ json: { messages: [], lastSeq: 0, readSeq: 0, unreadCount: 0, writable: true } });
    if (path.endsWith('/events')) return route.fulfill({ json: [{ id: 'created', event: 'CREATED', payload: {}, actorType: 'CLIENT', occurredAt: createdAt }] });
    return route.fulfill({ json: [] });
  });

  const applicationPath = `/api/v1/applications/${id}`;
  let releaseResponse!: () => void;
  const responseGate = new Promise<void>(resolve => { releaseResponse = resolve; });
  await page.route(`**${applicationPath}`, async route => {
    expect(route.request().method()).toBe('GET');
    await responseGate;
    await route.fulfill({ json: application });
  });

  const statusTarget = page.locator('[data-tour="app-status"]');
  const spotlight = page.getByTestId('onboarding-spotlight');
  const requested = page.waitForRequest(request => new URL(request.url()).pathname === applicationPath && request.method() === 'GET');
  try {
    await page.goto(`/client/applications/${id}`);
    await requested;
    await expect(statusTarget).toHaveCount(0);
    await page.getByTestId('onboarding-welcome-banner').getByRole('button', { name: 'Начать обучение' }).click();
    // Keep the response pending for more than a second after the user starts the tour.
    await delay(1100);
    await expect(page.getByRole('dialog', { name: 'Обучение' })).toHaveAttribute('aria-busy', 'true');
    await expect(spotlight).toHaveCount(0);
  } finally {
    releaseResponse();
  }

  const expectSpotlightOn = async (target: Locator) => {
    await expect(spotlight).toBeVisible();
    await expect.poll(async () => {
      const box = await spotlight.boundingBox();
      const anchor = await target.boundingBox();
      return box !== null && anchor !== null
        && Math.abs(box.x - Math.max(4, anchor.x - 6)) < 2
        && Math.abs(box.y - Math.max(4, anchor.y - 6)) < 2
        && Math.abs(box.width - anchor.width - 12) < 2
        && Math.abs(box.height - anchor.height - 12) < 2;
    }).toBe(true);
  };
  const firstStep = page.getByRole('dialog', { name: 'Шаг 1 из 5: Где сейчас заявка и что нужно от вас' });
  await expect(firstStep).toBeVisible();
  await expectSpotlightOn(statusTarget);
  await firstStep.getByRole('button', { name: 'Далее' }).click();
  await expect(page.getByRole('dialog', { name: 'Шаг 2 из 5: Банк просит дополнить заявку' })).toBeVisible();
  await expectSpotlightOn(page.locator('[data-tour="app-info"]'));
});

test('Клиент проходит тур карточки заявки, прогресс сохраняется отдельно от тура кабинета', async ({ page, request }) => {
  test.setTimeout(150_000);
  const { session, app } = await createScenario(request, 'client');
  try {
    await login(page, 'client');
    const welcome = page.getByTestId('onboarding-welcome-banner');
    await expect(welcome.getByText('Впервые в кредитном кабинете?', { exact: true })).toBeVisible();
    await welcome.getByRole('button', { name: 'Скрыть', exact: true }).click();
    await expect(welcome).toHaveCount(0);

    await page.goto(`/client/applications/${app.id}`);
    await expect(welcome.getByText('Впервые в карточке заявки?', { exact: true })).toBeVisible();
    await expect(page.locator('[data-tour="app-history"]')).toBeVisible();
    await noHorizontalOverflow(page);
    await welcome.getByRole('button', { name: 'Начать обучение', exact: true }).click();
    await expect(page.getByText('Где сейчас заявка и что нужно от вас', { exact: true })).toBeVisible();
    await walkTour(page);
    await expect(welcome).toHaveCount(0);

    await page.reload();
    await expect(page.locator('[data-tour="app-status"]')).toBeVisible();
    await expect(welcome).toHaveCount(0);
    await page.goto('/client');
    await expect(page.locator('[data-tour="client-new-btn"]')).toBeVisible();
    await expect(welcome).toHaveCount(0);
  } finally {
    await clearActive(request, session);
  }
});

test('Андеррайтер запускает тур карточки оценки из меню «Обучение»', async ({ page, request }) => {
  test.setTimeout(150_000);
  const { session, app } = await createScenario(request, 'client');
  try {
    await login(page, 'underwriter');
    await page.goto(`/bank/applications/${app.id}`);
    const welcome = page.getByTestId('onboarding-welcome-banner');
    await expect(welcome.getByText('Как читать карточку оценки', { exact: true })).toBeVisible();
    await expect(page.locator('[data-tour="bank-app-audit"]')).toBeVisible();
    await welcome.getByRole('button', { name: 'Скрыть', exact: true }).click();
    await page.getByRole('button', { name: 'Обучение', exact: true }).click();
    await page.getByRole('menuitem', { name: /Гид по этой странице/ }).click();
    await expect(page.getByText('Главное о заявке', { exact: true })).toBeVisible();
    await walkTour(page);
  } finally {
    await clearActive(request, session);
  }
});

test('Риск-администратор видит тур жизненного цикла продуктов', async ({ page }) => {
  await login(page, 'risk');
  await page.goto('/bank/products');
  const welcome = page.getByTestId('onboarding-welcome-banner');
  await expect(welcome.getByText('Конструктор продуктов', { exact: true })).toBeVisible();
  await expect(page.locator('[data-tour="products-history"]')).toBeVisible();
  await noHorizontalOverflow(page);
  await welcome.getByRole('button', { name: 'Начать обучение', exact: true }).click();
  await expect(page.getByText('Начните с черновика', { exact: true })).toBeVisible();
  await walkTour(page);
});
