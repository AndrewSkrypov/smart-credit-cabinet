import { act, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../api/client';
import { Application } from '../../types';
import { AgentApplicationPage } from './AgentApplicationPage';

const { getApplication, updateApplication, requestConfirmation, list } = vi.hoisted(() => ({
  getApplication: vi.fn(), updateApplication: vi.fn(), requestConfirmation: vi.fn(), list: vi.fn()
}));
vi.mock('../../api/client', async (importOriginal) => ({
  ...await importOriginal<typeof import('../../api/client')>(),
  agentApi: { getApplication, updateApplication, requestConfirmation }, productsApi: { list }
}));
vi.mock('../../components/MessagesBlock', () => ({ MessagesBlock: () => null }));
vi.mock('../client/application/HistoryBlock', () => ({ HistoryBlock: () => null }));

const draft = (version: number, termMonths: number) => ({
  id: 'draft', status: 'DRAFT', editable: true, version, termMonths, amount: 2_000_000,
  product: { code: 'INVESTMENT', name: 'Инвестиционный кредит' }, purpose: 'EQUIPMENT', purposeTitle: 'Оборудование',
  company: { name: 'Тест', inn: '000100000004' }, allowedActions: ['REQUEST_CLIENT_CONFIRMATION'], submissionProblems: [],
  documents: [], requiredDocuments: [], process: {}, createdAt: '2026-11-20T12:00:00Z'
}) as unknown as Application;
const term = () => screen.getByRole('textbox', { name: 'Срок, месяцев' });
const click = (name: string) => act(async () => { fireEvent.click(screen.getByRole('button', { name })); });
const focus = () => act(async () => { window.dispatchEvent(new Event('focus')); });

describe('agent draft versions', () => {
  afterEach(() => vi.resetAllMocks());

  it('refreshes a clean form and keeps local edits after a conflict, including a second conflict on overwrite', async () => {
    list.mockResolvedValue([]);
    getApplication.mockResolvedValue(draft(1, 12));
    render(<MemoryRouter initialEntries={['/agent/applications/draft']}><Routes>
      <Route path="/agent/applications/:id" element={<AgentApplicationPage />} />
    </Routes></MemoryRouter>);
    await screen.findByRole('textbox', { name: 'Срок, месяцев' });
    getApplication.mockResolvedValue(draft(2, 18));
    await focus();
    expect(term()).toHaveValue('18');

    fireEvent.change(term(), { target: { value: '20' } });
    getApplication.mockResolvedValue(draft(3, 24));
    await focus();
    expect(term()).toHaveValue('20');
    updateApplication.mockRejectedValueOnce(new ApiError('Конфликт', 409));
    await click('Сохранить черновик');
    expect(updateApplication).toHaveBeenLastCalledWith('draft', expect.objectContaining({ termMonths: 20 }), 2);

    updateApplication.mockRejectedValueOnce(new ApiError('Новый конфликт', 409));
    await click('Сохранить поверх');
    expect(updateApplication).toHaveBeenLastCalledWith('draft', expect.objectContaining({ termMonths: 20 }), 3);
    expect(term()).toHaveValue('20');
    getApplication.mockResolvedValue(draft(4, 30));
    await click('Загрузить с сервера');
    expect(term()).toHaveValue('30');
  });

  it('passes the saved version and requires review if the draft changes before handoff', async () => {
    list.mockResolvedValue([]);
    getApplication.mockResolvedValue(draft(1, 12));
    updateApplication.mockResolvedValueOnce(draft(2, 20));
    requestConfirmation.mockRejectedValueOnce(new ApiError('Заявка изменена', 409, 'draft-version'));
    render(<MemoryRouter initialEntries={['/agent/applications/draft']}><Routes>
      <Route path="/agent/applications/:id" element={<AgentApplicationPage />} />
    </Routes></MemoryRouter>);
    await screen.findByRole('textbox', { name: 'Срок, месяцев' });
    fireEvent.change(term(), { target: { value: '20' } });
    await click('Отправить клиенту на подтверждение');
    expect(requestConfirmation).toHaveBeenCalledWith('draft', 2, 0);
    expect(term()).toHaveValue('20');
    expect(screen.getByRole('button', { name: 'Отправить клиенту на подтверждение' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Сохранить поверх' })).not.toBeInTheDocument();
    getApplication.mockResolvedValueOnce(draft(3, 24));
    await click('Загрузить с сервера');
    expect(term()).toHaveValue('24');
    expect(screen.getByRole('button', { name: 'Отправить клиенту на подтверждение' })).toBeEnabled();
  });
});
