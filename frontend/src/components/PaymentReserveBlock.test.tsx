import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PaymentReserveInputs, PaymentReserveQuote, paymentReserveApi } from '../api/financing';
import { PaymentReserveBlock } from './PaymentReserveBlock';

vi.mock('../api/financing', () => ({
  paymentReserveApi: { get: vi.fn(), save: vi.fn(), receipt: vi.fn(), confirm: vi.fn() }
}));

const inputs: PaymentReserveInputs = {
  openingBalance: 1_900_000,
  expectedReceiptAmount: 140_000,
  payableAmount: 2_000_000,
  targetBuffer: 30_000,
  controlDate: '2026-12-10',
  paymentDate: '2026-12-15'
};

const pending: PaymentReserveQuote = {
  version: 1,
  inputs,
  receiptStatus: 'PENDING',
  receivedAmount: 0,
  remainingLimit: 200_000,
  requiredAmount: 0,
  suggestedDraw: 0,
  shortfall: 0,
  projectedBalance: 40_000,
  canDraw: false,
  drawnAmount: 0
};

const delayed: PaymentReserveQuote = {
  ...pending,
  version: 2,
  receiptStatus: 'DELAYED',
  requiredAmount: 130_000,
  suggestedDraw: 130_000,
  projectedBalance: -100_000,
  canDraw: true
};

describe('PaymentReserveBlock', () => {
  beforeEach(() => vi.resetAllMocks());

  it('acts on a current delayed quote and refreshes the loan only after separate confirmation', async () => {
    const refreshLoan = vi.fn().mockResolvedValue(undefined);
    vi.mocked(paymentReserveApi.get).mockResolvedValue({ enabled: true, quote: pending });
    vi.mocked(paymentReserveApi.receipt).mockResolvedValue(delayed);
    vi.mocked(paymentReserveApi.confirm).mockResolvedValue({
      ...delayed, version: 3, drawnAmount: 130_000, requiredAmount: 0, suggestedDraw: 0, projectedBalance: 30_000, canDraw: false
    });
    render(<PaymentReserveBlock loanId="loan-1" active onDrawn={refreshLoan} />);

    expect(await screen.findByText('Ожидается поступление')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Подтвердить выборку/ })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /деньги задержались/ }));
    expect(await screen.findByText('Доступна выборка по действующей линии')).toBeInTheDocument();
    expect(paymentReserveApi.receipt).toHaveBeenCalledWith('loan-1', expect.any(String), 'DELAYED');
    expect(paymentReserveApi.confirm).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: /Подтвердить выборку/ }));
    await waitFor(() => expect(refreshLoan).toHaveBeenCalledTimes(1));
    expect(paymentReserveApi.confirm).toHaveBeenCalledWith('loan-1', 2);
    expect(screen.getByText(/Транш .+подтверждён/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Подтвердить выборку/ })).not.toBeInTheDocument();
  });

  it('shows the shortfall instead of proposing a partial draw', async () => {
    vi.mocked(paymentReserveApi.get).mockResolvedValue({
      enabled: true,
      quote: { ...delayed, remainingLimit: 80_000, suggestedDraw: 80_000, shortfall: 50_000, canDraw: false }
    });
    render(<PaymentReserveBlock loanId="loan-2" active onDrawn={vi.fn()} />);

    expect(await screen.findByText(/До целевого остатка не хватает/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Подтвердить выборку/ })).not.toBeInTheDocument();
  });
});
