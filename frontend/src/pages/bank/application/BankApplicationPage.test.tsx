import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { Application, AssessmentView } from '../../../types';
import { BankApplicationPage } from './BankApplicationPage';

const { get, assessment, downloadDocument } = vi.hoisted(() => ({ get: vi.fn(), assessment: vi.fn(), downloadDocument: vi.fn() }));
vi.mock('../../../api/client', () => ({ applicationsApi: { get, downloadDocument }, underwritingApi: { assessment } }));
vi.mock('../../../components/MessagesBlock', () => ({ MessagesBlock: () => null }));
vi.mock('../../../components/GeneratedFilesBlock', () => ({ GeneratedFilesBlock: () => null }));
vi.mock('./AssessmentBlocks', () => ({ AuditBlock: () => null, ScoreBlock: () => null, SourcesBlock: () => null, DiscrepanciesBlock: () => null }));
vi.mock('./DecisionPanel', () => ({ DecisionPanel: () => null, outcomeTitles: {} }));

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

const app = (id: string, number: number) => ({
  id, number, status: 'MANUAL_REVIEW', company: { name: 'Компания', inn: '123' },
  product: { name: 'Кредит' }, purposeTitle: 'Оборот', amount: 100000, termMonths: 12,
  documents: [], process: { offer: null, loan: null, outcome: null }
}) as unknown as Application;
const view = (id: string) => ({ applicationId: id, status: 'MANUAL_REVIEW', actions: [], latestDecision: null, automaticDecision: null,
  registry: null, tax: null, bureau: null, bank: null, turnoverVariation: null, discrepancies: [] }) as AssessmentView;

let navigate: ReturnType<typeof useNavigate>;
function Navigation() { navigate = useNavigate(); return null; }
function mount() {
  return render(<MemoryRouter initialEntries={['/bank/a']}><Navigation /><Routes>
    <Route path="/bank/:id" element={<BankApplicationPage />} />
  </Routes></MemoryRouter>);
}

describe('bank application route isolation', () => {
  it('reports a failed file download without replacing the loaded dossier with a stale-data error', async () => {
    get.mockResolvedValue({ ...app('a', 101), documents: [{ id: 'file', fileName: 'Сведения.pdf', title: 'Сведения', sizeBytes: 1234, uploadedAt: '2026-10-25T10:00:00Z' }] });
    assessment.mockResolvedValue(view('a'));
    downloadDocument.mockRejectedValueOnce(new Error('Доступ к файлу запрещён'));
    mount();
    await userEvent.click(await screen.findByRole('button', { name: 'Сведения.pdf' }));
    expect(await screen.findByText('Доступ к файлу запрещён')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Заявка № 101' })).toBeInTheDocument();
    expect(screen.queryByText(/Показаны последние полученные данные/)).not.toBeInTheDocument();
    expect(downloadDocument).toHaveBeenCalledWith('a', 'file', 'Сведения.pdf');
    downloadDocument.mockResolvedValueOnce(undefined);
    await userEvent.click(screen.getByRole('button', { name: 'Сведения.pdf' }));
    expect(screen.queryByText('Доступ к файлу запрещён')).not.toBeInTheDocument();
  });

  it('finishes a slow card load instead of superseding it on every poll', async () => {
    vi.useFakeTimers();
    const pending = deferred<Application>();
    get.mockReturnValue(pending.promise);
    assessment.mockResolvedValue(view('a'));
    const mounted = mount();
    try {
      const initialCalls = get.mock.calls.length;
      await act(async () => { vi.advanceTimersByTime(20000); });
      expect(get.mock.calls.length).toBe(initialCalls);
      await act(async () => { pending.resolve(app('a', 101)); });
      expect(screen.getByRole('heading', { name: 'Заявка № 101' })).toBeInTheDocument();
    } finally {
      mounted.unmount();
      vi.useRealTimers();
    }
  });

  it('removes the previous application and its actions while another card loads', async () => {
    const pending = deferred<Application>();
    get.mockImplementation((id: string) => id === 'a' ? Promise.resolve(app('a', 101)) : pending.promise);
    assessment.mockImplementation((id: string) => Promise.resolve(view(id)));
    mount();
    await screen.findByRole('heading', { name: 'Заявка № 101' });
    await act(async () => { navigate('/bank/b'); });
    expect(screen.queryByRole('heading', { name: 'Заявка № 101' })).not.toBeInTheDocument();
    await act(async () => { pending.resolve(app('b', 202)); });
    expect(await screen.findByRole('heading', { name: 'Заявка № 202' })).toBeInTheDocument();
  });

  it('does not overwrite the new card with a late previous response', async () => {
    const pending = deferred<Application>();
    get.mockImplementation((id: string) => id === 'a' ? pending.promise : Promise.resolve(app('b', 202)));
    assessment.mockImplementation((id: string) => Promise.resolve(view(id)));
    mount();
    await act(async () => { navigate('/bank/b'); });
    await screen.findByRole('heading', { name: 'Заявка № 202' });
    await act(async () => { pending.resolve(app('a', 101)); });
    expect(screen.getByRole('heading', { name: 'Заявка № 202' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Заявка № 101' })).not.toBeInTheDocument();
  });
});
