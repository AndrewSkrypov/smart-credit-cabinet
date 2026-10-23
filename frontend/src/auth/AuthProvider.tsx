import React, { createContext, useCallback, useContext, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { authApi } from '../api/client';
import { Role, SignUpInput, UserSession } from '../types';
import { clearSession, readSession, writeSession } from './session';

interface AuthContextValue {
  session: UserSession | null;
  signIn: (email: string, password: string) => Promise<UserSession>;
  signUp: (input: SignUpInput) => Promise<UserSession>;
  signOut: () => void;
  acceptSession: (session: UserSession) => UserSession;
  endSession: () => void;
  isAllowed: (roles: Role[]) => boolean;
}

const AuthContext = createContext<AuthContextValue | null>(null);

/** Where each role starts: clients in their cabinet, bank staff in the bank workspace. */
export const roleHome: Record<Role, string> = {
  CLIENT: '/client',
  MANAGER: '/bank',
  UNDERWRITER: '/bank',
  RISK_ADMIN: '/bank'
};

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const navigate = useNavigate();
  const [session, setSession] = useState<UserSession | null>(readSession);

  const start = useCallback((next: UserSession) => {
    writeSession(next);
    setSession(next);
    // Back to the page the user was on when the session expired, if it is in their area.
    const requested = new URLSearchParams(window.location.search).get('next');
    const home = roleHome[next.user.role];
    navigate(requested && requested.startsWith(`${home}/`) ? requested : home, { replace: true });
    return next;
  }, [navigate]);

  const signIn = useCallback(async (email: string, password: string) => start(await authApi.signIn(email, password)), [start]);

  const signUp = useCallback(async (input: SignUpInput) => start(await authApi.signUp(input)), [start]);

  const endSession = useCallback(() => {
    clearSession();
    setSession(null);
  }, []);

  const signOut = useCallback(() => {
    endSession();
    navigate('/sign-in', { replace: true });
  }, [endSession, navigate]);

  const isAllowed = useCallback((roles: Role[]) => !!session && roles.includes(session.user.role), [session]);

  const value = useMemo<AuthContextValue>(
    () => ({ session, signIn, signUp, signOut, isAllowed, acceptSession: start, endSession }),
    [session, signIn, signUp, signOut, isAllowed, start, endSession]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used inside AuthProvider');
  }
  return context;
}
