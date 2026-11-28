import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { applicationsApi, loanServicingApi } from '../../api/client';
import { Application, ApplicationSummary, LoanAccount } from '../../types';
import { CreditTimeline } from './CreditTimeline';
import { creditTimeline } from './creditTimelineEvents';

vi.mock('../../api/client', () => ({
  applicationsApi: { list: vi.fn(), get: vi.fn(), events: vi.fn() },
  loanServicingApi: { getByApplication: vi.fn() }
}));

const issued: ApplicationSummary = {
  id: 'issued', number: 17, status: 'DISBURSED', companyInn: '000100000004', companyName: 'Демо',
  productCode: 'DEMO', productName: 'Кредит на развитие', amount: 300000, termMonths: 3,
  createdAt: '2025-01-01T10:00:00Z', submittedAt: '2025-01-02T10:00:00Z',
  statusChangedAt: '2025-01-15T10:00:00Z',
};
const offer: ApplicationSummary = {
  ...issued, id: 'offer', number: 18, status: 'OFFER_ISSUED', amount: 500000,
};
const application = {
  ...issued, process: { loan: null, offer: null, infoRequest: null, signing: null, outcome: null }
} as unknown as Application;
const offeredApplication = {
  ...application, ...offer,
  process: {
    ...application.process,
    offer: { amount: 450000, validUntil: '2025-01-30T10:00:00Z', status: 'ISSUED' },
  },
} as unknown as Application;
const account: LoanAccount = {
  id: 'loan', applicationId: issued.id, contractNumber: 'sample', status: 'ACTIVE', approvedLimit: 300000,
  disbursedAmount: 300000, availableLimit: 0, outstandingPrincipal: 200000, termMonths: 3,
  revolving: false, rate: 20, disbursedAt: '2025-01-15T10:00:00Z', maturityDate: '2025-04-15', repaidAt: null,
  tranches: [],
  payments: [
    { id: 'paid', paymentNumber: 1, dueDate: '2025-02-15', paidAt: '2025-02-12T12:00:00Z', status: 'PAID',
      paymentAmount: 104000, principalAmount: 100000, interestAmount: 4000, remainingBalance: 200000 },
    { id: 'upcoming', paymentNumber: 2, dueDate: '2025-03-15', paidAt: null, status: 'SCHEDULED',
      paymentAmount: 104000, principalAmount: 100000, interestAmount: 4000, remainingBalance: 100000 },
  ],
};

describe('Кредитный календарь', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.mocked(applicationsApi.list).mockResolvedValue([issued, offer]);
    vi.mocked(applicationsApi.get).mockImplementation(async (id) => id === issued.id ? application : offeredApplication);
    vi.mocked(applicationsApi.events).mockResolvedValue([]);
    vi.mocked(loanServicingApi.getByApplication).mockResolvedValue(account);
  });

  it('показывает фактические даты оплаты и помечает план отдельно', () => {
    const events = creditTimeline(issued, [], application, account);
    expect(events).toContainEqual(expect.objectContaining({ kind: 'paid', date: account.payments[0].paidAt, amount: 104000 }));
    expect(events).toContainEqual(expect.objectContaining({ kind: 'scheduled', date: account.payments[1].dueDate, amount: 104000 }));
    expect(creditTimeline(issued, [], application, { ...account, status: 'REPAID', repaidAt: '2025-04-01' }))
      .not.toContainEqual(expect.objectContaining({ kind: 'scheduled' }));
  });

  it('переключает события с клавиатуры и читает сроки выбранной заявки', async () => {
    const user = userEvent.setup();
    render(<MemoryRouter><CreditTimeline /></MemoryRouter>);
    const graph = await screen.findByRole('group', { name: /График кредитных событий/ });
    graph.focus();
    await user.keyboard('{ArrowLeft}');
    expect(screen.getByText(/Платёж № 2 по графику/)).toBeInTheDocument();
    await user.keyboard('{ArrowLeft}');
    expect(screen.getByText(/Платёж № 1 внесён/)).toBeInTheDocument();

    await user.click(screen.getByRole('combobox'));
    await user.click(screen.getByRole('option', { name: /№ 18/ }));
    expect(await screen.findByText(/Принять предложение до/)).toBeInTheDocument();
    expect(loanServicingApi.getByApplication).toHaveBeenCalledTimes(1);
  });

  it('показывает частичную ошибку и сохраняет текущий статус заявки', async () => {
    vi.mocked(loanServicingApi.getByApplication).mockRejectedValue(new Error('Сервис недоступен'));
    render(<MemoryRouter><CreditTimeline /></MemoryRouter>);
    expect(await screen.findByRole('alert')).toHaveTextContent('Часть кредитных данных не загрузилась');
    expect(screen.getAllByText('Деньги на счёте').length).toBeGreaterThan(0);
    expect(screen.getByRole('link', { name: 'Открыть заявку' })).toHaveAttribute('href', '/client/applications/issued');
  });
});
