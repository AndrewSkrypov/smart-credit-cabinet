import { useEffect, useState } from 'react';

import { api } from '../../api/client';
import { useAuth } from '../../auth/AuthProvider';

const REFRESH_MS = 30_000;

export function useKeshaEnabled(): boolean {
  const { session } = useAuth();
  const role = session?.user.role;
  const token = role === 'CLIENT' || role === 'AGENT' ? session?.token : undefined;
  const [enabledForToken, setEnabledForToken] = useState<string | null>(null);

  useEffect(() => {
    setEnabledForToken(null);
    if (!token) return;
    const controller = new AbortController();
    let pending = false;
    const refresh = async () => {
      if (document.visibilityState === 'hidden' || pending) return;
      pending = true;
      try {
        const { data } = await api.get<{ keshaEnabled: boolean }>('/assistant/ui-config', {
          signal: controller.signal,
          timeout: 5_000
        });
        if (!controller.signal.aborted) setEnabledForToken(data?.keshaEnabled === true ? token : null);
      } catch {
        if (!controller.signal.aborted) setEnabledForToken(null);
      } finally {
        pending = false;
      }
    };

    void refresh();
    const interval = window.setInterval(refresh, REFRESH_MS);
    document.addEventListener('visibilitychange', refresh);
    return () => {
      controller.abort();
      window.clearInterval(interval);
      document.removeEventListener('visibilitychange', refresh);
    };
  }, [token]);

  return token !== undefined && enabledForToken === token;
}
