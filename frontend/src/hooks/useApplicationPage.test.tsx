import { act, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, useLocation, useNavigate } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { applicationsApi } from '../api/client';
import { ApplicationPage, ApplicationSummary } from '../types';
import { useApplicationPage, useApplicationPageParams } from './useApplicationPage';

let userId = 0;
vi.mock('../api/client', () => ({ applicationsApi: { page: vi.fn() } }));
vi.mock('../auth/AuthProvider', () => ({ useAuth: () => ({ session: { user: { id: `page-user-${userId}`, role: 'MANAGER' } } }) }));
const row = (id: string) => ({ id, number: 1 } as ApplicationSummary);
const page = (id: string): ApplicationPage => ({ items: [row(id)], page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false, countsByGroup: { review: 1 }, actionRequiredCount: 0, actionRequiredPreview: [] });
function Fixture({ ready = true }: { ready?: boolean }) {
  const state = useApplicationPage('review', 20, ['review', 'done'], [20], 10000, { scrollTarget: 'list-start', ready });
  const location = useLocation();
  return <><div id="list-start" /><output>{state.data?.items.map((r) => r.id).join(',')}</output><span>{location.search}</span>
    <input aria-label="Поиск" value={state.search} onChange={(e) => state.setSearch(e.target.value)} />
    <button onClick={() => state.update({ group: 'done', page: 0 })}>Готовые</button>
    <button onClick={() => state.update({ page: 1 })}>Вторая</button>
    <button onClick={() => state.update({ sort: 'oldest', page: 0 })}>Сортировка</button>
    {state.hasUpdates && <button onClick={state.apply}>Обновления</button>}{state.error && <span>{state.error}</span>}</>;
}
beforeEach(() => { userId++; vi.useFakeTimers(); vi.resetAllMocks(); vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined); });
afterEach(() => vi.useRealTimers());

it('preserves search focus and never scrolls for debounced typing, clearing, sorting or grouping', async () => {
  vi.mocked(applicationsApi.page).mockResolvedValue(page('first'));
  await act(async () => { render(<MemoryRouter><Fixture /></MemoryRouter>); });
  await act(async () => { vi.advanceTimersByTime(20); });
  vi.mocked(window.scrollTo).mockClear();
  const search = screen.getByLabelText('Поиск');
  search.focus();
  for (const value of ['12', '123', '']) {
    await act(async () => { fireEvent.change(search, { target: { value } }); });
    await act(async () => { vi.advanceTimersByTime(350); });
    await act(async () => { vi.advanceTimersByTime(20); });
    expect(search).toHaveFocus();
    expect(window.scrollTo).not.toHaveBeenCalled();
  }
  for (const label of ['Сортировка', 'Готовые']) {
    await act(async () => { fireEvent.click(screen.getByText(label)); });
    await act(async () => { vi.advanceTimersByTime(20); });
    expect(window.scrollTo).not.toHaveBeenCalled();
  }
});

function DualFixture() {
  const params = useApplicationPageParams();
  const active = useApplicationPage('active', 20, ['active'], [20], 10000, { params, pageParam: 'activePage', restoreScroll: false });
  const done = useApplicationPage('done', 20, ['done'], [20], 10000, { params, fixedGroup: 'done', pageParam: 'donePage', restoreScroll: false });
  const location = useLocation();
  const navigate = useNavigate();
  return <><output aria-label="URL">{location.search}</output>
    <output aria-label="active">{active.loading ? 'loading' : active.data?.items[0]?.id ?? active.error}</output>
    <output aria-label="done">{done.loading ? 'loading' : done.data?.items[0]?.id ?? done.error}</output>
    <button onClick={() => { active.apply(); done.apply(); }}>Применить оба</button>
    <button onClick={() => navigate('/client?activePage=1&donePage=1&q=keep')}>Обе вторые</button>
    <button onClick={() => navigate(-1)}>Назад</button><button onClick={() => navigate(1)}>Вперёд</button>
  </>;
}

