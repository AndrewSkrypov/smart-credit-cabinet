export type Role = 'CLIENT' | 'MANAGER' | 'UNDERWRITER' | 'RISK_ADMIN';

export interface ChatFile { id: string; fileName: string; sizeBytes: number }
export interface ChatMessage { id: string; seq: number; authorName: string; side: 'COMPANY' | 'BANK'; text: string; createdAt: string; attachments: ChatFile[] }
export interface ChatPage {
  messages: ChatMessage[]; lastSeq: number; readSeq: number; unreadCount: number; side: 'COMPANY' | 'BANK';
  writable: boolean; readOnlyReason: string | null; hasOlder: boolean; hasNewer: boolean;
}
export interface ChatRead { lastSeq: number; readSeq: number; unreadCount: number }
export interface ChatAudit {
  id: string; actorId: string; actorRole: string; action: 'MESSAGE_SENT' | 'ATTACHMENT_UPLOADED' | 'READ_CURSOR_ADVANCED';
  messageId: string | null; documentId: string | null; readSeq: number | null; occurredAt: string;
}

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
  version?: number;
  active?: boolean;
}

export interface ProductRevision {
  product: Product & { version: number };
  authorId: string | null;
  reason: string;
  createdAt: string;
  authorName?: string | null;
  authorRole?: string | null;
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
  revolving?: boolean;
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

export interface ContractTextView {
  snapshot: { id: string; applicationId: string; offerId: string; templateVersion: number; text: string; sha256: string; createdAt: string } | null;
  text: string;
  legacySummary: boolean;
  signable: boolean;
  signedAt: string | null;
}

export interface GeneratedArtifact {
  id: string;
  kind: 'CONTRACT_PDF' | 'SCHEDULE_PDF' | 'SCHEDULE_XLSX';
  phase: 'PREVIEW' | 'FINAL';
  title: string;
  fileName: string;
  contentType: string;
  status: 'PENDING' | 'READY' | 'ERROR';
  sizeBytes: number | null;
  checksum: string | null;
  createdAt: string;
  readyAt: string | null;
  errorMessage: string | null;
  downloadUrl: string | null;
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
  product: { code: string; name: string; revolving: boolean; version?: number };
  currentProduct?: Product | null;
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
  unreadChatCount?: number;
}

export interface ApplicationPage {
  items: ApplicationSummary[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
  countsByGroup: Record<string, number>;
  actionRequiredCount: number;
  actionRequiredPreview: ApplicationSummary[];
}

export interface ApplicationPageQuery {
  page: number;
  size: number;
  group: string;
  q: string;
  sort: string;
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
  sourceFetchedAt?: Partial<Record<'REGISTRY' | 'TAX' | 'BUREAU' | 'BANK', string>>;
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
  versions: Array<{ version: number; createdAt: string; createdBy: string | null; comment: string | null; authorName?: string | null; authorRole?: string | null }>;
}

export interface RulesSnapshot {
  version: number;
  params: RuleParams;
  metadata: RulesResponse['versions'][number];
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

export interface CabinetNotification {
  id: string;
  type: 'APPLICATION_STATUS' | 'ACTION_REQUIRED' | 'NEW_REVIEW' | 'APPLICATION_MESSAGE';
  title: string;
  body: string;
  link: string | null;
  read: boolean;
  createdAt: string;
}

export interface NotificationPage {
  items: CabinetNotification[];
  page: number;
  size: number;
  total: number;
  unread: number;
}

export interface CompanyInvitation {
  id: string;
  email: string;
  canSign: boolean;
  expiresAt: string;
  status: 'PENDING' | 'ACCEPTED' | 'REVOKED' | 'EXPIRED';
  link: string | null;
}

export interface Employees {
  members: User[];
  invitations: CompanyInvitation[];
  canManage: boolean;
  mailAvailable: boolean;
}

export interface InvitationPreview {
  companyName: string;
  email: string;
  canSign: boolean;
  expiresAt: string;
}

export interface NotificationSettings {
  emailAvailable: boolean;
  emailEnabled: boolean;
  version: number;
}

export interface AssistantTurn {
  role: 'user' | 'assistant';
  text: string;
}

export interface AssistantAnswer {
  text: string;
  mode: 'MODEL' | 'RULES';
  sources: string[];
  /** Where the cabinet continues what the assistant started, e.g. the wizard with the need filled in. */
  link?: { label: string; path: string } | null;
}

export type AdviceKind = 'STOP' | 'RISK' | 'CHECK' | 'ASK' | 'OPTION' | 'INFO';

export interface AdviceItem {
  code: string;
  kind: AdviceKind;
  title: string;
  text: string;
  because: string[];
}

export interface UnderwritingAdvice {
  applicationId: string;
  engine: string;
  modelVersion: number;
  verdict: string;
  items: AdviceItem[];
  rulesFired: number;
  rulesTotal: number;
}
