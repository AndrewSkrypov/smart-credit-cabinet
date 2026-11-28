import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { loanServicingApi } from '../api/client';
import { AuthProvider, useAuth } from '../auth/AuthProvider';
import { clearSession, readSession, writeSession } from '../auth/session';
import { rubExact } from '../shared/format';
import { LoanAccount, UserSession } from '../types';
import { LoanServicingBlock } from './LoanServicingBlock';

vi.mock('../api/client', async (importOriginal) => ({
  ...await importOriginal<typeof import('../api/client')>(),
  loanServicingApi: {
    getByApplication: vi.fn(),
    drawTranche: vi.fn(),
    payNext: vi.fn(),
    repayEarly: vi.fn()
  }
}));

vi.mock('./PaymentReserveBlock', () => ({
  AgentPaymentReserveBlock: () => null,
  PaymentReserveBlock: () => null
}));

const employeeA: UserSession = {
  token: 'token-a',
  user: {
    id: 'employee-a', companyId: 'company-1', role: 'CLIENT', status: 'ACTIVE',
    fullName: 'Клиент А', email: 'a@example.test', canSign: true
  }
};
const employeeB: UserSession = {
  token: 'token-b',
  user: { ...employeeA.user, id: 'employee-b', fullName: 'Клиент Б', email: 'b@example.test' }
};
const otherCompany: UserSession = {
  token: 'token-other-company',
  user: { ...employeeA.user, companyId: 'company-2' }
};
const applicationId = 'app-1';
const pendingKeyA = 'cabinet.loan-operation:v2:["employee-a","company-1","app-1"]';
const pendingKeyB = 'cabinet.loan-operation:v2:["employee-b","company-1","app-1"]';

function renderLoan(view = <LoanServicingBlock applicationId={applicationId} />) {
  return render(<MemoryRouter><AuthProvider>{view}</AuthProvider></MemoryRouter>);
}

