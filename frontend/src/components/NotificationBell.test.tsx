import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { expect, it, vi } from 'vitest';
import { notificationsApi } from '../api/client';
import { NotificationBell } from './NotificationBell';

vi.mock('../api/client', () => ({ notificationsApi: { unreadCount: vi.fn(), page: vi.fn(), markAllRead: vi.fn() } }));

it('keeps notifications unread and shows a failure when mark-all is rejected', async () => {
  vi.mocked(notificationsApi.unreadCount).mockResolvedValue(1);
  vi.mocked(notificationsApi.page).mockResolvedValue({
    unread: 1, page: 0, size: 10, total: 1,
    items: [{ id: 'n1', type: 'APPLICATION_STATUS', title: 'Решение готово', body: 'Откройте заявку', read: false, link: '/client', createdAt: '2026-10-24T12:00:00Z' }]
  });
  vi.mocked(notificationsApi.markAllRead).mockRejectedValue(new Error('Сеть недоступна'));
  render(<MemoryRouter><NotificationBell /></MemoryRouter>);
  await userEvent.click(await screen.findByRole('button', { name: 'Уведомления: 1 непрочитанных' }));
  await userEvent.click(await screen.findByRole('button', { name: 'Прочитать все' }));
  await waitFor(() => expect(notificationsApi.markAllRead).toHaveBeenCalledOnce());
  expect(await screen.findByText('Сеть недоступна')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Прочитать все' })).toBeEnabled();
  await userEvent.keyboard('{Escape}');
  expect(await screen.findByRole('button', { name: 'Уведомления: 1 непрочитанных' })).toBeInTheDocument();
});