it.each(['active-first', 'done-first', 'same-turn'])('merges both initial server clamps without another poll: %s', async order => {
  const resolve: Record<string, (value: ApplicationPage) => void> = {};
  vi.mocked(applicationsApi.page).mockImplementation(query => query.page === 0 ? Promise.resolve(page(`${query.group}-fresh`)) : new Promise(r => { resolve[query.group] = r; }));
  await act(async () => { render(<MemoryRouter initialEntries={['/client?activePage=1&donePage=1&q=keep']}><DualFixture /></MemoryRouter>); });
  const groups = order === 'done-first' ? ['done', 'active'] : ['active', 'done'];
  if (order === 'same-turn') {
    await act(async () => { groups.forEach(group => resolve[group](page(`${group}-fresh`))); });
  } else {
    for (const group of groups) await act(async () => { resolve[group](page(`${group}-fresh`)); });
  }
  expect(screen.getByLabelText('URL')).toHaveTextContent('activePage=0');
  expect(screen.getByLabelText('URL')).toHaveTextContent('donePage=0');
  expect(screen.getByLabelText('URL')).toHaveTextContent('q=keep');
  expect(screen.getByLabelText('active')).toHaveTextContent('active-fresh');
  expect(screen.getByLabelText('done')).toHaveTextContent('done-fresh');
});

it('merges two buffered clamps in one apply', async () => {
  let shrink = false;
  vi.mocked(applicationsApi.page).mockImplementation(async query => ({ ...page(`${query.group}-${shrink ? 'fresh' : 'second'}`), page: shrink ? 0 : query.page, totalPages: shrink ? 1 : 2 }));
  await act(async () => { render(<MemoryRouter initialEntries={['/client?activePage=1&donePage=1']}><DualFixture /></MemoryRouter>); });
  shrink = true;
  await act(async () => { vi.advanceTimersByTime(10000); });
  await act(async () => { fireEvent.click(screen.getByText('Применить оба')); });
  expect(screen.getByLabelText('URL')).toHaveTextContent('activePage=0');
  expect(screen.getByLabelText('URL')).toHaveTextContent('donePage=0');
  expect(screen.getByLabelText('active')).toHaveTextContent('active-fresh');
  expect(screen.getByLabelText('done')).toHaveTextContent('done-fresh');
});

it('keeps one clamped list usable while the other fails, then restores both on retry', async () => {
  let fail = true;
  vi.mocked(applicationsApi.page).mockImplementation(async query => {
    if (query.group === 'done' && fail) throw new Error('Недоступно');
    return page(`${query.group}-fresh`);
  });
  await act(async () => { render(<MemoryRouter initialEntries={['/client?activePage=1&donePage=1']}><DualFixture /></MemoryRouter>); });
  expect(screen.getByLabelText('URL')).toHaveTextContent('activePage=0');
  expect(screen.getByLabelText('URL')).toHaveTextContent('donePage=1');
  expect(screen.getByLabelText('active')).toHaveTextContent('active-fresh');
  expect(screen.getByLabelText('done')).toHaveTextContent('Недоступно');
  fail = false;
  await act(async () => { vi.advanceTimersByTime(10000); });
  expect(screen.getByLabelText('URL')).toHaveTextContent('donePage=0');
  expect(screen.getByLabelText('active')).toHaveTextContent('active-fresh');
  expect(screen.getByLabelText('done')).toHaveTextContent('done-fresh');
});

