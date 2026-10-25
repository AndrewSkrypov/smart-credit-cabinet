import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ThemeProvider } from '@mui/material';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, expect, it, vi } from 'vitest';
import { cabinetTheme } from '../theme/theme';
import { SignInPage, SignUpPage } from './AuthPages';
import { generateTestInn } from '../shared/inn';
import { passwordError } from '../shared/authValidation';

const auth = vi.hoisted(() => ({ signIn: vi.fn(), signUp: vi.fn() }));
vi.mock('../auth/AuthProvider', () => ({ useAuth: () => ({ ...auth, session: null }), roleHome: {} }));
vi.mock('../api/client', () => ({ authApi: { options: vi.fn(async () => ({ demoAccounts: false })) } }));
beforeEach(() => { vi.resetAllMocks(); });
const show = (signup = false) => render(<ThemeProvider theme={cabinetTheme}><MemoryRouter>{signup ? <SignUpPage /> : <SignInPage />}</MemoryRouter></ThemeProvider>);
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
  fireEvent.click(screen.getByRole('checkbox')); expect(submit).toBeDisabled();
  fireEvent.click(screen.getByRole('checkbox'));
  let reject!: (reason: Error) => void;
  auth.signUp.mockImplementationOnce(() => new Promise((_, fail) => { reject = fail; }));
  await act(async () => { fireEvent.submit(view.container.querySelector('form')!); fireEvent.submit(view.container.querySelector('form')!); });
  expect(auth.signUp).toHaveBeenCalledTimes(1);
  expect(submit).toBeDisabled();
  await act(async () => { reject(new Error('Сеть недоступна')); });
  expect(screen.getByText('Сеть недоступна')).toBeInTheDocument();
  expect(submit).toBeEnabled();
  expect(screen.getByLabelText('Ваше имя', { exact: false })).toHaveValue('  Alice Example  ');
  auth.signUp.mockResolvedValueOnce(undefined);
  await act(async () => { fireEvent.submit(view.container.querySelector('form')!); });
  expect(auth.signUp).toHaveBeenCalledTimes(2);
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
