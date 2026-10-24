import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import { applicationsApi } from '../../../api/client';
import { Application, ContractTextView } from '../../../types';
import { SigningBlock } from './SigningBlock';

vi.mock('../../../auth/AuthProvider', () => ({ useAuth: () => ({ session: { user: { canSign: true } } }) }));
vi.mock('../../../api/client', () => ({ applicationsApi: { contract: vi.fn(), sign: vi.fn() } }));

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
