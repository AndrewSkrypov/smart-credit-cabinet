export type Role = 'CLIENT' | 'MANAGER' | 'UNDERWRITER' | 'RISK_ADMIN';

export type LegalForm = 'LEGAL_ENTITY' | 'ENTREPRENEUR';

export interface User {
  id: string;
  fullName: string;
  email: string;
  role: Role;
  status: 'ACTIVE' | 'BLOCKED';
  companyId: string | null;
  canSign: boolean;
}

export interface UserSession {
  token: string;
  user: User;
}

export interface CompanyMember {
  id: string;
  fullName: string;
  canSign: boolean;
}

export interface Company {
  id: string;
  inn: string;
  name: string | null;
  legalForm: LegalForm;
  members: CompanyMember[];
}

export interface SignUpInput {
  fullName: string;
  email: string;
  password: string;
  inn: string;
  personalDataConsent: boolean;
}

export type FinancingPurpose =
  | 'CASH_GAP'
  | 'CURRENT_EXPENSES'
  | 'WORKING_CAPITAL'
  | 'EQUIPMENT'
  | 'VEHICLE'
  | 'RENOVATION'
  | 'BUSINESS_DEVELOPMENT';

export interface Purpose {
  code: FinancingPurpose;
  title: string;
}

export interface Product {
  code: string;
  name: string;
  description: string;
  purposes: FinancingPurpose[];
  minAmount: number;
  maxAmount: number;
  minTermMonths: number;
  maxTermMonths: number;
  baseRate: number;
  autoApprovalLimit: number;
  revolving: boolean;
}

export interface ProductMatch {
  product: Product;
  fits: boolean;
  reasons: string[];
  adjustments: string[];
  termMonths: number;
  monthlyPayment: number | null;
  overpayment: number | null;
}

export interface ScheduleRow {
  month: number;
  payment: number;
  principal: number;
  interest: number;
  balance: number;
}

export interface Calculation {
  rate: number;
  monthlyPayment: number;
  totalPayment: number;
  overpayment: number;
  schedule: ScheduleRow[];
}

export type ApplicationStatus =
  | 'DRAFT'
  | 'SUBMITTED'
  | 'INFO_REQUESTED'
  | 'ENRICHMENT'
  | 'SCORING'
  | 'MANUAL_REVIEW'
  | 'OFFER_ISSUED'
  | 'SIGNING'
  | 'DISBURSING'
  | 'DISBURSED'
  | 'REJECTED'
  | 'WITHDRAWN'
  | 'CLOSED';

export type CloseReason = 'INFO_EXPIRED' | 'OFFER_DECLINED' | 'OFFER_EXPIRED';

export type DocumentType = 'TAX_RETURN' | 'FINANCIAL_STATEMENTS' | 'EQUIPMENT_INVOICE' | 'BANK_STATEMENT' | 'OTHER';

export interface RequiredDocument {
  type: DocumentType;
  title: string;
  reason: string;
  uploaded: boolean;
}

export interface ApplicationDocument {
  id: string;
  type: DocumentType;
  title: string;
  fileName: string;
  sizeBytes: number;
  uploadedAt: string;
}

export interface InfoItem {
  documentType: DocumentType | null;
  title: string;
  reason: string | null;
  question: string | null;
}

export interface InfoRequest {
  id: string;
  items: InfoItem[];
  comment: string | null;
  deadline: string;
  requestedAt: string;
}

export interface Offer {
  id: string;
  kind: 'APPROVED' | 'COUNTER';
  status: 'ISSUED' | 'ACCEPTED' | 'DECLINED' | 'EXPIRED' | 'WITHDRAWN';
  productCode: string;
  productName: string;
  amount: number;
  termMonths: number;
  rate: number;
  monthlyPayment: number;
  totalPayment: number;
  validUntil: string;
  changes: string[];
}

export interface Signing {
  code: string | null;
  expiresAt: string | null;
  signedAt: string | null;
}

export interface Loan {
  contractNumber: string;
  loanAccount: string;
  settlementAccount: string;
  amount: number;
  termMonths: number;
  rate: number;
  disbursedAt: string;
  maturityDate: string;
}

export interface Outcome {
  outcome: string;
  reasonCode: string | null;
  reasonTitle: string | null;
  explanation: string[];
}

export interface Application {
  id: string;
  number: number | null;
  status: ApplicationStatus;
  closeReason: CloseReason | null;
  company: { id: string; inn: string; name: string | null; legalForm: LegalForm };
  product: { code: string; name: string; revolving: boolean };
  purpose: FinancingPurpose;
  purposeTitle: string;
  purposeComment: string | null;
  amount: number;
  termMonths: number;
  declaredRevenue12m: number | null;
  declaredMonthlyDebtPayments: number | null;
  bureauConsent: boolean;
  createdAt: string;
  submittedAt: string | null;
  statusChangedAt: string;
  version: number | null;
  allowedActions: string[];
  editable: boolean;
  canUploadDocuments: boolean;
  submissionProblems: string[];
  requiredDocuments: RequiredDocument[];
  documents: ApplicationDocument[];
  estimate: { rate: number; monthlyPayment: number } | null;
  process: {
    infoRequest: InfoRequest | null;
    offer: Offer | null;
    signing: Signing | null;
    loan: Loan | null;
    outcome: Outcome | null;
  };
}

export interface ApplicationSummary {
  id: string;
  number: number | null;
  status: ApplicationStatus;
  companyInn: string;
  companyName: string | null;
  productCode: string;
  productName: string;
  amount: number;
  termMonths: number;
  createdAt: string;
  submittedAt: string | null;
  statusChangedAt: string;
}

export interface JournalEntry {
  id: string;
  event: string;
  fromStatus: ApplicationStatus | null;
  toStatus: ApplicationStatus;
  actorType: 'CLIENT' | 'SYSTEM' | 'MANAGER' | 'UNDERWRITER';
  actorId: string | null;
  reasonCode: string | null;
  comment: string | null;
  payload: Record<string, unknown>;
  occurredAt: string;
}

/** What the client fills in; the same shape for creating and editing a draft. */
export interface ApplicationInput {
  productCode: string;
  purpose: FinancingPurpose;
  purposeComment?: string | null;
  amount: number;
  termMonths: number;
  declaredRevenue12m?: number | null;
  declaredMonthlyDebtPayments?: number | null;
  bureauConsent: boolean;
}
