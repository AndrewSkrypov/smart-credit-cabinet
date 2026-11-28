import { StrictMode } from 'react';
import { ThemeProvider } from '@mui/material';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { createBrowserRouter, RouterProvider, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { AuthProvider } from '../auth/AuthProvider';
import { cabinetTheme } from '../theme/theme';
import { Role, UserSession } from '../types';
import { JuryLinkPage } from './JuryLinkPage';

vi.mock('../auth/demoAccess', async (original) => ({
  ...await original<typeof import('../auth/demoAccess')>(),
  demoAccess: { basename: '/demo/jury', showPicker: false },
  cabinetStorageScope: 'cabinet.jury'
}));

const token = 'A'.repeat(43);
const fetchMock = vi.fn<typeof fetch>();
const fullName = 'Участник жюри';
const email = 'jury@example.test';

function fixtureSession(role: Role): UserSession {
  return {
    token: 'synthetic-session',
    user: { id: 'jury-fixture', fullName, email, role, status: 'ACTIVE', companyId: null, canSign: false }
  };
}

function Position() {
  const { pathname, search, hash } = useLocation();
  return <output aria-label="Текущий маршрут">{pathname + search + hash}</output>;
}

function open(path: string) {
  window.history.replaceState(null, '', path);
  const router = createBrowserRouter([{
    element: <AuthProvider><JuryLinkPage /></AuthProvider>,
    path: '/'
  }, {
    element: <Position />,
    path: '*'
  }], { basename: '/demo/jury', future: { v7_relativeSplatPath: true } });

  render(
    <StrictMode>
      <ThemeProvider theme={cabinetTheme}>
        <RouterProvider router={router} future={{ v7_startTransition: true }} />
      </ThemeProvider>
    </StrictMode>
  );
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  cleanup();
  localStorage.clear();
  window.history.replaceState(null, '', '/');
  vi.unstubAllGlobals();
});

it.each([
  ['CLIENT', '/client'], ['AGENT', '/agent'], ['MANAGER', '/bank'], ['UNDERWRITER', '/bank'], ['RISK_ADMIN', '/bank']
] as const)('prefills the %s identity and waits for one click to open its cabinet', async (role, home) => {
  const session = fixtureSession(role);
  localStorage.setItem('cabinet.session', 'ordinary');
  localStorage.setItem('cabinet.demo.session', 'public-demo');
  fetchMock.mockImplementationOnce(async () => {
    expect(window.location.hash).toBe('');
    expect(window.location.search).toBe('');
    return Response.json({ fullName, email, role });
  }).mockResolvedValueOnce(Response.json(session));

  open(`/demo/jury?next=%2Fbank%2Fproducts#${token}`);

  expect(await screen.findByText(fullName)).toBeInTheDocument();
  expect(screen.getByRole('textbox', { name: 'Логин' })).toHaveValue(email);
  expect(screen.getByRole('textbox', { name: 'Одноразовый ключ' })).not.toHaveValue(token);
  expect(localStorage.getItem('cabinet.jury.session')).toBeNull();

  const request = {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token }),
    mode: 'same-origin', credentials: 'omit', redirect: 'error', cache: 'no-store', referrerPolicy: 'no-referrer'
  };
  expect(fetchMock).toHaveBeenCalledExactlyOnceWith('/demo/jury/api/v1/auth/jury-link/prepare', request);

  const button = screen.getByRole('button', { name: 'Войти' });
  fireEvent.submit(button.closest('form')!);
  fireEvent.submit(button.closest('form')!);

  expect(await screen.findByLabelText('Текущий маршрут')).toHaveTextContent(new RegExp(`^${home}$`));
  expect(window.location.pathname).toBe(`/demo/jury${home}`);
  expect(fetchMock).toHaveBeenCalledTimes(2);
  expect(fetchMock).toHaveBeenNthCalledWith(2, '/demo/jury/api/v1/auth/jury-link/redeem', request);
  expect(JSON.parse(localStorage.getItem('cabinet.jury.session')!)).toEqual(session);
  expect(localStorage.getItem('cabinet.session')).toBe('ordinary');
  expect(localStorage.getItem('cabinet.demo.session')).toBe('public-demo');
});

it.each(['', '#short', `#${'A'.repeat(42)}B`, `?token=${token}`])('ignores malformed or misplaced tokens (%s)', (suffix) => {
  open(`/demo/jury${suffix}`);
  expect(screen.getByRole('alert')).toHaveTextContent('попросите организатора выдать новый');
  expect(fetchMock).not.toHaveBeenCalled();
  expect(window.location.hash).toBe('');
  expect(window.location.search).toBe('');
});

it('shows generic help when an invitation becomes unavailable after the preview', async () => {
  const prior = JSON.stringify(fixtureSession('CLIENT'));
  localStorage.setItem('cabinet.jury.session', prior);
  fetchMock.mockResolvedValueOnce(Response.json({ fullName, email, role: 'AGENT' }))
    .mockResolvedValueOnce(new Response('server-only rejection details', { status: 410 }));

  open(`/demo/jury#${token}`);
  fireEvent.click(await screen.findByRole('button', { name: 'Войти' }));

  expect(await screen.findByRole('alert')).toHaveTextContent('QR-ссылка недоступна');
  expect(screen.queryByText(/server-only rejection details/)).not.toBeInTheDocument();
  expect(localStorage.getItem('cabinet.jury.session')).toBe(prior);
});

it('rejects a session for a different identity than the prefilled invitation', async () => {
  fetchMock.mockResolvedValueOnce(Response.json({ fullName, email, role: 'AGENT' }))
    .mockResolvedValueOnce(Response.json(fixtureSession('CLIENT')));

  open(`/demo/jury#${token}`);
  fireEvent.click(await screen.findByRole('button', { name: 'Войти' }));

  expect(await screen.findByRole('alert')).toHaveTextContent('QR-ссылка недоступна');
  expect(localStorage.getItem('cabinet.jury.session')).toBeNull();
});
