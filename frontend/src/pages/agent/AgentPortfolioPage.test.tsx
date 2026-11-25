import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ThemeProvider } from '@mui/material';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { cabinetTheme } from '../../theme/theme';
import { AgentPortfolioPage } from './AgentPortfolioPage';
import { ApplicationPage } from '../client/application/ApplicationPage';
import { Application } from '../../types';

const mocks = vi.hoisted(() => ({
  portfolio: vi.fn(),
  clients: vi.fn(),
  preApprovedOffers: vi.fn(),
  applications: vi.fn(),
  applyPreApprovedOffer: vi.fn(),
  getApp: vi.fn(),
  confirmApp: vi.fn(),
  returnToAgent: vi.fn(),
  withdrawApp: vi.fn()
}));

vi.mock('../../api/client', () => ({
  agentApi: {
    portfolio: mocks.portfolio,
    clients: mocks.clients,
    preApprovedOffers: mocks.preApprovedOffers,
    applications: mocks.applications,
    applyPreApprovedOffer: mocks.applyPreApprovedOffer
  },
  applicationsApi: {
    get: mocks.getApp,
    confirm: mocks.confirmApp,
    returnToAgent: mocks.returnToAgent,
    withdraw: mocks.withdrawApp,
    events: vi.fn(async () => [])
  }
}));

vi.mock('../../auth/AuthProvider', () => ({
  useAuth: () => ({
    session: {
      token: 'test-token',
      user: { id: 'agent-1', fullName: 'Соколов Артём', email: 'agent@demo.test', role: 'AGENT', status: 'ACTIVE', companyId: null, canSign: false }
    }
  }),
  roleHome: { CLIENT: '/client', AGENT: '/agent', MANAGER: '/bank', UNDERWRITER: '/bank', RISK_ADMIN: '/bank' }
}));

vi.mock('../../components/MessagesBlock', () => ({ MessagesBlock: () => null }));
vi.mock('../../components/GeneratedFilesBlock', () => ({ GeneratedFilesBlock: () => null }));
vi.mock('../client/application/HistoryBlock', () => ({ HistoryBlock: () => null }));
vi.mock('../client/application/DocumentsBlock', () => ({ DocumentsBlock: ({ application }: { application: Application }) => <div>Документы ({application.documents.length})</div> }));

describe('AgentPortfolioPage', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    mocks.portfolio.mockResolvedValue({
      assignedCompaniesCount: 4,
      totalApplications: 5,
      draftCount: 1,
      pendingConfirmationCount: 1,
      inProgressCount: 2,
      disbursedCount: 1,
      rejectedOrClosedCount: 0,
      expectedCommissionTotal: 150000,
      accruedCommissionTotal: 90000
    });
    mocks.clients.mockResolvedValue([
      { companyId: 'comp-1', inn: '7701000001', name: 'ООО «Альфа Логистика»', legalForm: 'LEGAL_ENTITY', assignedAt: '2026-11-01T10:00:00Z' },
      { companyId: 'comp-2', inn: '7702000002', name: 'ИП Смирнов', legalForm: 'ENTREPRENEUR', assignedAt: '2026-11-02T10:00:00Z' }
    ]);
    mocks.preApprovedOffers.mockResolvedValue([
      {
        id: 'offer-1',
        companyId: 'comp-1',
        productCode: 'WORKING_CAPITAL',
        productName: 'Оборотный кредит',
        maxAmount: 5000000,
        minTermMonths: 6,
        maxTermMonths: 24,
        rate: 16.5,
        validUntil: '2026-12-31T23:59:59Z',
        status: 'ACTIVE'
      }
    ]);
    mocks.applications.mockResolvedValue([
      {
        id: 'app-draft',
        number: 101,
        status: 'DRAFT',
        companyInn: '7701000001',
        companyName: 'ООО «Альфа Логистика»',
        productCode: 'WORKING_CAPITAL',
        productName: 'Оборотный кредит',
        amount: 3000000,
        termMonths: 12,
        createdAt: '2026-11-20T10:00:00Z',
        submittedAt: null,
        statusChangedAt: '2026-11-20T12:00:00Z',
        unreadChatCount: 0,
        agentId: 'agent-1',
        agentName: 'Соколов Артём',
        agentCommissionAmount: 45000,
        agentCommissionStatus: 'EXPECTED'
      },
      {
        id: 'app-wait',
        number: 102,
        status: 'PENDING_CLIENT_CONFIRMATION',
        companyInn: '7702000002',
        companyName: 'ИП Смирнов',
        productCode: 'EQUIPMENT',
        productName: 'Кредит на оборудование',
        amount: 2000000,
        termMonths: 24,
        createdAt: '2026-11-21T10:00:00Z',
        submittedAt: null,
        statusChangedAt: '2026-11-21T12:00:00Z',
        unreadChatCount: 1,
        agentId: 'agent-1',
        agentName: 'Соколов Артём',
        agentCommissionAmount: 30000,
        agentCommissionStatus: 'EXPECTED'
      }
    ]);
  });

  it('renders KPI cards, assigned clients with pre-approved badges, applications table with next step, and action filter', async () => {
    render(
      <ThemeProvider theme={cabinetTheme}>
        <MemoryRouter initialEntries={['/agent']}>
          <AgentPortfolioPage />
        </MemoryRouter>
      </ThemeProvider>
    );

    expect((await screen.findAllByText('ООО «Альфа Логистика»')).length).toBeGreaterThanOrEqual(2);
    // KPI metrics
    expect(screen.getByText('Активных клиентов')).toBeInTheDocument();
    expect(screen.getByText('Заявок в работе')).toBeInTheDocument();
    expect(screen.getAllByText('4').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('Ожидаемая комиссия')).toBeInTheDocument();
    expect(screen.getByText(/150\s?000\s?₽/)).toBeInTheDocument();
    expect(screen.getByText('Начисленная комиссия')).toBeInTheDocument();
    expect(screen.getByText(/90\s?000\s?₽/)).toBeInTheDocument();

    // Pre-approved badge and quick action
    expect(screen.getByText(/Предодобрено/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /По предодобрению/i })).toBeInTheDocument();

    // Applications table with next-step column
    expect(screen.getByText('Следующий шаг')).toBeInTheDocument();
    expect(screen.getByText(/отправьте клиенту на подтверждение/i)).toBeInTheDocument();
    expect(screen.getByText(/Ожидаем подтверждения клиента/i)).toBeInTheDocument();

    // Filter toggle "Требуют моего действия"
    const toggle = screen.getByRole('checkbox', { name: /Требуют моего действия/i });
    fireEvent.click(toggle);
    expect(screen.getByText('№ 101')).toBeInTheDocument();
    expect(screen.queryByText('№ 102')).not.toBeInTheDocument();
  });
});

