import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { notificationsApi } from '../api/client';
import { ProfilePage } from './ProfilePage';

vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({ session: { user: { fullName: 'Представитель компании', email: 'synthetic@example.test', role: 'CLIENT' } } })
}));
vi.mock('../api/client', () => ({ notificationsApi: { settings: vi.fn(), saveSettings: vi.fn() } }));

const label = 'Дублировать важные уведомления на почту';

describe('ProfilePage notification settings', () => {
  beforeEach(() => vi.clearAllMocks());

  it('hides the switch when the mail profile is absent', async () => {
    vi.mocked(notificationsApi.settings).mockResolvedValue({ emailAvailable: false, emailEnabled: false, version: 0 });
    render(<MemoryRouter><ProfilePage /></MemoryRouter>);
    expect(await screen.findByText(/Почтовый канал на этом стенде выключен/)).toBeInTheDocument();
    expect(screen.queryByLabelText(label)).not.toBeInTheDocument();
  });

  it('starts with opt-in off and saves the expected version', async () => {
    vi.mocked(notificationsApi.settings).mockResolvedValue({ emailAvailable: true, emailEnabled: false, version: 3 });
    vi.mocked(notificationsApi.saveSettings).mockResolvedValue({ emailAvailable: true, emailEnabled: true, version: 4 });
    render(<MemoryRouter><ProfilePage /></MemoryRouter>);
    const toggle = await screen.findByLabelText(label);
    expect(toggle).not.toBeChecked();
    await userEvent.click(toggle);
    await waitFor(() => expect(notificationsApi.saveSettings).toHaveBeenCalledWith(true, 3));
    await waitFor(() => expect(toggle).toBeChecked());
  });

  it('shows a save failure and reloads the current server preference', async () => {
    vi.mocked(notificationsApi.settings).mockResolvedValue({ emailAvailable: true, emailEnabled: false, version: 3 });
    vi.mocked(notificationsApi.saveSettings).mockRejectedValue(new Error('Настройки уже изменились — обновите страницу'));
    render(<MemoryRouter><ProfilePage /></MemoryRouter>);
    await userEvent.click(await screen.findByLabelText(label));
    expect(await screen.findByText('Настройки уже изменились — обновите страницу')).toBeInTheDocument();
    await waitFor(() => expect(notificationsApi.settings).toHaveBeenCalledTimes(2));
    expect(await screen.findByLabelText(label)).not.toBeChecked();
  });
});
