import { Alert, Box, Button, Checkbox, FormControlLabel, Link, Paper, Stack, TextField, Typography, useMediaQuery } from '@mui/material';
import { FormEvent, useEffect, useRef, useState } from 'react';
import { Link as RouterLink, Navigate, useSearchParams, useLocation } from 'react-router-dom';
import { authApi } from '../api/client';
import { roleHome, useAuth } from '../auth/AuthProvider';
import { BrandMark } from '../components/BrandMark';
import { DocumentSurface } from '../components/DocumentSurface';
import { BulletItem } from '../components/BulletItem';
import { tokens } from '../theme/theme';
import { Illustration } from '../components/Illustration';
import { usePageTitle } from '../hooks/usePageTitle';
import { authPath, roleDestination } from '../shared/financingSelection';
import { generateTestInn, isValidInn } from '../shared/inn';
import { emailError, nameError, passwordError, passwordHint, requiredError, useFieldFeedback } from '../shared/authValidation';

const DEMO_PASSWORD = 'Demo2026';

const demoAccounts = [
  { email: 'client@demo.test', label: 'Предприниматель' },
  { email: 'manager@demo.test', label: 'Кредитный менеджер' },
  { email: 'underwriter@demo.test', label: 'Андеррайтер' },
  { email: 'risk@demo.test', label: 'Риск-администратор' }
];

const promises = [
  'Автоматическая проверка небольших заявок',
  'Сведения о компании банк получит сам: из реестра, налоговой и бюро',
  'Предложение и подписание договора — онлайн, в этом же кабинете'
];

/** Sign-in and sign-up share one frame: what the cabinet gives on the left, the form on the right. */
export function AuthFrame({ title, children }: { title: string; children: React.ReactNode }) {
  usePageTitle(title);
  return (
    <DocumentSurface kind="auth"><Box sx={{ minHeight: '100vh', display: 'grid', gridTemplateColumns: { xs: '1fr', md: '5fr 7fr' }, bgcolor: 'background.default' }}>
      <Box component="aside" sx={{ display: { xs: 'none', md: 'flex' }, flexDirection: 'column', justifyContent: 'space-between', bgcolor: tokens.night, color: '#fff', p: 6, position: 'relative', overflow: 'hidden' }}>
        <BrandMark inverted />
        <Box>
          <Box aria-hidden sx={{ position: 'relative', height: 200, mb: 4 }}>
            <Illustration name="coin" size={170} sx={{ position: 'absolute', left: 0, top: 0, transform: 'rotate(-8deg)' }} />
            <Illustration name="credit-card" size={140} sx={{ position: 'absolute', left: 150, top: 50, transform: 'rotate(10deg)' }} />
            <Illustration name="chart" size={90} sx={{ position: 'absolute', left: 280, top: 10 }} />
          </Box>
          <Typography variant="h4" component="p" fontWeight={700} sx={{ maxWidth: 420 }}>Кредит для бизнеса онлайн</Typography>
          <Stack component="ul" spacing={2} sx={{ mt: 4, p: 0, listStyle: 'none' }}>
            {promises.map((text) => (
              <BulletItem key={text} color={tokens.red}>
                <Typography sx={{ color: 'rgba(255,255,255,0.8)' }}>{text}</Typography>
              </BulletItem>
            ))}
          </Stack>
        </Box>
        <Typography variant="caption" sx={{ color: 'rgba(255,255,255,0.56)' }}>Прототип: все данные синтетические.</Typography>
      </Box>
      <Box sx={{ display: 'flex', flexDirection: 'column', px: 2, py: { xs: 2, md: 4 } }}>
        <Box sx={{ display: { xs: 'block', md: 'none' }, mb: { xs: 2, sm: 3 } }}>
          <BrandMark />
        </Box>
        <Box sx={{ flexGrow: 1, display: 'grid', alignItems: { xs: 'start', sm: 'center' }, justifyItems: 'center' }}>
          <Paper sx={{ width: '100%', maxWidth: 440, p: { xs: 2, sm: 5 }, borderRadius: '24px' }}>
            <Typography variant="h5" component="h1" fontWeight={700} sx={{ mb: { xs: 2, sm: 3 }, fontSize: { xs: '1.75rem', sm: '1.875rem' }, overflowWrap: 'anywhere' }}>{title}</Typography>
            {children}
          </Paper>
        </Box>
      </Box>
    </Box></DocumentSurface>
  );
}

