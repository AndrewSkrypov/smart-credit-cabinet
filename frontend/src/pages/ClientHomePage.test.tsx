import { act, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { applicationsApi, companyApi } from '../api/client';
import { ClientHomePage } from './ClientHomePage';

vi.mock('../api/client', () => ({ applicationsApi: { page: vi.fn() }, companyApi: { mine: vi.fn() } }));
vi.mock('../auth/AuthProvider', () => ({ useAuth: () => ({ session: { user: { id: 'poll-client', role: 'CLIENT', canSign: true, companyId: 'company' } } }) }));

beforeEach(() => {
  vi.useFakeTimers();
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
  vi.mocked(companyApi.mine).mockResolvedValue({ id: 'company', name: 'Тестовая компания', inn: '000100000004', legalForm: 'ENTREPRENEUR', members: [] });
  vi.mocked(applicationsApi.page).mockResolvedValue({ items: [], page: 0, size: 10, totalElements: 0, totalPages: 0, hasNext: false, countsByGroup: { active: 0, done: 0, action: 0 }, actionRequiredCount: 0, actionRequiredPreview: [] });
});
afterEach(() => vi.useRealTimers());

it('keeps the last page and keyboard focus during a slow poll and a temporary failure', async () => {
  await act(async () => { render(<MemoryRouter><ClientHomePage /></MemoryRouter>); });
  const action = screen.getByRole('link', { name: 'Подобрать финансирование' });
  action.focus();
  let rejectPoll!: (reason: Error) => void;
  vi.mocked(applicationsApi.page).mockImplementationOnce(() => new Promise((_, reject) => { rejectPoll = reject; }));
  await act(async () => { vi.advanceTimersByTime(5000); });
  expect(screen.getByRole('heading', { name: 'Тестовая компания' })).toBeInTheDocument();
  expect(action).toHaveFocus();
  await act(async () => { rejectPoll(new Error('Временный сбой')); });
  expect(screen.getByRole('heading', { name: 'Тестовая компания' })).toBeInTheDocument();
  expect(screen.getByText(/Временный сбой/)).toBeInTheDocument();
  expect(action).toHaveFocus();
  await act(async () => { vi.advanceTimersByTime(5000); });
  expect(screen.queryByText(/Временный сбой/)).not.toBeInTheDocument();
});
