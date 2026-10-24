import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { LandingPage } from './LandingPage';
import { productsApi } from '../../api/client';

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
  it('does not advertise unavailable products when the catalogue is empty', async () => {
    session = null;
    vi.mocked(productsApi.list).mockResolvedValueOnce([]);
    render(<MemoryRouter><LandingPage /></MemoryRouter>);
    expect(await screen.findByText(/В каталоге пока нет доступных продуктов/)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Овердрафт для кассовых разрывов/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Кредит с господдержкой/ })).not.toBeInTheDocument();
  });

  it('shows a retry action on a catalogue error instead of permanent skeletons', async () => {
    session = null;
    vi.mocked(productsApi.list).mockRejectedValueOnce(new Error('Каталог недоступен'));
    render(<MemoryRouter><LandingPage /></MemoryRouter>);
    expect(await screen.findByText('Каталог недоступен')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Повторить' })).toBeInTheDocument();
  });

  it('clears the old payment and disables proceeding after the amount is cleared', async () => {
    session = null;
    render(<MemoryRouter><LandingPage /></MemoryRouter>);
    const calculator = screen.getByRole('heading', { name: 'Рассчитайте кредит' }).parentElement!;
    expect(await within(calculator).findByText(/101/)).toBeInTheDocument();
    await userEvent.clear(within(calculator).getByLabelText('Сумма'));
    expect(within(calculator).queryByText(/101/)).not.toBeInTheDocument();
    const action = within(calculator).getByText('Оформить заявку онлайн').closest('a,button');
    expect(action).toHaveAttribute('aria-disabled', 'true');
  });
  it('shows the product line and the payment to a guest and leads to sign-up', async () => {
    session = null;
    render(<MemoryRouter><LandingPage /></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: 'Кредит на пополнение оборота' })).toBeInTheDocument();
    const calculator = screen.getByRole('heading', { name: 'Рассчитайте кредит' }).parentElement!;
    expect(await within(calculator).findByText(/101/)).toBeInTheDocument();
    const calculatorLink = within(calculator).getByRole('link', { name: 'Оформить заявку онлайн' });
    const next = new URLSearchParams(calculatorLink.getAttribute('href')!.split('?')[1]).get('next');
    expect(next).toContain('/client/new?purpose=WORKING_CAPITAL');
    expect(next).toContain('amount=2000000');
    expect(next).toContain('termMonths=24');
    expect(next).toContain('product=WORKING_CAPITAL');
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
