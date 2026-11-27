import { expect, test, type Page } from '@playwright/test';
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
  await noHorizontalOverflow(page);
  await welcome.getByRole('button', { name: 'Начать обучение', exact: true }).click();
  await expect(page.getByText('Начните с черновика', { exact: true })).toBeVisible();
  await walkTour(page);
});
