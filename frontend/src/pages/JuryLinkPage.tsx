import { Alert, Button, CircularProgress, Stack, TextField, Typography } from '@mui/material';
import { FormEvent, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { authApi, JuryLinkPreview } from '../api/client';
import { roleHome, useAuth } from '../auth/AuthProvider';
import { roleLabels } from '../shared/labels';
import { AuthFrame } from './AuthPages';

// A canonical base64url encoding of 32 bytes has two zero bits in the final character.
const TOKEN_FORMAT = /^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$/;

type InviteView =
  | { phase: 'loading' }
  | { phase: 'unavailable' }
  | { phase: 'ready' | 'confirming'; invite: JuryLinkPreview };

export function JuryLinkPage() {
  const { hash, search } = useLocation();
  const candidate = hash.slice(1);
  const token = TOKEN_FORMAT.test(candidate) ? candidate : null;
  const [view, setView] = useState<InviteView>({ phase: 'loading' });
  const scan = useRef<{ token: string; preview: Promise<JuryLinkPreview>; confirming: boolean }>();
  const mounted = useRef(false);
  const { updateSession } = useAuth();
  const navigate = useNavigate();

  useLayoutEffect(() => {
    if (hash || search) {
      window.history.replaceState(window.history.state, '', window.location.pathname);
    }
  }, [hash, search]);

  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  useEffect(() => {
    if (!token) {
      scan.current = undefined;
      return;
    }
    // Keep a single request if React StrictMode restarts this effect.
    if (scan.current?.token !== token) {
      scan.current = { token, preview: authApi.prepareJuryLink(token), confirming: false };
      setView({ phase: 'loading' });
    }
    let current = true;
    void scan.current.preview.then((invite) => {
      if (!current) return;
      const valid = typeof invite?.fullName === 'string' && invite.fullName.trim() &&
        typeof invite?.email === 'string' && invite.email.trim() &&
        Object.prototype.hasOwnProperty.call(roleHome, invite.role);
      setView(valid ? { phase: 'ready', invite } : { phase: 'unavailable' });
    }, () => {
      if (current) setView({ phase: 'unavailable' });
    });
    return () => { current = false; };
  }, [token]);

  async function confirm(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const attempt = scan.current;
    if (!token || !attempt || attempt.token !== token || attempt.confirming || view.phase !== 'ready') return;

    const { invite } = view;
    attempt.confirming = true;
    setView({ phase: 'confirming', invite });
    try {
      const session = await authApi.redeemJuryLink(token);
      if (!mounted.current || scan.current !== attempt) return;
      if (typeof session?.token !== 'string' || !session.token || session.user?.status !== 'ACTIVE' ||
        session.user.email !== invite.email || session.user.role !== invite.role) {
        setView({ phase: 'unavailable' });
        return;
      }
      updateSession(session);
      navigate(roleHome[session.user.role], { replace: true });
    } catch {
      if (mounted.current && scan.current === attempt) setView({ phase: 'unavailable' });
    }
  }

  return (
    <AuthFrame title="Вход для жюри">
      {!token || view.phase === 'unavailable' ? (
        <Alert severity="warning">
          QR-ссылка недоступна. Отсканируйте действующий QR или попросите организатора выдать новый.
        </Alert>
      ) : view.phase === 'loading' ? (
        <Stack role="status" direction="row" spacing={2} alignItems="center">
          <CircularProgress size={20} aria-hidden="true" />
          <Typography>Проверяем приглашение…</Typography>
        </Stack>
      ) : (
        <Stack component="form" onSubmit={confirm} spacing={2.5} autoComplete="off">
          <Typography>
            <strong>{view.invite.fullName}</strong><br />{roleLabels[view.invite.role]}
          </Typography>
          <TextField label="Логин" value={view.invite.email} InputProps={{ readOnly: true }} />
          <TextField
            label="Одноразовый ключ"
            value="••••••••"
            helperText="Ключ уже подставлен из QR"
            InputProps={{ readOnly: true }}
          />
          <Button type="submit" variant="contained" size="large" disabled={view.phase === 'confirming'}>
            {view.phase === 'confirming' ? 'Входим…' : 'Войти'}
          </Button>
        </Stack>
      )}
    </AuthFrame>
  );
}
