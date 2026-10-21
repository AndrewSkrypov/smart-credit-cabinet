import axios from 'axios';
import { clearSession, readSession } from '../auth/session';
import { Company, SignUpInput, UserSession } from '../types';

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
    const sentToken = Boolean(error?.config?.headers?.Authorization);
    if (error?.response?.status === 401 && sentToken && !window.location.pathname.startsWith('/sign-in')) {
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
  options: () => get<{ demoAccounts: boolean }>('/auth/options'),
  signIn: (email: string, password: string) => post<UserSession>('/auth/sign-in', { email, password }),
  signUp: (input: SignUpInput) => post<UserSession>('/auth/sign-up', input),
  session: () => get<UserSession>('/auth/session')
};

export const companyApi = {
  mine: () => get<Company>('/companies/me')
};
