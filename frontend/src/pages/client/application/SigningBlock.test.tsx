import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';
import { applicationsApi } from '../../../api/client';
import { Application, ContractTextView } from '../../../types';
import { SigningBlock } from './SigningBlock';

let canSign = true;
vi.mock('../../../auth/AuthProvider', () => ({ useAuth: () => ({ session: { user: { canSign } } }) }));
vi.mock('../../../api/client', () => ({ applicationsApi: { contract: vi.fn(), sign: vi.fn() } }));
beforeEach(() => { canSign = true; vi.resetAllMocks(); });

it('explains who must sign before showing any unavailable action to a representative without permission', async () => {
  canSign = false;
  vi.mocked(applicationsApi.contract).mockResolvedValue({} as ContractTextView);
  render(<SigningBlock application={{ id: 'readonly', process: {} } as Application} onChanged={vi.fn()} />);
  expect(await screen.findByText('Договор должен подписать представитель компании с правом подписи.')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Подписать' })).not.toBeInTheDocument();
  expect(screen.queryByLabelText('Код из 6 цифр')).not.toBeInTheDocument();
  expect(applicationsApi.sign).not.toHaveBeenCalled();
});

it('blocks the code when text cannot be loaded and enables signing only after a successful retry', async () => {
  const application = { id: 'synthetic', process: { signing: { code: '123456', expiresAt: '2099-01-01T00:00:00Z', signedAt: null } } } as Application;
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
