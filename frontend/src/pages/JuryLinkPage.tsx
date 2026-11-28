import { Alert, CircularProgress, Stack, Typography } from '@mui/material';
import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { authApi } from '../api/client';
import { roleHome, useAuth } from '../auth/AuthProvider';
import { UserSession } from '../types';
import { AuthFrame } from './AuthPages';

// A canonical base64url encoding of 32 bytes has two zero bits in the final character.
const TOKEN_FORMAT = /^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$/;

export function JuryLinkPage() {
  const { hash, search } = useLocation();
  const candidate = hash.slice(1);
  const token = TOKEN_FORMAT.test(candidate) ? candidate : null;
  const [unavailable, setUnavailable] = useState(false);
  const exchange = useRef<{ token: string; pending: Promise<UserSession> }>();
  const { updateSession } = useAuth();
  const navigate = useNavigate();

  useLayoutEffect(() => {
    if (hash || search) {
      window.history.replaceState(window.history.state, '', window.location.pathname);
    }
  }, [hash, search]);

  useEffect(() => {
    if (!token) return;
    if (exchange.current?.token !== token) {
      setUnavailable(false);
      exchange.current = { token, pending: authApi.redeemJuryLink(token) };
    }
    // React StrictMode can restart an effect. The invitation can be exchanged only once.
    let current = true;
    void exchange.current.pending.then((session) => {
      if (!current) return;
      if (typeof session?.token !== 'string' || !session.token || session.user?.status !== 'ACTIVE' ||
        !Object.prototype.hasOwnProperty.call(roleHome, session.user.role)) {
        setUnavailable(true);
        return;
      }
      updateSession(session);
      navigate(roleHome[session.user.role], { replace: true });
    }, () => {
      if (current) setUnavailable(true);
    });
    return () => { current = false; };
  }, [navigate, token, updateSession]);

  return (
    <AuthFrame title="Вход для жюри">
      {!token || unavailable ? (
        <Alert severity="warning">
          QR-ссылка недоступна. Проверьте приглашение или попросите организатора выдать новое.
        </Alert>
      ) : (
        <Stack role="status" direction="row" spacing={2} alignItems="center">
          <CircularProgress size={20} aria-hidden="true" />
          <Typography>Открываем демонстрационный кабинет…</Typography>
        </Stack>
      )}
    </AuthFrame>
  );
}
