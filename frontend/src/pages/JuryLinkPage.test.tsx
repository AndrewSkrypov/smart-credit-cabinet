import { StrictMode } from 'react';
import { ThemeProvider } from '@mui/material';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { BrowserRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { AuthProvider } from '../auth/AuthProvider';
import { cabinetTheme } from '../theme/theme';
import { Role, UserSession } from '../types';
import { JuryLinkPage } from './JuryLinkPage';

vi.mock('../auth/demoAccess', async (original) => ({
  ...await original<typeof import('../auth/demoAccess')>(),
  demoAccess: { basename: '/demo/jury', showPicker: false }
}));

const token = 'A'.repeat(43);
const fetchMock = vi.fn<typeof fetch>();

function fixtureSession(role: Role): UserSession {
  return {
    token: 'synthetic-session',
    user: { id: 'jury-fixture', fullName: 'Участник жюри', email: 'jury@example.test', role, status: 'ACTIVE', companyId: null, canSign: false }
  };
}

function Location() {
  const { pathname, search, hash } = useLocation();
  return <output aria-label="Текущий маршрут">{pathname + search + hash}</output>;
}

function open(path: string) {
  window.history.replaceState(null, '', path);
  render(
    <StrictMode>
      <ThemeProvider theme={cabinetTheme}>
        <BrowserRouter basename="/demo/jury" future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
          <AuthProvider>
            <Routes>
              <Route path="/" element={<JuryLinkPage />} />
              <Route path="*" element={<Location />} />
            </Routes>
          </AuthProvider>
        </BrowserRouter>
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
] as const)('exchanges a jury link once and opens the %s cabinet inside its own scope', async (role, home) => {
  const session = fixtureSession(role);
  localStorage.setItem('cabinet.session', 'ordinary');
  localStorage.setItem('cabinet.demo.session', 'public-demo');
  fetchMock.mockImplementationOnce(async () => {
    expect(window.location.hash).toBe('');
    expect(window.location.search).toBe('');
    return new Response(JSON.stringify(session), { status: 200 });
  });

  open(`/demo/jury?next=%2Fbank%2Fproducts#${token}`);

  expect(await screen.findByLabelText('Текущий маршрут')).toHaveTextContent(new RegExp(`^${home}$`));
  expect(window.location.pathname).toBe(`/demo/jury${home}`);
  expect(fetchMock).toHaveBeenCalledExactlyOnceWith('/demo/jury/api/v1/auth/jury-link/redeem', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token }),
    mode: 'same-origin', credentials: 'omit', redirect: 'error', cache: 'no-store', referrerPolicy: 'no-referrer'
  });
  expect(JSON.parse(localStorage.getItem('cabinet.jury.session')!)).toEqual(session);
  expect(localStorage.getItem('cabinet.session')).toBe('ordinary');
  expect(localStorage.getItem('cabinet.demo.session')).toBe('public-demo');
});

it.each(['', '#short', `#${'A'.repeat(42)}B`, `?token=${token}`])('renders help without posting malformed or misplaced tokens (%s)', (suffix) => {
  open(`/demo/jury${suffix}`);

  expect(screen.getByRole('alert')).toHaveTextContent('попросите организатора выдать новое');
  expect(fetchMock).not.toHaveBeenCalled();
  expect(window.location.hash).toBe('');
  expect(window.location.search).toBe('');
});

it.each([404, 410, 503])('shows generic help after HTTP %s and preserves an existing session', async (status) => {
  const prior = JSON.stringify(fixtureSession('CLIENT'));
  localStorage.setItem('cabinet.jury.session', prior);
  fetchMock.mockResolvedValueOnce(new Response('details should not be shown', { status }));

  open(`/demo/jury#${token}`);

  expect(await screen.findByRole('alert')).toHaveTextContent('QR-ссылка недоступна');
  expect(screen.queryByText(/details should not be shown/)).not.toBeInTheDocument();
  expect(fetchMock).toHaveBeenCalledOnce();
  expect(localStorage.getItem('cabinet.jury.session')).toBe(prior);
});

it('rejects a malformed session response without replacing the user in the cabinet', async () => {
  fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({ token: 'invalid', user: { role: 'constructor', status: 'ACTIVE' } }), { status: 200 }));

  open(`/demo/jury#${token}`);

  await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('QR-ссылка недоступна'));
  expect(localStorage.getItem('cabinet.jury.session')).toBeNull();
});

it('opens a replacement invitation on the same page after the first code expires', async () => {
  fetchMock.mockResolvedValueOnce(new Response(null, { status: 410 }));
  fetchMock.mockResolvedValueOnce(new Response(JSON.stringify(fixtureSession('AGENT')), { status: 200 }));
  open(`/demo/jury#${token}`);
  await screen.findByRole('alert');

  const replacement = `B${'A'.repeat(42)}`;
  await act(async () => { window.location.hash = replacement; });

  expect(await screen.findByLabelText('Текущий маршрут')).toHaveTextContent('/agent');
  expect(fetchMock).toHaveBeenCalledTimes(2);
  expect(fetchMock.mock.calls[1][1]?.body).toBe(JSON.stringify({ token: replacement }));
  expect(window.location.hash).toBe('');
});
