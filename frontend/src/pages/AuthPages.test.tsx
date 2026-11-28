import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ThemeProvider } from '@mui/material';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { cabinetTheme } from '../theme/theme';
import { SignInPage, SignUpPage } from './AuthPages';
import { demoAccess, resolveDemoAccess } from '../auth/demoAccess';
import { generateTestInn } from '../shared/inn';
import { emailError, passwordError } from '../shared/authValidation';

const fetchDemo = vi.fn<typeof fetch>();
const auth = vi.hoisted(() => ({
  signIn: vi.fn(), signUp: vi.fn(), updateSession: vi.fn(), options: vi.fn(), demoSignIn: vi.fn(),
  session: vi.fn()
}));
vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({ ...auth, session: auth.session() }),
  roleHome: { CLIENT: '/client', MANAGER: '/bank', UNDERWRITER: '/bank', RISK_ADMIN: '/bank' }
}));
vi.mock('../auth/demoAccess', async (original) => ({
  ...await original<typeof import('../auth/demoAccess')>(), demoAccess: { basename: '', showPicker: false }
}));
vi.mock('../api/client', () => ({ authApi: { options: auth.options, signIn: auth.demoSignIn } }));
beforeEach(() => {
  vi.resetAllMocks();
  scope('http://localhost:3000', '/sign-in');
  auth.session.mockReturnValue(null);
  auth.options.mockResolvedValue({ demoAccounts: false });
  fetchDemo.mockResolvedValue(new Response(null, { status: 404 }));
  vi.stubGlobal('fetch', fetchDemo);
});
afterEach(() => { vi.unstubAllGlobals(); });
const scope = (origin: string, pathname = '/demo/sign-in') =>
  Object.assign(demoAccess, resolveDemoAccess({ origin, pathname }));
function Location() {
  const { pathname, search } = useLocation();
  return <output aria-label="Текущий маршрут">{pathname + search}</output>;
}
const show = (signup = false, path = '/sign-in') => render(
  <ThemeProvider theme={cabinetTheme}>
    <MemoryRouter initialEntries={[path]}>
      <Location />
      {signup ? <SignUpPage /> : <SignInPage />}
    </MemoryRouter>
  </ThemeProvider>
);
const change = (name: string, value: string) => fireEvent.change(screen.getByLabelText(name, { exact: false }), { target: { value } });

it('keeps sign-in unready, explains touched errors and validates Enter without checking existing password strength', async () => {
  const view = show();
  const submit = screen.getByRole('button', { name: 'Войти' });
  expect(submit).toBeDisabled();
  expect(screen.queryByText('Поле обязательно для заполнения')).not.toBeInTheDocument();
  const email = screen.getByLabelText('Email', { exact: false });
  fireEvent.blur(email);
  expect(email).toHaveAttribute('aria-invalid', 'true');
  expect(document.getElementById(email.getAttribute('aria-describedby')!)).toHaveTextContent('Поле обязательно для заполнения');
  change('Email', 'invalid'); fireEvent.blur(email);
  expect(screen.getByText('Укажите корректный email')).toBeInTheDocument();
  fireEvent.submit(view.container.querySelector('form')!);
  expect(auth.signIn).not.toHaveBeenCalled();
  change('Email', 'client@demo.test'); change('Пароль', 'x');
  expect(submit).toBeEnabled();
  expect(email).toHaveAttribute('aria-invalid', 'false');
  await act(async () => { fireEvent.submit(view.container.querySelector('form')!); });
  expect(auth.signIn).toHaveBeenCalledExactlyOnceWith('client@demo.test', 'x');
});

const roleCases = [
  ['client', 'Предприниматель', 'CLIENT', '/client'],
  ['manager', 'Кредитный менеджер', 'MANAGER', '/bank'],
  ['underwriter', 'Андеррайтер', 'UNDERWRITER', '/bank'],
  ['risk', 'Риск-администратор', 'RISK_ADMIN', '/bank']
] as const;