function LoanWithSessionControls() {
  const { acceptSession, session, signOut } = useAuth();
  return (
    <>
      <button onClick={signOut}>Выйти</button>
      <button onClick={() => acceptSession(employeeA)}>Войти как А</button>
      <button onClick={() => acceptSession(employeeB)}>Войти как Б</button>
      <button onClick={() => acceptSession(otherCompany)}>Войти в другую компанию</button>
      {session && <LoanServicingBlock applicationId={applicationId} />}
    </>
  );
}

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
    vi.setSystemTime(new Date('2026-11-20T12:00:00Z'));
    sessionStorage.clear();
    writeSession(employeeA);
  });

  afterEach(() => {
    clearSession();
    vi.useRealTimers();
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

    renderLoan();

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
    expect(loanServicingApi.drawTranche).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: 'Подтвердить' }));

    await waitFor(() => {
      expect(loanServicingApi.drawTranche).toHaveBeenCalledWith('loan-1', 500_000, 'Закупка сырья', expect.any(String));
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

    renderLoan();

    await userEvent.click(await screen.findByRole('button', { name: 'Внести очередной платёж' }));
    expect(loanServicingApi.payNext).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: 'Подтвердить' }));
    await waitFor(() => expect(loanServicingApi.payNext).toHaveBeenCalledWith('loan-1', 'pay-1', expect.any(String)));
    expect(await screen.findByText('Оплачен')).toBeInTheDocument();

    await userEvent.click(await screen.findByRole('button', { name: 'Досрочно погасить' }));
    expect(await screen.findByRole('dialog', { name: 'Погасить кредит досрочно?' })).toBeInTheDocument();
    expect(loanServicingApi.repayEarly).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: 'Погасить' }));
    await waitFor(() => expect(loanServicingApi.repayEarly).toHaveBeenCalledWith('loan-1'));
    expect(await screen.findByText('Погашен')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Внести очередной платёж' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Запросить транш' })).not.toBeInTheDocument();
  });

  it('reuses the original payment and key after losing the response and reopening the page', async () => {
    const afterPayment: LoanAccount = {
      ...activeRevolvingLoan,
      payments: [{ ...activeRevolvingLoan.payments[0], status: 'PAID' }, activeRevolvingLoan.payments[1]],
    };
    vi.mocked(loanServicingApi.getByApplication).mockResolvedValueOnce(activeRevolvingLoan).mockResolvedValue(afterPayment);
    vi.mocked(loanServicingApi.payNext).mockRejectedValueOnce(new Error('Нет ответа')).mockResolvedValueOnce(afterPayment);
    const firstVisit = renderLoan();
    await userEvent.click(await screen.findByRole('button', { name: 'Внести очередной платёж' }));
    await userEvent.click(screen.getByRole('button', { name: 'Подтвердить' }));
    expect(await screen.findByText('Нет ответа')).toBeInTheDocument();
    firstVisit.unmount();

    renderLoan();
    await userEvent.click(await screen.findByRole('button', { name: 'Повторить' }));
    expect(await screen.findByText(/Платёж № 1 .* проведён/)).toBeInTheDocument();
    expect(vi.mocked(loanServicingApi.payNext).mock.calls[1]).toEqual(vi.mocked(loanServicingApi.payNext).mock.calls[0]);
  });

  it('restores a pending payment only for its owner after logout and login on the same application', async () => {
    const payNext = vi.mocked(loanServicingApi.payNext);
    payNext.mockRejectedValueOnce(new Error('Нет ответа')).mockResolvedValueOnce(activeRevolvingLoan);
    vi.mocked(loanServicingApi.getByApplication).mockResolvedValue(activeRevolvingLoan);
    renderLoan(<LoanWithSessionControls />);

    await userEvent.click(await screen.findByRole('button', { name: 'Внести очередной платёж' }));
    await userEvent.click(screen.getByRole('button', { name: 'Подтвердить' }));
    expect(await screen.findByText('Нет ответа')).toBeInTheDocument();
    const originalRequest = payNext.mock.calls[0];
    expect(originalRequest).toEqual(['loan-1', 'pay-1', expect.any(String)]);

    const legacyKey = 'cabinet.loan-operation:app-1';
    const [, paymentId, operationKey] = originalRequest;
    const payment = activeRevolvingLoan.payments[0];
    sessionStorage.setItem(legacyKey, JSON.stringify({
      loanId: 'loan-1', key: operationKey,
      operation: { kind: 'pay', paymentId, amount: payment.paymentAmount, dueDate: payment.dueDate, paymentNumber: payment.paymentNumber }
    }));

    await userEvent.click(await screen.findByRole('button', { name: 'Выйти' }));
    expect(readSession()).toBeNull();
    expect(screen.queryByRole('heading', { name: 'Обслуживание кредита и транши' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Войти как Б' }));
    expect(readSession()).toEqual(employeeB);
    const payButton = await screen.findByRole('button', { name: 'Внести очередной платёж' });
    expect(screen.queryByRole('button', { name: 'Повторить' })).not.toBeInTheDocument();
    expect(payButton).toBeEnabled();

    await userEvent.click(screen.getByRole('button', { name: 'Войти в другую компанию' }));
    expect(readSession()).toEqual(otherCompany);
    expect(await screen.findByRole('button', { name: 'Внести очередной платёж' })).toBeEnabled();
    expect(screen.queryByRole('button', { name: 'Повторить' })).not.toBeInTheDocument();
    expect(payNext).toHaveBeenCalledTimes(1);

    await userEvent.click(screen.getByRole('button', { name: 'Войти как А' }));
    expect(readSession()).toEqual(employeeA);
    await userEvent.click(await screen.findByRole('button', { name: 'Повторить' }));
    expect(await screen.findByText(/Платёж № 1 .* проведён/)).toBeInTheDocument();
    expect(payNext).toHaveBeenCalledTimes(2);
    expect(payNext).toHaveBeenLastCalledWith(...originalRequest);
    expect(sessionStorage.getItem(pendingKeyA)).toBeNull();
  });

  it('keeps a second employee’s pending tranche when the first employee’s retry finishes after switching accounts', async () => {
    const loadLoan = vi.mocked(loanServicingApi.getByApplication).mockResolvedValue(activeRevolvingLoan);
    const drawTranche = vi.mocked(loanServicingApi.drawTranche);
    let finishRetry!: (loan: LoanAccount) => void;
    drawTranche
      .mockRejectedValueOnce(new Error('Нет ответа для А'))
      .mockImplementationOnce(() => new Promise(resolve => { finishRetry = resolve; }))
      .mockRejectedValueOnce(new Error('Нет ответа для Б'))
      .mockResolvedValueOnce(activeRevolvingLoan);
    const view = renderLoan(<LoanWithSessionControls />);

    await userEvent.type(await screen.findByLabelText(/Сумма транша/i), '500000');
    await userEvent.click(screen.getByRole('button', { name: 'Запросить транш' }));
    await userEvent.click(screen.getByRole('button', { name: 'Подтвердить' }));
    expect(await screen.findByText('Нет ответа для А')).toBeInTheDocument();
    await userEvent.click(await screen.findByRole('button', { name: 'Повторить' }));
    const [requestA, retryA] = drawTranche.mock.calls;
    expect(retryA).toEqual(requestA);

    await userEvent.click(screen.getByRole('button', { name: 'Войти как Б' }));
    expect(readSession()).toEqual(employeeB);
    await screen.findByRole('heading', { name: 'Обслуживание кредита и транши' });
    expect(screen.queryByRole('button', { name: 'Повторить' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Запросить транш' })).toBeEnabled();
    expect(JSON.parse(sessionStorage.getItem(pendingKeyA)!).key).toBe(requestA[3]);
    await userEvent.type(screen.getByLabelText(/Сумма транша/i), '350000');
    await userEvent.click(screen.getByRole('button', { name: 'Запросить транш' }));
    await userEvent.click(screen.getByRole('button', { name: 'Подтвердить' }));
    expect(await screen.findByText('Нет ответа для Б')).toBeInTheDocument();
    const requestB = drawTranche.mock.calls[2];
    expect(requestB).toEqual(['loan-1', 350_000, undefined, expect.any(String)]);
    expect(requestB[3]).not.toBe(requestA[3]);
    const pendingB = sessionStorage.getItem(pendingKeyB);
    expect(JSON.parse(pendingB!).key).toBe(requestB[3]);

    await act(async () => finishRetry(activeRevolvingLoan));
    expect(loadLoan).toHaveBeenCalledTimes(2);
    expect(await screen.findByRole('button', { name: 'Повторить' })).toBeEnabled();
    expect(screen.queryByText(/График платежей пересчитан/)).not.toBeInTheDocument();
    expect(sessionStorage.getItem(pendingKeyA)).toBeNull();
    expect(sessionStorage.getItem(pendingKeyB)).toEqual(pendingB);

    view.unmount();
    renderLoan();
    await userEvent.click(await screen.findByRole('button', { name: 'Повторить' }));
    expect(await screen.findByText(/График платежей пересчитан/)).toBeInTheDocument();
    expect(drawTranche).toHaveBeenCalledTimes(4);
    expect(drawTranche).toHaveBeenLastCalledWith(...requestB);
    expect(sessionStorage.getItem(pendingKeyB)).toBeNull();
  });

  it.each(['ACTIVE', 'REPAID'] as const)('keeps the current %s loan when a replay succeeds but refreshing fails', async (status) => {
    const [firstPayment, secondPayment] = activeRevolvingLoan.payments;
    const receipt: LoanAccount = {
      ...activeRevolvingLoan,
      outstandingPrincipal: firstPayment.remainingBalance,
      payments: [{ ...firstPayment, status: 'PAID' }, secondPayment],
    };
    const current: LoanAccount = {
      ...receipt,
      status,
      outstandingPrincipal: status === 'REPAID' ? 0 : 1_500_000,
    };
    const refreshed: LoanAccount = {
      ...current,
      outstandingPrincipal: status === 'REPAID' ? 0 : 1_450_000,
    };
    const key = 'a1da0b52-e765-4ba4-b17b-a42e4a55c50b';
    sessionStorage.setItem(pendingKeyA, JSON.stringify({
      loanId: current.id,
      key,
      operation: {
        kind: 'pay',
        paymentId: firstPayment.id,
        amount: firstPayment.paymentAmount,
        dueDate: firstPayment.dueDate,
        paymentNumber: firstPayment.paymentNumber,
      },
    }));
    vi.mocked(loanServicingApi.getByApplication)
      .mockResolvedValueOnce(current)
      .mockRejectedValueOnce(new Error('Нет ответа'))
      .mockResolvedValueOnce(refreshed);
    vi.mocked(loanServicingApi.payNext).mockResolvedValueOnce(receipt);

    renderLoan();
    await userEvent.click(await screen.findByRole('button', { name: 'Повторить' }));

    const refreshButton = await screen.findByRole('button', { name: 'Обновить график' });
    expect(screen.getByText('Не удалось обновить график платежей.')).toBeInTheDocument();
    expect(screen.getByText(status === 'REPAID' ? 'Погашен' : 'Активен')).toBeInTheDocument();
    const balance = screen.getByText('Остаток долга').parentElement;
    expect(balance?.textContent).toContain(rubExact(current.outstandingPrincipal));
    expect(screen.queryByRole('button', { name: 'Повторить' })).not.toBeInTheDocument();
    const mutationActions = ['Запросить транш', 'Внести очередной платёж', 'Досрочно погасить'];
    for (const name of mutationActions) {
      const button = screen.queryByRole('button', { name });
      if (status === 'REPAID') expect(button).not.toBeInTheDocument();
      else expect(button).toBeDisabled();
    }

    await userEvent.click(refreshButton);

    expect(loanServicingApi.getByApplication).toHaveBeenCalledTimes(3);
    expect(loanServicingApi.payNext).toHaveBeenCalledExactlyOnceWith(current.id, firstPayment.id, key);
    expect(sessionStorage.getItem(pendingKeyA)).toBeNull();
    expect(balance?.textContent).toContain(rubExact(refreshed.outstandingPrincipal));
    expect(screen.queryByRole('button', { name: 'Обновить график' })).not.toBeInTheDocument();
    if (status === 'ACTIVE') {
      for (const name of mutationActions) expect(screen.getByRole('button', { name })).toBeEnabled();
    }
  });

  it('hides all mutation buttons in readOnly mode for AGENT', async () => {
    vi.mocked(loanServicingApi.getByApplication).mockResolvedValue(activeRevolvingLoan);
    writeSession({ ...employeeA, user: { ...employeeA.user, role: 'AGENT', companyId: null } });

    renderLoan(<LoanServicingBlock applicationId={applicationId} readOnly />);

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

    renderLoan();

    expect(await screen.findByText(/КД-2026-0001/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Запросить транш' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Внести очередной платёж' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Досрочно погасить' })).toBeInTheDocument();
  });
});
