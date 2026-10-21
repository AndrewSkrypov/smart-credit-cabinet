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

export type Rating = 'A' | 'B' | 'C' | 'D' | 'E';

export interface FactorScore {
  factor: string;
  title: string;
  value: string;
  points: number;
  maxPoints: number;
  hint: string | null;
}

export interface OfferTerms {
  productCode: string;
  amount: number;
  termMonths: number;
  rate: number;
  monthlyPayment: number;
  totalPayment: number;
}

export interface Recommendation {
  outcome: 'APPROVE' | 'COUNTER_OFFER' | 'MANUAL_REVIEW' | 'REJECT' | 'REQUEST_INFO';
  terms: OfferTerms | null;
  rejectReason: string | null;
  notes: string[];
}

export interface DecisionView {
  id: string;
  decidedByType: string;
  decidedBy: string | null;
  ruleSetVersion: number;
  score: number | null;
  rating: Rating | null;
  stopFactors: Array<{ code: string; title: string }>;
  factors: FactorScore[];
  outcome: Recommendation['outcome'];
  reasonCode: string | null;
  manualReasons: Array<{ code: string; title: string }>;
  recommendation: Recommendation | null;
  comment: string | null;
  createdAt: string;
}

export interface RegistryInfo {
  inn: string;
  ogrn: string;
  fullName: string;
  shortName: string;
  status: string;
  statusTitle: string;
  registrationDate: string;
  okvedCode: string;
  okvedName: string;
  directorName: string;
  address: string;
  unreliableAddress: boolean;
  unreliableDirector: boolean;
}

export interface TaxInfo {
  hasReports: boolean;
  reportingYear: number | null;
  revenue: number | null;
  expenses: number | null;
  taxDebt: number;
  averageHeadcount: number | null;
  taxRegime: string | null;
  massAddress: boolean;
}

export interface BureauInfo {
  obligations: Array<{ creditor: string; type: string; limit: number; balance: number; monthlyPayment: number; currentOverdueDays: number }>;
  monthlyPaymentsTotal: number;
  currentOverdueDaysMax: number;
  worstOverdueDays24m: number;
  inquiries30d: number;
}

export interface BankInfo {
  client: boolean;
  settlementAccount: string | null;
  monthlyInflows: number[];
}

export interface Discrepancy {
  field: string;
  declared: string;
  source: string;
  actual: string;
  differencePercent: number | null;
}

export type UnderwritingAction = 'APPROVE' | 'COUNTER_OFFER' | 'REQUEST_INFO' | 'REJECT';

export interface AssessmentView {
  applicationId: string;
  status: ApplicationStatus;
  registry: RegistryInfo | null;
  tax: TaxInfo | null;
  bureau: BureauInfo | null;
  bank: BankInfo | null;
  turnoverVariation: number | null;
  discrepancies: Discrepancy[];
  automaticDecision: DecisionView | null;
  latestDecision: DecisionView | null;
  actions: UnderwritingAction[];
}

export interface RuleParams {
  maxLoad: number;
  revenueMismatch: number;
  ratingCutoffs: Record<'A' | 'B' | 'C' | 'D', number>;
  ratingMargins: Record<'A' | 'B' | 'C' | 'D', number>;
  lowRiskOkved: string[];
  highRiskOkved: string[];
  weakAmountShare: number;
  weakRevenueShare: number;
}

export interface RulesResponse {
  version: number;
  params: RuleParams;
  versions: Array<{ version: number; createdAt: string; createdBy: string | null; comment: string | null }>;
}

export interface Analytics {
  from: string;
  to: string;
  funnel: Array<{ code: string; title: string; applications: number }>;
  stageTimes: Array<{ status: string; title: string; transitions: number; averageMinutes: number | null; medianMinutes: number | null }>;
  automaticOutcomes: Array<{ outcome: string; decisions: number }>;
  manualOutcomes: Array<{ outcome: string; decisions: number }>;
  manualOverrides: number;
  disbursedAmount: number;
  currentStatuses: Record<string, number>;
}