it.each(['https://dev.credit-cabinet.ru', 'https://credit-cabinet.ru'])('shows four fixed demo links after readiness on %s', async (origin) => {
  scope(origin, '/sign-in');
  fetchDemo.mockResolvedValueOnce(new Response('{"demoAccounts":true}', { status: 200 }));
  show(false, '/sign-in?demoRole=manager');
  for (const [hint, label] of roleCases) {
    expect(await screen.findByRole('link', { name: label }))
      .toHaveAttribute('href', `/demo/sign-in?demoRole=${hint}`);
  }
  expect(screen.getByText('Демо-кабинет с синтетическими данными.')).toBeInTheDocument();
  expect(screen.queryByRole('link', { name: /Агент/ })).not.toBeInTheDocument();
  expect(fetchDemo).toHaveBeenCalledExactlyOnceWith('/demo/__ready', expect.objectContaining({
    method: 'GET', mode: 'same-origin', credentials: 'omit', redirect: 'error', cache: 'no-store'
  }));
  expect(auth.demoSignIn).not.toHaveBeenCalled();
  expect(auth.options).not.toHaveBeenCalled();
});

it.each([[403, '{"demoAccounts":true}'], [200, '{"demoAccounts":false}'], [200, '{"demoAccounts":"true"}'], [200, '<html>']] as const)(
  'keeps the native form when readiness is %s %s', async (status, body) => {
    scope('https://credit-cabinet.ru', '/sign-in');
    fetchDemo.mockResolvedValueOnce(new Response(body, { status }));
    await act(async () => { show(); });
    expect(fetchDemo).toHaveBeenCalledOnce();
    expect(screen.queryByText('Демо-доступ')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Войти' })).toBeDisabled();
  }
);

it.each(roleCases)('authenticates %s once in the demo scope with no extra destination', async (hint, _label, role, path) => {
  scope('https://credit-cabinet.ru');
  auth.options.mockResolvedValue({ demoAccounts: true });
  const session = { user: { role }, token: 'fixture' };
  auth.demoSignIn.mockResolvedValue(session);
  show(false, `/sign-in?demoRole=${hint}&next=%2Fclient%2Fnew`);
  await waitFor(() => expect(screen.getByLabelText('Текущий маршрут')).toHaveTextContent(path));
  expect(screen.getByLabelText('Текущий маршрут')).not.toHaveTextContent('?');
  expect(auth.demoSignIn).toHaveBeenCalledExactlyOnceWith(`${hint}@demo.test`, 'Demo2026');
  expect(auth.updateSession).toHaveBeenCalledExactlyOnceWith(session);
  expect(fetchDemo).not.toHaveBeenCalled();
});

it('replaces an existing demo session only after authentication succeeds', async () => {
  scope('https://dev.credit-cabinet.ru');
  auth.options.mockResolvedValue({ demoAccounts: true });
  auth.session.mockReturnValue({ user: { role: 'CLIENT' } });
  let finish!: (session: unknown) => void;
  auth.demoSignIn.mockImplementationOnce(() => new Promise((resolve) => { finish = resolve; }));
  show(false, '/sign-in?demoRole=manager');
  await waitFor(() => expect(auth.demoSignIn).toHaveBeenCalledOnce());
  expect(auth.updateSession).not.toHaveBeenCalled();
  expect(screen.getByLabelText('Текущий маршрут')).toHaveTextContent('/sign-in');
  expect(screen.getByLabelText('Текущий маршрут')).not.toHaveTextContent('?');
  const next = { user: { role: 'MANAGER' } };
  await act(async () => finish(next));
  expect(auth.updateSession).toHaveBeenCalledExactlyOnceWith(next);
});

it('keeps the prior demo session when the one-time role switch fails', async () => {
  scope('https://dev.credit-cabinet.ru');
  auth.options.mockResolvedValue({ demoAccounts: true });
  auth.session.mockReturnValue({ user: { role: 'CLIENT' } });
  auth.demoSignIn.mockRejectedValue(new Error('Сеть недоступна'));
  show(false, '/sign-in?demoRole=manager');
  expect(await screen.findByRole('alert')).toHaveTextContent('Сеть недоступна');
  expect(screen.getByRole('link', { name: 'Продолжить в прежней демо-роли' })).toHaveAttribute('href', '/client');
  expect(screen.getByLabelText('Текущий маршрут')).toHaveTextContent(/^\/sign-in$/);
  expect(auth.demoSignIn).toHaveBeenCalledOnce();
  expect(auth.updateSession).not.toHaveBeenCalled();
});

it('rejects a session for a different role without writing it', async () => {
  scope('https://credit-cabinet.ru');
  auth.options.mockResolvedValue({ demoAccounts: true });
  auth.demoSignIn.mockResolvedValue({ user: { role: 'RISK_ADMIN' }, token: 'fixture' });
  show(false, '/sign-in?demoRole=manager');
  expect(await screen.findByRole('alert')).toHaveTextContent('Демо-роль недоступна');
  expect(auth.demoSignIn).toHaveBeenCalledOnce();
  expect(auth.updateSession).not.toHaveBeenCalled();
});

it('shows an unavailable role when options fail instead of redirecting to the prior demo role', async () => {
  scope('https://dev.credit-cabinet.ru');
  auth.session.mockReturnValue({ user: { role: 'CLIENT' } });
  auth.options.mockRejectedValue(new Error('HTTP 429'));
  show(false, '/sign-in?demoRole=manager');
  expect(await screen.findByRole('alert')).toHaveTextContent('Демо-вход временно недоступен.');
  expect(screen.getByRole('link', { name: 'Продолжить в прежней демо-роли' })).toHaveAttribute('href', '/client');
  expect(auth.demoSignIn).not.toHaveBeenCalled();
  expect(auth.updateSession).not.toHaveBeenCalled();
});

it.each([
  ['https://credit-cabinet.ru', 'agent', true],
  ['https://dev.credit-cabinet.ru', 'client&demoRole=manager', true],
  ['https://dev.credit-cabinet.ru', 'client', false],
  ['https://demo.dev.credit-cabinet.ru:444', 'client', true],
  ['https://other.example.test', 'client', true],
  ['http://dev.credit-cabinet.ru', 'client', false],
  ['https://www.credit-cabinet.ru', 'client', false]
] as const)('keeps normal sign-in on %s with hint %s and demo enabled=%s', async (origin, hint, enabled) => {
  scope(origin);
  auth.options.mockResolvedValue({ demoAccounts: enabled });
  await act(async () => show(false, `/sign-in?demoRole=${hint}`));
  expect(auth.demoSignIn).not.toHaveBeenCalled();
  expect(fetchDemo).not.toHaveBeenCalled();
  expect(screen.getByRole('button', { name: 'Войти' })).toBeDisabled();
  if (enabled) {
    fireEvent.click(screen.getByRole('button', { name: 'Предприниматель' }));
    expect(auth.signIn).toHaveBeenCalledExactlyOnceWith('client@demo.test', 'Demo2026');
  }
});

it('requires every registration field and consent; retains input after network failure and suppresses duplicate submit', async () => {
  const view = show(true);
  const submit = screen.getByRole('button', { name: 'Подключить' });
  fireEvent.click(screen.getByRole('checkbox'));
  expect(submit).toBeDisabled();
  fireEvent.submit(view.container.querySelector('form')!);
  expect(auth.signUp).not.toHaveBeenCalled();
  const inn = screen.getByLabelText('ИНН компании или ИП', { exact: false });
  expect(inn).toHaveAttribute('inputmode', 'numeric');
  expect(inn).toHaveAttribute('name', 'inn');
  expect(inn).not.toHaveAttribute('type', 'number');
  change('ИНН компании или ИП', generateTestInn(true));
  change('Ваше имя', '  Alice Example  '); change('Email', 'alice@example.test'); change('Пароль', 'Demo2026');
  expect(submit).toBeEnabled();
  expect(submit).toHaveAccessibleDescription('Данные готовы к отправке.');
  fireEvent.click(screen.getByRole('checkbox')); expect(submit).toBeDisabled();
  fireEvent.click(screen.getByRole('checkbox'));
  let reject!: (reason: Error) => void;
  auth.signUp.mockImplementationOnce(() => new Promise((_, fail) => { reject = fail; }));
  await act(async () => { fireEvent.submit(view.container.querySelector('form')!); fireEvent.submit(view.container.querySelector('form')!); });
  expect(auth.signUp).toHaveBeenCalledTimes(1);
  expect(submit).toBeDisabled();
  await act(async () => { reject(new Error('Сеть недоступна')); });
  expect(screen.getByText('Сеть недоступна')).toBeInTheDocument();
  expect(screen.queryByText('Данные готовы к отправке.')).not.toBeInTheDocument();
  expect(submit).not.toHaveAttribute('aria-describedby');
  expect(submit).toBeEnabled();
  expect(screen.getByLabelText('Ваше имя', { exact: false })).toHaveValue('  Alice Example  ');
  const correctedInn = generateTestInn(false);
  change('ИНН компании или ИП', correctedInn);
  auth.signUp.mockResolvedValueOnce(undefined);
  await act(async () => { fireEvent.submit(view.container.querySelector('form')!); });
  expect(auth.signUp).toHaveBeenCalledTimes(2);
  expect(auth.signUp.mock.lastCall![0].inn).toBe(correctedInn);
  expect(auth.signUp.mock.lastCall![0].fullName).toBe('Alice Example');
});

it('preserves checksum feedback on blur and clears an error immediately when corrected', async () => {
  show(true);
  const inn = screen.getByLabelText('ИНН компании или ИП', { exact: false });
  change('ИНН компании или ИП', '12345'); fireEvent.blur(inn);
  expect(screen.getByText('Сейчас 5 цифр — нужно 10 или 12')).toBeInTheDocument();
  change('ИНН компании или ИП', '1234567890'); fireEvent.blur(inn);
  expect(screen.getByText(/не сходится контрольная цифра/)).toBeInTheDocument();
  change('ИНН компании или ИП', generateTestInn(false));
  expect(inn).toHaveAttribute('aria-invalid', 'false');
  change('ИНН компании или ИП', '');
  expect(inn).toHaveAttribute('aria-invalid', 'true');
  await waitFor(() => expect(screen.getByText('Поле обязательно для заполнения')).toBeInTheDocument());
});

it('matches UTF-16 length and UTF-8 byte limits without requiring Cyrillic names', () => {
  expect(passwordError('A' + 'я'.repeat(35) + '1')).toBe(''); // 72 bytes
  expect(passwordError('A' + 'я'.repeat(36) + '1')).toMatch(/72 байта/);
  expect(passwordError('1' + 'A'.repeat(71))).toBe('');
  expect(passwordError('1' + 'A'.repeat(72))).toMatch(/72 символов/);
  expect(passwordError('abcdefgh')).toMatch(/буквы и цифры/);
  expect(passwordError('12345678')).toMatch(/буквы и цифры/);
});

it('rejects malformed mailbox dots and domain labels while retaining aliases and single-label domains', () => {
  for (const value of ['a@.', 'a..b@example.test', '.a@example.test', 'a@example..test', 'a@-example.test']) expect(emailError(value)).toBe('Укажите корректный email');
  for (const value of ['a@b', 'alice+team@example.test', ' first.last@example.test ']) expect(emailError(value)).toBe('');
});
