import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { applicationsApi } from '../../../api/client';
import { Application, ContractTextView } from '../../../types';
import { SigningBlock } from './SigningBlock';

let canSign = true;
vi.mock('../../../auth/AuthProvider', () => ({ useAuth: () => ({ session: { user: { canSign } } }) }));
vi.mock('../../../api/client', () => ({ applicationsApi: { contract: vi.fn(), sign: vi.fn(), requestCode: vi.fn() } }));
beforeEach(() => { canSign = true; vi.resetAllMocks(); });
afterEach(() => { vi.useRealTimers(); });

it('explains who must sign before showing any unavailable action to a representative without permission', async () => {
  canSign = false;
  vi.mocked(applicationsApi.contract).mockResolvedValue({} as ContractTextView);
  render(<SigningBlock application={draft('readonly', '123456')} onChanged={vi.fn()} />);
  expect(await screen.findByText('Договор должен подписать представитель компании с правом подписи.')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Подписать' })).not.toBeInTheDocument();
  expect(screen.queryByLabelText('Код из 6 цифр')).not.toBeInTheDocument();
  expect(applicationsApi.sign).not.toHaveBeenCalled();
  expect(screen.queryByTestId('signing-code-popup')).not.toBeInTheDocument();
  expect(screen.queryByText('123456', { exact: true })).not.toBeInTheDocument();
});

function draft(id: string, code?: string, expiresAt = '2099-01-01T00:00:00Z'): Application {
  return { id, status: 'SIGNING', process: { signing: code ? { code, expiresAt, signedAt: null } : null } } as Application;
}

const loadedContract: ContractTextView = { text: 'Условия договора', legacySummary: true, signable: true, signedAt: null, snapshot: null };

it('shows the newly issued and replaced code and removes it as soon as signing succeeds', async () => {
  vi.mocked(applicationsApi.contract).mockResolvedValue(loadedContract);
  vi.mocked(applicationsApi.requestCode).mockResolvedValueOnce(draft('draft', '123456')).mockResolvedValueOnce(draft('draft', '654321'));
  vi.mocked(applicationsApi.sign).mockResolvedValue({ ...draft('draft'), status: 'DISBURSING' });
  function Harness() {
    const [application, setApplication] = useState(draft('draft'));
    return <SigningBlock application={application} onChanged={setApplication} />;
  }
  render(<Harness />);
  expect(screen.queryByTestId('signing-code-popup')).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: 'Получить код' }));
  expect(await screen.findByTestId('signing-code-popup')).toHaveTextContent('123456');
  expect(screen.getAllByText('123456', { exact: true })).toHaveLength(1);
  await userEvent.click(screen.getByRole('button', { name: 'Закрыть' }));
  expect(screen.queryByText('123456', { exact: true })).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: 'Показать код' }));
  expect(screen.getByTestId('signing-code-popup')).toHaveTextContent('123456');
  expect(applicationsApi.requestCode).toHaveBeenCalledTimes(1);

  await userEvent.click(screen.getByRole('button', { name: 'Новый код' }));
  await waitFor(() => expect(screen.getByTestId('signing-code-popup')).toHaveTextContent('654321'));
  expect(screen.queryByText('123456', { exact: true })).not.toBeInTheDocument();
  await userEvent.type(screen.getByLabelText('Код из 6 цифр'), '654321');
  await userEvent.click(screen.getByRole('button', { name: 'Подписать' }));
  expect(applicationsApi.sign).toHaveBeenCalledWith('draft', '654321');
  expect(screen.queryByText('654321', { exact: true })).not.toBeInTheDocument();
});

it('reopens an auto-hidden code without issuing another one', async () => {
  vi.useFakeTimers();
  vi.mocked(applicationsApi.contract).mockResolvedValue(loadedContract);
  render(<SigningBlock application={draft('draft', '123456')} onChanged={vi.fn()} />);
  await act(async () => { vi.advanceTimersByTime(12000); });
  expect(screen.queryByText('123456', { exact: true })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Показать код' }));
  expect(screen.getByTestId('signing-code-popup')).toHaveTextContent('123456');
  expect(applicationsApi.requestCode).not.toHaveBeenCalled();
});

it('hides an expired code without waiting for polling and discards the notice when switching applications', async () => {
  vi.useFakeTimers();
  vi.mocked(applicationsApi.contract).mockResolvedValue(loadedContract);
  const props = { onChanged: vi.fn() };
  const expiration = new Date(Date.now() + 1000).toISOString();
  const ui = render(<SigningBlock application={draft('first', '111111', expiration)} {...props} />);
  expect(screen.getByTestId('signing-code-popup')).toHaveTextContent('111111');
  await act(async () => { vi.advanceTimersByTime(1000); });
  expect(screen.queryByText('111111', { exact: true })).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Получить код' })).toBeEnabled();
  expect(screen.queryByRole('button', { name: 'Показать код' })).not.toBeInTheDocument();

  ui.rerender(<SigningBlock application={draft('first', '222222')} {...props} />);
  expect(screen.getByTestId('signing-code-popup')).toHaveTextContent('222222');
  ui.rerender(<SigningBlock application={draft('second')} {...props} />);
  expect(screen.queryByText('222222', { exact: true })).not.toBeInTheDocument();
  await act(async () => {});
});

it('blocks the code when text cannot be loaded and enables signing only after a successful retry', async () => {
  const application = draft('synthetic', '123456');
  const contract: ContractTextView = {
    snapshot: { id: 'snapshot', applicationId: 'synthetic', offerId: 'offer', text: 'Сохранённые условия', templateVersion: 1, sha256: 'a'.repeat(64), createdAt: '2026-10-24T12:00:00Z' },
    text: 'Сохранённые условия', legacySummary: false, signable: true, signedAt: null
  };
  vi.mocked(applicationsApi.contract).mockRejectedValueOnce(new Error('Не удалось получить текст')).mockResolvedValue(contract);
  render(<SigningBlock application={application} onChanged={vi.fn()} />);
  expect(await screen.findByText('Не удалось получить текст')).toBeInTheDocument();
  expect(screen.getByLabelText('Код из 6 цифр')).toBeDisabled();
  expect(screen.getByRole('button', { name: 'Подписать' })).toBeDisabled();
  await userEvent.click(screen.getByRole('button', { name: /Повторить/ }));
  expect(await screen.findByText('Сохранённые условия')).toBeInTheDocument();
  await waitFor(() => expect(screen.getByLabelText('Код из 6 цифр')).toBeEnabled());
  await userEvent.type(screen.getByLabelText('Код из 6 цифр'), '123456');
  expect(screen.getByRole('button', { name: 'Подписать' })).toBeEnabled();
  expect(applicationsApi.sign).not.toHaveBeenCalled();
});
