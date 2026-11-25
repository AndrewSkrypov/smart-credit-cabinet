import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { loanServicingApi } from '../api/client';
import { LoanAccount } from '../types';
import { LoanServicingBlock } from './LoanServicingBlock';

vi.mock('../api/client', () => ({
  loanServicingApi: {
    getByApplication: vi.fn(),
    drawTranche: vi.fn(),
    payNext: vi.fn(),
    repayEarly: vi.fn()
  }
}));

const activeRevolvingLoan: LoanAccount = {
  id: 'loan-1',
  applicationId: 'app-1',
  contractNumber: 'КД-2026-0001',
  status: 'ACTIVE',
  approvedLimit: 4_000_000,
  disbursedAmount: 2_000_000,
  availableLimit: 2_000_000,
  outstandingPrincipal: 2_000_000,
  rate: 18.0,
  termMonths: 12,
  revolving: true,
  disbursedAt: '2026-11-01T10:00:00Z',
  maturityDate: '2027-11-01',
  repaidAt: null,
  tranches: [
    {
      id: 'tr-1',
      trancheNumber: 1,
      amount: 2_000_000,
      purpose: 'Первая выборка',
      disbursedAt: '2026-11-01T10:00:00Z'
    }
  ],
  payments: [
    {
      id: 'pay-1',
      paymentNumber: 1,
      dueDate: '2026-12-01',
      paymentAmount: 183359.98,
      principalAmount: 153359.98,
      interestAmount: 30000.0,
      remainingBalance: 1846640.02,
      status: 'SCHEDULED',
      paidAt: null
    },
    {
      id: 'pay-2',
      paymentNumber: 2,
      dueDate: '2027-01-01',
      paymentAmount: 183359.98,
      principalAmount: 155660.38,
      interestAmount: 27699.6,
      remainingBalance: 1690979.64,
      status: 'SCHEDULED',
      paidAt: null
    }
  ]
};

describe('LoanServicingBlock', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  it('displays loan summary, tranches, schedule and lets CLIENT draw a tranche on a revolving loan', async () => {
    const afterTranche: LoanAccount = {
      ...activeRevolvingLoan,
      disbursedAmount: 2_500_000,
      availableLimit: 1_500_000,
      outstandingPrincipal: 2_500_000,
      tranches: [
        ...activeRevolvingLoan.tranches,
        {
          id: 'tr-2',
          trancheNumber: 2,
          amount: 500_000,
          purpose: 'Закупка сырья',
          disbursedAt: '2026-11-10T12:00:00Z'
        }
      ]
    };
    vi.mocked(loanServicingApi.getByApplication).mockResolvedValue(activeRevolvingLoan);
    vi.mocked(loanServicingApi.drawTranche).mockResolvedValue(afterTranche);

    render(<LoanServicingBlock applicationId="app-1" />);

    expect(await screen.findByText(/КД-2026-0001/)).toBeInTheDocument();
    expect(screen.getByText('Активен')).toBeInTheDocument();
    expect(screen.getByText('Первая выборка')).toBeInTheDocument();
    expect(screen.getAllByText('Запланирован')).toHaveLength(2);

    const amountInput = screen.getByLabelText(/Сумма транша/i);
    const purposeInput = screen.getByLabelText(/Назначение транша/i);
    await userEvent.clear(amountInput);
    await userEvent.type(amountInput, '500000');
    await userEvent.type(purposeInput, 'Закупка сырья');
    await userEvent.click(screen.getByRole('button', { name: 'Запросить транш' }));

    await waitFor(() => {
      expect(loanServicingApi.drawTranche).toHaveBeenCalledWith('loan-1', 500_000, 'Закупка сырья');
    });
    expect(await screen.findByText('Закупка сырья')).toBeInTheDocument();
  });

  it('calls payNext and repayEarly and switches to repaid state', async () => {
    const afterPayNext: LoanAccount = {
      ...activeRevolvingLoan,
      outstandingPrincipal: 1_846_640.02,
      payments: [
        { ...activeRevolvingLoan.payments[0], status: 'PAID', paidAt: '2026-12-01T09:00:00Z' },
        activeRevolvingLoan.payments[1]
      ]
    };
    const afterRepayEarly: LoanAccount = {
      ...afterPayNext,
      status: 'REPAID',
      outstandingPrincipal: 0,
      repaidAt: '2026-12-05T10:00:00Z',
      payments: afterPayNext.payments.map((p) => ({ ...p, status: 'PAID', paidAt: '2026-12-05T10:00:00Z' }))
    };

    vi.mocked(loanServicingApi.getByApplication).mockResolvedValue(activeRevolvingLoan);
    vi.mocked(loanServicingApi.payNext).mockResolvedValue(afterPayNext);
    vi.mocked(loanServicingApi.repayEarly).mockResolvedValue(afterRepayEarly);

    render(<LoanServicingBlock applicationId="app-1" />);

    await userEvent.click(await screen.findByRole('button', { name: 'Внести очередной платёж' }));
    await waitFor(() => expect(loanServicingApi.payNext).toHaveBeenCalledWith('loan-1'));
    expect(await screen.findByText('Оплачен')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Досрочно погасить' }));
    await waitFor(() => expect(loanServicingApi.repayEarly).toHaveBeenCalledWith('loan-1'));
    expect(await screen.findByText('Погашен')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Внести очередной платёж' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Запросить транш' })).not.toBeInTheDocument();
  });

  it('hides all mutation buttons in readOnly mode for AGENT', async () => {
    vi.mocked(loanServicingApi.getByApplication).mockResolvedValue(activeRevolvingLoan);

    render(<LoanServicingBlock applicationId="app-1" readOnly />);

    expect(await screen.findByText(/КД-2026-0001/)).toBeInTheDocument();
    expect(screen.getByText('Первая выборка')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Запросить транш' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Внести очередной платёж' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Досрочно погасить' })).not.toBeInTheDocument();
  });

  it('hides tranche form for non-revolving loan while keeping repayment buttons', async () => {
    const termLoan: LoanAccount = {
      ...activeRevolvingLoan,
      revolving: false,
      disbursedAmount: 4_000_000,
      availableLimit: 0
    };
    vi.mocked(loanServicingApi.getByApplication).mockResolvedValue(termLoan);

    render(<LoanServicingBlock applicationId="app-1" />);

    expect(await screen.findByText(/КД-2026-0001/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Запросить транш' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Внести очередной платёж' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Досрочно погасить' })).toBeInTheDocument();
  });
});