export function SignInPage() {
  const { session, signIn } = useAuth();
  const location = useLocation();
  const [params] = useSearchParams();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [demo, setDemo] = useState(false);
  const submitting = useRef(false);
  const errors = { email: emailError(email), password: password ? '' : requiredError };
  const feedback = useFieldFeedback(errors);
  const ready = !Object.values(errors).some(Boolean);
  const mobile = useMediaQuery('(max-width: 599.95px)');

  useEffect(() => {
    authApi.options().then((options) => setDemo(options.demoAccounts)).catch(() => setDemo(false));
  }, []);

  if (session) return <Navigate to={roleDestination(session.user.role, location.search, roleHome[session.user.role])} replace />;

  const submit = async (event: FormEvent, credentials?: { email: string; password: string }) => {
    event.preventDefault();
    if (submitting.current) return;
    if (!credentials && !ready) { feedback.visitAll(); return; }
    submitting.current = true;
    setBusy(true);
    setError(null);
    try {
      await signIn(credentials?.email ?? email.trim(), credentials?.password ?? password);
    } catch (nextError) {
      setError(nextError instanceof Error ? nextError.message : 'Не удалось войти');
      setBusy(false);
    } finally {
      submitting.current = false;
    }
  };

  return (
    <AuthFrame title="Вход">
      <Stack component="form" spacing={2} onSubmit={submit} noValidate>
        {params.get('expired') && <Alert severity="info">Сессия истекла — войдите снова.</Alert>}
        {params.get('security') && <Alert severity="success">Все сессии завершены. Войдите заново.</Alert>}
        {error && <Alert severity="error">{error}</Alert>}
        <TextField {...feedback.field('email')} label="Email" type="email" autoComplete="email" value={email} onChange={(event) => setEmail(event.target.value)} required />
        <TextField {...feedback.field('password')} label="Пароль" type="password" autoComplete="current-password" value={password} onChange={(event) => setPassword(event.target.value)} required />
        <Typography id="signin-readiness" variant="body2" color="text.secondary">{ready ? 'Данные заполнены — можно войти.' : 'Для входа укажите корректный email и пароль.'}</Typography>
        <Button type="submit" variant="contained" size="large" disabled={busy || !ready} aria-describedby="signin-readiness">Войти</Button>
        <Link component={RouterLink} to="/password-recovery">Забыли пароль?</Link>
        <Typography variant="body2">
          Нет кабинета? <Link component={RouterLink} to={authPath('/sign-up', location.search)}>Подключите компанию</Link>
        </Typography>
      </Stack>
      {demo && (
        <Box component={mobile ? 'details' : 'div'} sx={{ mt: 3, pt: { xs: 1, sm: 3 }, borderTop: 1, borderColor: 'divider' }}>
          <Typography component={mobile ? 'summary' : 'p'} variant="subtitle2" gutterBottom sx={mobile ? { cursor: 'pointer', minHeight: 44, py: 1.5 } : {}}>Демо-доступ</Typography>
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
  const location = useLocation();
  const [demo, setDemo] = useState(false);

  useEffect(() => {
    authApi.options().then((options) => setDemo(options.demoAccounts)).catch(() => setDemo(false));
  }, []);
  const [form, setForm] = useState({ fullName: '', email: '', password: '', inn: '', personalDataConsent: false });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const submitting = useRef(false);

  const innDigits = form.inn.replace(/\D/g, '');
  const innComplete = innDigits.length === 10 || innDigits.length === 12;
  const errors = {
    inn: !innDigits ? requiredError : !innComplete ? `Сейчас ${innDigits.length} цифр — нужно 10 или 12` : !isValidInn(innDigits) ? 'Похоже на опечатку: не сходится контрольная цифра' : '',
    fullName: nameError(form.fullName), email: emailError(form.email), password: passwordError(form.password)
  };
  const feedback = useFieldFeedback(errors);
  const ready = form.personalDataConsent && !Object.values(errors).some(Boolean);

  if (session) return <Navigate to={roleDestination(session.user.role, location.search, roleHome[session.user.role])} replace />;

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (submitting.current) return;
    if (!ready) { feedback.visitAll(); return; }
    submitting.current = true;
    setBusy(true);
    setError(null);
    try {
      await signUp({ ...form, fullName: form.fullName.trim(), email: form.email.trim(), inn: innDigits });
    } catch (nextError) {
      setError(nextError instanceof Error ? nextError.message : 'Не удалось зарегистрироваться');
      setBusy(false);
    } finally {
      submitting.current = false;
    }
  };

  return (
    <AuthFrame title="Подключение компании">
      <Stack component="form" spacing={2} onSubmit={submit} noValidate>
        {error && <Alert severity="error">{error}</Alert>}
        <TextField {...feedback.field('inn', '10 цифр для организации, 12 — для ИП')} label="ИНН компании или ИП" inputProps={{ inputMode: 'numeric' }} autoComplete="off" value={form.inn}
          onChange={(event) => setForm({ ...form, inn: event.target.value })} required />
        {demo && (
          <Typography variant="body2" color="text.secondary">
            Демо: синтетический ИНН для{' '}
            <Link component="button" type="button" onClick={() => setForm({ ...form, inn: generateTestInn(true) })}>организации</Link>
            {' '}или{' '}
            <Link component="button" type="button" onClick={() => setForm({ ...form, inn: generateTestInn(false) })}>ИП</Link>
            {' '}— такого региона нет, данные не совпадут с реальным бизнесом.
          </Typography>
        )}
        <TextField {...feedback.field('fullName')} label="Ваше имя" autoComplete="name" value={form.fullName} onChange={(event) => setForm({ ...form, fullName: event.target.value })} required />
        <TextField {...feedback.field('email')} label="Email" type="email" autoComplete="email" value={form.email} onChange={(event) => setForm({ ...form, email: event.target.value })} required />
        <TextField {...feedback.field('password', passwordHint)} label="Пароль" type="password" autoComplete="new-password" value={form.password}
          onChange={(event) => setForm({ ...form, password: event.target.value })} required />
        <FormControlLabel
          control={<Checkbox checked={form.personalDataConsent} onChange={(event) => setForm({ ...form, personalDataConsent: event.target.checked })} />}
          label="Согласен на обработку персональных данных"
        />
        <Typography id="signup-readiness" variant="body2" color="text.secondary">{ready ? 'Все требования выполнены — можно подключить компанию.' : 'Заполните ИНН, имя, email и пароль по требованиям и подтвердите согласие.'}</Typography>
        <Button type="submit" variant="contained" size="large" disabled={busy || !ready} aria-describedby="signup-readiness">Подключить</Button>
        <Typography variant="body2">
          Уже есть кабинет? <Link component={RouterLink} to={authPath('/sign-in', location.search)}>Войти</Link>
        </Typography>
      </Stack>
    </AuthFrame>
  );
}
