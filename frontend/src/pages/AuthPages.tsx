import { Alert, Box, Button, Checkbox, FormControlLabel, Link, Paper, Stack, TextField, Typography } from '@mui/material';
import { FormEvent, useEffect, useState } from 'react';
import { Link as RouterLink, Navigate, useSearchParams } from 'react-router-dom';
import { authApi } from '../api/client';
import { roleHome, useAuth } from '../auth/AuthProvider';
import { usePageTitle } from '../hooks/usePageTitle';

const DEMO_PASSWORD = 'Demo2026';

const demoAccounts = [
  { email: 'client@demo.test', label: 'Предприниматель' },
  { email: 'manager@demo.test', label: 'Кредитный менеджер' },
  { email: 'underwriter@demo.test', label: 'Андеррайтер' },
  { email: 'risk@demo.test', label: 'Риск-администратор' }
];

function AuthFrame({ title, children }: { title: string; children: React.ReactNode }) {
  usePageTitle(title);
  return (
    <Box sx={{ minHeight: '100vh', display: 'grid', placeItems: 'center', bgcolor: 'background.default', px: 2, py: 4 }}>
      <Paper variant="outlined" sx={{ width: '100%', maxWidth: 440, p: { xs: 3, sm: 4 } }}>
        <Typography variant="overline" color="text.secondary">Кабинет предпринимателя</Typography>
        <Typography variant="h5" component="h1" fontWeight={700} sx={{ mb: 3 }}>{title}</Typography>
        {children}
      </Paper>
    </Box>
  );
}

export function SignInPage() {
  const { session, signIn } = useAuth();
  const [params] = useSearchParams();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [demo, setDemo] = useState(false);

  useEffect(() => {
    authApi.options().then((options) => setDemo(options.demoAccounts)).catch(() => setDemo(false));
  }, []);

  if (session) return <Navigate to={roleHome[session.user.role]} replace />;

  const submit = async (event: FormEvent, credentials?: { email: string; password: string }) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await signIn(credentials?.email ?? email, credentials?.password ?? password);
    } catch (nextError) {
      setError(nextError instanceof Error ? nextError.message : 'Не удалось войти');
      setBusy(false);
    }
  };

  return (
    <AuthFrame title="Вход">
      <Stack component="form" spacing={2} onSubmit={submit} noValidate>
        {params.get('expired') && <Alert severity="info">Сессия истекла — войдите снова.</Alert>}
        {error && <Alert severity="error">{error}</Alert>}
        <TextField label="Email" type="email" autoComplete="email" value={email} onChange={(event) => setEmail(event.target.value)} required />
        <TextField label="Пароль" type="password" autoComplete="current-password" value={password} onChange={(event) => setPassword(event.target.value)} required />
        <Button type="submit" variant="contained" size="large" disabled={busy}>Войти</Button>
        <Typography variant="body2">
          Нет кабинета? <Link component={RouterLink} to="/sign-up">Подключите компанию</Link>
        </Typography>
      </Stack>
      {demo && (
        <Box sx={{ mt: 3, pt: 3, borderTop: 1, borderColor: 'divider' }}>
          <Typography variant="subtitle2" gutterBottom>Демо-доступ</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>Синтетические учётные записи для проверки сценария.</Typography>
          <Stack direction="row" flexWrap="wrap" gap={1}>
            {demoAccounts.map((account) => (
              <Button key={account.email} variant="outlined" size="small" disabled={busy}
                onClick={(event) => submit(event, { email: account.email, password: DEMO_PASSWORD })}>
                {account.label}
              </Button>
            ))}
          </Stack>
        </Box>
      )}
    </AuthFrame>
  );
}

export function SignUpPage() {
  const { session, signUp } = useAuth();
  const [form, setForm] = useState({ fullName: '', email: '', password: '', inn: '', personalDataConsent: false });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  if (session) return <Navigate to={roleHome[session.user.role]} replace />;

  const innDigits = form.inn.replace(/\D/g, '');
  const innHint = innDigits.length === 0 || innDigits.length === 10 || innDigits.length === 12
    ? '10 цифр для организации, 12 — для ИП'
    : `Сейчас ${innDigits.length} цифр — нужно 10 или 12`;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await signUp({ ...form, inn: innDigits });
    } catch (nextError) {
      setError(nextError instanceof Error ? nextError.message : 'Не удалось зарегистрироваться');
      setBusy(false);
    }
  };

  return (
    <AuthFrame title="Подключение компании">
      <Stack component="form" spacing={2} onSubmit={submit} noValidate>
        {error && <Alert severity="error">{error}</Alert>}
        <TextField label="ИНН компании или ИП" inputMode="numeric" value={form.inn} helperText={innHint}
          onChange={(event) => setForm({ ...form, inn: event.target.value })} required />
        <TextField label="Ваше имя" autoComplete="name" value={form.fullName} onChange={(event) => setForm({ ...form, fullName: event.target.value })} required />
        <TextField label="Email" type="email" autoComplete="email" value={form.email} onChange={(event) => setForm({ ...form, email: event.target.value })} required />
        <TextField label="Пароль" type="password" autoComplete="new-password" value={form.password} helperText="От 8 символов, буквы и цифры"
          onChange={(event) => setForm({ ...form, password: event.target.value })} required />
        <FormControlLabel
          control={<Checkbox checked={form.personalDataConsent} onChange={(event) => setForm({ ...form, personalDataConsent: event.target.checked })} />}
          label="Согласен на обработку персональных данных"
        />
        <Button type="submit" variant="contained" size="large" disabled={busy || !form.personalDataConsent}>Подключить</Button>
        <Typography variant="body2">
          Уже есть кабинет? <Link component={RouterLink} to="/sign-in">Войти</Link>
        </Typography>
      </Stack>
    </AuthFrame>
  );
}
