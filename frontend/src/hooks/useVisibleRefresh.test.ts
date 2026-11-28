import { act, renderHook } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { useVisibleRefresh } from './useVisibleRefresh';

afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
});

it('skips ticks in a hidden tab and refreshes immediately when visible or focused', async () => {
  vi.useFakeTimers();
  const hidden = vi.spyOn(document, 'hidden', 'get').mockReturnValue(true);
  const refresh = vi.fn(async () => undefined);
  const view = renderHook(() => useVisibleRefresh(refresh, 1000));
  await act(async () => { vi.advanceTimersByTime(5000); });
  expect(refresh).not.toHaveBeenCalled();

  hidden.mockReturnValue(false);
  await act(async () => {
    document.dispatchEvent(new Event('visibilitychange'));
    window.dispatchEvent(new Event('focus'));
  });
  expect(refresh).toHaveBeenCalledTimes(1);
  await act(async () => { window.dispatchEvent(new Event('focus')); });
  expect(refresh).toHaveBeenCalledTimes(2);
  view.unmount();
  await act(async () => { vi.advanceTimersByTime(5000); });
  expect(refresh).toHaveBeenCalledTimes(2);
});