it('corrects only one page and keeps both independent pages coherent on Back and Forward', async () => {
  let clamp = true;
  vi.mocked(applicationsApi.page).mockImplementation(async query => ({
    ...page(`${query.group}-${clamp && query.group === 'active' ? 0 : query.page}`), page: clamp && query.group === 'active' ? 0 : query.page, totalPages: 2
  }));
  await act(async () => { render(<MemoryRouter initialEntries={['/client?activePage=1&donePage=1&q=keep']}><DualFixture /></MemoryRouter>); });
  expect(screen.getByLabelText('URL')).toHaveTextContent('activePage=0');
  expect(screen.getByLabelText('URL')).toHaveTextContent('donePage=1');
  expect(screen.getByLabelText('active')).toHaveTextContent('active-0');
  expect(screen.getByLabelText('done')).toHaveTextContent('done-1');
  clamp = false;
  await act(async () => { fireEvent.click(screen.getByText('Обе вторые')); });
  expect(screen.getByLabelText('active')).toHaveTextContent('active-1');
  await act(async () => { fireEvent.click(screen.getByText('Назад')); });
  expect(screen.getByLabelText('active')).toHaveTextContent('active-0');
  expect(screen.getByLabelText('done')).toHaveTextContent('done-1');
  await act(async () => { fireEvent.click(screen.getByText('Вперёд')); });
  expect(screen.getByLabelText('active')).toHaveTextContent('active-1');
  expect(screen.getByLabelText('done')).toHaveTextContent('done-1');
  expect(screen.getByLabelText('URL')).toHaveTextContent('q=keep');
});

it('waits for the surrounding page composition before restoring scroll', async () => {
  vi.mocked(applicationsApi.page).mockResolvedValue(page('first'));
  let view!: ReturnType<typeof render>;
  await act(async () => { view = render(<MemoryRouter><Fixture ready={false} /></MemoryRouter>); });
  await act(async () => { vi.advanceTimersByTime(20); });
  expect(window.scrollTo).not.toHaveBeenCalled();
  await act(async () => { view.rerender(<MemoryRouter><Fixture /></MemoryRouter>); });
  await act(async () => { vi.advanceTimersByTime(20); });
  expect(window.scrollTo).toHaveBeenCalledTimes(1);
});

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

it('caches a clamped background result under its actual page before correcting the URL', async () => {
  let shrunk = false;
  vi.mocked(applicationsApi.page).mockImplementation(async (query) => shrunk ? page('fresh-first') : {
    ...page('second'), items: query.page === 0 ? Array.from({ length: 20 }, (_, i) => row(`cached-old-first-${i}`)) : [row('second')],
    page: query.page, totalElements: 21, totalPages: 2, hasNext: query.page === 0, countsByGroup: { review: 21 }
  });
  await act(async () => { render(<MemoryRouter><Fixture /></MemoryRouter>); });
  await act(async () => { fireEvent.click(screen.getByText('Вторая')); });
  expect(screen.getByRole('status')).toHaveTextContent('second');
  shrunk = true;
  await act(async () => { vi.advanceTimersByTime(10000); });
  expect(screen.getByRole('status')).toHaveTextContent('second');
  await act(async () => { fireEvent.click(screen.getByText('Обновления')); });
  expect(screen.getByRole('status')).toHaveTextContent('fresh-first');
  expect(screen.getByText(/page=0/)).toBeInTheDocument();
  expect(screen.queryByText('Обновления')).not.toBeInTheDocument();
});

it('scrolls once after deliberate pagination resolves, never on a subsequent poll', async () => {
  let finish!: (result: ApplicationPage) => void;
  vi.mocked(applicationsApi.page).mockResolvedValue(page('first'));
  await act(async () => { render(<MemoryRouter><Fixture /></MemoryRouter>); vi.advanceTimersByTime(20); });
  await act(async () => { vi.advanceTimersByTime(20); });
  vi.mocked(window.scrollTo).mockClear();
  vi.mocked(applicationsApi.page).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  await act(async () => { fireEvent.click(screen.getByText('Вторая')); vi.advanceTimersByTime(20); });
  expect(window.scrollTo).not.toHaveBeenCalled();
  const next = { ...page('second'), page: 1, totalElements: 21, totalPages: 2 };
  vi.mocked(applicationsApi.page).mockResolvedValue(next);
  await act(async () => { finish(next); });
  await act(async () => { vi.advanceTimersByTime(20); });
  expect(window.scrollTo).toHaveBeenCalledTimes(1);
  await act(async () => { vi.advanceTimersByTime(10000); });
  expect(window.scrollTo).toHaveBeenCalledTimes(1);
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
