import { act, render, renderHook, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, test, vi } from 'vitest';

import { KeshaProactivitySetting } from './KeshaProactivitySetting';
import { useKeshaProactivity } from './useKeshaProactivity';

const { auth } = vi.hoisted(() => ({
  auth: { session: { user: { id: 'test-user', role: 'AGENT' } } }
}));
vi.mock('../../auth/AuthProvider', () => ({ useAuth: () => auth }));

function key(id: string, role = 'AGENT') {
  return `cabinet.kesha.proactive.v1:${id}:${role}`;
}

function Setting() {
  const { proactive, setProactive } = useKeshaProactivity();
  return <KeshaProactivitySetting proactive={proactive} onChange={setProactive} />;
}

afterEach(() => {
  vi.restoreAllMocks();
  window.localStorage.clear();
});

test('the named checkbox defaults on and persists keyboard changes as booleans', async () => {
  auth.session.user = { id: 'keyboard-user', role: 'AGENT' };
  const user = userEvent.setup();
  const { unmount } = render(<Setting />);
  const checkbox = screen.getByRole('checkbox', { name: 'Кеша сам предлагает подсказки' });
  expect(checkbox).toBeChecked();
  expect(checkbox).toHaveAccessibleDescription('Выключите, чтобы Кеша оставался у кнопки чата');

  await user.tab();
  expect(checkbox).toHaveFocus();
  await user.keyboard('[Space]');
  expect(checkbox).not.toBeChecked();
  expect(window.localStorage.getItem(key('keyboard-user'))).toBe('false');
  unmount();

  render(<Setting />);
  const reopened = screen.getByRole('checkbox', { name: 'Кеша сам предлагает подсказки' });
  expect(reopened).not.toBeChecked();
  await user.click(reopened);
  expect(reopened).toBeChecked();
  expect(window.localStorage.getItem(key('keyboard-user'))).toBe('true');
});

test('each account and role reads its own preference on the first render', () => {
  auth.session.user = { id: 'pilot', role: 'AGENT' };
  window.localStorage.setItem(key('pilot'), 'false');
  const { result, rerender } = renderHook(useKeshaProactivity);
  expect(result.current.proactive).toBe(false);

  auth.session.user = { id: 'different-account', role: 'AGENT' };
  rerender();
  expect(result.current.proactive).toBe(true);

  auth.session.user = { id: 'pilot', role: 'CLIENT' };
  rerender();
  expect(result.current.proactive).toBe(true);

  auth.session.user = { id: 'pilot', role: 'AGENT' };
  rerender();
  expect(result.current.proactive).toBe(false);
});

test('the same account observes its saved preference from a second tab', () => {
  auth.session.user = { id: 'cross-tab-user', role: 'AGENT' };
  const { result } = renderHook(useKeshaProactivity);

  function storageChange(changedKey: string, value: string | null) {
    if (value === null) window.localStorage.removeItem(changedKey);
    else window.localStorage.setItem(changedKey, value);
    act(() => {
      window.dispatchEvent(new StorageEvent('storage', { key: changedKey, newValue: value, storageArea: window.localStorage }));
    });
  }

  storageChange(key('other-tab-user'), 'false');
  expect(result.current.proactive).toBe(true);
  storageChange(key('cross-tab-user'), 'false');
  expect(result.current.proactive).toBe(false);
  storageChange(key('cross-tab-user'), null);
  expect(result.current.proactive).toBe(true);
});

test('a denied write uses in-memory preference for this tab and account', () => {
  auth.session.user = { id: 'denied-storage-user', role: 'AGENT' };
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
    throw new DOMException('Storage blocked', 'QuotaExceededError');
  });
  const initial = renderHook(useKeshaProactivity);
  expect(initial.result.current.proactive).toBe(true);
  act(() => initial.result.current.setProactive(false));
  expect(initial.result.current.proactive).toBe(false);
  expect(window.localStorage.getItem(key('denied-storage-user'))).toBeNull();
  initial.unmount();

  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
    throw new DOMException('Storage blocked', 'SecurityError');
  });
  const { result, rerender } = renderHook(useKeshaProactivity);
  expect(result.current.proactive).toBe(false);
  auth.session.user = { id: 'other-denied-user', role: 'AGENT' };
  rerender();
  expect(result.current.proactive).toBe(true);
});
