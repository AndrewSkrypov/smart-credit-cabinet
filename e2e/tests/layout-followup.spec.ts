import { test, expect } from '@playwright/test';
import { noHorizontalOverflow } from '../helpers/cabinet.js';

// Isolated analytics responses: no writes to the preview, financial state or read cursors.
if (process.env.MAIL_TESTS !== '1') {
  test('Аналитика: длинные этапы и суммы не расширяют страницу, все столбцы доступны', async ({ page }, info) => {
    test.skip(info.project.name !== 'desktop', 'All viewport widths are covered in one bounded pass');
    await page.addInitScript(() => localStorage.setItem('cabinet.session', JSON.stringify({
      token: 'isolated-layout-fixture', user: { id: 'risk-ui', fullName: 'Проверка', email: 'fixture@example.test', role: 'RISK_ADMIN', companyId: null, canSign: false }
    })));
    await page.route('**/api/v1/**', route => {
      const path = new URL(route.request().url()).pathname;
      return route.fulfill({ json: path.endsWith('/analytics/funnel') ? {
        from: '2026-09-25T00:00:00Z', to: '2026-10-25T00:00:00Z',
        funnel: [{ code: 'SENT', title: 'Отправлено', applications: 1250 }],
        stageTimes: [{ status: 'SCORING', title: 'Оценка кредитоспособности и долговой нагрузки', transitions: 1250, averageMinutes: 4325.5, medianMinutes: 2610.5 }],
        automaticOutcomes: [{ outcome: 'APPROVE', decisions: 700 }], manualOutcomes: [], manualOverrides: 0,
        disbursedAmount: 3456789123, currentStatuses: { MANUAL_REVIEW: 300 }
      } : { unread: 0 } });
    });
    for (const width of [320, 360, 375, 390, 430, 768, 1280, 1440]) {
      await page.setViewportSize({ width, height: 900 });
      await page.goto('/bank/analytics');
      const table = page.getByRole('table', { name: 'Время на этапах, таблица' });
      await expect(table).toBeVisible();
      await page.evaluate(() => document.fonts.ready);
      await noHorizontalOverflow(page);
      const region = page.getByRole('region', { name: 'Все столбцы времени на этапах' });
      await region.scrollIntoViewIfNeeded();
      const overflow = await region.evaluate(element => element.scrollWidth - element.clientWidth);
      if (overflow > 1) {
        await region.focus();
        await page.keyboard.press('ArrowRight');
        await expect.poll(() => region.evaluate(element => element.scrollLeft)).toBeGreaterThan(0);
        await region.evaluate(element => { element.scrollLeft = element.scrollWidth; });
      }
      const last = await table.getByRole('columnheader', { name: 'Переходов', exact: true }).boundingBox();
      const bounds = await region.boundingBox();
      expect(last!.x).toBeGreaterThanOrEqual(bounds!.x - 1);
      expect(last!.x + last!.width).toBeLessThanOrEqual(bounds!.x + bounds!.width + 1);
      await expect(table.getByRole('cell', { name: '1250', exact: true })).toHaveCount(1);
      await noHorizontalOverflow(page);
      if (width === 320 || width === 1440) await info.attach(`analytics-all-columns-${width}`, { body: await page.screenshot(), contentType: 'image/png' });
    }
    await page.setViewportSize({ width: 390, height: 900 });
    await page.goto('/bank/analytics');
    await page.getByRole('table').waitFor();
    await page.addStyleTag({ content: 'html { font-size: 200% !important; }' });
    await noHorizontalOverflow(page);
    await expect(page.getByRole('button', { name: '90 дней' })).toBeVisible();
  });
}
