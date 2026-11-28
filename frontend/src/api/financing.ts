import { api, get, post, put } from './client';

export interface FinancingSource {
  page: number;
  amountQuote: string;
  dateQuote: string;
  documentId?: string | null;
}

export interface FinancingInputs {
  openingBalance: number;
  expectedReceiptAmount: number;
  expectedReceiptDate: string;
  paymentDate: string;
  delayDays: number;
  alternativeTermMonths: number;
  selectedTermMonths: number;
  source?: FinancingSource | null;
}

export interface FinancingOption {
  termMonths: number;
  monthlyPayment: number;
  totalInterest: number;
  balanceOnPaymentDate: number;
}

export interface FinancingScenario {
  revision: number;
  status: 'DRAFT' | 'AWAITING_CLIENT' | 'CONFIRMED';
  inputs: FinancingInputs;
  loanAmount: number;
  annualRatePercent: number;
  options: FinancingOption[];
  changes?: string[];
}

export interface FinancingPreviewRequest extends Omit<FinancingInputs, 'selectedTermMonths' | 'source'> {
  mode: 'WEB_FORM';
  draftTermsOverride: {
    amount: number;
    baseTermMonths: number;
  };
}

export interface FinancingPreview {
  source: 'credit';
  asOf: string;
  preview: true;
  basis: {
    applicationVersion: string;
    productCode: string;
    productVersion: string;
    savedScenarioRevision: string | null;
  };
  inputsUsed: {
    currency: 'RUB';
    paymentModel: 'ANNUITY' | 'REVOLVING';
    loanAmountRub: string;
    annualRatePercent: string;
    baseTermMonths: number;
    alternativeTermMonths: number;
    openingBalanceRub: string;
    expectedReceiptAmountRub: string;
    expectedReceiptDate: string;
    paymentDate: string;
    delayDays: number;
    receiptIncludedByPayment: boolean;
    lastIncludedDelayDays: number | null;
  };
  options: {
    termMonths: number;
    monthlyPaymentRub: string;
    totalInterestRub: string;
    balanceOnPaymentDateRub: string;
  }[];
  cabinetPath: string;
}

export interface AgentMandate {
  id: string;
  agentUserId: string;
  agentName: string;
  status: 'ACTIVE' | 'EXPIRED' | 'REVOKED';
  assignedAt: string;
  expiresAt: string | null;
  revokedAt: string | null;
}

async function readScenario(path: string): Promise<FinancingScenario | null> {
  const response = await api.get<FinancingScenario>(path);
  return response.status === 204 ? null : response.data;
}

export const financingApi = {
  agent: (id: string) => readScenario(`/agent/applications/${id}/financing-scenario`),
  client: (id: string) => readScenario(`/applications/${id}/financing-scenario`),
  bank: (id: string) => readScenario(`/underwriting/applications/${id}/financing-scenario`),
  preview: (id: string, input: FinancingPreviewRequest) =>
    post<FinancingPreview>(`/agent/applications/${id}/financing-scenario/preview`, input),
  save: (id: string, input: FinancingInputs) => put<FinancingScenario>(`/agent/applications/${id}/financing-scenario`, input)
};

export const agentMandatesApi = {
  list: () => get<AgentMandate[]>('/companies/me/agent-mandates'),
  grant: (agentUserId: string, expiresAt: string) => post<AgentMandate>('/companies/me/agent-mandates', { agentUserId, expiresAt }),
  revoke: (id: string) => api.delete(`/companies/me/agent-mandates/${id}`)
};

export interface InvoiceCandidate<T> {
  value: T;
  page: number;
  quote: string;
}

export interface InvoiceExtraction {
  mode: 'MODEL' | 'MANUAL';
  amount: InvoiceCandidate<number> | null;
  dates: InvoiceCandidate<string>[];
  reason: string | null;
  needsConfirmation?: boolean;
}

export async function extractInvoice(applicationId: string, file: File): Promise<InvoiceExtraction> {
  const form = new FormData();
  form.append('file', file);
  const response = await api.post<InvoiceExtraction>(`/agent/applications/${applicationId}/invoice-extraction`, form, {
    headers: { 'Content-Type': 'multipart/form-data' }
  });
  return response.data;
}

export interface PaymentReserveInputs {
  openingBalance: number;
  expectedReceiptAmount: number;
  payableAmount: number;
  targetBuffer: number;
  controlDate: string;
  paymentDate: string;
}

type ReceiptStatus = 'PENDING' | 'RECEIVED' | 'DELAYED';

export interface PaymentReserveQuote {
  version: number;
  inputs: PaymentReserveInputs;
  receiptStatus: ReceiptStatus;
  receivedAmount: number;
  remainingLimit: number;
  requiredAmount: number;
  suggestedDraw: number;
  shortfall: number;
  projectedBalance: number;
  canDraw: boolean;
  drawnAmount: number;
}

export interface AgentPaymentReserveSummary {
  version: number;
  receiptStatus: ReceiptStatus;
  paymentDate: string;
  action: 'WAIT_FOR_RECEIPT' | 'NO_FINANCING_NEEDED' | 'CLIENT_CONFIRM_DRAW' | 'REVIEW_REQUIRED' | 'TRANCHE_CONFIRMED';
}

async function readReserve<T>(path: string): Promise<T | null> {
  const response = await api.get<T>(path, { validateStatus: (status) => status === 200 || status === 204 || status === 404 });
  return response.status === 200 ? response.data : null;
}

export const paymentReserveApi = {
  get: async (id: string) => {
    const response = await api.get<PaymentReserveQuote>(`/loans/${id}/payment-reserve`,
      { validateStatus: (status) => status === 200 || status === 204 || status === 404 });
    return { enabled: response.status !== 404, quote: response.status === 200 ? response.data : null };
  },
  agent: (id: string) => readReserve<AgentPaymentReserveSummary>(`/agent/applications/${id}/payment-reserve`),
  save: (id: string, input: PaymentReserveInputs) =>
    put<PaymentReserveQuote>(`/loans/${id}/payment-reserve`, input),
  receipt: (id: string, eventId: string, status: Exclude<ReceiptStatus, 'PENDING'>) =>
    post<PaymentReserveQuote>(`/loans/${id}/payment-reserve/receipt-event`, { eventId, status }),
  confirm: (id: string, expectedVersion: number) =>
    post<PaymentReserveQuote>(`/loans/${id}/payment-reserve/confirm-draw`, { expectedVersion })
};
