import { GlobalStyles } from '@mui/material';
import { createContext, ReactNode, useContext, useLayoutEffect, useMemo } from 'react';
import { Outlet } from 'react-router-dom';
import { tokens } from '../theme/theme';

export type Surface = 'landing' | 'workspace' | 'auth' | 'light';
const Owner = createContext<(surface: Surface, priority: number) => () => void>(() => () => undefined);

/** Sole owner of the document canvas. Cleanup releases its own claim, never a newer layout. */
export function DocumentCanvas({ children }: { children: ReactNode }) {
  const claim = useMemo(() => {
    const claims = new Map<symbol, { surface: Surface; priority: number }>();
    const apply = () => {
      const winner = [...claims.values()].sort((a, b) => b.priority - a.priority)[0];
      document.documentElement.dataset.surface = winner?.surface ?? 'light';
    };
    return (surface: Surface, priority: number) => {
      const id = Symbol(); claims.set(id, { surface, priority }); apply();
      return () => { claims.delete(id); apply(); };
    };
  }, []);
  return <Owner.Provider value={claim}>
    <GlobalStyles styles={{
      html: { minHeight: '100%', background: tokens.page },
      body: { minHeight: '100vh', background: 'transparent' },
      '#root': { minHeight: '100vh', background: 'transparent' },
      'html[data-surface="landing"]': { background: tokens.night },
      'html[data-surface="workspace"]': { background: `linear-gradient(${tokens.night} 0px, ${tokens.night} 120px, ${tokens.page} 120px, ${tokens.page} 100%)` },
      'html[data-surface="auth"]': { background: `linear-gradient(to right, ${tokens.night} 0%, ${tokens.night} 41.666667%, ${tokens.page} 41.666667%, ${tokens.page} 100%)`,
        '@media (max-width: 899.95px)': { background: tokens.page } }
    }} />
    {children}
  </Owner.Provider>;
}

export function DocumentSurface({ kind, priority = 0, children }: { kind: Surface; priority?: number; children?: ReactNode }) {
  const claim = useContext(Owner);
  useLayoutEffect(() => claim(kind, priority), [claim, kind, priority]);
  return children === undefined ? <Outlet /> : <>{children}</>;
}
