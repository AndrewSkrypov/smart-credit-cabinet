import axios from 'axios';
import { clearSession, readSession } from '../auth/session';
import {
  Analytics,
  Application,
  AssessmentView,
  ApplicationDocument,
  ApplicationInput,
  ApplicationSummary,
  AssistantAnswer,
  AssistantTurn,
  Calculation,
  Company,
  DocumentType,
  FinancingPurpose,
  JournalEntry,
  NotificationPage,
  NotificationSettings,
  Product,
  ProductMatch,
  ProductRevision,
  RuleParams,
  RulesResponse,
  RulesSnapshot,
  Purpose,
  SignUpInput,
  UserSession
} from '../types';
import { ChatAudit, ChatFile, ChatMessage, ChatPage, ChatRead } from '../types';
import { CompanyInvitation, Employees, InvitationPreview } from '../types';

const baseURL = import.meta.env.VITE_API_BASE_URL ?? '/api/v1';

export const api = axios.create({
  baseURL,
  headers: { 'Content-Type': 'application/json' }
});

api.interceptors.request.use((config) => {
  const session = readSession();
  if (session) {
    config.headers.Authorization = `Bearer ${session.token}`;
  }
  return config;
});

api.interceptors.response.use(
  (response) => response,
  (error) => {
    // A signed-in request answered 401: the token expired or was revoked. Start over at the
    // sign-in page instead of showing the same error on every page.
    const sentToken = error?.config?.headers?.Authorization;
    const currentToken = readSession()?.token;
    if (error?.response?.status === 401 && currentToken && sentToken === `Bearer ${currentToken}` && !window.location.pathname.startsWith('/sign-in')) {
      clearSession();
      window.location.assign(`/sign-in?expired=1&next=${encodeURIComponent(window.location.pathname)}`);
      return new Promise(() => undefined);
    }
    const backendMessage = error?.response?.data?.message;
    if (typeof backendMessage === 'string' && backendMessage.trim()) {
      return Promise.reject(new Error(backendMessage));
    }
    if (error?.response?.status === 401) {
      return Promise.reject(new Error('Сессия истекла — войдите заново'));
    }
    if (!error?.response) {
      return Promise.reject(new Error('Нет соединения с сервером'));
    }
    return Promise.reject(error);
  }
);

export async function get<T>(url: string, params?: Record<string, unknown>): Promise<T> {
  const response = await api.get<T>(url, { params });
  return response.data;
}

export async function post<T>(url: string, body?: unknown): Promise<T> {
  const response = await api.post<T>(url, body ?? {});
  return response.data;
}

export async function put<T>(url: string, body: unknown): Promise<T> {
  const response = await api.put<T>(url, body);
  return response.data;
}

export const authApi = {
  options: () => get<{ demoAccounts: boolean; mailAvailable: boolean }>('/auth/options'),
  signIn: (email: string, password: string) => post<UserSession>('/auth/sign-in', { email, password }),
  signUp: (input: SignUpInput) => post<UserSession>('/auth/sign-up', input),
  session: () => get<UserSession>('/auth/session'),
  recoverPassword: (email: string) => post<{ message: string }>('/auth/password-recovery', { email }),
  resetPassword: (token: string, password: string) => post<{ message: string }>('/auth/password-reset', { token, password }),
  changePassword: (currentPassword: string, password: string) => post<{ message: string }>('/auth/password', { currentPassword, password }),
  logoutAll: () => post<{ message: string }>('/auth/logout-all'),
  updateProfile: (fullName: string, email: string, currentPassword?: string) =>
    put<UserSession>('/users/me', { fullName, email, currentPassword: currentPassword || undefined }),
  inspectInvitation: (token: string) => post<InvitationPreview>('/auth/invitations/inspect', { token }),
  acceptInvitation: (token: string, fullName: string, password: string, personalDataConsent: boolean) =>
    post<UserSession>('/auth/invitations/accept', { token, fullName, password, personalDataConsent })
};

export const employeesApi = {
  list: () => get<Employees>('/companies/me/employees'),
  invite: async (email: string, canSign: boolean, key: string) =>
    (await api.post<CompanyInvitation>('/companies/me/invitations', { email, canSign }, { headers: { 'Idempotency-Key': key } })).data,
  resend: async (id: string, key: string) =>
    (await api.post<CompanyInvitation>(`/companies/me/invitations/${id}/resend`, {}, { headers: { 'Idempotency-Key': key } })).data,
  cancel: async (id: string, key: string) =>
    (await api.delete<{ message: string }>(`/companies/me/invitations/${id}`, { headers: { 'Idempotency-Key': key } })).data,
  revoke: async (id: string, key: string) =>
    (await api.delete<{ message: string }>(`/companies/me/employees/${id}/access`, { headers: { 'Idempotency-Key': key } })).data
};

export const companyApi = {
  mine: () => get<Company>('/companies/me')
};

export const productsApi = {
  list: () => get<Product[]>('/products'),
  purposes: () => get<Purpose[]>('/products/purposes'),
  match: (purpose: FinancingPurpose, amount: number, termMonths: number) => post<ProductMatch[]>('/products/match', { purpose, amount, termMonths })
};

export const productEditorApi = {
  list: () => get<Product[]>('/bank/products'),
  history: (code: string) => get<ProductRevision[]>(`/bank/products/${code}`),
  save: (product: Product, reason: string) => put<ProductRevision>(`/bank/products/${product.code}`, { ...product, expectedVersion: product.version, reason })
};

