import { test, expect, type Page } from '@playwright/test';
import { setTimeout as pause } from 'node:timers/promises';
import { clearActive, createScenario, headers, login, noHorizontalOverflow, waitStatus } from '../helpers/cabinet.js';

test.use({ trace: 'off', screenshot: 'off' });
test.setTimeout(180_000);
test.beforeEach(async ({}, info) => {
  test.skip(info.project.name !== 'desktop' || process.env.MAIL_TESTS === '1', 'Один отдельный прогон всех ширин');
  await pause(6000);
});

const widths = [320, 390, 640, 768, 1024, 1365];
const heroAction = (page: Page) => page.getByRole('heading', { name: 'Кредит для бизнеса онлайн', exact: true })
  .locator('..').getByRole('link', { name: 'Оформить заявку онлайн', exact: true });

async function ready(page: Page, heading?: string) {
  if (heading) await expect(page.getByRole('heading', { name: heading, exact: true })).toBeVisible();
  else await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  await page.evaluate(() => document.fonts.ready);
  await noHorizontalOverflow(page);
}

test('Лендинг сохраняет отступы CTA и видимые якоря на узких ширинах и границах', async ({ page }, info) => {
  for (const width of [320, 390, 599, 600, 640, 768, 899, 900, 1023, 1024, 1365]) {
    await page.setViewportSize({ width, height: 1000 });
    await page.goto('/');
    await ready(page, 'Кредит для бизнеса онлайн');
    const action = heroAction(page);
    const bounds = await action.evaluate((element) => {
      const box = element.getBoundingClientRect();
      const node = Array.from(element.childNodes).find((child) => child.nodeType === Node.TEXT_NODE && child.textContent?.trim());
      if (!node) throw new Error('Не найден текст CTA');
      const range = document.createRange(); range.selectNodeContents(node);
      const text = range.getBoundingClientRect();
      let clip = element.parentElement;
      while (clip && getComputedStyle(clip).overflowX !== 'hidden') clip = clip.parentElement;
      return { left: text.left - box.left, right: box.right - text.right,
        bottom: box.bottom, clipBottom: clip?.getBoundingClientRect().bottom,
        rightEdge: box.right, clipRight: clip?.getBoundingClientRect().right };
    });
    expect(bounds.left, `Левый отступ при ${width}`).toBeGreaterThanOrEqual(16);
    expect(bounds.right, `Правый отступ при ${width}`).toBeGreaterThanOrEqual(16);
    if (bounds.clipRight !== undefined) expect(bounds.rightEdge, `CTA обрезана при ${width}`).toBeLessThanOrEqual(bounds.clipRight);
    if (bounds.clipBottom !== undefined) expect(bounds.bottom).toBeLessThanOrEqual(bounds.clipBottom);
    await info.attach(`landing-${width}`, { body: await page.screenshot(), contentType: 'image/png' });
    await page.getByRole('link', { name: 'Рассчитать платёж', exact: true }).click();
    await expect(page).toHaveURL(/#calculator$/);
    await info.attach(`calculator-anchor-${width}`, { body: await page.screenshot(), contentType: 'image/png' });
    await expect.poll(async () => {
      const title = await page.locator('#calculator-title').boundingBox();
      const header = await page.locator('header').boundingBox();
      return title && header ? title.y - (header.y + header.height) : -1;
    }, { message: `Заголовок расчёта не должен скрываться под шапкой при ${width}` }).toBeGreaterThanOrEqual(0);
  }
  await page.setViewportSize({ width: 320, height: 1000 });
  await page.goto('/');
  await page.addStyleTag({ content: 'main a.MuiButton-root { font-size: 21.25px !important; }' });
  await ready(page, 'Кредит для бизнеса онлайн');
  await expect(heroAction(page)).toBeVisible();
  const enlarged = await heroAction(page).evaluate((element) => {
    const box = element.getBoundingClientRect();
    const node = Array.from(element.childNodes).find((child) => child.nodeType === Node.TEXT_NODE && child.textContent?.trim());
    if (!node) throw new Error('Не найден текст CTA');
    const range = document.createRange(); range.selectNodeContents(node);
    const text = range.getBoundingClientRect();
    return text.width <= box.width - 32 && text.height <= box.height - 8;
  });
  expect(enlarged).toBe(true);
  await info.attach('landing-320-cta-text-125-percent', { body: await page.screenshot(), contentType: 'image/png' });
});

test('Клиентские карточка, сотрудники и профиль читаются на шести ширинах', async ({ page, request }, info) => {
  const prepared = await createScenario(request, 'client');
  try {
    await waitStatus(request, prepared.session, prepared.app.id, ['OFFER_ISSUED']);
    await login(page, 'client');
    for (const width of widths) {
      await page.setViewportSize({ width, height: 1000 });
      for (const route of ['/client', '/client/employees', '/profile', `/client/applications/${prepared.app.id}`]) {
        await page.goto(route); await ready(page);
        await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
      }
      await info.attach(`client-offer-${width}`, { body: await page.screenshot(), contentType: 'image/png' });
    }
  } finally { await clearActive(request, prepared.session); }
});

test('Банковский итог, история и редакторы читаются на шести ширинах', async ({ page, request }, info) => {
  const prepared = await createScenario(request, 'logistic');
  try {
    await waitStatus(request, prepared.session, prepared.app.id, ['MANUAL_REVIEW']);
    for (const account of ['underwriter', 'risk']) {
      await login(page, account);
      for (const width of widths) {
        await page.setViewportSize({ width, height: 1000 });
        for (const route of ['/bank', '/bank/rules', '/bank/products', `/bank/applications/${prepared.app.id}`]) {
          await page.goto(route);
          if (route === '/bank/rules') await expect(page.getByRole('heading', { name: 'История версий правил', exact: true })).toBeVisible();
          if (route === '/bank/products') await expect(page.getByRole('heading', { name: 'История версий продукта', exact: true })).toBeVisible();
          await ready(page);
          await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
        }
        await expect(page.getByRole('heading', { name: 'Краткий итог', exact: true })).toBeVisible();
        await info.attach(`bank-${account}-${width}`, { body: await page.screenshot(), contentType: 'image/png' });
      }
      const state = await request.get(`/api/v1/applications/${prepared.app.id}`, { headers: headers(prepared.session) });
      expect((await state.json()).status).toBe('MANUAL_REVIEW');
      await page.getByRole('button', { name: 'Выйти', exact: true }).click();
    }
  } finally { await clearActive(request, prepared.session); }
});