describe('Client confirmation flow in PENDING_CLIENT_CONFIRMATION', () => {
  const pendingApp = {
    id: 'app-pending-1',
    number: 205,
    status: 'PENDING_CLIENT_CONFIRMATION',
    closeReason: null,
    company: { id: 'comp-1', inn: '7701000001', name: 'ООО «Альфа Логистика»', legalForm: 'LEGAL_ENTITY' },
    product: { code: 'WORKING_CAPITAL', name: 'Оборотный кредит', revolving: false, version: 1 },
    currentProduct: null,
    purpose: 'WORKING_CAPITAL',
    purposeTitle: 'Пополнение оборотных средств',
    purposeComment: 'Закупка сырья',
    amount: 3000000,
    termMonths: 12,
    declaredRevenue12m: 25000000,
    declaredMonthlyDebtPayments: 100000,
    bureauConsent: false,
    createdAt: '2026-11-20T10:00:00Z',
    submittedAt: null,
    statusChangedAt: '2026-11-20T12:00:00Z',
    version: 2,
    allowedActions: ['CONFIRM_AND_SUBMIT', 'RETURN_TO_AGENT', 'WITHDRAW'],
    editable: false,
    canUploadDocuments: false,
    submissionProblems: [],
    requiredDocuments: [],
    documents: [
      { id: 'doc-1', type: 'TAX_RETURN', title: 'Налоговая декларация', fileName: 'tax-2025.pdf', sizeBytes: 120000, uploadedAt: '2026-11-20T11:00:00Z' }
    ],
    estimate: { rate: 17.5, monthlyPayment: 274000 },
    process: { infoRequest: null, offer: null, signing: null, loan: null, outcome: null },
    agentId: 'agent-1',
    agentName: 'Соколов Артём',
    preApprovedOfferId: null,
    agentCommissionRateBps: 150,
    agentCommissionAmount: 45000,
    agentCommissionStatus: 'EXPECTED'
  } as unknown as Application;

  beforeEach(() => {
    vi.resetAllMocks();
    mocks.getApp.mockResolvedValue(pendingApp);
    mocks.confirmApp.mockResolvedValue({ ...pendingApp, status: 'SUBMITTED', bureauConsent: true, allowedActions: ['WITHDRAW'] });
    mocks.returnToAgent.mockResolvedValue({ ...pendingApp, status: 'DRAFT', allowedActions: [] });
  });

  it('requires BKI consent before confirming and allows returning to agent with a comment', async () => {
    render(
      <ThemeProvider theme={cabinetTheme}>
        <MemoryRouter initialEntries={['/client/applications/app-pending-1']}>
          <Routes>
            <Route path="/client/applications/:id" element={<ApplicationPage />} />
          </Routes>
        </MemoryRouter>
      </ThemeProvider>
    );

    expect(await screen.findByRole('heading', { name: 'Подтверждение агентской заявки' })).toBeInTheDocument();
    expect(screen.getAllByText(/Соколов Артём/).length).toBeGreaterThanOrEqual(1);
    const confirmBtn = screen.getByRole('button', { name: /Подтвердить и отправить в банк/i });
    expect(confirmBtn).toBeDisabled();

    const consentCheckbox = screen.getByRole('checkbox', { name: /бюро кредитных историй/i });
    fireEvent.click(consentCheckbox);
    expect(confirmBtn).toBeEnabled();

    await act(async () => {
      fireEvent.click(confirmBtn);
    });
    await waitFor(() => {
      expect(mocks.confirmApp).toHaveBeenCalledWith('app-pending-1', true, 1);
    });
  });

  it('returns application to agent with comment', async () => {
    render(
      <ThemeProvider theme={cabinetTheme}>
        <MemoryRouter initialEntries={['/client/applications/app-pending-1']}>
          <Routes>
            <Route path="/client/applications/:id" element={<ApplicationPage />} />
          </Routes>
        </MemoryRouter>
      </ThemeProvider>
    );

    await screen.findByRole('heading', { name: 'Подтверждение агентской заявки' });
    fireEvent.click(screen.getByRole('button', { name: /Вернуть агенту/i }));
    const commentField = screen.getByLabelText(/Комментарий для агента/i);
    fireEvent.change(commentField, { target: { value: 'Уточните сумму до 2,5 млн' } });
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Отправить на доработку/i }));
    });
    await waitFor(() => {
      expect(mocks.returnToAgent).toHaveBeenCalledWith('app-pending-1', 'Уточните сумму до 2,5 млн');
    });
  });
});
