import { act, renderHook } from '@testing-library/react';
import { afterEach, expect, test, vi } from 'vitest';

import { useKeshaEnabled } from './useKeshaEnabled';

const { request, auth } = vi.hoisted(() => ({
  request: vi.fn(),
  auth: { session: { token: 'agent-session', user: { role: 'AGENT' } } }
}));
vi.mock('../../api/client', () => ({ api: { get: request } }));
vi.mock('../../auth/AuthProvider', () => ({ useAuth: () => auth }));

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

test('starts off, refreshes only in a visible tab and turns off after a failed refresh', async () => {
  vi.useFakeTimers();
  const visibility = vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
  request.mockResolvedValueOnce({ data: { keshaEnabled: true } });
  const { result, unmount } = renderHook(useKeshaEnabled);
  expect(result.current).toBe(false);

  await act(async () => {});
  expect(result.current).toBe(true);
  expect(request).toHaveBeenCalledWith('/assistant/ui-config', expect.objectContaining({ timeout: 5_000 }));

  visibility.mockReturnValue('hidden');
  await act(async () => vi.advanceTimersByTimeAsync(30_000));
  expect(request).toHaveBeenCalledTimes(1);

  request.mockRejectedValueOnce(new Error('offline'));
  visibility.mockReturnValue('visible');
  await act(async () => {
    document.dispatchEvent(new Event('visibilitychange'));
  });
  expect(request).toHaveBeenCalledTimes(2);
  expect(result.current).toBe(false);

  request.mockResolvedValueOnce({ data: { keshaEnabled: true } });
  await act(async () => vi.advanceTimersByTimeAsync(30_000));
  expect(result.current).toBe(true);
  unmount();
  expect(request.mock.lastCall?.[1].signal.aborted).toBe(true);
});
