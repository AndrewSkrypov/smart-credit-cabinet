import { act, fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Application } from '../../../types';
import { ApplicationPage } from './ApplicationPage';

const { get, action, withdraw } = vi.hoisted(() => ({ get: vi.fn(), action: vi.fn(), withdraw: vi.fn() }));
vi.mock('../../../api/client', () => ({ applicationsApi: { get, withdraw } }));
vi.mock('../../../components/MessagesBlock', () => ({ MessagesBlock: () => null }));
vi.mock('../../../components/GeneratedFilesBlock', () => ({ GeneratedFilesBlock: () => null }));
vi.mock('./HistoryBlock', () => ({ HistoryBlock: () => null }));
vi.mock('./DocumentsBlock', () => ({ DocumentsBlock: ({ application }: { application: Application }) => <div>Документы {application.id}</div> }));
vi.mock('./DraftForm', () => ({ DraftForm: ({ application, onSubmitted }: { application: Application; onSubmitted: (next: Application) => void }) => {
  const [value, setValue] = useState('');
  return <><input aria-label="Локальный черновик" value={value} onChange={event => setValue(event.target.value)} />
    <button onClick={() => void action(application.id).then(onSubmitted)}>Отправить {application.id}</button></>;
} }));
function deferred<T>() {
  let resolve!: (value: T) => void; let reject!: (error: Error) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject };
}
const app = (id: string, number: number, status = 'DRAFT') => ({
  id, number, status, editable: true, company: { name: 'Компания', inn: '123' }, product: { name: 'Кредит' },
  purposeTitle: 'Оборот', amount: 100000, termMonths: 12, createdAt: '2026-10-25T12:00:00Z',
  documents: [], allowedActions: [], process: { offer: null, loan: null, outcome: null }
}) as unknown as Application;
let navigate: ReturnType<typeof useNavigate>;
function Navigation() { navigate = useNavigate(); return null; }
function mount() {
  return render(<MemoryRouter initialEntries={['/client/applications/a']}><Navigation /><Routes>
    <Route path="/client/applications/:id" element={<ApplicationPage />} />
  </Routes></MemoryRouter>);
}
const go = (path: string | number) => act(async () => { if (typeof path === 'number') navigate(path); else navigate(`/client/applications/${path}`); });

describe('client application route isolation', () => {
  beforeEach(() => { get.mockReset(); action.mockReset(); withdraw.mockReset(); });
  afterEach(() => { vi.useRealTimers(); });

  it.each(['a-first', 'b-first'])('never shows A on route B with %s response order', async order => {
    const a = deferred<Application>(); const b = deferred<Application>();
    get.mockImplementation((id: string) => id === 'a' ? a.promise : b.promise);
    mount(); await go('b');
    if (order === 'a-first') {
      await act(async () => a.resolve(app('a', 101)));
      expect(screen.queryByRole('heading', { name: 'Заявка № 101' })).not.toBeInTheDocument();
    }
    await act(async () => b.resolve(app('b', 202)));
    if (order === 'b-first') await act(async () => a.resolve(app('a', 101)));
    expect(screen.getByRole('heading', { name: 'Заявка № 202' })).toBeInTheDocument();
    expect(screen.queryByText('Документы a')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Отправить a' })).not.toBeInTheDocument();
  });

  it('resets forms while B loads, handles its 404 and restores separate cards with Back/Forward', async () => {
    const b = deferred<Application>();
    get.mockImplementation((id: string) => id === 'a' ? Promise.resolve(app('a', 101)) : b.promise);
    mount(); await screen.findByRole('heading', { name: 'Заявка № 101' });
    fireEvent.change(screen.getByLabelText('Локальный черновик'), { target: { value: 'Только A' } });
    await go('b');
    expect(screen.queryByRole('heading', { name: 'Заявка № 101' })).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Локальный черновик')).not.toBeInTheDocument();
    await act(async () => b.reject(new Error('Заявка B не найдена (404)')));
    expect(screen.getByText('Заявка B не найдена (404)')).toBeInTheDocument();
    await go(-1);
    expect(screen.getByRole('heading', { name: 'Заявка № 101' })).toBeInTheDocument();
    expect(screen.getByLabelText('Локальный черновик')).toHaveValue('');
    get.mockImplementation((id: string) => Promise.resolve(id === 'a' ? app('a', 101) : app('b', 202)));
    await go(1);
    expect(screen.getByRole('heading', { name: 'Заявка № 202' })).toBeInTheDocument();
  });

  it('ignores a late action after A-B-A even when the application id matches again', async () => {
    const oldA = deferred<Application>(); const savedA = deferred<Application>();
    get.mockResolvedValueOnce(app('a', 101)).mockReturnValueOnce(oldA.promise).mockResolvedValueOnce(app('a', 303));
    action.mockReturnValue(savedA.promise);
    mount(); await screen.findByRole('heading', { name: 'Заявка № 101' });
    fireEvent.click(screen.getByRole('button', { name: 'Отправить a' }));
    await go('b'); await go('a');
    await act(async () => { oldA.resolve(app('b', 202)); savedA.resolve(app('a', 101, 'SUBMITTED')); });
    expect(screen.getByRole('heading', { name: 'Заявка № 303' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Отправить a' })).toBeInTheDocument();
  });

  it('keeps the new A instance isolated from late first-A and B loads', async () => {
    const firstA = deferred<Application>(); const b = deferred<Application>();
    get.mockReturnValueOnce(firstA.promise).mockReturnValueOnce(b.promise).mockResolvedValueOnce(app('a', 303));
    mount(); await go('b'); await go('a');
    await act(async () => { firstA.resolve(app('a', 101)); b.resolve(app('b', 202)); });
    expect(screen.getByRole('heading', { name: 'Заявка № 303' })).toBeInTheDocument();
  });

  it('allows a slow poll to finish without superseding it on every interval', async () => {
    vi.useFakeTimers(); const poll = deferred<Application>();
    get.mockResolvedValueOnce(app('a', 101, 'SCORING')).mockReturnValue(poll.promise);
    const ui = mount(); await act(async () => {});
    await act(async () => { vi.advanceTimersByTime(10000); });
    expect(get).toHaveBeenCalledTimes(2);
    await act(async () => poll.resolve(app('a', 102, 'DRAFT')));
    expect(screen.getByRole('heading', { name: 'Заявка № 102' })).toBeInTheDocument();
    ui.unmount();
  });

  it('does not overwrite a completed withdrawal with a poll started before it', async () => {
    vi.useFakeTimers(); const poll = deferred<Application>();
    get.mockResolvedValueOnce({ ...app('a', 101, 'SCORING'), allowedActions: ['WITHDRAW'] }).mockReturnValue(poll.promise);
    withdraw.mockResolvedValue(app('a', 404, 'WITHDRAWN'));
    const ui = mount(); await act(async () => {});
    await act(async () => { vi.advanceTimersByTime(2000); });
    fireEvent.click(screen.getByRole('button', { name: 'Отозвать заявку' }));
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Отозвать' })));
    await act(async () => poll.resolve(app('a', 101, 'SCORING')));
    await act(async () => { vi.advanceTimersByTime(300); });
    expect(screen.getByRole('heading', { name: 'Заявка № 404' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Отозвать заявку' })).not.toBeInTheDocument();
    ui.unmount();
  });
});
