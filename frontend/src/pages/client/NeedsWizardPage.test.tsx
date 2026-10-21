import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { NeedsWizardPage } from './NeedsWizardPage';

const create = vi.fn().mockResolvedValue({ id: 'app-1' });

vi.mock('../../api/client', () => ({
  productsApi: {
    purposes: vi.fn().mockResolvedValue([
      { code: 'EQUIPMENT', title: 'Покупка оборудования' },
      { code: 'CASH_GAP', title: 'Покрыть кассовый разрыв' }
    ]),
    match: vi.fn().mockResolvedValue([
      {
        product: { code: 'INVESTMENT', name: 'Инвестиционный кредит', description: 'Оборудование', minAmount: 1_000_000, maxAmount: 50_000_000, baseRate: 18, revolving: false },
        fits: true,
        reasons: ['Подходит для цели «покупка оборудования»'],
        adjustments: [],
        termMonths: 12,
        monthlyPayment: 91_679.99,
        overpayment: 100_159.88
      }
    ])
  },
  applicationsApi: { create: (...args: unknown[]) => create(...args) }
}));

describe('NeedsWizardPage', () => {
  it('suggests products for the chosen purpose and starts a draft from one', async () => {
    render(
      <MemoryRouter initialEntries={['/client/new']}>
        <Routes>
          <Route path="/client/new" element={<NeedsWizardPage />} />
          <Route path="/client/applications/:id" element={<p>Черновик открыт</p>} />
        </Routes>
      </MemoryRouter>
    );
    const search = await screen.findByRole('button', { name: 'Подобрать' });
    expect(search).toBeDisabled();
    await userEvent.click(screen.getByText('Покупка оборудования'));
    await userEvent.click(search);
    expect(await screen.findByText('Инвестиционный кредит')).toBeInTheDocument();
    expect(screen.getByText('Подходит для цели «покупка оборудования»')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Оформить заявку' }));
    expect(create).toHaveBeenCalledWith({ productCode: 'INVESTMENT', purpose: 'EQUIPMENT', amount: 1_000_000, termMonths: 12, bureauConsent: false });
    expect(await screen.findByText('Черновик открыт')).toBeInTheDocument();
  });
});
