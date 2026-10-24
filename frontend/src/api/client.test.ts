import { AxiosError, InternalAxiosRequestConfig } from 'axios';
import { Blob as ReadableBlob } from 'node:buffer';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, artifactsApi } from './client';
import { readSession, writeSession } from '../auth/session';
import { UserSession } from '../types';

const session = (token: string): UserSession => ({ token, user: { id: 'synthetic', fullName: 'Тест', email: 'user@example.test', role: 'CLIENT', status: 'ACTIVE', companyId: 'company', canSign: false } });

describe('session response ordering', () => {
  const originalAdapter = api.defaults.adapter;
  beforeEach(() => {
    const values = new Map<string, string>();
    vi.stubGlobal('localStorage', {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
      removeItem: (key: string) => values.delete(key),
      clear: () => values.clear()
    });
  });
  afterEach(() => {
    api.defaults.adapter = originalAdapter;
    localStorage.clear();
    vi.unstubAllGlobals();
  });

  it('a delayed 401 from a former session cannot remove a new session', async () => {
    const assign = vi.fn();
    vi.stubGlobal('window', { location: { pathname: '/profile', assign } });
    writeSession(session('old-session'));
    let rejectResponse: (reason: Error) => void = () => undefined;
    let sent: InternalAxiosRequestConfig | undefined;
    const started = new Promise<void>((resolve) => {
      api.defaults.adapter = (config) => new Promise((_, reject) => { sent = config; rejectResponse = reject; resolve(); });
    });
    const request = api.get('/notifications');
    await started;
    writeSession(session('new-session'));
    rejectResponse(new AxiosError('expired', 'ERR_BAD_REQUEST', sent, undefined, { status: 401, statusText: 'Unauthorized', headers: {}, config: sent!, data: { message: 'Прежняя сессия завершена' } }));
    await expect(request).rejects.toThrow('Прежняя сессия завершена');
    expect(readSession()?.token).toBe('new-session');
    expect(assign).not.toHaveBeenCalled();
  });

  it('an authority outage preserves the session and returns the retryable error', async () => {
    const assign = vi.fn();
    vi.stubGlobal('window', { location: { pathname: '/profile', assign } });
    writeSession(session('current-session'));
    api.defaults.adapter = async (config) => { throw new AxiosError('outage', 'ERR_BAD_RESPONSE', config, undefined, { status: 503, statusText: 'Unavailable', headers: {}, config, data: { message: 'Проверка сессии временно недоступна' } }); };
    await expect(api.get('/notifications')).rejects.toThrow('Проверка сессии временно недоступна');
    expect(readSession()?.token).toBe('current-session');
    expect(assign).not.toHaveBeenCalled();
  });

  it('a failed bearer file download exposes the JSON blob error without clearing the session', async () => {
    vi.stubGlobal('Blob', ReadableBlob);
    writeSession(session('file-session'));
    api.defaults.adapter = async (config) => {
      expect(config.responseType).toBe('blob');
      expect(config.headers.Authorization).toBe('Bearer file-session');
      const data = new ReadableBlob([JSON.stringify({ message: 'Файл временно недоступен. Повторите создание.' })], { type: 'application/json' });
      throw new AxiosError('download failed', 'ERR_BAD_RESPONSE', config, undefined, { status: 503, statusText: 'Unavailable', headers: {}, config, data });
    };
    await expect(artifactsApi.download('application', 'file', 'schedule.pdf')).rejects.toThrow('Файл временно недоступен. Повторите создание.');
    expect(readSession()?.token).toBe('file-session');
  });
});
