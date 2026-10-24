import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';
import { artifactsApi } from '../api/client';
import { GeneratedArtifact } from '../types';
import { GeneratedFilesBlock } from './GeneratedFilesBlock';

vi.mock('../api/client', () => ({ artifactsApi: { list: vi.fn(), download: vi.fn(), retry: vi.fn() } }));

const pending: GeneratedArtifact = {
  id: 'file', kind: 'SCHEDULE_PDF', phase: 'FINAL', title: 'График платежей', fileName: 'schedule.pdf',
  contentType: 'application/pdf', status: 'PENDING', sizeBytes: null, checksum: null,
  createdAt: '2026-10-24T12:00:00Z', readyAt: null, errorMessage: null, downloadUrl: null,
};

beforeEach(() => { vi.resetAllMocks(); });

it('offers downloads only for ready files and keeps the cached list on a failed refresh', async () => {
  const ready = { ...pending, status: 'READY' as const, sizeBytes: 1234, downloadUrl: '/download' };
  vi.mocked(artifactsApi.list).mockResolvedValueOnce([ready]).mockRejectedValue(new Error('Сервис недоступен'));
  vi.mocked(artifactsApi.download).mockRejectedValue(new Error('Файл временно недоступен'));
  render(<GeneratedFilesBlock applicationId="application" final />);
  await userEvent.click(await screen.findByRole('button', { name: 'Скачать График платежей, PDF' }));
  expect(artifactsApi.download).toHaveBeenCalledWith('application', 'file', 'schedule.pdf');
  expect(await screen.findByText('Файл временно недоступен')).toBeInTheDocument();
  expect(await screen.findByText('Сервис недоступен')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Скачать График платежей, PDF' })).toBeInTheDocument();
});

it('shows pending state without a broken download and retries an explicit generation failure', async () => {
  vi.mocked(artifactsApi.list).mockResolvedValue([pending]);
  const view = render(<GeneratedFilesBlock applicationId="application" final={false} />);
  expect(await screen.findByText('Готовим файл…')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /Скачать/ })).not.toBeInTheDocument();
  view.unmount();
  vi.mocked(artifactsApi.list).mockResolvedValueOnce([{ ...pending, status: 'ERROR', errorMessage: 'Не удалось подготовить файл.' }]).mockResolvedValue([pending]);
  render(<GeneratedFilesBlock applicationId="application" final />);
  await userEvent.click(await screen.findByRole('button', { name: 'Повторить создание' }));
  expect(artifactsApi.retry).toHaveBeenCalledWith('application', 'file');
  await waitFor(() => expect(screen.getByText('Готовим файл…')).toBeInTheDocument());
  expect(artifactsApi.download).not.toHaveBeenCalled();
});
