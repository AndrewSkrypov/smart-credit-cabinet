import { act, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { applicationsApi } from '../api/client';
import { ApplicationPage, ApplicationSummary } from '../types';
import { useApplicationPage } from './useApplicationPage';

let userId = 0;
vi.mock('../api/client', () => ({ applicationsApi: { page: vi.fn() } }));
vi.mock('../auth/AuthProvider', () => ({ useAuth: () => ({ session: { user: { id: `page-user-${userId}`, role: 'MANAGER' } } }) }));
const row = (id: string) => ({ id, number: 1 } as ApplicationSummary);
const page = (id: string): ApplicationPage => ({ items: [row(id)], page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false, countsByGroup: { review: 1 }, actionRequiredCount: 0, actionRequiredPreview: [] });
function Fixture() {
  const state = useApplicationPage('review', 20, ['review', 'done'], [20]);
  const location = useLocation();
  return <><output>{state.data?.items.map((r) => r.id).join(',')}</output><span>{location.search}</span>
    <input aria-label="Поиск" value={state.search} onChange={(e) => state.setSearch(e.target.value)} />
    <button onClick={() => state.update({ group: 'done', page: 0 })}>Готовые</button>
    {state.hasUpdates && <button onClick={state.apply}>Обновления</button>}{state.error && <span>{state.error}</span>}</>;
}
beforeEach(() => { userId++; vi.useFakeTimers(); vi.resetAllMocks(); vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined); });
afterEach(() => vi.useRealTimers());

it('does not overlap slow polls, preserves visible rows and applies changes explicitly', async () => {
  vi.mocked(applicationsApi.page).mockResolvedValue(page('original'));
  await act(async () => { render(<MemoryRouter><Fixture /></MemoryRouter>); });
  let resolve!: (result: ApplicationPage) => void;
  vi.mocked(applicationsApi.page).mockImplementationOnce(() => new Promise((r) => { resolve = r; }));
  await act(async () => { vi.advanceTimersByTime(30000); });
  expect(applicationsApi.page).toHaveBeenCalledTimes(2);
  expect(screen.getByRole('status')).toHaveTextContent('original');
  await act(async () => { resolve(page('changed')); });
  expect(screen.getByRole('status')).toHaveTextContent('original');
  await act(async () => { fireEvent.click(screen.getByText('Обновления')); });
  expect(screen.getByRole('status')).toHaveTextContent('changed');
});

it('clears the buffered B after the latest response returns to the visible A', async () => {
  vi.mocked(applicationsApi.page).mockResolvedValueOnce(page('A')).mockResolvedValueOnce(page('B')).mockResolvedValue(page('A'));
  await act(async () => { render(<MemoryRouter><Fixture /></MemoryRouter>); });
  await act(async () => { vi.advanceTimersByTime(10000); });
  expect(screen.getByRole('status')).toHaveTextContent('A');
  expect(screen.getByText('Обновления')).toBeInTheDocument();
  await act(async () => { vi.advanceTimersByTime(10000); });
  expect(applicationsApi.page).toHaveBeenCalledTimes(3);
  expect(screen.getByRole('status')).toHaveTextContent('A');
  expect(screen.queryByText('Обновления')).not.toBeInTheDocument();
});

it('debounces URL search, ignores a previous filter response and preserves data after a poll failure', async () => {
  let resolveOld!: (result: ApplicationPage) => void;
  vi.mocked(applicationsApi.page).mockImplementationOnce(() => new Promise((r) => { resolveOld = r; })).mockResolvedValue(page('new-group'));
  await act(async () => { render(<MemoryRouter><Fixture /></MemoryRouter>); });
  await act(async () => { fireEvent.click(screen.getByText('Готовые')); });
  await act(async () => { resolveOld(page('old-group')); });
  expect(screen.getByRole('status')).toHaveTextContent('new-group');
  expect(screen.getByRole('status')).not.toHaveTextContent('old-group');
  await act(async () => { fireEvent.change(screen.getByLabelText('Поиск'), { target: { value: '123' } }); });
  await act(async () => { vi.advanceTimersByTime(299); });
  expect(applicationsApi.page).toHaveBeenCalledTimes(2);
  await act(async () => { vi.advanceTimersByTime(1); });
  expect(applicationsApi.page).toHaveBeenLastCalledWith(expect.objectContaining({ q: '123', page: 0, group: 'done' }), expect.any(AbortSignal));
  vi.mocked(applicationsApi.page).mockRejectedValueOnce(new Error('Сеть недоступна'));
  await act(async () => { vi.advanceTimersByTime(10000); });
  expect(screen.getByRole('status')).toHaveTextContent('new-group');
  expect(screen.getByText('Сеть недоступна')).toBeInTheDocument();
});
