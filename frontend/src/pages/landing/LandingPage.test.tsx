import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { LandingPage } from './LandingPage';

const { product } = vi.hoisted(() => ({ product: { code: 'WORKING_CAPITAL', name: 'Кредит на пополнение оборота', description: 'Закупки и зарплаты', purposes: ['WORKING_CAPITAL'], minAmount: 300_000, maxAmount: 10_000_000, minTermMonths: 6, maxTermMonths: 36, baseRate: 19.5, autoApprovalLimit: 3_000_000, revolving: false } }));

vi.mock('../../api/client', () => ({
  productsApi: {
    list: vi.fn().mockResolvedValue([product]),
    purposes: vi.fn().mockResolvedValue([{ code: 'WORKING_CAPITAL', title: 'Пополнить оборотные средства' }]),
    match: vi.fn().mockResolvedValue([{ product, fits: true, reasons: [], adjustments: [], termMonths: 24, monthlyPayment: 101_234.5, overpayment: 429_628 }])
  }
}));

let session: unknown = null;
vi.mock('../../auth/AuthProvider', () => ({
  useAuth: () => ({ session }),
  roleHome: { CLIENT: '/client', CREDIT_MANAGER: '/bank', UNDERWRITER: '/bank', RISK_ADMIN: '/bank' }
}));

describe('LandingPage', () => {
  it('shows the product line and the payment to a guest and leads to sign-up', async () => {
    session = null;
    render(<MemoryRouter><LandingPage /></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: 'Кредит на пополнение оборота' })).toBeInTheDocument();
    const calculator = screen.getByRole('heading', { name: 'Рассчитайте кредит' }).parentElement!;
    expect(await within(calculator).findByText(/101/)).toBeInTheDocument();
    for (const link of screen.getAllByRole('link', { name: 'Оформить заявку онлайн' })) expect(link).toHaveAttribute('href', '/sign-up');
    expect(screen.getByRole('link', { name: 'Войти' })).toHaveAttribute('href', '/sign-in');
  });

  it('takes a signed-in client to the cabinet', async () => {
    session = { user: { role: 'CLIENT' } };
    render(<MemoryRouter><LandingPage /></MemoryRouter>);
    expect(screen.getByRole('link', { name: 'В кабинет' })).toHaveAttribute('href', '/client');
    expect(screen.getAllByRole('link', { name: 'Оформить заявку онлайн' })[0]).toHaveAttribute('href', '/client/new');
    await screen.findByRole('heading', { name: 'Кредит на пополнение оборота' });
  });
});
