import { useCallback, useSyncExternalStore } from 'react';

import { useAuth } from '../../auth/AuthProvider';

const STORAGE_PREFIX = 'cabinet.kesha.proactive.v1';
const UPDATED_EVENT = 'cabinet:kesha-proactivity';
const tabPreferences = new Map<string, { value: boolean; saved: boolean }>();

export function useKeshaProactivity() {
  const { session } = useAuth();
  const user = session?.user;
  const key = user ? `${STORAGE_PREFIX}:${user.id}:${user.role}` : null;

  const readPreference = useCallback(() => {
    if (!key) return true;
    const tabPreference = tabPreferences.get(key);
    if (tabPreference && !tabPreference.saved) return tabPreference.value;
    try {
      return window.localStorage.getItem(key) !== 'false';
    } catch {
      return tabPreference?.value ?? true;
    }
  }, [key]);

  const subscribe = useCallback((notify: () => void) => {
    function fromStorage(event: StorageEvent) {
      if (event.key !== key && event.key !== null) return;
      try {
        if (event.storageArea !== window.localStorage) return;
      } catch {
        return;
      }
      if (key) tabPreferences.delete(key);
      notify();
    }

    function fromTab(event: Event) {
      if ((event as CustomEvent<string>).detail === key) notify();
    }

    window.addEventListener('storage', fromStorage);
    window.addEventListener(UPDATED_EVENT, fromTab);
    return () => {
      window.removeEventListener('storage', fromStorage);
      window.removeEventListener(UPDATED_EVENT, fromTab);
    };
  }, [key]);

  const setProactive = useCallback((value: boolean) => {
    if (!key) return;
    try {
      window.localStorage.setItem(key, String(value));
      tabPreferences.set(key, { value, saved: true });
    } catch {
      tabPreferences.set(key, { value, saved: false });
    }
    window.dispatchEvent(new CustomEvent(UPDATED_EVENT, { detail: key }));
  }, [key]);

  return { proactive: useSyncExternalStore(subscribe, readPreference), setProactive };
}
