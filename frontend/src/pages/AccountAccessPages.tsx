import { Alert, Button, Checkbox, FormControlLabel, Link, Stack, TextField, Typography } from '@mui/material';
import { FormEvent, useLayoutEffect, useState } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { authApi } from '../api/client';
import { useAuth } from '../auth/AuthProvider';
import { ErrorState } from '../components/ErrorState';
import { LoadingBlock } from '../components/LoadingBlock';
import { useApi } from '../hooks/useApi';
import { AuthFrame } from './AuthPages';

/** Fragments never reach the server, and must leave browser history once the page captures the capability. */
function useLinkToken() {
  const [token, setToken] = useState(() => new URLSearchParams(window.location.hash.slice(1)).get('token') ?? '');
  useLayoutEffect(() => {
    if (window.location.hash) window.history.replaceState(window.history.state, '', window.location.pathname + window.location.search);
  }, []);
  return { token, clearToken: () => setToken('') };
}

export function PasswordRecoveryPage() {
  const options = useApi(authApi.options);
  const [email, setEmail] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      setMessage((await authApi.recoverPassword(email)).message);
    } catch (next) {
      setError(next instanceof Error ? next.message : 'Не удалось запросить восстановление');
    } finally { setBusy(false); }
  };
  return (
    <AuthFrame title="Восстановление пароля">
      <Stack spacing={2}>
        {options.loading ? <LoadingBlock /> : options.error ? <ErrorState message={options.error} onRetry={options.reload} /> : (
          options.data?.mailAvailable ? (
            message ? <Alert severity="success">{message}</Alert> : (
              <Stack component="form" spacing={2} onSubmit={submit}>
                <Typography color="text.secondary">Укажите адрес из вашего профиля. Ссылка для нового пароля действует 30 минут.</Typography>
                {error && <Alert severity="error">{error}</Alert>}
                <TextField id="recovery-email" name="email" label="Email" type="email" autoComplete="email" required value={email} onChange={(event) => setEmail(event.target.value)} />
                <Button type="submit" variant="contained" disabled={busy}>Отправить ссылку</Button>
              </Stack>
            )
          ) : <Alert severity="info">Восстановление через почту на этом стенде выключено. Обратитесь к владельцу стенда.</Alert>
        )}
        <Link component={RouterLink} to="/sign-in">Вернуться ко входу</Link>
      </Stack>
    </AuthFrame>
  );
}

export function PasswordResetPage() {
  const { token, clearToken } = useLinkToken();
  const { endSession } = useAuth();
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      setMessage((await authApi.resetPassword(token, password)).message);
      endSession();
      clearToken();
      setPassword('');
    } catch (next) {
      setError(next instanceof Error ? next.message : 'Не удалось изменить пароль');
    } finally { setBusy(false); }
  };
  return (
    <AuthFrame title="Новый пароль">
      <Stack spacing={2}>
        {message ? <Alert severity="success">{message}</Alert> : !token ? (
          <Alert severity="warning">Ссылка не найдена. Откройте ссылку из письма или запросите новую.</Alert>
        ) : (
          <Stack component="form" spacing={2} onSubmit={submit}>
            <Typography color="text.secondary">После смены пароля все прежние сессии завершатся.</Typography>
            {error && <Alert severity="error">{error}</Alert>}
            <TextField id="reset-password" name="newPassword" label="Новый пароль" type="password" autoComplete="new-password" required helperText="От 8 до 72 символов, буквы и цифры; максимум 72 байта UTF-8"
              value={password} onChange={(event) => setPassword(event.target.value)} />
            <Button type="submit" variant="contained" disabled={busy}>Установить пароль</Button>
          </Stack>
        )}
        <Link component={RouterLink} to="/password-recovery">Запросить новую ссылку</Link>
        <Link component={RouterLink} to="/sign-in">Вернуться ко входу</Link>
      </Stack>
    </AuthFrame>
  );
}

export function AcceptInvitationPage() {
  const { token, clearToken } = useLinkToken();
  const { acceptSession } = useAuth();
  const preview = useApi(() => token ? authApi.inspectInvitation(token) : Promise.reject(new Error('Ссылка не найдена. Откройте приглашение или запросите новое у представителя компании.')), [token]);
  const [fullName, setFullName] = useState('');
  const [password, setPassword] = useState('');
  const [consent, setConsent] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      const session = await authApi.acceptInvitation(token, fullName, password, consent);
      clearToken();
      setPassword('');
      acceptSession(session);
    } catch (next) {
      setError(next instanceof Error ? next.message : 'Не удалось принять приглашение');
      setBusy(false);
    }
  };
  return (
    <AuthFrame title="Приглашение в компанию">
      <Stack spacing={2}>
        {preview.loading ? <LoadingBlock /> : preview.error ? <Alert severity="warning">{preview.error}</Alert> : preview.data && (
          <Stack component="form" spacing={2} onSubmit={submit}>
            <Typography fontWeight={600}>{preview.data.companyName}</Typography>
            <Typography sx={{ overflowWrap: 'anywhere' }}>{preview.data.email}</Typography>
            <Alert severity="info">{preview.data.canSign ? 'С правом подписи от компании.' : 'Без права подписи от компании.'} После принятия вы войдёте как этот сотрудник.</Alert>
            {error && <Alert severity="error">{error}</Alert>}
            <TextField id="invitation-name" name="fullName" label="Ваше имя" autoComplete="name" required value={fullName} onChange={(event) => setFullName(event.target.value)} />
            <TextField id="invitation-password" name="newPassword" label="Пароль" type="password" autoComplete="new-password" required helperText="От 8 до 72 символов, буквы и цифры; максимум 72 байта UTF-8"
              value={password} onChange={(event) => setPassword(event.target.value)} />
            <FormControlLabel control={<Checkbox checked={consent} onChange={(_, checked) => setConsent(checked)} />} label="Согласен на обработку персональных данных" />
            <Button type="submit" variant="contained" disabled={busy || !consent}>Принять приглашение</Button>
          </Stack>
        )}
        <Link component={RouterLink} to="/sign-in">Вернуться ко входу</Link>
      </Stack>
    </AuthFrame>
  );
}