export const chatApi = {
  page: async (id: string, params: { after?: number; before?: number; limit?: number } = {}) =>
    (await api.get<ChatPage>(`/applications/${id}/messages`, { params })).data,
  send: async (id: string, key: string, text: string, documentIds: string[]) =>
    (await api.post<ChatMessage>(`/applications/${id}/messages`, { text, documentIds }, { headers: { 'Idempotency-Key': key } })).data,
  read: (id: string, lastReadSeq: number) => post<ChatRead>(`/applications/${id}/messages/read`, { lastReadSeq }),
  audit: (id: string) => get<ChatAudit[]>(`/applications/${id}/messages/audit`),
  upload: async (id: string, key: string, file: File) => {
    const form = new FormData(); form.append('file', file);
    return (await api.post<ChatFile>(`/applications/${id}/messages/attachments`, form, { headers: { 'Idempotency-Key': key, 'Content-Type': 'multipart/form-data' } })).data;
  }
};

export const applicationsApi = {
  list: () => get<ApplicationSummary[]>('/applications'),
  get: (id: string) => get<Application>(`/applications/${id}`),
  create: (input: ApplicationInput) => post<Application>('/applications', input),
  update: (id: string, input: ApplicationInput) => put<Application>(`/applications/${id}`, input),
  /** The key makes a repeated click or a retry after a timeout return the first result. */
  submit: async (id: string, idempotencyKey: string, acceptedProductVersion?: number) =>
    (await api.post<Application>(`/applications/${id}/submit`, { acceptedProductVersion }, { headers: { 'Idempotency-Key': idempotencyKey } })).data,
  withdraw: (id: string, comment?: string) => post<Application>(`/applications/${id}/withdraw`, { comment }),
  events: (id: string) => get<JournalEntry[]>(`/applications/${id}/events`),
  uploadDocument: async (id: string, type: DocumentType, file: File) => {
    const form = new FormData();
    form.append('file', file);
    return (await api.post<ApplicationDocument>(`/applications/${id}/documents`, form, { params: { type }, headers: { 'Content-Type': 'multipart/form-data' } })).data;
  },
  deleteDocument: async (id: string, documentId: string) => {
    await api.delete(`/applications/${id}/documents/${documentId}`);
  },
  /** Downloads with the caller's token: a plain link would not carry it. */
  downloadDocument: async (id: string, documentId: string, fileName: string) => {
    const response = await api.get<Blob>(`/applications/${id}/documents/${documentId}`, { responseType: 'blob' });
    const url = URL.createObjectURL(response.data);
    const link = document.createElement('a');
    link.href = url;
    link.download = fileName;
    link.click();
    URL.revokeObjectURL(url);
  },
  provideInfo: (id: string, answer?: string) => post<Application>(`/applications/${id}/info-provided`, { answer }),
  acceptOffer: (id: string) => post<Application>(`/applications/${id}/offer/accept`),
  declineOffer: (id: string, comment?: string) => post<Application>(`/applications/${id}/offer/decline`, { comment }),
  requestCode: (id: string) => post<Application>(`/applications/${id}/signing/code`),
  sign: (id: string, code: string) => post<Application>(`/applications/${id}/sign`, { code }),
  schedule: (id: string) => get<Calculation>(`/applications/${id}/schedule`),
  contract: (id: string) => get<import('../types').ContractTextView>(`/applications/${id}/contract`)
};

export interface RequestedItem {
  documentType?: DocumentType;
  question?: string;
}

/** The underwriter's card and decisions (T13–T15); a manager may only ask for information. */
export const underwritingApi = {
  assessment: (id: string) => get<AssessmentView>(`/underwriting/applications/${id}`),
  approve: (id: string, comment?: string) => post<{ status: string }>(`/underwriting/applications/${id}/approve`, { comment }),
  counterOffer: (id: string, body: { productCode?: string; amount: number; termMonths: number; comment?: string }) =>
    post<{ status: string }>(`/underwriting/applications/${id}/counter-offer`, body),
  requestInfo: (id: string, items: RequestedItem[], comment?: string) => post<{ status: string }>(`/underwriting/applications/${id}/request-info`, { items, comment }),
  reject: (id: string, comment: string) => post<{ status: string }>(`/underwriting/applications/${id}/reject`, { comment })
};

export const rulesApi = {
  current: () => get<RulesResponse>('/rules'),
  version: (version: number) => get<RulesSnapshot>(`/rules/${version}`),
  save: (params: RuleParams, comment: string, expectedVersion: number) => post<RulesResponse>('/rules', { params, comment, expectedVersion })
};

export const analyticsApi = {
  funnel: (from?: string, to?: string) => get<Analytics>('/analytics/funnel', { from, to })
};

export const notificationsApi = {
  settings: () => get<NotificationSettings>('/notifications/settings'),
  saveSettings: async (emailEnabled: boolean, version: number) =>
    (await api.put<NotificationSettings>('/notifications/settings', { emailEnabled, version })).data,
  page: (page = 0, size = 10) => get<NotificationPage>('/notifications', { page, size }),
  unreadCount: async () => (await get<{ unread: number }>('/notifications/unread-count')).unread,
  markRead: (id: string) => post<void>(`/notifications/${id}/read`),
  markAllRead: () => post<{ updated: number }>('/notifications/read-all')
};

export const assistantApi = {
  status: () => get<{ mode: 'MODEL' | 'RULES'; model: string | null }>('/assistant/status'),
  ask: (question: string, applicationId: string | null, history: AssistantTurn[]) =>
    post<AssistantAnswer>('/assistant/messages', { question, applicationId, history })
};
