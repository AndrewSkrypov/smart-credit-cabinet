import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, expect, it, vi } from 'vitest';
import { notificationsApi } from '../api/client';
import { NotificationBell } from './NotificationBell';

vi.mock('../api/client', () => ({ notificationsApi: { unreadCount: vi.fn(), page: vi.fn(), markAllRead: vi.fn(), markRead: vi.fn() } }));
beforeEach(() => vi.resetAllMocks());

it.each([0, 10, 11, 51])('replaces notification pages for total %i without changing read state', async (total) => {
  const user = userEvent.setup();
  vi.mocked(notificationsApi.unreadCount).mockResolvedValue(total);
  vi.mocked(notificationsApi.page).mockImplementation(async (page = 0) => ({
    unread: total, total, page, size: 10,
    items: Array.from({ length: Math.min(10, total - page * 10) }, (_, i) => ({ id: `n${page * 10 + i}`, type: 'APPLICATION_STATUS',
      title: `Уведомление ${page * 10 + i}`, body: 'Синтетическая запись', read: false, link: '/client', createdAt: '2026-10-25T10:00:00Z' }))
  }));
  render(<MemoryRouter><NotificationBell /></MemoryRouter>);
  await user.click(await screen.findByRole('button', { name: total ? `Уведомления: ${total} непрочитанных` : 'Уведомления: нет новых' }));
  if (!total) expect(await screen.findByText(/Пока ничего/)).toBeInTheDocument();
  else await screen.findByText('Уведомление 0', { exact: true });
  expect(!!screen.queryByRole('navigation', { name: 'Страницы уведомлений' })).toBe(total > 10);
  if (total > 10) {
    await user.click(screen.getByRole('button', { name: 'Более ранние' }));
    await screen.findByText('Уведомление 10', { exact: true });
    expect(screen.queryByText('Уведомление 0', { exact: true })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Более новые' }));
    await screen.findByText('Уведомление 0', { exact: true });
    expect(screen.queryByText('Уведомление 10', { exact: true })).not.toBeInTheDocument();
  }
  expect(notificationsApi.markRead).not.toHaveBeenCalled();
  expect(notificationsApi.markAllRead).not.toHaveBeenCalled();
});

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
