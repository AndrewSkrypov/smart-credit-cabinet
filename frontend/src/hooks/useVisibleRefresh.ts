import { useEffect } from 'react';

export function useVisibleRefresh(refresh: () => Promise<unknown>, intervalMs: number) {
  useEffect(() => {
    let pending = false;
    const refreshVisible = () => {
      if (document.hidden || pending) return;
      pending = true;
      void refresh().finally(() => { pending = false; });
    };
    const timer = window.setInterval(refreshVisible, intervalMs);
    window.addEventListener('focus', refreshVisible);
    document.addEventListener('visibilitychange', refreshVisible);
    return () => {
      window.clearInterval(timer);
      window.removeEventListener('focus', refreshVisible);
      document.removeEventListener('visibilitychange', refreshVisible);
    };
  }, [refresh, intervalMs]);
}
